package com.usedcar;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 *
 * @SpringBootApplication 是三个注解的组合：
 *   @SpringBootConfiguration  声明这是配置类
 *   @EnableAutoConfiguration  按 classpath 上的依赖自动装配（有 MySQL 驱动就配数据源）
 *   @ComponentScan            扫描本类所在包 com.usedcar 及其子包
 * 所以包名必须是 com.usedcar，所有业务代码都要放在它下面，否则扫不到。
 *
 * @MapperScan 把 mapper 接口注册成 Bean，省掉每个接口上写 @Mapper。
 *
 * @EnableScheduling 打开 @Scheduled，滞销巡检和证件预警靠它定时跑。
 * @EnableAsync      打开 @Async，视频任务提交/轮询靠它丢到线程池。
 */
@SpringBootApplication
@MapperScan("com.usedcar.**.mapper")
@EnableScheduling
@EnableAsync
public class UsedCarApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsedCarApplication.class, args);
    }
}
