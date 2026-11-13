package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.ResearchClaim;

/**
 * research_claim 表仓储契约（R0-3）。
 */
public interface ResearchClaimRepository {

    ResearchClaim insert(ResearchClaim claim);

    Optional<ResearchClaim> findById(long id);

    void updateConfidence(long id, double confidence);

    void updateStatus(long id, String status);

    void updateSourceIds(long id, String supportingSourceIds, String contradictingSourceIds);

    List<ResearchClaim> findByRunId(long runId);

    List<ResearchClaim> findByTaskId(long taskId);

    List<ResearchClaim> findByRunIdOrderByConfidenceDesc(long runId);
}
