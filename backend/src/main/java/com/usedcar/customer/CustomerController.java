package com.usedcar.customer;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.usedcar.common.ApiResult;
import com.usedcar.customer.domain.Customer;
import com.usedcar.customer.dto.CustomerCreateDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户接口。
 *
 * 挂在 /api/customers，SaTokenConfig 默认拦 /api/**，无需单独配置鉴权。
 * 这一步做完，customer 表才算「Java 侧有写入口和读入口」，而不只是库里有 8 行数据。
 */
@Tag(name = "客户")
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Operation(summary = "分页查询客户")
    @GetMapping
    public ApiResult<IPage<Customer>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String customerType) {
        return ApiResult.ok(customerService.page(page, size, keyword, customerType));
    }

    @Operation(summary = "客户详情")
    @GetMapping("/{id}")
    public ApiResult<Customer> detail(@PathVariable Long id) {
        return ApiResult.ok(customerService.detail(id));
    }

    @Operation(summary = "新增客户（手机号查重）")
    @PostMapping
    public ApiResult<Long> create(@Valid @RequestBody CustomerCreateDTO dto) {
        return ApiResult.ok(customerService.create(dto));
    }
}
