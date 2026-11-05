package com.usedcar.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 登录校验配置。
 *
 * 思路：默认所有 /api/** 都要登录，白名单显式放开。
 * 反过来（默认放开、逐个加校验）一定会漏，漏一个接口就是越权漏洞。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                // 拦全部业务接口
                .addPathPatterns("/api/**")
                // 放行：登录接口本身、健康检查、OpenAPI 文档
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/health",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                );
    }
}
