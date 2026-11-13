package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * QA Phase 2：发布门禁结论（R5 §4 + freeze §13）。
 *
 * <p>一棵 QA 树（Lead Task）对一个 config_version 只有一条 Gate 行。
 *
 * <p>Gate 与 Lead Run 的 RUNNING/COMPLETED <b>正交</b>——
 * Lead Run 即便 COMPLETED，Gate 仍可为 FAIL/PENDING（防"Run 完成=过 Gate"假等价）。
 *
 * <p>红线（Refrain 特别禁用）：Child timeout → Parent PASS 任何路径均不被实现。
 */
public record QaReleaseGate(
        Long id,
        Long leadTaskId,
        Long runId,               // Lead Run
        String configVersion,
        String status,            // PENDING / PASS / CONDITIONAL_PASS / FAIL
        String automaticResult,   // PASS / CONDITIONAL_PASS / FAIL / INCONCLUSIVE
        String ruleVersion,       // 判定公式版本（QA-S3 可校准）
        String leadDecision,      // QA Lead LLM 判定文本
        String leadDecisionEvidenceJson,
        String finalResult,       // 人工确认后的权威结果
        int isConsistent,         // auto vs lead 一致（1=一致，0=不一致）
        int inconclusiveCount,    // 关键 evidence 缺失计数
        int manualOverride,       // 1=人工覆盖
        String overrideReason,
        String overrideBy,
        String overrideAt,
        int criticalBugs,
        int highBugs,
        int mediumBugs,
        int softLockCount,
        Double crashRate,
        Double dominantBuildShare,
        String statsJson,
        Long reportDocId,         // Release Report → design_document
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {

    public static final java.util.List<String> STATUSES = java.util.List.of(
            "PENDING", "PASS", "CONDITIONAL_PASS", "FAIL", "INCONCLUSIVE");

    public static final java.util.List<String> AUTOMATIC_RESULTS = java.util.List.of(
            "PASS", "CONDITIONAL_PASS", "FAIL", "INCONCLUSIVE");
}
