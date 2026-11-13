package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.QaRoleAssignment;

/**
 * QA Phase 2：角色分配仓储（R5 §4 / freeze DB3）。
 *
 * <p>接口声明（Impl 见 QaRoleAssignmentRepositoryImpl）。事务性编排写操作
 * 由 QaOrchestratorService 以 @Transactional 编排，本仓储只做单表存取。
 */
public interface QaRoleAssignmentRepository {

    QaRoleAssignment insert(QaRoleAssignment a);

    Optional<QaRoleAssignment> findById(long id);

    /** 一棵 QA 树（Lead Task）的全部角色分配，按 order_no 升序。 */
    List<QaRoleAssignment> findByLeadTask(long leadTaskId);

    /** 一棵 QA 树（Lead Run）的全部角色分配，按 order_no 升序。 */
    List<QaRoleAssignment> findByParentRun(long parentRunId);

    Optional<QaRoleAssignment> findByLeadTaskAndRole(long leadTaskId, String role);

    /** start_subrun 反查：由子 Task 定位 assignment（校验归属用）。 */
    Optional<QaRoleAssignment> findBySubTask(long subTaskId);

    void updateStatus(long id, String status, String resultSummary, String errorMessage);

    /** start_subrun 建好子 Run 后回填关联 + 用量快照。 */
    void bindSubRun(long id, Long subTaskId, Long subRunId, String status,
                    Integer stepsUsed, Integer tokensUsed);

    void updateDetail(long id, String detailJson);
}
