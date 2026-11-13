package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.ChangeProposal;

/**
 * change_proposal 表仓储（蓝图 §7/§14/§15）。
 */
public interface ChangeProposalRepository {

    /** 插入 PENDING 提案并回填 id/createdAt。 */
    ChangeProposal insert(ChangeProposal proposal);

    Optional<ChangeProposal> findById(long id);

    List<ChangeProposal> findByStatus(String status);

    /**
     * 审批决策回填：APPROVED 时 targetVersion 非空；REJECTED 时 targetVersion 传 null。
     * 同时写 decided_at 与 decided_comment。
     */
    void decide(long id, String status, String targetVersion, String comment);
}
