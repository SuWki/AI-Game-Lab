package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.ConfigVersion;

/**
 * game_config_version 表仓储（蓝图 §7/§14）。append-only。
 */
public interface ConfigVersionRepository {

    /** 插入全量快照。version 主键冲突抛 DuplicateKeyException。 */
    ConfigVersion insert(ConfigVersion version);

    Optional<ConfigVersion> findByVersion(String version);

    /** 最新版本（version 字典序最大，v%03d 格式保证与序号一致）。 */
    Optional<ConfigVersion> findLatest();

    List<ConfigVersion> findAll();
}
