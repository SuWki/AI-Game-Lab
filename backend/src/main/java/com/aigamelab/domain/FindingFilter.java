package com.aigamelab.domain;

import java.util.List;

/**
 * QA P1-3：Finding 多维过滤条件（R3-B1）。
 *
 * <p>维度以实际 Finding 领域契约为准（不凑数）：
 * <ul>
 *   <li>configVersion — 被测配置版本（契约 1 主维度，既有）；</li>
 *   <li>severity — BLOCKER/HIGH/MEDIUM/LOW/INFO（既有）；</li>
 *   <li>category — CRASH/SOFT_LOCK/INVARIANT_VIOLATION/UNEXPECTED_STATE/BALANCE_ANOMALY/OTHER；</li>
 *   <li>source — INVARIANT/LLM/MANUAL（检测来源，agent 侧角色代理）；</li>
 *   <li>status — OPEN/CONFIRMED/DISMISSED/FIXED（QA 生命周期主维度）；</li>
 *   <li>runIds — 多 Run 聚合（QA Lead 按 Run 域汇总）；</li>
 *   <li>testPlanId / taskId — 计划/任务归属过滤。</li>
 * </ul>
 *
 * <p>语义：所有维度 AND 组合；null/空集合 = 忽略该维度；全空 = 返回全部（空过滤合法）。
 * 枚举维度（severity/category/source/status）由 Service 层按 {@link Finding} 白名单校验，
 * 非法值抛 40001（与 FindingService 既有校验契约一致），不做静默忽略。
 */
public record FindingFilter(
        String configVersion,
        String severity,
        String category,
        String source,
        String status,
        List<Long> runIds,
        Long testPlanId,
        Long taskId) {

    /** 防御性归一化：runIds null → 空集合（不可变副本）。 */
    public FindingFilter {
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
    }

    public boolean isEmpty() {
        return configVersion == null && severity == null && category == null
                && source == null && status == null && runIds.isEmpty()
                && testPlanId == null && taskId == null;
    }
}
