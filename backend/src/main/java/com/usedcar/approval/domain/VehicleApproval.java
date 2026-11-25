package com.usedcar.approval.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 车辆管理申请单，对应 vehicle_approval 表。
 *
 * payloadJson 是「提交那一刻的参数快照」，不是业务表引用。
 * 员工提交到老板批准之间，车可能已经被别人改过；批准时必须按申请内容执行。
 */
@Data
@Accessors(chain = true)
@TableName("vehicle_approval")
public class VehicleApproval {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** CREATE_VEHICLE / AI_INTAKE_CREATE / UPDATE_VEHICLE / CHANGE_STATUS / CHANGE_PRICE / ADD_COST */
    private String operationType;

    /** MANUAL 表单 / AI 聊天收车 */
    private String source;

    /** 针对已有车辆的操作；CREATE 类为 null */
    private Long targetVehicleId;

    /** 参数快照 JSON */
    private String payloadJson;

    /** 申请人 sys_user.id，批准后作为 vehicle.created_by */
    private Long applicantId;

    /** PENDING / APPROVED / REJECTED / CANCELLED */
    private String status;

    private Long approverId;

    private String approveRemark;

    /** CREATE 类批准后生成的 vehicle.id */
    private Long resultVehicleId;

    /** AI 收车来源会话；批准后要回写该会话的卡片状态，否则页面一直显示「待审批」 */
    private Long sourceConversationId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
