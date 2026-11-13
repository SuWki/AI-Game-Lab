package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.PromptVersion;

/**
 * prompt_version 表仓储（蓝图 §7）。全表至多一条 is_active=1。
 */
public interface PromptVersionRepository {

    /** 插入版本（默认 inactive）。version UNIQUE 冲突抛 DuplicateKeyException。 */
    PromptVersion insert(PromptVersion version);

    Optional<PromptVersion> findActive();

    Optional<PromptVersion> findById(long id);

    List<PromptVersion> findAll();

    /**
     * 切换 active：事务内先清全表 active 再置目标行，
     * 保证任意时刻至多一条 active。
     */
    void setActive(long id);
}
