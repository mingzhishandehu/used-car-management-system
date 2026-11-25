package com.usedcar.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.system.domain.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 员工账号数据访问。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 办理离职的 CAS。条件写进 WHERE：两个管理员同时点「办理离职」时，
     * 第二个请求等第一个提交后重新判断，影响行数变成 0，不会把同一批车流转两次。
     * 同时把 status 置 0（禁止登录），但账号本身保留，历史流水的 operator_id 不变孤儿。
     */
    @Update("UPDATE sys_user SET employment_status = 'LEFT', status = 0 " +
            "WHERE id = #{id} AND employment_status = 'ACTIVE'")
    int casLeave(@Param("id") Long id);
}
