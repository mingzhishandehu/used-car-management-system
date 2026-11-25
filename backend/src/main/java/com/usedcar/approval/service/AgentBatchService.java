package com.usedcar.approval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.usedcar.vehicle.mapper.VehicleMapper;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.common.BizException;
import com.usedcar.vehicle.dto.*;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import java.util.*;

/** Agent only proposes immutable batches; the normal admin approval endpoint executes them. */
@Service
public class AgentBatchService {
    public record Item(String operation, Long targetId, Map<String, Object> payload, JsonNode vehicleSnapshot) {}
    public record Plan(List<Item> items) {}
    private final ApprovalGate gate;
    private final ObjectMapper json;
    private final Validator validator;
    private final VehicleMapper vehicles;
    public AgentBatchService(ApprovalGate gate, ObjectMapper json, Validator validator, VehicleMapper vehicles) {
        this.gate = gate; this.json = json; this.validator = validator; this.vehicles = vehicles;
    }
    public Plan parse(String raw) {
        try {
            Plan plan = json.readValue(raw, Plan.class);
            if (plan.items() == null || plan.items().isEmpty() || plan.items().size() > 100)
                throw new BizException(400, "每批需要 1 到 100 项操作");
            Set<String> targets = new HashSet<>();
            for (Item item : plan.items()) {
                if (item == null || item.operation() == null || item.targetId() == null || item.targetId() <= 0 || item.payload() == null)
                    throw new BizException(400, "操作缺少有效目标或参数");
                String namespace = Set.of("APPROVE", "REJECT").contains(item.operation()) ? "approval:" : "vehicle:";
                if (!targets.add(namespace + item.targetId())) throw new BizException(400, "同一批次不允许重复目标");
                Class<?> type = switch (item.operation()) {
                    case "CHANGE_PRICE" -> VehiclePriceChangeDTO.class;
                    case "CHANGE_STATUS" -> VehicleStatusChangeDTO.class;
                    case "UPDATE_VEHICLE" -> VehicleUpdateDTO.class;
                    case "APPROVE", "REJECT" -> Map.class;
                    default -> throw new BizException(400, "不支持的批量操作");
                };
                if (type != Map.class && !validator.validate(json.convertValue(item.payload(), type)).isEmpty())
                    throw new BizException(400, "操作参数未通过校验");
                if ("REJECT".equals(item.operation()) && !(item.payload().get("remark") instanceof String remark && !remark.isBlank()))
                    throw new BizException(400, "驳回必须提供原因");
            }
            return plan;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "批量操作格式无效"); }
    }
    private JsonNode snapshotOf(Vehicle vehicle) {
        try {
            // Compare the same JSON representation after storage and retrieval, including decimal values.
            return json.readTree(json.writeValueAsBytes(vehicle));
        } catch (Exception e) {
            throw new BizException(500, "无法生成车辆快照");
        }
    }
    private boolean isVehicleOperation(Item item) {
        return !Set.of("APPROVE", "REJECT").contains(item.operation());
    }

    /** Call only inside the approval transaction; locks prevent a check/write race. */
    public void lockAndVerify(Plan plan) {
        List<Item> items = plan.items().stream().filter(this::isVehicleOperation)
                .sorted(Comparator.comparing(Item::targetId)).toList();
        for (Item item : items) {
            if (item.vehicleSnapshot() == null || item.vehicleSnapshot().isNull())
                throw new BizException(400, "旧批量申请缺少车辆快照，请重新生成申请");
            Vehicle current = vehicles.selectActiveForUpdate(item.targetId());
            if (current == null || !item.vehicleSnapshot().equals(snapshotOf(current)))
                throw new BizException(409, "车辆 " + item.targetId() + " 已发生变化，请重新生成申请");
        }
    }
    public Map<String, Object> propose(Map<String, Object> args) {
        Long viewerId = com.usedcar.agent.service.AgentActorContext.viewerId();
        var me = viewerId == null ? gate.current() : gate.requireActive(viewerId);
        Plan plan = parse(String.valueOf(args.getOrDefault("planJson", "")));
        if (plan.items().stream().anyMatch(i -> Set.of("APPROVE", "REJECT").contains(i.operation()))
                && !"ADMIN".equals(me.getRole())) throw new BizException(403, "只有管理员能发起审批操作");
        // Ignore any snapshot supplied by the model: only the server can capture it.
        List<Item> captured = new ArrayList<>();
        for (Item item : plan.items()) {
            JsonNode snapshot = null;
            if (isVehicleOperation(item)) {
                Vehicle vehicle = vehicles.selectById(item.targetId());
                if (vehicle == null) throw new BizException(400, "目标车辆不存在：" + item.targetId());
                snapshot = snapshotOf(vehicle);
            }
            captured.add(new Item(item.operation(), item.targetId(), item.payload(), snapshot));
        }
        plan = new Plan(List.copyOf(captured));
        Long id = gate.insert("AGENT_BATCH", "AI", null, plan, me.getId(), null);
        return Map.of("approvalId", id, "itemCount", plan.items().size(), "status", "PENDING",
                "message", "操作清单已提交审批中心，必须由管理员确认后执行，当前尚未修改业务数据");
    }
}
