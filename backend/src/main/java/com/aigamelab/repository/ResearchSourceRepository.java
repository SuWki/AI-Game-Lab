package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.ResearchSource;

/**
 * research_source 表仓储契约（R0-3）。
 */
public interface ResearchSourceRepository {

    ResearchSource insert(ResearchSource source);

    Optional<ResearchSource> findById(long id);

    List<ResearchSource> findByRunId(long runId);

    List<ResearchSource> findByTaskId(long taskId);

    /** TC：按 run + canonical URL 查已存来源（URL 去重幂等）。 */
    Optional<ResearchSource> findByRunIdAndUrl(long runId, String url);
}
