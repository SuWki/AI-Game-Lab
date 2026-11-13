package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * System Prompt 版本（prompt_version 表）。is_active=1 表示当前生效版本；
 * 全表至多一条 active。
 */
public record PromptVersion(
        Long id,
        String version,
        String systemPrompt,
        String description,
        boolean active,
        LocalDateTime createdAt) {
}
