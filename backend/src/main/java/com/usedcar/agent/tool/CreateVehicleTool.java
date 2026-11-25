package com.usedcar.agent.tool;

import com.usedcar.vehicle.dto.VehicleCreateDTO;
import com.usedcar.vehicle.service.VehicleService;
import org.springframework.stereotype.Component;

/**
 * 「收车入库」业务工具。AI 收车链路里唯一能写业务数据的地方。
 *
 * 这个类的依赖列表就是它的权限清单：只注入 VehicleService，
 * 不注入 VehicleMapper / VehicleCostMapper / VehicleStatusLogMapper 中的任何一个。
 * 所以 Agent 想绕过业务规则直接改表，在这个类里根本没有路径。
 *
 * VehicleService.create() 这个方法一次事务写三张表：
 *   vehicle + vehicle_cost(收车款) + vehicle_status_log(首条流水)，
 * 并且状态固定为 PURCHASED、库存编号由 Service 生成。
 * 工具层不重复实现这些规则，只做一次转调。
 */
@Component
public class CreateVehicleTool implements AgentTool<VehicleCreateDTO, Long> {

    private final VehicleService vehicleService;

    public CreateVehicleTool(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @Override
    public String name() {
        return "createVehicle";
    }

    @Override
    public String description() {
        return "收车入库：写入车辆主表、收车款成本、首条状态流水；仅在老板确认后由服务端调用";
    }

    @Override
    public Long execute(VehicleCreateDTO params) {
        return vehicleService.create(params);
    }

    /**
     * 审批通过后的执行入口：显式指定申请人和审批人。
     *
     * 老板点「通过」时登录态是老板，若走单参 execute()，
     * created_by 会全记成老板，员工归属就废了。
     * 这里把 created_by 定为申请人、approved_by 定为审批人，
     * 业务写入仍然只经过本工具，不绕过边界。
     */
    public Long executeApproved(VehicleCreateDTO params, Long applicantId, Long approverId) {
        return vehicleService.create(params, applicantId, approverId);
    }

    /**
     * 确认后回查库存编号，给卡片和聊天记录展示用。
     * 走的是 VehicleService.detail() 这个业务读方法，不是自己注入 VehicleMapper。
     * 这样 AiIntakeService 只依赖工具，连业务 Service 都不用认识。
     */
    public String stockNoOf(Long vehicleId) {
        if (vehicleId == null) {
            return null;
        }
        return vehicleService.detail(vehicleId).vehicle().getStockNo();
    }
}
