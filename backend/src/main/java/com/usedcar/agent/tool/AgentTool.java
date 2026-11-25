package com.usedcar.agent.tool;

/**
 * Agent 可调用的业务工具。
 *
 * 这里刻意做成显式接口，而不是让 Service 直接 new 一个 DTO 调 Mapper：
 * 「工具」有名字、有说明、有唯一执行入口，边界才说得清。
 *
 * 重要边界：本项目的工具**不由模型自主触发**。
 * 模型只产出参数，工具由 AiIntakeService 在「老板点了确认」之后调用。
 * 这样 AI 全程没有写库的路径，哪怕模型被提示词注入、输出了再离谱的内容，
 * 也到不了 vehicle 表。面试里这一条要主动讲，比堆 function calling 更有说服力。
 */
public interface AgentTool<P, R> {

    String name();

    String description();

    R execute(P params);
}
