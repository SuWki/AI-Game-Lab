package com.aigamelab.qa;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aigamelab.domain.Experiment;
import com.aigamelab.repository.ExperimentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * QA Phase 3（HD-1 A2 / HD-3 / HD-4）：从既有 Experiment 数据实时派生 dominant build share。
 *
 * <p>注入点：由 {@link QaOrchestratorService} 构造注入；在 completeOrchestration 调用
 * {@link QaReleaseGateService#evaluate} 之前调用 {@link #deriveDominantBuildShare}，
 * 将结果作为第 6 参数传入 6-arg evaluate 重载。</p>
 *
 * <p>数据源：<code>experiment.result_json.buildDistribution</code>（scenario='ALL_BUILDS'
 * 聚合实验；{@code findByFilter} 按 id 升序 → 取最新 DONE 行）。</p>
 *
 * <p><b>HD-3 语义</b>：share = max(games) / Σ(games)，只依赖 games 计数，
 * 不硬编码任何 build 名。</p>
 *
 * <p><b>失败模式</b>：全部 degrade 为 null（share 缺失）——无实验 / 非 DONE / JSON 解析失败 /
 * buildDistribution 缺失 / 零局数。绝不抛异常阻断 Gate 决策。</p>
 */
@Service
public class QaBuildShareDeriver {

    private static final Logger log = LoggerFactory.getLogger(QaBuildShareDeriver.class);

    /** 与 ExperimentService.ALL_BUILDS_SCENARIO 同款语义（package-private 常量）。 */
    static final String ALL_BUILDS_SCENARIO = "ALL_BUILDS";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExperimentRepository experimentRepo;

    public QaBuildShareDeriver(ExperimentRepository experimentRepo) {
        this.experimentRepo = experimentRepo;
    }

    /**
     * 从指定 configVersion 的最新 ALL_BUILDS 实验派生 dominant build share。
     *
     * @param configVersion 被测配置版本（从 lead orchestration context 获取）
     * @return 主导 build 的 games 占比（0.0~1.0），数据不可用时返回 null
     */
    public Double deriveDominantBuildShare(String configVersion) {
        if (configVersion == null || configVersion.isBlank()) {
            return null;
        }
        List<Experiment> experiments;
        try {
            experiments = experimentRepo.findByFilter(configVersion, ALL_BUILDS_SCENARIO);
        } catch (RuntimeException e) {
            log.warn("build share 实验查询失败 config={}: {}", configVersion, e.getMessage());
            return null;
        }
        Experiment latest = null;
        for (Experiment ex : experiments) {
            if ("DONE".equals(ex.status())) {
                latest = ex; // id 升序 → 最后一行为最新 DONE 实验
            }
        }
        if (latest == null || latest.resultJson() == null || latest.resultJson().isBlank()) {
            return null;
        }
        return computeShare(latest.resultJson(), latest.id());
    }

    private Double computeShare(String resultJson, long experimentId) {
        try {
            JsonNode buildDistribution = MAPPER.readTree(resultJson).get("buildDistribution");
            if (buildDistribution == null || !buildDistribution.isObject()) {
                return null;
            }
            long totalGames = 0;
            long maxGames = 0;
            for (JsonNode node : buildDistribution) {
                long games = node.path("games").asLong(0);
                totalGames += games;
                if (games > maxGames) {
                    maxGames = games;
                }
            }
            if (totalGames <= 0) {
                return null;
            }
            return (double) maxGames / totalGames;
        } catch (Exception e) {
            log.warn("build share result_json 解析失败 experiment={}: {}", experimentId, e.getMessage());
            return null;
        }
    }
}
