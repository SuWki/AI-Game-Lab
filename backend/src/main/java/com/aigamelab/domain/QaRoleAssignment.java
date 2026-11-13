package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * QA Phase 2：角色分配（R5 §4）。
 *
 * <p>父子 run 关联权威——run 表保持零改动（R5 §3 严禁修改 run 表），
 * 父子关系由本表的 parent_run_id + sub_run_id 承载。
 *
 * <p>6 态（freeze DB3）：PENDING / RUNNING / COMPLETED / FAILED / TIMEOUT / CANCELLED。
 * A7 裁决：Gate 与聚合一律以 run 行最新 status 为准；assignment.status 仅是编排视图。
 */
public record QaRoleAssignment(
        Long id,
        Long parentRunId,      // Lead Run id
        Long leadTaskId,       // Lead Task id（R5 §4 必含）
        Long subTaskId,        // 子角色 Task id（nullable：create_subtask 建）
        Long subRunId,         // 子 Run id（nullable：start_subrun 建）
        String role,           // QA 角色名（PLAYER_NOVICE / COMBAT_ANALYST / ...）
        String playerProfile,  // NOVICE / AGGRESSIVE / BUILD_SPECIALIST（nullable）
        String status,         // PENDING / RUNNING / COMPLETED / FAILED / TIMEOUT / CANCELLED
        int orderNo,           // 顺序编排号
        String resultSummary,  // 子 Run 完成摘要
        String errorMessage,   // 失败/超时原因
        Integer subRunStepsUsed,
        Integer subRunTokensUsed,
        String detailJson,     // 扩展信息
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final java.util.List<String> STATUSES = java.util.List.of(
            "PENDING", "RUNNING", "COMPLETED", "FAILED", "TIMEOUT", "CANCELLED");

    public static final java.util.List<String> ROLES = java.util.List.of(
            "QA_LEAD",
            "PLAYER_NOVICE", "PLAYER_AGGRESSIVE", "PLAYER_BUILD_SPECIALIST",
            "COMBAT_ANALYST", "NUMERICAL_ANALYST",
            "EXPLOIT_HUNTER", "UX_REVIEWER");

    /** 角色 → AgentType 映射（freeze §3 / §B Contract Matrix） */
    public static String agentTypeForRole(String role) {
        return switch (role) {
            case "QA_LEAD" -> "QA_LEAD";
            case "PLAYER_NOVICE", "PLAYER_AGGRESSIVE", "PLAYER_BUILD_SPECIALIST",
                 "EXPLOIT_HUNTER" -> "QA_TESTER";
            case "COMBAT_ANALYST", "NUMERICAL_ANALYST" -> "QA_ANALYST";
            case "UX_REVIEWER" -> "QA_RESEARCHER";
            default -> throw new IllegalArgumentException("未知 QA 角色: " + role);
        };
    }
}
