package com.usedcar.vehicle.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.usedcar.approval.dto.WriteResult;
import com.usedcar.approval.service.ApprovalGate;
import com.usedcar.common.ApiResult;
import com.usedcar.pricing.PricingService;
import com.usedcar.pricing.dto.VehiclePricingVO;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.domain.VehicleCost;
import com.usedcar.vehicle.dto.VehicleCostDTO;
import com.usedcar.vehicle.dto.VehicleCreateDTO;
import com.usedcar.vehicle.dto.VehicleDetailVO;
import com.usedcar.vehicle.dto.VehicleHandoverVO;
import com.usedcar.vehicle.dto.VehicleStatusChangeDTO;
import com.usedcar.vehicle.dto.VehicleUpdateDTO;
import com.usedcar.vehicle.dto.VehiclePriceChangeDTO;
import com.usedcar.vehicle.service.VehicleHandoverService;
import com.usedcar.vehicle.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车辆接口。
 *
 * 这一层只做三件事：接参数、调 Service、包响应。不写任何业务判断。
 * 判断写在 Controller 里的后果是：定时任务、消息消费这类非 HTTP 入口拿不到同一套规则，
 * 同一辆车从两个入口能被改成两种结果，这类 bug 极难查。
 */
@Tag(name = "车辆管理")
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;
    private final PricingService pricingService;
    private final ApprovalGate approvalGate;
    private final VehicleHandoverService handoverService;

    public VehicleController(VehicleService vehicleService, PricingService pricingService,
                             ApprovalGate approvalGate, VehicleHandoverService handoverService) {
        this.vehicleService = vehicleService;
        this.pricingService = pricingService;
        this.approvalGate = approvalGate;
        this.handoverService = handoverService;
    }

    /**
     * 写操作统一过闸门：ADMIN 直接执行，员工落申请单。
     * 判断放在后端，前端按角色选接口能被绕过。
     */
    private ApiResult<WriteResult> gate(String operationType, Long vehicleId, Object payload,
                                        java.util.function.Supplier<Object> executor) {
        Long pending = approvalGate.submitIfEmployee(operationType, "MANUAL", vehicleId, payload);
        if (pending != null) {
            return ApiResult.ok(WriteResult.pending(pending));
        }
        return ApiResult.ok(WriteResult.executed(executor.get()));
    }

    /**
     * 分页查询车辆。
     *
     * @param page    页码，从 1 开始
     * @param size    每页条数，上限由分页插件兜到 500
     * @param keyword 模糊匹配品牌、车型、库存编号，可为空
     * @param status  按状态精确过滤，可为空
     */
    @Operation(summary = "分页查询车辆")
    @GetMapping
    public ApiResult<IPage<Vehicle>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResult.ok(vehicleService.page(page, size, keyword, status));
    }

    @Operation(summary = "车辆详情（含成本合计与状态流水）")
    @GetMapping("/{id}")
    public ApiResult<VehicleDetailVO> detail(@PathVariable Long id) {
        return ApiResult.ok(vehicleService.detail(id));
    }

    @Operation(summary = "该车辆的对接权流转记录，按时间正序，只追加不删除")
    @GetMapping("/{id}/handovers")
    public ApiResult<List<VehicleHandoverVO>> handovers(@PathVariable Long id) {
        return ApiResult.ok(handoverService.listByVehicle(id));
    }

    /**
     * 定价测算：资金成本、保本底价、阶梯建议价、滞销等级。
     * 所有参数来自 pricing_rule 表，改表后最迟 30 秒生效，不用重启。
     */
    @Operation(summary = "定价测算")
    @GetMapping("/{id}/pricing")
    public ApiResult<VehiclePricingVO> pricing(@PathVariable Long id) {
        return ApiResult.ok(pricingService.calculateByVehicleId(id));
    }

    @Operation(summary = "调整挂牌价（员工提交申请）")
    @PostMapping("/{id}/price-change")
    public ApiResult<WriteResult> changePrice(@PathVariable Long id,
                                              @Valid @RequestBody VehiclePriceChangeDTO dto) {
        return gate("CHANGE_PRICE", id, dto, () -> {
            vehicleService.changePrice(id, dto);
            return null;
        });
    }

    /**
     * 收车入库。返回新建车辆的 id，前端拿它跳详情页。
     */
    @Operation(summary = "收车入库（员工提交申请）")
    @PostMapping
    public ApiResult<WriteResult> create(@Valid @RequestBody VehicleCreateDTO dto) {
        return gate("CREATE_VEHICLE", null, dto, () -> vehicleService.create(dto));
    }

    @Operation(summary = "修改车辆基础信息（员工提交申请）")
    @PutMapping("/{id}")
    public ApiResult<WriteResult> update(@PathVariable Long id, @Valid @RequestBody VehicleUpdateDTO dto) {
        return gate("UPDATE_VEHICLE", id, dto, () -> {
            vehicleService.update(id, dto);
            return null;
        });
    }

    /**
     * 状态流转。合法路径由 VehicleStatus 枚举的 ALLOWED 表决定，
     * 非法流转返回 400 与具体原因。
     */
    @Operation(summary = "状态流转（员工提交申请）")
    @PostMapping("/{id}/status")
    public ApiResult<WriteResult> changeStatus(@PathVariable Long id,
                                               @Valid @RequestBody VehicleStatusChangeDTO dto) {
        return gate("CHANGE_STATUS", id, dto, () -> {
            vehicleService.changeStatus(id, dto);
            return null;
        });
    }

    @Operation(summary = "追加成本（员工提交申请）")
    @PostMapping("/{id}/costs")
    public ApiResult<WriteResult> addCost(@PathVariable Long id, @Valid @RequestBody VehicleCostDTO dto) {
        return gate("ADD_COST", id, dto, () -> {
            vehicleService.addCost(id, dto);
            return null;
        });
    }

    @Operation(summary = "成本明细列表")
    @GetMapping("/{id}/costs")
    public ApiResult<java.util.List<VehicleCost>> costs(@PathVariable Long id) {
        return ApiResult.ok(vehicleService.costs(id));
    }
}
