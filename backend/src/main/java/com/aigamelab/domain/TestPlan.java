package com.aigamelab.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 测试计划（test_plan 表，T-DB1 提案 DDL——表未批准前仅作领域模型/内存表示使用，
 * 禁止落库）。字段与技术设计 §6 test_plan DDL 一一对应。
 *
 * scenariosJson 为场景数组 JSON（技术设计 §5 结构：id/type/goal/seed/repeat/
 * playerProfile/invariants/overrides）。
 *
 * status 流转：DRAFT → ACTIVE（activate 后才允许起 Run）→ COMPLETED。
 */
public record TestPlan(
        Long id,
        Long taskId,
        String configVersion,
        String status,
        String scenariosJson,
        LocalDateTime createdAt) {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 场景内单个测试目标的结构化视图（scenariosJson 的元素形状，供 T3 Service 解析） */
    public record Scenario(
            String id,
            String type,
            String goal,
            long seed,
            int repeat,
            String playerProfile,
            List<String> invariants,
            java.util.Map<String, Object> overrides) {
    }
}
