package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * 变更提案（change_proposal 表）。Human-in-the-loop 核心。
 * status: PENDING/APPROVED/REJECTED；approve 后回填 targetVersion 与 decidedAt。
 */
public record ChangeProposal(
        Long id,
        Long runId,
        String baseVersion,
        String targetVersion,
        String title,
        String reason,
        String evidenceExperimentIds,
        String diffJson,
        String afterConfigJson,
        String status,
        LocalDateTime decidedAt,
        String decidedComment,
        LocalDateTime createdAt) {
}
