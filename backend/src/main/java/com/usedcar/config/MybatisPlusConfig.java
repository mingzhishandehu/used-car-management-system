package com.usedcar.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * 只配分页插件，其余走默认。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件。
     *
     * 不配这个 Bean 的后果：Mapper 里传 Page 对象会被当成普通参数忽略，
     * 查询直接返回全表数据，不会报错——这是最容易埋雷的地方，所以必须配。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大条数上限，防止前端传 size=999999 一次把库拖死
        pagination.setMaxLimit(500L);
        // 请求页码超过总页数时，回到第一页而不是返回空列表
        pagination.setOverflow(false);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
