package com.usedcar.agent.service;

import com.usedcar.agent.domain.AiAgentTrace;
import com.usedcar.agent.mapper.AiAgentTraceMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轨迹写入器。独立成一个 Bean 只为一件事：让 REQUIRES_NEW 真的生效。
 *
 * 为什么必须独立事务（实测踩到的）：
 *   AiIntakeService.chat() 是 @Transactional，整条链路共用同一个事务。
 *   把轨迹写在里面，一旦后面抽取器或业务校验抛异常、事务回滚，
 *   刚落的轨迹会跟着一起消失——而失败场景恰恰是最需要留痕的。
 *   实测：故意把模型名配错，route_failed 已经 INSERT 成功（日志 Updates: 1），
 *   请求最后一抛异常，查库一条都没有。
 *
 * 为什么单独一个类：
 *   @Transactional 靠 Spring 代理生效，同一个类里自己调自己不走代理，
 *   REQUIRES_NEW 会被忽略，退化成加入当前事务，等于没改。
 *
 * 代价：每次工具执行多占一个连接（一次提问最多 3 轮，Hikari 默认 10 个连接够用）。
 * 主事务仍持有自己的连接，两者写不同表，不会互相等锁。
 */
@Component
public class AgentTraceRecorder {

    private final AiAgentTraceMapper mapper;

    public AgentTraceRecorder(AiAgentTraceMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(AiAgentTrace trace) {
        mapper.insert(trace);
    }
}
