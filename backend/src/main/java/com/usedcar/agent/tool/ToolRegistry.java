package com.usedcar.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.usedcar.approval.domain.VehicleApproval;
import com.usedcar.approval.mapper.VehicleApprovalMapper;
import com.usedcar.common.BizException;
import com.usedcar.order.domain.AfterSale;
import com.usedcar.order.mapper.AfterSaleMapper;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.mapper.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具注册表：模型能调用哪些查询，只有这里说了算。
 *
 * 三层防护：
 *   1. 模型只能给工具名，找不到名字直接拒绝，不可能执行任意方法；
 *   2. 参数按 argKeys 白名单过滤，模型夹带的别的键进不到 SQL；
 *   3. 工具实现本身只读，且查车辆时带 LIMIT，不允许把整张表拉出来。
 *
 * 工具清单会动态拼进意图路由的 prompt，加一个工具不用改 prompt。
 */
@Service
public class ToolRegistry {

    @org.springframework.beans.factory.annotation.Autowired
    void registerBusinessTool(com.usedcar.approval.service.AgentBatchService batches) {
        register(new SimpleTool("propose_business_batch",
            "提交业务操作清单等待管理员确认，不直接执行。planJson 为 JSON 字符串，格式 {items:[{operation:CHANGE_PRICE|CHANGE_STATUS|UPDATE_VEHICLE|APPROVE|REJECT,targetId:数据库ID,payload:{参数}}]}。车辆操作 targetId 是车辆ID，审批操作是申请ID。调价参数 newPrice/reason，状态参数 targetStatus/remark，审批参数 remark。先查询确认目标ID和范围，不可猜测；最多100项；即使管理员发起也必须到审批中心确认。",
            List.of("planJson"), batches::propose));
    }

    private final Map<String, ReadonlyTool> tools = new LinkedHashMap<>();

    private record SimpleTool(String name, String description, List<String> argKeys,
                              Function<Map<String, Object>, Map<String, Object>> handler)
            implements ReadonlyTool {
        @Override
        public Map<String, Object> execute(Map<String, Object> args) {
            return handler.apply(args);
        }
    }

    public ToolRegistry(VehicleMapper vehicleMapper,
                        VehicleApprovalMapper approvalMapper,
                        AfterSaleMapper afterSaleMapper) {
        register(new SimpleTool(
                "count_vehicles", "统计车辆台数；不传 status 时统计「未清库的在库车辆」（排除 CLEARED），传 status 时按该状态精确统计",
                List.of("status"),
                args -> {
                    String status = text(args.get("status"));
                    Map<String, Object> out = new LinkedHashMap<>();
                    if (status != null) {
                        Long count = vehicleMapper.selectCount(new LambdaQueryWrapper<Vehicle>()
                                .eq(Vehicle::getStatus, status.toUpperCase()));
                        out.put("total", count == null ? 0 : count);
                        out.put("status", status.toUpperCase());
                    } else {
                        // 库存口径与项目里「未完成 = 未清库」保持一致：
                        // CLEARED 已经退出库存，不能混进「现在库存多少台」的答案。
                        Long inStock = vehicleMapper.selectCount(new LambdaQueryWrapper<Vehicle>()
                                .ne(Vehicle::getStatus, "CLEARED"));
                        Long cleared = vehicleMapper.selectCount(new LambdaQueryWrapper<Vehicle>()
                                .eq(Vehicle::getStatus, "CLEARED"));
                        out.put("total", inStock == null ? 0 : inStock);
                        out.put("status", "IN_STOCK");
                        out.put("clearedExcluded", cleared == null ? 0 : cleared);
                    }
                    return out;
                }));

        register(new SimpleTool(
                "vehicle_status_summary", "按状态分组统计车辆数量，返回每个状态各有多少台",
                List.of(),
                args -> {
                    QueryWrapper<Vehicle> w = new QueryWrapper<Vehicle>()
                            .select("status", "COUNT(*) AS cnt")
                            .groupBy("status");
                    List<Map<String, Object>> rows = vehicleMapper.selectMaps(w);
                    Map<String, Object> summary = new LinkedHashMap<>();
                    for (Map<String, Object> row : rows) {
                        Object key = row.get("status");
                        Object cnt = row.get("cnt");
                        if (key != null) {
                            // 直接给中文标签。交给模型自己翻译英文枚举时，
                            // 实测出现过 CLEARED→「清关完成」、PURCHASED→「已采购」这种失真。
                            summary.put(statusLabel(String.valueOf(key)), cnt);
                        }
                    }
                    return Map.of("summary", summary);
                }));

        register(new SimpleTool(
                "find_vehicle", "按库存号精确查车，或按品牌/车型关键词模糊查车，最多返回 5 台",
                List.of("stockNo", "keyword"),
                args -> {
                    String stockNo = text(args.get("stockNo"));
                    String keyword = text(args.get("keyword"));
                    LambdaQueryWrapper<Vehicle> w = new LambdaQueryWrapper<Vehicle>()
                            .eq(StringUtils.hasText(stockNo), Vehicle::getStockNo, stockNo)
                            .and(StringUtils.hasText(keyword), a -> a
                                    .like(Vehicle::getBrand, keyword)
                                    .or().like(Vehicle::getModel, keyword))
                            .last("LIMIT 5");
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (Vehicle v : vehicleMapper.selectList(w)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", v.getId());
                        item.put("stockNo", v.getStockNo());
                        item.put("brand", v.getBrand());
                        item.put("model", v.getModel());
                        item.put("status", v.getStatus());
                        item.put("listPrice", v.getListPrice());
                        list.add(item);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("count", list.size());
                    out.put("vehicles", list);
                    return out;
                }));

        register(new SimpleTool(
                "count_pending_approvals", "统计待管理员审批的申请单数量",
                List.of(),
                args -> {
                    Long count = approvalMapper.selectCount(new LambdaQueryWrapper<VehicleApproval>()
                            .eq(VehicleApproval::getStatus, "PENDING"));
                    return Map.of("pending", count == null ? 0 : count);
                }));

        register(new SimpleTool(
                "count_open_after_sales", "统计尚未结案的售后单数量（未结案会阻止车辆清库）",
                List.of(),
                args -> {
                    Long count = afterSaleMapper.selectCount(new LambdaQueryWrapper<AfterSale>()
                            .eq(AfterSale::getStatus, "OPEN"));
                    return Map.of("open", count == null ? 0 : count);
                }));
    }

    private void register(ReadonlyTool tool) {
        tools.put(tool.name(), tool);
    }

    /** 拼给模型看的工具清单，格式 "- name(arg1,arg2): 说明"。 */
    public List<String> descriptions() {
        return tools.values().stream()
                .map(t -> "- " + t.name() + "(" + String.join(",", t.argKeys()) + "): " + t.description())
                .toList();
    }

    /**
     * 生成 OpenAI tools 协议的函数声明，给 function calling 用。
     *
     * 参数统一声明成 string：工具实现里 ToolRegistry.execute 只接受标量，
     * 声明成 integer / boolean 会让模型传数字，进来还得转一次，反而多一处不一致。
     * required 故意留空：所有参数都是可选的，缺省值由工具自己按业务口径补。
     */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (ReadonlyTool tool : tools.values()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (String key : tool.argKeys()) {
                properties.put(key, Map.of("type", "string", "description", "参数 " + key));
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", properties);
            parameters.put("required", List.of());

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", parameters);

            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", "function");
            def.put("function", function);
            defs.add(def);
        }
        return defs;
    }

    /**
     * 白名单执行。模型给的工具名和参数在这里被收口，
     * 它拿不到 Mapper、拿不到 SQL，只能拿到执行后的结构化结果。
     */
    public Map<String, Object> execute(String name, Map<String, Object> args) {
        ReadonlyTool tool = tools.get(name);
        if (tool == null) {
            throw new BizException(400, "没有名为 " + name + " 的查询工具");
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        Map<String, Object> given = args == null ? Map.of() : args;
        for (String key : tool.argKeys()) {
            Object v = given.get(key);
            if (v != null && (!(v instanceof String) || StringUtils.hasText((String) v))) {
                // 参数值只接受标量，挡掉模型塞进来的嵌套对象/数组
                if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                    safe.put(key, v);
                }
            }
        }
        return tool.execute(safe);
    }

    /** 状态枚举 → 中文标签，标签只在 Java 侧定义一次，模型照抄即可。 */
    private static String statusLabel(String status) {
        return switch (status) {
            case "PURCHASED" -> "已收车";
            case "ON_SALE" -> "在售";
            case "SOLD" -> "已成交";
            case "AFTER_SALE" -> "售后";
            case "CLEARED" -> "已清库";
            default -> status;
        };
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
