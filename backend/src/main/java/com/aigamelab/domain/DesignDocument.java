package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * Agent 产出的设计文档（design_document 表）。
 */
public record DesignDocument(
        Long id,
        Long runId,
        Long taskId,
        String title,
        String contentMd,
        LocalDateTime createdAt) {
}
