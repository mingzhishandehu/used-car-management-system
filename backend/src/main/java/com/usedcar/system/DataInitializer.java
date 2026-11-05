package com.usedcar.system;

import com.usedcar.system.domain.SysUser;
import com.usedcar.system.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动初始化管理员账号。
 *
 * 为什么不在 SQL 脚本里写死一条 INSERT：
 *   BCrypt 密文必须由 PasswordEncoder 生成，而盐是随机的，同一条明文每次编码结果都不同，
 *   手写一个哈希进 SQL 无法验证来源；放在代码里生成，能保证「密文一定对得上这个明文」。
 *
 * 幂等：只有 sys_user 一条记录都没有时才插入，重启不会重复建。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    /** 初始管理员账号，首次登录后请立刻改密码 */
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(SysUserMapper sysUserMapper, PasswordEncoder passwordEncoder) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = sysUserMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }

        SysUser admin = new SysUser();
        admin.setUsername(DEFAULT_USERNAME);
        admin.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        admin.setRealName("系统管理员");
        admin.setRole("ADMIN");
        admin.setStatus(1);
        sysUserMapper.insert(admin);

        log.warn("已创建初始管理员 {} / {}，上线前必须修改该密码", DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }
}
