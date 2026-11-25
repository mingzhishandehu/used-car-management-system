package com.usedcar.customer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.customer.domain.Customer;

/**
 * 客户数据访问。
 *
 * 单表 CRUD 全部由 BaseMapper 提供，不写 XML。
 * 这个接口必须出现在 UsedCarApplication 的 @MapperScan 显式包列表里，
 * 否则不会被注册成 Bean。
 */
public interface CustomerMapper extends BaseMapper<Customer> {
}
