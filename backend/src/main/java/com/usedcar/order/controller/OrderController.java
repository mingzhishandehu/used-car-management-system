package com.usedcar.order.controller;

import com.usedcar.approval.dto.WriteResult;
import com.usedcar.approval.service.ApprovalGate;
import com.usedcar.common.ApiResult;
import com.usedcar.order.domain.AfterSale;
import com.usedcar.order.domain.SaleOrder;
import com.usedcar.order.dto.AfterSaleCreateDTO;
import com.usedcar.order.dto.SaleOrderCreateDTO;
import com.usedcar.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 成交订单与售后接口。
 * 三个写操作和车辆其他写操作一样过 ApprovalGate：ADMIN 直接执行，员工落申请单。
 */
@Tag(name = "成交与售后")
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;
    private final ApprovalGate approvalGate;

    public OrderController(OrderService orderService, ApprovalGate approvalGate) {
        this.orderService = orderService;
        this.approvalGate = approvalGate;
    }

    private ApiResult<WriteResult> gate(String type, Long vehicleId, Object payload,
                                        Supplier<Object> executor) {
        Long pending = approvalGate.submitIfEmployee(type, "MANUAL", vehicleId, payload);
        if (pending != null) {
            return ApiResult.ok(WriteResult.pending(pending));
        }
        return ApiResult.ok(WriteResult.executed(executor.get()));
    }

    @Operation(summary = "某辆车的成交订单")
    @GetMapping("/vehicles/{vehicleId}/sale-orders")
    public ApiResult<List<SaleOrder>> listOrders(@PathVariable Long vehicleId) {
        return ApiResult.ok(orderService.listOrders(vehicleId));
    }

    @Operation(summary = "成交登记（员工提交申请）")
    @PostMapping("/vehicles/{vehicleId}/sale-orders")
    public ApiResult<WriteResult> createOrder(@PathVariable Long vehicleId,
                                              @Valid @RequestBody SaleOrderCreateDTO dto) {
        return gate("CREATE_SALE_ORDER", vehicleId, dto,
                () -> orderService.createSaleOrder(vehicleId, dto));
    }

    @Operation(summary = "某辆车的售后记录")
    @GetMapping("/vehicles/{vehicleId}/after-sales")
    public ApiResult<List<AfterSale>> listAfterSales(@PathVariable Long vehicleId) {
        return ApiResult.ok(orderService.listAfterSales(vehicleId));
    }

    @Operation(summary = "新增售后单（员工提交申请）")
    @PostMapping("/vehicles/{vehicleId}/after-sales")
    public ApiResult<WriteResult> createAfterSale(@PathVariable Long vehicleId,
                                                  @Valid @RequestBody AfterSaleCreateDTO dto) {
        return gate("ADD_AFTER_SALE", vehicleId, dto,
                () -> orderService.createAfterSale(vehicleId, dto));
    }

    @Operation(summary = "售后结案（员工提交申请）")
    @PostMapping("/after-sales/{id}/close")
    public ApiResult<WriteResult> closeAfterSale(@PathVariable Long id) {
        return gate("CLOSE_AFTER_SALE", null, Map.of("afterSaleId", id),
                () -> {
                    orderService.closeAfterSale(id);
                    return null;
                });
    }
}
