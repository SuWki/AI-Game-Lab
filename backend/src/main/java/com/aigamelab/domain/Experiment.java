package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * 可复现实验（experiment 表）。字段与蓝图 §13 一致。
 * 业务唯一键 (configVersion, seed, simulationCount, scenario)——UNIQUE 冲突时
 * JdbcTemplate 翻译为 DuplicateKeyException，供幂等复用（reused:true）判断。
 * status: RUNNING/DONE/FAILED。resultJson 为 SimulationResult 全量 JSON。
 */
public record Experiment(
        Long id,
        Long runId,
        String configVersion,
        long seed,
        int simulationCount,
        String scenario,
        String status,
        Double winRate,
        Double avgDps,
        Double avgGameTimeS,
        String resultJson,
        Long durationMs,
        LocalDateTime createdAt) {
}
