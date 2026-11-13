package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * 一次 Agent 执行（run 表）。字段与蓝图 §9.1 一致。
 * status 用字符串存储、RunStatus 枚举校验由上层（P1-1 状态机）负责——
 * Repository 层保持薄，不做状态迁移判断。
 */
public record Run(
        Long id,
        long taskId,
        String status,
        Long promptVersionId,
        String model,
        int currentStep,
        int maxSteps,
        int tokenBudget,
        long tokensUsed,
        String errorMessage,
        String finalResult,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt) {
}
