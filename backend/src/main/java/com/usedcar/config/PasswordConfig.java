package com.usedcar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器。
 *
 * 只引了 spring-security-crypto 这一个子模块，没有引 spring-boot-starter-security。
 * 原因：整套 Spring Security 会自带一整套过滤器链和默认登录页，和 Sa-Token 的鉴权体系冲突，
 * 我们要的只是 BCrypt 这一个算法。
 */
@Configuration
public class PasswordConfig {

    /**
     * BCrypt 而不是 MD5 / SHA-256 的原因：
     *   MD5 是无盐哈希，同一个密码永远得到同一个值，彩虹表一秒就撞开；
     *   BCrypt 自带随机盐，且计算慢（默认强度 10，单次约 50~100ms），
     *   攻击者想暴力枚举，成本被放大几万倍。
     * 算出来的密文形如 $2a$10$N9qo8uLOickgx2ZMRZoMye...，前 29 位包含盐，所以不需要单独存盐字段。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
