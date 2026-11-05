package com.usedcar.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.usedcar.alert.domain.StockAlert;
import com.usedcar.alert.mapper.StockAlertMapper;
import com.usedcar.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 库存预警。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final StockAlertMapper stockAlertMapper;

    public AlertService(StockAlertMapper stockAlertMapper) {
        this.stockAlertMapper = stockAlertMapper;
    }

    /**
     * 若该车该类型没有待处理预警，则新建一条；已存在则跳过。
     *
     * 双重保护：
     *   1. 先查一次，绝大多数情况在这里就返回了，不打数据库异常；
     *   2. 并发下两个线程可能同时查不到，真正插入时撞上 uk_open_alert，
     *      捕获 DuplicateKeyException 当成「已被别人建过」处理。
     * 只做第 1 步是典型的 check-then-act 竞态，压测必现重复数据。
     *
     * @return 是否真的新建了一条
     */
    public boolean openIfAbsent(Long vehicleId, String alertType, String alertLevel, String content) {
        boolean exists = stockAlertMapper.exists(new LambdaQueryWrapper<StockAlert>()
                .eq(StockAlert::getVehicleId, vehicleId)
                .eq(StockAlert::getAlertType, alertType)
                .eq(StockAlert::getStatus, "OPEN"));
        if (exists) {
            return false;
        }

        StockAlert alert = new StockAlert();
        alert.setVehicleId(vehicleId);
        alert.setAlertType(alertType);
        alert.setAlertLevel(alertLevel);
        alert.setContent(content);
        alert.setStatus("OPEN");
        try {
            stockAlertMapper.insert(alert);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("预警已存在，跳过: vehicle={} type={}", vehicleId, alertType);
            return false;
        }
    }

    /** 待处理预警列表（含车辆编号、品牌车型） */
    public List<Map<String, Object>> listOpen() {
        return stockAlertMapper.selectOpenWithVehicle();
    }

    /**
     * 处理预警：RESOLVED 已处理 / IGNORED 已忽略。
     *
     * 处理过的预警不会被物理删除——它是一条历史，用来回答「这台车当时为什么降了 13%」。
     */
    public void handle(Long id, String status) {
        if (!"RESOLVED".equals(status) && !"IGNORED".equals(status)) {
            throw new BizException(400, "处理结果只能是 RESOLVED 或 IGNORED");
        }
        StockAlert alert = stockAlertMapper.selectById(id);
        if (alert == null) {
            throw new BizException(404, "预警不存在");
        }
        if (!"OPEN".equals(alert.getStatus())) {
            throw new BizException(400, "该预警已处理，不能重复处理");
        }

        StockAlert patch = new StockAlert();
        patch.setId(id);
        patch.setStatus(status);
        patch.setHandledAt(LocalDateTime.now());
        stockAlertMapper.updateById(patch);
    }

    /** 待处理预警条数，给仪表盘用 */
    public long countOpen() {
        return stockAlertMapper.selectCount(new LambdaQueryWrapper<StockAlert>()
                .eq(StockAlert::getStatus, "OPEN"));
    }
}
