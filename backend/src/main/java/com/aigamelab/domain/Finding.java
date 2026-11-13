package com.aigamelab.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 缺陷/发现（finding 表，T-DB1 提案 DDL——表未批准前仅作领域模型/内存表示使用，
 * 禁止落库）。字段与技术设计 §6 finding DDL 一一对应。
 *
 * severity: BLOCKER/HIGH/MEDIUM/LOW/INFO
 * category: CRASH/SOFT_LOCK/INVARIANT_VIOLATION/UNEXPECTED_STATE/BALANCE_ANOMALY/OTHER
 * source:   INVARIANT/LLM/MANUAL —— 缺陷检测来源（引擎零漏报基线 vs Agent 判断 vs 人工登记）
 * status:   OPEN/CONFIRMED/DISMISSED/FIXED
 *
 * evidenceJson 形状见 {@link Evidence}——定位锚为 sessionId + runId/toolCallId 区间，
 * 不依赖全局单调 tick（T1 技术债：RESTART 后 tick 会重置）。
 */
public record Finding(
        Long id,
        Long runId,
        Long testPlanId,
        Long taskId,
        String scenarioId,
        String configVersion,
        String severity,
        String category,
        String source,
        String title,
        String expected,
        String actual,
        String reproSteps,
        String evidenceJson,
        Double reproductionRate,
        String status,
        LocalDateTime createdAt) {

    public static final List<String> SEVERITIES =
            List.of("BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO");
    public static final List<String> CATEGORIES = List.of("CRASH", "SOFT_LOCK",
            "INVARIANT_VIOLATION", "UNEXPECTED_STATE", "BALANCE_ANOMALY", "OTHER");
    /** 检测来源：确定性引擎基线 / Agent 判断 / 人工登记 */
    public static final List<String> SOURCES =
            List.of("INVARIANT", "LLM", "MANUAL");
    public static final List<String> STATUSES =
            List.of("OPEN", "CONFIRMED", "DISMISSED", "FIXED");

    /**
     * 证据链引用结构（evidenceJson 的反序列化形状）。
     *
     * 定位契约（指令 §九）：Finding → Run/Session → Step/ToolCall/Event/Observation/Metric。
     * 必含 sessionId；tick 仅作会话内辅助信息展示，不作为唯一定位键。
     */
    public record Evidence(
            /** 产生证据的游戏会话 id（GameSessionHandle.sessionId）——必填主锚点 */
            String sessionId,
            /** 关联 Agent Run id（可空：纯环境级验证可无 Run） */
            Long runId,
            /** 证据所在的 tool_call 表 id 列表（observation/events 原文都在 tool_call 行） */
            List<Long> toolCallIds,
            /** 违规发生时刻的 Observation 快照（完整状态，非摘要） */
            Map<String, Object> observationSnapshot,
            /** 会话内 tick（仅辅助展示；RESTART 后不保证全局单调） */
            Long tick,
            /** 复现所需 seed 与 overrides（同 seed+动作序列+overrides ⇒ 可重放） */
            Long seed,
            Map<String, Object> overrides) {
    }
}
