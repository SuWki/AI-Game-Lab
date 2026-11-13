package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * Research Agent 的断言记录（research_claim 表）。
 * R0-3：Agent 从来源中提取的 Claims，支持 confidence 动态更新。
 */
public record ResearchClaim(
        Long id,
        Long runId,
        Long taskId,
        String claim,
        double confidence,
        String supportingSourceIds,    // JSON array of research_source.id
        String contradictingSourceIds, // JSON array of research_source.id
        String status,                 // DRAFT/VALIDATED/DISPUTED
        LocalDateTime createdAt) {
}
