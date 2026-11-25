package com.usedcar.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.approval.domain.VehicleApproval;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 申请单 Mapper。
 *
 * casApprove 把「只有 PENDING 才能批准」写进 UPDATE 的 WHERE：
 * 两个管理员同时点通过时，第二个请求会被行锁挡住，
 * 等第一个提交后重新判断条件，影响行数变成 0，不会重复执行业务写入。
 */
public interface VehicleApprovalMapper extends BaseMapper<VehicleApproval> {

    @Update("UPDATE vehicle_approval SET status = 'APPROVED', approver_id = #{approverId}, " +
            "approve_remark = #{remark} WHERE id = #{id} AND status = 'PENDING'")
    int casApprove(@Param("id") Long id, @Param("approverId") Long approverId,
                   @Param("remark") String remark);

    @Update("UPDATE vehicle_approval SET status = 'REJECTED', approver_id = #{approverId}, " +
            "approve_remark = #{remark} WHERE id = #{id} AND status = 'PENDING'")
    int casReject(@Param("id") Long id, @Param("approverId") Long approverId,
                  @Param("remark") String remark);

    @Update("UPDATE vehicle_approval SET status = 'CANCELLED' " +
            "WHERE id = #{id} AND applicant_id = #{applicantId} AND status = 'PENDING'")
    int casCancel(@Param("id") Long id, @Param("applicantId") Long applicantId);
}
