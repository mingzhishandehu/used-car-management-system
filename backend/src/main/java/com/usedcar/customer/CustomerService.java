package com.usedcar.customer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.usedcar.common.BizException;
import com.usedcar.customer.domain.Customer;
import com.usedcar.customer.dto.CustomerCreateDTO;
import com.usedcar.customer.mapper.CustomerMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 客户业务。
 *
 * 这是 customer 表从「只有 DDL」变成「Java 侧接线」的最小闭环：
 * 分页查询 / 详情 / 新增（带手机号查重）。
 *
 * 不在这里做「成交时关联客户」——那属于 sale_order 模块，等 R03 接线时再接。
 */
@Service
public class CustomerService {

    /** 与 V1 DDL 的 customer_type 注释保持一致，非法值在 create 里挡掉 */
    private static final Set<String> ALLOWED_TYPES = Set.of("BUYER", "SELLER", "BOTH");
    private static final Set<String> ALLOWED_LEVELS = Set.of("NORMAL", "VIP");

    private final CustomerMapper customerMapper;

    public CustomerService(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    /**
     * 分页查询。keyword 同时模糊匹配姓名和手机号，customerType 精确过滤。
     */
    public IPage<Customer> page(long page, long size, String keyword, String customerType) {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<Customer>()
                .eq(StringUtils.hasText(customerType), Customer::getCustomerType, customerType)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Customer::getName, keyword)
                        .or()
                        .like(Customer::getPhone, keyword))
                .orderByDesc(Customer::getId);
        return customerMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public Customer detail(Long id) {
        Customer customer = customerMapper.selectById(id);
        if (customer == null) {
            throw new BizException(404, "客户不存在");
        }
        return customer;
    }

    /**
     * 新增客户。
     *
     * 查重按 phone 做：V1 的 DDL 注释把 phone 定为「查重主依据」。
     *
     * 已知限制（不要假装已经解决）：V1 里 idx_phone 是普通索引，不是唯一索引，
     * 所以这个「先查后插」在并发下仍可能插入两个同号客户。
     * 彻底解决要加唯一索引 + 捕 DuplicateKeyException，与 REVIEW 清单 R10 是同一类问题。
     *
     * created_at / updated_at 不在这里赋值：MyBatis-Plus 默认 NOT_NULL 策略会跳过 null 字段，
     * 由 DDL 的 DEFAULT CURRENT_TIMESTAMP 自动填。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(CustomerCreateDTO dto) {
        String type = normalize(dto.customerType(), ALLOWED_TYPES, "BUYER", "客户类型");
        String level = normalize(dto.level(), ALLOWED_LEVELS, "NORMAL", "客户等级");

        String phone = dto.phone().trim();
        Long exists = customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getPhone, phone));
        if (exists != null && exists > 0) {
            throw new BizException(400, "手机号已存在，请勿重复录入: " + phone);
        }

        Customer customer = new Customer();
        customer.setName(dto.name().trim());
        customer.setPhone(phone);
        customer.setIdCard(dto.idCard());
        customer.setCustomerType(type);
        customer.setLevel(level);
        customer.setSource(dto.source());
        customer.setRemark(dto.remark());
        try {
            customerMapper.insert(customer);
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求刚好在我们「查重」之后插完了同一个号，
            // 上面的 selectCount 看不到它。这里由 uk_phone 唯一索引兜底。
            // customer 表除了主键只有这一个唯一键，且 insert 不指定 id，
            // 所以这个异常可以确定为手机号冲突，转成业务提示而不是 500。
            // 抛出 BizException 后由外层事务回滚，本次 insert 不会留下数据。
            throw new BizException(400, "手机号已存在，请勿重复录入: " + phone);
        }
        return customer.getId();
    }

    /** 空值落默认值；非空但非法直接抛业务异常，不让脏值写进库 */
    private String normalize(String raw, Set<String> allowed, String fallback, String label) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        String value = raw.trim().toUpperCase();
        if (!allowed.contains(value)) {
            throw new BizException(400, "非法的" + label + ": " + raw);
        }
        return value;
    }
}
