package com.usedcar.system;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.usedcar.common.BizException;
import com.usedcar.system.domain.SysUser;
import com.usedcar.system.dto.EmployeeCreateDTO;
import com.usedcar.system.dto.EmployeePasswordDTO;
import com.usedcar.system.dto.EmployeeUpdateDTO;
import com.usedcar.system.dto.EmployeeVO;
import com.usedcar.system.dto.LeaveResult;
import com.usedcar.system.mapper.SysUserMapper;
import com.usedcar.vehicle.service.VehicleHandoverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 员工账号管理。
 *
 * 权限模型：老板持有 ADMIN 账号，员工持有 SALES / FINANCE 账号。
 * 所有「写」操作集中在本类的 requireAdmin() 一道闸，读列表不限制
 * （车辆列表页、收车表单都要用它做姓名映射和对接人下拉）。
 *
 * 关于角色这个字段：之前它只被写进 Sa-Token 会话、从没用于鉴权。
 * 这里开始真正用它，但**不读 session 里的 role 快照**——管理员可能刚被改了角色
 * 或被停用，会话里的旧值会让他继续拥有权限。每次写操作都回查一次 sys_user，
 * 员工数量是门店级的，这一次查询的成本可以忽略。
 *
 * 不做删除：sys_user 的注释里写过，删掉的账号会让 vehicle_status_log.operator_id
 * 和 vehicle.created_by 变成孤儿。停用（status=0）才是正确做法。
 */
@Service
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);
    private static final Set<String> ROLES = Set.of("ADMIN", "SALES", "FINANCE");

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final VehicleHandoverService handoverService;

    public EmployeeService(SysUserMapper sysUserMapper, PasswordEncoder passwordEncoder,
                           VehicleHandoverService handoverService) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.handoverService = handoverService;
    }

    // ==================== 读 ====================

    /**
     * 员工列表。keyword 匹配登录名/姓名/手机号；
     * employmentStatus 传 ACTIVE / LEFT 做在职筛选，不传返回全部。
     */
    public List<EmployeeVO> list(String keyword, String employmentStatus) {
        List<SysUser> rows = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(SysUser::getUsername, keyword)
                                .or().like(SysUser::getRealName, keyword)
                                .or().like(SysUser::getPhone, keyword))
                        .eq(StringUtils.hasText(employmentStatus), SysUser::getEmploymentStatus,
                                employmentStatus == null ? null : employmentStatus.trim().toUpperCase())
                        .orderByAsc(SysUser::getId));
        return rows.stream().map(this::toVO).toList();
    }

    /** 离职弹窗里预览「将流转几辆车」。 */
    public long pendingHandoverCount(Long id) {
        requireAdmin();
        requireUser(id);
        return handoverService.pendingCount(id);
    }

    // ==================== 离职交接 ====================

    /**
     * 办理离职：把该员工名下未完成的车辆对接权转给接手人，并留下完整流转记录。
     *
     * 顺序是有讲究的：先 casLeave 抢到「只有 ACTIVE 才能离职」这一行的锁，
     * 再做车辆流转。两个管理员同时操作时第二个 CAS 影响行数为 0，直接拒绝，
     * 不会出现同一批车被流转两次。所有写操作在同一个事务里，中途失败全部回滚。
     *
     * 只停用不删除账号：vehicle.created_by、状态流水、申请单、AI 会话都还指着这个 id。
     */
    @Transactional(rollbackFor = Exception.class)
    public LeaveResult leave(Long id, Long successorId, String reason) {
        Long operatorId = requireAdmin();
        SysUser target = requireUser(id);
        if ("LEFT".equals(target.getEmploymentStatus())) {
            throw new BizException(400, "该员工已办理离职");
        }
        if (id.equals(successorId)) {
            throw new BizException(400, "不能把车辆流转给离职员工本人");
        }
        if (id.equals(operatorId)) {
            throw new BizException(400, "不能给自己办理离职");
        }
        SysUser successor = requireUser(successorId);
        if (!"ACTIVE".equals(successor.getEmploymentStatus())) {
            throw new BizException(400, "接手人必须是离职以外的在职员工");
        }
        if (successor.getStatus() == null || successor.getStatus() != 1) {
            throw new BizException(400, "接手人账号已停用，请先启用");
        }

        if (sysUserMapper.casLeave(id) == 0) {
            throw new BizException(400, "该员工已办理离职，请勿重复操作");
        }
        int count = handoverService.handover(id, successorId, operatorId, reason);

        String name = StringUtils.hasText(successor.getRealName())
                ? successor.getRealName() : successor.getUsername();
        log.info("员工 {} 办理离职，{} 辆未清库车辆流转给 {}，操作人 {}", id, count, successorId, operatorId);
        return new LeaveResult(id, successorId, name, count);
    }

    // ==================== 写（全部要求 ADMIN） ====================

    @Transactional(rollbackFor = Exception.class)
    public EmployeeVO create(EmployeeCreateDTO dto) {
        requireAdmin();
        String role = checkRole(dto.role());
        String username = dto.username().trim();

        // Java 层查重只负责给出友好提示，真正兜住并发的是 sys_user.uk_username 唯一键
        Long exists = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (exists != null && exists > 0) {
            throw new BizException(400, "登录名已存在");
        }

        SysUser row = new SysUser();
        row.setUsername(username);
        row.setPassword(passwordEncoder.encode(dto.password()));
        row.setRealName(dto.realName().trim());
        row.setPhone(StringUtils.hasText(dto.phone()) ? dto.phone().trim() : null);
        row.setRole(role);
        row.setStatus(1);
        // 显式给，别依赖数据库默认值：insert 后 Java 对象要直接拿去转 VO 返回
        row.setEmploymentStatus("ACTIVE");

        try {
            sysUserMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 两个管理员同时建同名账号时，先查后插会都查到 0 条，靠唯一键拦下第二个
            throw new BizException(400, "登录名已存在");
        }
        log.info("新增员工 {} / {}，操作人 {}", username, role, StpUtil.getLoginIdAsLong());
        return toVO(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public EmployeeVO update(Long id, EmployeeUpdateDTO dto) {
        requireAdmin();
        String role = checkRole(dto.role());
        requireUser(id);

        // 把自己从 ADMIN 改成 SALES 会当场失去管理权限，且没有第二个管理员能改回来，
        // 这类「把自己锁在门外」的操作直接拒绝
        if (id.equals(StpUtil.getLoginIdAsLong()) && !"ADMIN".equals(role)) {
            throw new BizException(400, "不能把自己的管理员角色改掉");
        }

        // 用 LambdaUpdateWrapper 而不是 updateById：
        // updateById 只更新非 null 字段，传 null 会被当成「这一项不动」，
        // 老板想把手机号清空就永远清不掉。这里显式 set，允许写 NULL。
        String phone = StringUtils.hasText(dto.phone()) ? dto.phone().trim() : null;
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .set(SysUser::getRealName, dto.realName().trim())
                .set(SysUser::getPhone, phone)
                .set(SysUser::getRole, role));
        return toVO(sysUserMapper.selectById(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void setStatus(Long id, Integer status) {
        requireAdmin();
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(400, "状态只能是 0 或 1");
        }
        requireUser(id);
        if (id.equals(StpUtil.getLoginIdAsLong()) && status == 0) {
            throw new BizException(400, "不能停用自己的账号");
        }

        SysUser patch = new SysUser();
        patch.setId(id);
        patch.setStatus(status);
        sysUserMapper.updateById(patch);
        log.info("员工 {} 状态改为 {}，操作人 {}", id, status, StpUtil.getLoginIdAsLong());
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, EmployeePasswordDTO dto) {
        requireAdmin();
        requireUser(id);

        SysUser patch = new SysUser();
        patch.setId(id);
        patch.setPassword(passwordEncoder.encode(dto.newPassword()));
        sysUserMapper.updateById(patch);
        log.info("重置员工 {} 的密码，操作人 {}", id, StpUtil.getLoginIdAsLong());
    }

    // ==================== 内部 ====================

    /**
     * 唯一的管理员校验入口。后续要接「员工申请 → 老板审批」时，
     * 权限判断仍收在这一处，不用去每个 Controller 里翻。
     */
    /** 校验管理员并返回其 id；离职交接要拿它当 operator_id 写进流转记录。 */
    private Long requireAdmin() {
        Long myId = StpUtil.getLoginIdAsLong();
        SysUser me = sysUserMapper.selectById(myId);
        if (me == null) {
            throw new BizException(401, "账号不存在");
        }
        if (me.getStatus() == null || me.getStatus() != 1) {
            throw new BizException(403, "账号已停用");
        }
        if (!"ADMIN".equals(me.getRole())) {
            throw new BizException(403, "只有管理员能管理员工");
        }
        return myId;
    }

    private SysUser requireUser(Long id) {
        SysUser row = sysUserMapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "员工不存在");
        }
        return row;
    }

    private String checkRole(String raw) {
        String role = raw == null ? "" : raw.trim().toUpperCase();
        if (!ROLES.contains(role)) {
            throw new BizException(400, "角色只能是 ADMIN / SALES / FINANCE");
        }
        return role;
    }

    private EmployeeVO toVO(SysUser u) {
        // V10 之前建的账号由 ALTER 默认值补成 ACTIVE；这里再兜一层，防止读到历史脏数据
        String employment = StringUtils.hasText(u.getEmploymentStatus())
                ? u.getEmploymentStatus() : "ACTIVE";
        return new EmployeeVO(u.getId(), u.getUsername(), u.getRealName(),
                u.getPhone(), u.getRole(), u.getStatus(), employment, u.getCreatedAt());
    }
}
