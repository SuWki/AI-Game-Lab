package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.Experiment;

/**
 * experiment 表仓储（蓝图 §7/§13）。
 */
public interface ExperimentRepository {

    /**
     * 插入 RUNNING 行。UNIQUE(configVersion,seed,simulationCount,scenario)
     * 冲突时向上抛 {@link org.springframework.dao.DuplicateKeyException}——
     * 调用方（P5-1 ExperimentService）据此返回已有结果实现幂等复用。
     */
    Experiment insert(Experiment experiment);

    /** 回填 DONE 指标与 result_json、duration_ms（P5-1 complete 调用）。 */
    void complete(long id, double winRate, double avgDps, double avgGameTimeS,
            String resultJson, long durationMs);

    void updateStatus(long id, String status);

    Optional<Experiment> findById(long id);

    /** 列表过滤：参数为 null 表示忽略该条件。 */
    List<Experiment> findByFilter(String configVersion, String scenario);

    List<Experiment> findByIds(List<Long> ids);
}
