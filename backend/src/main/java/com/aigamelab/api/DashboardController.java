package com.aigamelab.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aigamelab.common.ApiResp;
import com.aigamelab.repository.RunRepository;

/**
 * P7-1：Dashboard 聚合端点。
 *
 * <p>复用既有能力，不新增数据层契约：
 * <ul>
 *   <li>Run 统计 → {@code RunRepository.aggregateStats()}（P1-4 已定义，单 SQL 聚合）；</li>
 *   <li>Experiment 数量 → 直接 JdbcTemplate 计数（轻量只读，不扩展 Repository 接口）；</li>
 *   <li>Token 消耗 → 取自 aggregateStats().totalTokens。</li>
 * </ul>
 *
 * <p>红线（P7-1 裁决）：不新增 schema、不修改 Repository 数据层契约、不修改 Error Contract、
 * 不新建复杂统计基础设施。蓝图 §8 Dashboard 字段中当前无数据来源的（如 design_document 计数）
 * 保持透明返回 0 并标注，不伪造。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final JdbcTemplate jdbc;
    private final RunRepository runRepo;

    public DashboardController(JdbcTemplate jdbc, RunRepository runRepo) {
        this.jdbc = jdbc;
        this.runRepo = runRepo;
    }

    @GetMapping("/stats")
    public ApiResp<Map<String, Object>> stats() {
        RunRepository.AggregateStats s = runRepo.aggregateStats();

        Long experimentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment", Long.class);
        if (experimentCount == null) experimentCount = 0L;

        Long doneExperiments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment WHERE status='DONE'", Long.class);
        if (doneExperiments == null) doneExperiments = 0L;

        long successRate = s.totalRuns() == 0 ? 0
                : Math.round(100.0 * s.completedRuns() / s.totalRuns());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalRuns", s.totalRuns());
        data.put("completedRuns", s.completedRuns());
        data.put("failedRuns", s.failedRuns());
        data.put("runningRuns", s.runningRuns());
        data.put("waitingApprovalRuns", s.waitingApprovalRuns());
        data.put("cancelledRuns", s.cancelledRuns());
        data.put("timeoutRuns", s.timeoutRuns());
        data.put("successRate", successRate);
        data.put("avgDurationMs", s.avgDurationMs());
        data.put("totalTokens", s.totalTokens());
        data.put("experimentCount", experimentCount);
        data.put("doneExperiments", doneExperiments);
        // 透明度：design_document 计数尚未在 P7-1 范围内聚合，返回真实值（若表存在则计数，否则 0）
        Long docCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM design_document", Long.class);
        data.put("designDocumentCount", docCount == null ? 0L : docCount);
        return ApiResp.ok(data);
    }
}
