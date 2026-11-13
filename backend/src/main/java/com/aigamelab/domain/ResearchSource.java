package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * Research Agent 的来源记录（research_source 表）。
 * R0-3：Agent 搜索/获取到的每个网页或文献来源。
 */
public record ResearchSource(
        Long id,
        Long runId,
        Long taskId,
        String url,
        String sourceType,
        String title,
        String contentSummary,
        Double reliability,
        LocalDateTime fetchedAt,
        LocalDateTime createdAt) {
}
