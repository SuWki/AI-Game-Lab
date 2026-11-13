package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * 一次 LLM 循环（step 表）。字段与蓝图 §9.1 一致。
 * phase: LLM_CALL / FINAL_ANSWER / ERROR。
 */
public record Step(
        Long id,
        long runId,
        int seq,
        String phase,
        String llmContent,
        long promptTokens,
        long completionTokens,
        Long durationMs,
        LocalDateTime createdAt) {
}
