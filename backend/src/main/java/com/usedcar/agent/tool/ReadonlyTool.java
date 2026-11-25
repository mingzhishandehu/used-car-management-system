package com.usedcar.agent.tool;

import java.util.List;
import java.util.Map;

/**
 * Agent 可用的只读工具。
 *
 * 刻意不叫「Tool」而叫「ReadonlyTool」：这个项目里模型能触发的只有查询，
 * 写库依然由 CreateVehicleTool 在 Java 侧硬编码调用、不由模型决定。
 * 读写权限在类型上分开，比靠 prompt 里写一句「不许写库」可靠得多。
 */
public interface ReadonlyTool {

    /** 工具名，模型只能输出注册表里已有的名字 */
    String name();

    /** 给模型看的一句话说明，会拼进意图路由的 prompt */
    String description();

    /** 允许的参数名白名单，模型传的其他键会被编排层丢掉 */
    List<String> argKeys();

    /** 执行只读查询，返回结构化结果；禁止在这里写任何表 */
    Map<String, Object> execute(Map<String, Object> args);
}
