package com.usedcar.vehicle.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.usedcar.common.ApiResult;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.mapper.VehicleMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车辆查询接口（骨架阶段只读，写接口在阶段一补齐）。
 *
 * 这一层只做参数接收和结果包装，不写业务规则。
 * 业务规则（状态机、定价）都在 Service 层，Controller 里出现 if 判断就是分层走偏了。
 */
@Tag(name = "车辆管理")
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleMapper vehicleMapper;

    public VehicleController(VehicleMapper vehicleMapper) {
        this.vehicleMapper = vehicleMapper;
    }

    /**
     * 分页查询车辆。
     *
     * @param page    页码，从 1 开始
     * @param size    每页条数，上限由分页插件兜到 500
     * @param keyword 模糊匹配品牌或车型，可为空
     * @param status  按状态精确过滤，可为空
     */
    @Operation(summary = "分页查询车辆")
    @GetMapping
    public ApiResult<IPage<Vehicle>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {

        // LambdaQueryWrapper 用方法引用来写列名，编译期就能发现字段改名，比字符串安全
        LambdaQueryWrapper<Vehicle> wrapper = new LambdaQueryWrapper<Vehicle>()
                .eq(StringUtils.hasText(status), Vehicle::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Vehicle::getBrand, keyword)
                        .or()
                        .like(Vehicle::getModel, keyword))
                // 默认按收车日期倒序，最新收的车排前面
                .orderByDesc(Vehicle::getPurchaseDate)
                .orderByDesc(Vehicle::getId);

        return ApiResult.ok(vehicleMapper.selectPage(new Page<>(page, size), wrapper));
    }
}
