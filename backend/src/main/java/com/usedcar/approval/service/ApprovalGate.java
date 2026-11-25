package com.usedcar.approval.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usedcar.approval.domain.VehicleApproval;
import com.usedcar.approval.mapper.VehicleApprovalMapper;
import com.usedcar.common.BizException;
import com.usedcar.system.domain.SysUser;
import com.usedcar.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

/**
 * 车辆写操作的闸门：ADMIN 放行，员工落申请单。
 *
 * 放在后端而不是让前端「按角色选接口」：前端判断能被绕过，
 * 员工直接调 POST /api/vehicles 就能插车。所有写接口都先过这一道，
 * 角色判断只有一个来源，也不读 Sa-Token 会话里的 role 快照
 * （管理员可能刚被改角色或停用，会话是旧值）。
 */
@Service
public class ApprovalGate {

    private final SysUserMapper sysUserMapper;
    private final VehicleApprovalMapper approvalMapper;
    private final ObjectMapper objectMapper;

    public ApprovalGate(SysUserMapper sysUserMapper,
                        VehicleApprovalMapper approvalMapper,
                        ObjectMapper objectMapper) {
        this.sysUserMapper = sysUserMapper;
        this.approvalMapper = approvalMapper;
        this.objectMapper = objectMapper;
    }

    public SysUser current() {
        return requireActive(StpUtil.getLoginIdAsLong());
    }

    /** viewerId must come from the authenticated controller, never model arguments. */
    public SysUser requireActive(Long viewerId) {
        SysUser me = sysUserMapper.selectById(viewerId);
        if (me == null) {
            throw new BizException(401, "账号不存在");
        }
        if (me.getStatus() == null || me.getStatus() != 1) {
            throw new BizException(403, "账号已停用");
        }
        return me;
    }

    public boolean isAdmin() {
        return "ADMIN".equals(current().getRole());
    }

    /**
     * ADMIN 返回 null，调用方直接执行原业务；
     * 员工返回新建申请单的 id，调用方返回 WriteResult.pending。
     *
     * @param operationType ApprovalFieldSpec.LABELS 里的类型
     * @param source        MANUAL / AI
     * @param payload       本次操作的参数对象，序列化成快照
     */
    public Long submitIfEmployee(String operationType, String source,
                                 Long targetVehicleId, Object payload) {
        return submitIfEmployee(operationType, source, targetVehicleId, payload, null);
    }

    /** 带来源会话的重载，只给 AI 收车用：批准后要把卡片状态回写到那个会话。 */
    public Long submitIfEmployee(String operationType, String source, Long targetVehicleId,
                                 Object payload, Long conversationId) {
        SysUser me = current();
        if ("ADMIN".equals(me.getRole())) {
            return null;
        }
        return insert(operationType, source, targetVehicleId, payload, me.getId(), conversationId);
    }

    /** 强制落一张申请单，给「复制重提」这类已知要走申请的路径用。 */
    public Long insert(String operationType, String source, Long targetVehicleId,
                       Object payload, Long applicantId, Long conversationId) {
        VehicleApproval row = new VehicleApproval()
                .setOperationType(operationType)
                .setSource(source)
                .setTargetVehicleId(targetVehicleId)
                .setPayloadJson(writeJson(payload))
                .setApplicantId(applicantId)
                .setSourceConversationId(conversationId)
                .setStatus("PENDING");
        approvalMapper.insert(row);
        return row.getId();
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BizException(500, "申请参数序列化失败");
        }
    }
}
