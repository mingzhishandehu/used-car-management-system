package com.usedcar.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 打开 AiProperties 的构造器绑定。
 *
 * 单独放一个配置类而不是在启动类上加 @ConfigurationPropertiesScan：
 * 扫描会把整个包扫一遍，而这个项目只需要一个属性类，显式注册更可控。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {
}
