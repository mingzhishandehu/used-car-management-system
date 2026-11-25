package com.usedcar.vehicle.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.usedcar.system.domain.SysUser;
import com.usedcar.system.mapper.SysUserMapper;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.domain.VehicleHandover;
import com.usedcar.vehicle.dto.VehicleHandoverVO;
import com.usedcar.vehicle.mapper.VehicleHandoverMapper;
import com.usedcar.vehicle.mapper.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 员工离职时的车辆对接权流转。
 *
 * 「未完成订单」的口径：本项目没有 sale_order 的业务实现（那张表是预留的），
 * 实际对应 owner_user_id = 离职员工 且 status <> 'CLEARED' 的车辆。
 * CLEARED 已清库视为完成，不参与流转。
 *
 * 只改 vehicle.owner_user_id，其余一概不动：
 * created_by（谁录入的）、status_log（状态流水）、price_change_log、
 * vehicle_approval、ai_conversation / ai_message 全部原样保留。
 * 原对接人信息写进 vehicle_handover，一辆车被流转两次也能查到完整链路。
 */
@Service
public class VehicleHandoverService {

    private final VehicleMapper vehicleMapper;
    private final VehicleHandoverMapper handoverMapper;
    private final SysUserMapper sysUserMapper;

    public VehicleHandoverService(VehicleMapper vehicleMapper,
                                  VehicleHandoverMapper handoverMapper,
                                  SysUserMapper sysUserMapper) {
        this.vehicleMapper = vehicleMapper;
        this.handoverMapper = handoverMapper;
        this.sysUserMapper = sysUserMapper;
    }

    /** 离职弹窗里给老板看的「将流转几辆车」。 */
    public long pendingCount(Long employeeId) {
        Long count = vehicleMapper.selectCount(pendingWrapper(employeeId));
        return count == null ? 0 : count;
    }

    /**
     * 批量流转，返回实际处理的车辆数。
     * 调用方（EmployeeService.leave）标了 @Transactional，这里 REQUIRED 加入同一个事务：
     * 中途任何一条写失败，改过的 owner_user_id、写过的流转记录、离职状态会一起回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public int handover(Long fromUserId, Long toUserId, Long operatorId, String reason) {
        List<Vehicle> vehicles = vehicleMapper.selectList(pendingWrapper(fromUserId));
        for (Vehicle v : vehicles) {
            Vehicle patch = new Vehicle();
            patch.setId(v.getId());
            patch.setOwnerUserId(toUserId);
            vehicleMapper.updateById(patch);

            handoverMapper.insert(new VehicleHandover()
                    .setVehicleId(v.getId())
                    .setFromUserId(fromUserId)
                    .setToUserId(toUserId)
                    .setOperatorId(operatorId)
                    .setReason(StringUtils.hasText(reason) ? reason.trim() : "员工离职交接"));
        }
        return vehicles.size();
    }

    /** 某辆车的完整流转链，按时间正序。 */
    public List<VehicleHandoverVO> listByVehicle(Long vehicleId) {
        List<VehicleHandover> rows = handoverMapper.selectList(
                new LambdaQueryWrapper<VehicleHandover>()
                        .eq(VehicleHandover::getVehicleId, vehicleId)
                        .orderByAsc(VehicleHandover::getId));
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<Long> ids = new HashSet<>();
        for (VehicleHandover r : rows) {
            ids.add(r.getFromUserId());
            ids.add(r.getToUserId());
            if (r.getOperatorId() != null) {
                ids.add(r.getOperatorId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        for (SysUser u : sysUserMapper.selectBatchIds(ids)) {
            names.put(u.getId(), StringUtils.hasText(u.getRealName()) ? u.getRealName() : u.getUsername());
        }

        List<VehicleHandoverVO> result = new ArrayList<>();
        for (VehicleHandover r : rows) {
            result.add(new VehicleHandoverVO(
                    r.getId(), r.getVehicleId(),
                    r.getFromUserId(), names.getOrDefault(r.getFromUserId(), "账号 #" + r.getFromUserId()),
                    r.getToUserId(), names.getOrDefault(r.getToUserId(), "账号 #" + r.getToUserId()),
                    r.getOperatorId() == null ? null
                            : names.getOrDefault(r.getOperatorId(), "账号 #" + r.getOperatorId()),
                    r.getReason(), r.getCreatedAt()));
        }
        return result;
    }

    private static LambdaQueryWrapper<Vehicle> pendingWrapper(Long employeeId) {
        return new LambdaQueryWrapper<Vehicle>()
                .eq(Vehicle::getOwnerUserId, employeeId)
                .ne(Vehicle::getStatus, "CLEARED");
    }
}
