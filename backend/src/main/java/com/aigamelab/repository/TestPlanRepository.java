package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.TestPlan;

/**
 * test_plan 表仓储契约（T-DB1 已批准，DDL 已落地）。
 *
 * 实现为 {@link TestPlanRepositoryImpl}（JdbcTemplate，2026-09-04 起随 T-DB1
 * 闸门批准后随 DDL 一并上线）。本条 Javadoc 由 R8-A3 勘误：原 T-DB1 未批准
 * 的过渡性描述已过期，schema 中 test_plan 表与 Impl 均已在树。
 */
public interface TestPlanRepository {

    TestPlan insert(TestPlan plan);

    void updateStatus(long id, String status);

    /** 用户编辑 scenarios/invariants（仅 DRAFT 态允许，由 Service 层校验） */
    void updateScenarios(long id, String scenariosJson);

    Optional<TestPlan> findById(long id);

    /** 一个 task 的全部 Plan（按创建时间升序；通常 0~N 条） */
    List<TestPlan> findByTaskId(long taskId);
}
