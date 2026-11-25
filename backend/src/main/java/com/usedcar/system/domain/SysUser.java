package com.usedcar.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 员工账号，对应 sys_user 表。
 *
 * 注意：这张表没有 deleted 列，所以不加 @TableLogic。
 * 账号「停用」用 status=0 表达，不做逻辑删除——删掉的账号会让历史流水的 operator_id 变成孤儿。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名 */
    private String username;

    /** BCrypt 密文，绝不返回给前端 */
    private String password;

    /** 真实姓名 */
    private String realName;

    private String phone;

    /** 角色：ADMIN 管理员 / SALES 销售 / FINANCE 财务 */
    private String role;

    /** 1 启用 / 0 停用，表达「能不能登录」 */
    private Integer status;

    /**
     * 在职状态：ACTIVE 在任 / LEFT 离职。
     * 和 status 是两个维度：临时停用的在职员工不能被当成离职。
     * 办理离职时两个字段一起改，只停用不删账号，历史流水的 operator_id 才不会变孤儿。
     */
    private String employmentStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
