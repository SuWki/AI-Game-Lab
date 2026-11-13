package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * 游戏配置版本（game_config_version 表）。version 为主键（'v001'…），
 * append-only 全量快照。
 */
public record ConfigVersion(
        String version,
        String configJson,
        String parentVersion,
        Long changeProposalId,
        String comment,
        LocalDateTime createdAt) {
}
