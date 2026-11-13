package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingFilter;

/**
 * finding 表仓储契约（T-DB1 已批准，DDL 已落地）。
 *
 * 实现为 {@link FindingRepositoryImpl}（JdbcTemplate，2026-09-04 起随 T-DB1
 * 闸门批准后随 DDL 一并上线）。本条 Javadoc 由 R8-A3 勘误：原 T-DB1 未批准
 * 的过渡性描述已过期，schema 中 finding 表与 Impl 均已在树。
 */
public interface FindingRepository {

    Finding insert(Finding finding);

    void updateStatus(long id, String status);

    Optional<Finding> findById(long id);

    /**
     * 过滤查询（回归对比核心路径）。参数为 null 表示忽略该条件；
     * configVersion+severity 命中 idx_finding_ver_sev 索引。
     */
    List<Finding> findByFilter(String configVersion, String severity, Long runId,
            Long testPlanId);

    /**
     * QA P1-3 多维过滤（R3-B1）。维度见 {@link FindingFilter}，全维度 AND 组合，
     * null/空集合 = 忽略该维度；空过滤返回全部。枚举维度合法性由 Service 层
     * 校验（40001），Repository 不做校验。
     */
    List<Finding> findByQaFilter(FindingFilter filter);
}
