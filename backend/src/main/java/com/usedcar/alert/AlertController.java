package com.usedcar.alert;

import com.usedcar.common.ApiResult;
import com.usedcar.job.StockInspectionJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 库存预警接口。
 */
@Tag(name = "库存预警")
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;
    private final StockInspectionJob stockInspectionJob;

    public AlertController(AlertService alertService, StockInspectionJob stockInspectionJob) {
        this.alertService = alertService;
        this.stockInspectionJob = stockInspectionJob;
    }

    /** 待处理预警，DANGER 排最前 */
    @Operation(summary = "待处理预警列表")
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.ok(alertService.listOpen());
    }

    /** 待处理预警条数 */
    @Operation(summary = "待处理预警条数")
    @GetMapping("/count")
    public ApiResult<Long> count() {
        return ApiResult.ok(alertService.countOpen());
    }

    /**
     * 处理预警。
     *
     * @param status RESOLVED 已处理 / IGNORED 已忽略
     */
    @Operation(summary = "处理预警")
    @PostMapping("/{id}/handle")
    public ApiResult<Void> handle(@PathVariable Long id,
                                  @RequestParam(defaultValue = "RESOLVED") String status) {
        alertService.handle(id, status);
        return ApiResult.ok();
    }

    /**
     * 手动触发一次巡检。
     *
     * 存在的意义：定时任务每天凌晨 2 点才跑，演示和联调不可能等到那时候。
     * 有了这个入口，改一条 pricing_rule 就能立刻看到预警变化。
     */
    @Operation(summary = "手动触发巡检（返回新增预警条数）")
    @PostMapping("/inspect")
    public ApiResult<Integer> inspect() {
        return ApiResult.ok(stockInspectionJob.inspect());
    }
}
