package com.usedcar.system;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.usedcar.common.BizException;
import com.usedcar.system.domain.SysUser;
import com.usedcar.system.dto.LoginDTO;
import com.usedcar.system.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录鉴权。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthService(SysUserMapper sysUserMapper, PasswordEncoder passwordEncoder) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 登录。
     *
     * 安全要求：用户名不存在和密码错误，必须返回同一句提示。
     * 如果分开提示（"用户不存在" / "密码错误"），攻击者就能先枚举出系统里有哪些账号，
     * 这一步叫用户名枚举，是登录接口最常见的低级漏洞。
     */
    public SaTokenInfo login(LoginDTO dto) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.username()));

        if (user == null) {
            log.warn("登录失败，用户名不存在: {}", dto.username());
            throw new BizException(401, "用户名或密码错误");
        }
        // 先校验密码、再校验状态，顺序不能反：
        // 否则攻击者能通过「账号已停用」这条提示确认账号存在
        if (!passwordEncoder.matches(dto.password(), user.getPassword())) {
            log.warn("登录失败，密码错误: {}", dto.username());
            throw new BizException(401, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(403, "账号已停用");
        }

        // 登录态交给 Sa-Token，userId 作为会话主体；角色写进 token 的扩展字段，
        // 后续鉴权直接读，不用每次都查库
        StpUtil.login(user.getId());
        StpUtil.getSession().set("role", user.getRole());
        StpUtil.getSession().set("realName", user.getRealName());

        log.info("登录成功: {}（{}）", user.getUsername(), user.getRole());
        return StpUtil.getTokenInfo();
    }

    /**
     * 当前登录人信息。前端刷新页面时用它恢复用户态。
     */
    public SysUser currentUser() {
        long userId = StpUtil.getLoginIdAsLong();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(401, "账号不存在");
        }
        // 密码字段不能出网，返回前清空
        user.setPassword(null);
        return user;
    }
}
