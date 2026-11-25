package com.usedcar.approval.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usedcar.approval.domain.VehicleApproval;
import com.usedcar.approval.dto.ApprovalVO;
import com.usedcar.approval.mapper.VehicleApprovalMapper;
import com.usedcar.agent.service.AiIntakeService;
import com.usedcar.agent.tool.CreateVehicleTool;
import com.usedcar.common.BizException;
import com.usedcar.order.dto.AfterSaleCreateDTO;
import com.usedcar.order.dto.SaleOrderCreateDTO;
import com.usedcar.order.service.OrderService;
import com.usedcar.system.domain.SysUser;
import com.usedcar.system.mapper.SysUserMapper;
import com.usedcar.vehicle.dto.VehicleCertificateDTO;
import com.usedcar.vehicle.dto.VehicleCostDTO;
import com.usedcar.vehicle.dto.VehicleCreateDTO;
import com.usedcar.vehicle.dto.VehiclePriceChangeDTO;
import com.usedcar.vehicle.dto.VehicleStatusChangeDTO;
import com.usedcar.vehicle.dto.VehicleUpdateDTO;
import com.usedcar.vehicle.service.CertificateService;
import com.usedcar.vehicle.service.VehicleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审批编排：员工提交 → 老板批准 → 才执行真正的业务写入。
 *
 * 三条硬边界：
 *   1. 申请单存参数快照，批准时按快照执行，不现查业务表最新值；
 *   2. 批准与业务写入在同一个事务里，CAS 成功但写车失败时整单回滚到 PENDING；
 *   3. 写业务数据仍走 Service / CreateVehicleTool，本类不注入任何业务 Mapper。
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    @org.springframework.beans.factory.annotation.Autowired
    private AgentBatchService agentBatches;

    private final VehicleApprovalMapper approvalMapper;
    private final SysUserMapper sysUserMapper;
    private final ApprovalGate gate;
    private final VehicleService vehicleService;
    private final CertificateService certificateService;
    private final CreateVehicleTool createVehicleTool;
    private final ObjectMapper objectMapper;
    private final AiIntakeService aiIntakeService;
    private final OrderService orderService;

    public ApprovalService(VehicleApprovalMapper approvalMapper,
                           SysUserMapper sysUserMapper,
                           ApprovalGate gate,
                           VehicleService vehicleService,
                           CertificateService certificateService,
                           CreateVehicleTool createVehicleTool,
                           ObjectMapper objectMapper,
                           AiIntakeService aiIntakeService,
                           OrderService orderService) {
        this.approvalMapper = approvalMapper;
        this.sysUserMapper = sysUserMapper;
        this.gate = gate;
        this.vehicleService = vehicleService;
        this.certificateService = certificateService;
        this.createVehicleTool = createVehicleTool;
        this.objectMapper = objectMapper;
        this.aiIntakeService = aiIntakeService;
        this.orderService = orderService;
    }

    // ==================== 查 ====================

    /** 字段规格给前端「复制重提」渲染表单，前端不自己维护字段清单。 */
    public Map<String, List<ApprovalFieldSpec.Field>> spec() {
        return ApprovalFieldSpec.SPEC;
    }

    /**
     * scope=pending 待办（仅 ADMIN）/ scope=mine 我的申请 / 其余为全部（仅 ADMIN）。
     */
    public List<ApprovalVO> list(String scope, String status) {
        LambdaQueryWrapper<VehicleApproval> w = new LambdaQueryWrapper<>();
        if ("pending".equalsIgnoreCase(scope)) {
            requireAdmin();
            w.eq(VehicleApproval::getStatus, "PENDING");
        } else if ("mine".equalsIgnoreCase(scope)) {
            w.eq(VehicleApproval::getApplicantId, StpUtil.getLoginIdAsLong());
        } else {
            requireAdmin();
        }
        if (StringUtils.hasText(status) && !"pending".equalsIgnoreCase(scope)) {
            w.eq(VehicleApproval::getStatus, status.trim().toUpperCase());
        }
        w.orderByDesc(VehicleApproval::getCreatedAt).orderByDesc(VehicleApproval::getId);

        List<VehicleApproval> rows = approvalMapper.selectList(w);
        Map<Long, String> names = loadNames(rows);
        return rows.stream().map(r -> toVO(r, names)).toList();
    }

    // ==================== 审批 ====================

    /**
     * 批准。CAS 与业务写入同事务：
     * 先抢到「只有 PENDING 才能改」，再执行业务；任何一步抛异常，
     * 整单回滚到 PENDING，老板能重试，不会出现「显示已通过但库里没车」。
     */
    @Transactional(rollbackFor = Exception.class)
    public ApprovalVO approve(Long id, String remark) {
        Long approverId = requireAdmin();
        VehicleApproval row = requireRow(id);

        int updated = approvalMapper.casApprove(id, approverId, trim(remark, "同意"));
        if (updated == 0) {
            throw new BizException(400, "该申请已处理，请勿重复审批");
        }

        Long vehicleId = execute(row, approverId);
        if (vehicleId != null) {
            approvalMapper.updateById(new VehicleApproval()
                    .setId(id)
                    .setResultVehicleId(vehicleId));
            // AI 收车申请：把会话卡片从 PENDING 回写成 CONFIRMED，
            // 否则老板批完了，员工那边还一直显示「等待审批」。
            // 与本次事务同生共死，回写失败车辆也会回滚。
            if (row.getSourceConversationId() != null) {
                aiIntakeService.markApproved(row.getSourceConversationId(), vehicleId,
                        createVehicleTool.stockNoOf(vehicleId));
            }
        }
        log.info("申请 {} 已批准，类型 {}，执行人 {}，生成车辆 {}", id, row.getOperationType(), approverId, vehicleId);
        VehicleApproval approvedRow = approvalMapper.selectById(id);
        return toVO(approvedRow, loadNames(List.of(approvedRow)));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApprovalVO reject(Long id, String remark) {
        Long approverId = requireAdmin();
        requireRow(id);
        if (!StringUtils.hasText(remark)) {
            throw new BizException(400, "驳回必须填写原因");
        }
        int updated = approvalMapper.casReject(id, approverId, remark.trim());
        if (updated == 0) {
            throw new BizException(400, "该申请已处理，请勿重复审批");
        }
        VehicleApproval changed = approvalMapper.selectById(id);
        return toVO(changed, loadNames(List.of(changed)));
    }

    // ==================== 员工侧 ====================

    /**
     * 复制重提：新建一张 PENDING 单，原单保留作历史。
     * 只覆盖员工在表单里改过的键，ownerUserId 这类不在规格里的键沿用原值，
     * 防止员工顺手把车改挂到别人名下。
     */
    @Transactional(rollbackFor = Exception.class)
    public ApprovalVO resubmit(Long id, Map<String, Object> payload) {
        Long me = StpUtil.getLoginIdAsLong();
        VehicleApproval old = requireRow(id);
        if (!old.getApplicantId().equals(me)) {
            throw new BizException(403, "只能重提自己的申请");
        }
        if (!"REJECTED".equals(old.getStatus()) && !"CANCELLED".equals(old.getStatus())) {
            throw new BizException(400, "只有被驳回或已撤回的申请才能重提");
        }
        if (payload == null || payload.isEmpty()) {
            throw new BizException(400, "重提内容不能为空");
        }

        if ("AGENT_BATCH".equals(old.getOperationType())) {
            throw new BizException(400, "批量清单请重新通过助手生成，管理员需重新确认");
        }
        Map<String, Object> merged = new LinkedHashMap<>(parseMap(old.getPayloadJson()));
        merged.putAll(payload);
        // 重提时把来源会话 id 一起带过去，否则这单被批准后仍无法回写卡片状态
        Long newId = gate.insert(old.getOperationType(), old.getSource(),
                old.getTargetVehicleId(), merged, me, old.getSourceConversationId());
        return toVO(approvalMapper.selectById(newId), loadNames(List.of(approvalMapper.selectById(newId))));
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        Long me = StpUtil.getLoginIdAsLong();
        requireRow(id);
        if (approvalMapper.casCancel(id, me) == 0) {
            throw new BizException(400, "只能撤回自己待审批的申请");
        }
    }

    // ==================== 执行分派 ====================

    /**
     * 按申请类型调对应的业务方法。
     * CREATE 类走 CreateVehicleTool.executeApproved()，把 applicantId 作为 created_by、
     * approverId 作为 approved_by；其余操作直接调 VehicleService 的公开方法。
     * 本类不碰任何业务 Mapper。
     */
    private Long execute(VehicleApproval row, Long approverId) {
        String json = row.getPayloadJson();
        return switch (row.getOperationType()) {
            case "AGENT_BATCH" -> {
                var plan = agentBatches.parse(json);
                agentBatches.lockAndVerify(plan);
                for (var item : plan.items()) {
                    if ("APPROVE".equals(item.operation()) || "REJECT".equals(item.operation())) {
                        VehicleApproval target = requireRow(item.targetId());
                        if ("AGENT_BATCH".equals(target.getOperationType()))
                            throw new BizException(400, "批量申请必须单独由管理员确认，不能被另一批次审批");
                        String remark = String.valueOf(item.payload().getOrDefault("remark", "同意"));
                        if ("APPROVE".equals(item.operation())) approve(item.targetId(), remark);
                        else reject(item.targetId(), remark);
                    } else {
                        try {
                            execute(new VehicleApproval().setOperationType(item.operation())
                                .setTargetVehicleId(item.targetId()).setApplicantId(row.getApplicantId())
                                .setPayloadJson(objectMapper.writeValueAsString(item.payload())), approverId);
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            throw new BizException(400, "操作参数无效");
                        }
                    }
                }
                yield null;
            }
            case "CREATE_VEHICLE", "AI_INTAKE_CREATE" ->
                    createVehicleTool.executeApproved(
                            read(json, VehicleCreateDTO.class), row.getApplicantId(), approverId);
            case "UPDATE_VEHICLE" -> {
                vehicleService.update(requireTarget(row), read(json, VehicleUpdateDTO.class));
                yield null;
            }
            case "CHANGE_STATUS" -> {
                vehicleService.changeStatus(requireTarget(row), read(json, VehicleStatusChangeDTO.class));
                yield null;
            }
            case "CHANGE_PRICE" -> {
                vehicleService.changePrice(requireTarget(row), read(json, VehiclePriceChangeDTO.class));
                yield null;
            }
            case "ADD_COST" -> {
                vehicleService.addCost(requireTarget(row), read(json, VehicleCostDTO.class));
                yield null;
            }
            case "ADD_CERTIFICATE" -> {
                // 返回证件 id，不能当车辆 id 写进 result_vehicle_id，所以这里 yield null
                certificateService.create(requireTarget(row), read(json, VehicleCertificateDTO.class));
                yield null;
            }
            case "DELETE_CERTIFICATE" -> {
                Object raw = parseMap(json).get("certificateId");
                if (raw == null) {
                    throw new BizException(400, "申请参数缺少 certificateId");
                }
                certificateService.delete(Long.valueOf(raw.toString()));
                yield null;
            }
            case "CREATE_SALE_ORDER" -> {
                // 成交登记会写订单并把车推进 SOLD；返回的是订单 id，同样不是车辆 id
                orderService.createSaleOrder(requireTarget(row), read(json, SaleOrderCreateDTO.class));
                yield null;
            }
            case "ADD_AFTER_SALE" -> {
                orderService.createAfterSale(requireTarget(row), read(json, AfterSaleCreateDTO.class));
                yield null;
            }
            case "CLOSE_AFTER_SALE" -> {
                Object raw = parseMap(json).get("afterSaleId");
                if (raw == null) {
                    throw new BizException(400, "申请参数缺少 afterSaleId");
                }
                orderService.closeAfterSale(Long.valueOf(raw.toString()));
                yield null;
            }
            default -> throw new BizException(400, "未知的申请类型: " + row.getOperationType());
        };
    }

    private static Long requireTarget(VehicleApproval row) {
        if (row.getTargetVehicleId() == null) {
            throw new BizException(400, "该申请缺少目标车辆 id，无法执行");
        }
        return row.getTargetVehicleId();
    }

    // ==================== 内部 ====================

    private Long requireAdmin() {
        SysUser me = gate.current();
        if (!"ADMIN".equals(me.getRole())) {
            throw new BizException(403, "只有管理员能审批");
        }
        return me.getId();
    }

    private VehicleApproval requireRow(Long id) {
        VehicleApproval row = approvalMapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "申请不存在");
        }
        return row;
    }

    /** 一次批量查人名，避免列表里每行都发一次 SQL。 */
    private Map<Long, String> loadNames(List<VehicleApproval> rows) {
        Set<Long> ids = rows.stream()
                .flatMap(r -> {
                    List<Long> pair = new ArrayList<>(2);
                    if (r.getApplicantId() != null) pair.add(r.getApplicantId());
                    if (r.getApproverId() != null) pair.add(r.getApproverId());
                    return pair.stream();
                })
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (SysUser u : sysUserMapper.selectBatchIds(ids)) {
            names.put(u.getId(), StringUtils.hasText(u.getRealName()) ? u.getRealName() : u.getUsername());
        }
        return names;
    }

    private ApprovalVO toVO(VehicleApproval r, Map<Long, String> names) {
        return new ApprovalVO(
                r.getId(),
                r.getOperationType(),
                ApprovalFieldSpec.label(r.getOperationType()),
                r.getSource(),
                r.getTargetVehicleId(),
                parseMap(r.getPayloadJson()),
                r.getApplicantId(),
                names.getOrDefault(r.getApplicantId(), "账号 #" + r.getApplicantId()),
                r.getStatus(),
                r.getApproverId(),
                r.getApproverId() == null ? null : names.getOrDefault(r.getApproverId(), "账号 #" + r.getApproverId()),
                r.getApproveRemark(),
                r.getResultVehicleId(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private Map<String, Object> parseMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private <T> T read(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new BizException(400, "申请参数已损坏，无法执行：" + clazz.getSimpleName());
        }
    }

    private static String trim(String s, String fallback) {
        return StringUtils.hasText(s) ? s.trim() : fallback;
    }
}
