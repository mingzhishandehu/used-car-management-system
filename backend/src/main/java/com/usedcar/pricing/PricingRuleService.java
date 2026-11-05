package com.usedcar.pricing;

import com.usedcar.pricing.domain.PricingRule;
import com.usedcar.pricing.mapper.PricingRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 定价规则参数读取。
 *
 * 设计要点一：把 14 个字符串参数一次性解析成一个不可变快照，业务代码只读快照，
 *   不再每次去解析 "0.08" → BigDecimal。参数是热路径（每台车详情都要算），
 *   每台车解析 14 次字符串，列表页 20 台车就是 280 次，没有必要。
 *
 * 设计要点二：每 30 秒从库里重新加载一次，也就是说「改表 → 最迟 30 秒后生效，不用重启」。
 *   这是「规则表驱动」这句话能被验证的地方，面试官改一行数据就能看到效果。
 *
 * 设计要点三：快照是不可变对象，用 volatile 整体替换，读线程永远看到完整的旧值或完整的新值，
 *   不会出现「年化利率是新的、阶梯幅度是旧的」这种半更新状态。
 */
@Service
public class PricingRuleService {

    private static final Logger log = LoggerFactory.getLogger(PricingRuleService.class);

    /** 规则快照。volatile 保证可见性，整体替换保证原子性 */
    private volatile RuleSet snapshot = RuleSet.DEFAULT;

    private final PricingRuleMapper pricingRuleMapper;

    public PricingRuleService(PricingRuleMapper pricingRuleMapper) {
        this.pricingRuleMapper = pricingRuleMapper;
    }

    /**
     * 应用启动完成后再加载，避免在数据源就绪前访问数据库。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        reload();
    }

    /**
     * 定时刷新。fixedDelay 表示「上一次执行结束后再等 30 秒」，
     * 用 fixedRate 的话，如果某次执行慢了会立刻补跑，容易堆积。
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void refresh() {
        reload();
    }

    /**
     * 从库里重新加载参数。手动改表后想立刻生效，可以调这个。
     */
    public synchronized void reload() {
        List<PricingRule> rows = pricingRuleMapper.selectList(null);
        if (rows == null || rows.isEmpty()) {
            // 一条规则都没有时保留默认值，不要让整个定价功能因为漏了初始化数据而崩掉
            log.warn("pricing_rule 表为空，沿用内置默认参数");
            return;
        }

        Map<String, String> map = new HashMap<>(rows.size());
        for (PricingRule row : rows) {
            map.put(row.getRuleKey(), row.getRuleValue());
        }

        RuleSet next = RuleSet.from(map);
        if (next.equals(snapshot)) {
            return;
        }
        snapshot = next;
        log.info("定价规则已更新: 年化={}, 保本加成={}, 阶梯={}/{} 天, 滞销阈值={}/{} 天",
                next.capitalAnnualRate(), next.breakevenMarkup(),
                next.tier1Days(), next.tier2Days(), next.slowWarnDays(), next.slowDangerDays());
    }

    public RuleSet rules() {
        return snapshot;
    }

    /**
     * 规则快照。用 record 保证不可变，equals 由编译器生成，
     * 所以 reload 里可以直接用 equals 判断「参数有没有真的变化」，避免每次刷新都打日志。
     */
    public record RuleSet(
            BigDecimal capitalAnnualRate,
            int capitalDaysBase,
            BigDecimal breakevenMarkup,
            BigDecimal breakevenFixed,
            int tier1Days,
            int tier2Days,
            int tier3Days,
            BigDecimal tier1Rate,
            BigDecimal tier2Rate,
            BigDecimal tier3Rate,
            int slowAttentionDays,
            int slowWarnDays,
            int slowDangerDays,
            int certWarnDays
    ) {

        /** 兜底默认值：库里读不到时用它，跟 V1 脚本里的初始值保持一致 */
        public static final RuleSet DEFAULT = new RuleSet(
                new BigDecimal("0.08"), 365,
                new BigDecimal("0.05"), new BigDecimal("2000"),
                45, 60, 75,
                new BigDecimal("0.03"), new BigDecimal("0.08"), new BigDecimal("0.13"),
                45, 60, 75, 30);

        static RuleSet from(Map<String, String> map) {
            return new RuleSet(
                    decimal(map, "capital.annual.rate", DEFAULT.capitalAnnualRate()),
                    integer(map, "capital.days.base", DEFAULT.capitalDaysBase()),
                    decimal(map, "breakeven.markup", DEFAULT.breakevenMarkup()),
                    decimal(map, "breakeven.fixed", DEFAULT.breakevenFixed()),
                    integer(map, "ladder.tier1.days", DEFAULT.tier1Days()),
                    integer(map, "ladder.tier2.days", DEFAULT.tier2Days()),
                    integer(map, "ladder.tier3.days", DEFAULT.tier3Days()),
                    decimal(map, "ladder.tier1.rate", DEFAULT.tier1Rate()),
                    decimal(map, "ladder.tier2.rate", DEFAULT.tier2Rate()),
                    decimal(map, "ladder.tier3.rate", DEFAULT.tier3Rate()),
                    integer(map, "slow.attention.days", DEFAULT.slowAttentionDays()),
                    integer(map, "slow.warn.days", DEFAULT.slowWarnDays()),
                    integer(map, "slow.danger.days", DEFAULT.slowDangerDays()),
                    integer(map, "cert.warn.days", DEFAULT.certWarnDays()));
        }

        /** 取不到或格式错时退回默认值，不让一条脏数据把整个定价打挂 */
        private static BigDecimal decimal(Map<String, String> map, String key, BigDecimal fallback) {
            String raw = map.get(key);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return new BigDecimal(raw.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static int integer(Map<String, String> map, String key, int fallback) {
            String raw = map.get(key);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
