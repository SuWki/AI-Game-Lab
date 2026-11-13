package com.aigamelab.experiment;

import java.util.List;
import java.util.SplittableRandom;

import org.springframework.stereotype.Service;

import com.aigamelab.common.BusinessException;
import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.domain.Experiment;
import com.aigamelab.repository.ExperimentRepository;
import com.aigamelab.sim.Aggregator;
import com.aigamelab.sim.BatchSimulator;
import com.aigamelab.sim.GameConfig;
import com.aigamelab.sim.GameOutcome;
import com.aigamelab.sim.GameSimulator;
import com.aigamelab.sim.SimulationResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P5-1：可复现实验服务（蓝图 §13 / 03 文档 P5-1）。
 *
 * <p>create(configVersion, seed, count, scenario)：
 * <ul>
 *   <li>scenario 为具体 buildType（"crit"/"penetration"/"attack_speed"）→ 仅跑该 build；</li>
 *   <li>scenario 为 null/空 → 枚举全部 3 个 build 各跑 count 局，聚合为单条 ALL_BUILDS 实验；</li>
 *   <li>UNIQUE(config_version, seed, simulation_count, scenario) 冲突 → 直接返回已有结果（reused=true）。</li>
 * </ul>
 *
 * <p>同步执行（10000 局 ~10s 可接受）；P5-3 负责异步化与 REST 暴露。
 * 复用 P4 BatchSimulator / GameSimulator / Aggregator（纯 Java，无 Spring/DB 依赖）。
 */
@Service
public class ExperimentService {

    /** 全部 3 个 Build 类型（PROJECT ORBITAL）。 */
    static final List<String> ALL_BUILDS = List.of("crit", "penetration", "attack_speed");
    static final String ALL_BUILDS_SCENARIO = "ALL_BUILDS";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExperimentRepository repo;
    private final ConfigVersionService configService;

    public ExperimentService(ExperimentRepository repo, ConfigVersionService configService) {
        this.repo = repo;
        this.configService = configService;
    }

    /**
     * 运行（或复用）一次实验。
     *
     * @param configVersion 配置版本号；为 null/空时取当前生效版本（=latest）
     * @param seed          RNG 种子
     * @param count         模拟局数（每 build）
     * @param scenario      buildType；null/空 → 全部 3 build 聚合
     * @param runId         关联 Run（可 null，P3-4 调用时填入）
     * @return 实验结果；{@code reused=true} 表示命中幂等键直接复用已有行
     */
    public ExperimentResult create(String configVersion, long seed, int count,
            String scenario, Long runId) {
        String cfgVersion = (configVersion == null || configVersion.isBlank())
                ? configService.getCurrent().version()
                : configVersion;
        String scen = (scenario == null || scenario.isBlank()) ? ALL_BUILDS_SCENARIO : scenario;

        // PF-001：被测 configVersion 必须真实存在（C4 修正），禁止静默回退 latest。
        ConfigVersion resolved = configService.findByVersion(cfgVersion)
                .orElseThrow(() -> BusinessException.notFound("ConfigVersion 不存在: " + cfgVersion));

        // 幂等：同 (config_version, seed, simulation_count, scenario) 直接复用
        var existing = repo.findByFilter(cfgVersion, scen);
        for (Experiment e : existing) {
            if (e.seed() == seed && e.simulationCount() == count && "DONE".equals(e.status())) {
                return new ExperimentResult(e, true);
            }
        }

        // 插 RUNNING 行（UNIQUE 冲突由 Repository 抛 DuplicateKeyException → 视为复用）
        Experiment running;
        try {
            running = repo.insert(new Experiment(null, runId, cfgVersion, seed, count,
                    scen, "RUNNING", null, null, null, null, null, null));
        } catch (org.springframework.dao.DuplicateKeyException dke) {
            var hit = repo.findByFilter(cfgVersion, scen);
            Experiment reused = hit.get(hit.size() - 1);
            return new ExperimentResult(reused, true);
        }

        // 同步执行模拟（PF-001：使用早校验已解析的版本，不再二次回退）
        GameConfig config = parseConfig(resolved);
        SimulationResult result;
        if (ALL_BUILDS_SCENARIO.equals(scen)) {
            result = runAllBuilds(config, seed, count);
        } else {
            result = new BatchSimulator(config, scen, seed, count).run();
        }

        // 回填指标 + result_json
        String resultJson;
        try {
            resultJson = MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("SimulationResult 序列化失败: " + e.getMessage(), e);
        }
        long durationMs = 0L; // 同步执行，P5-3 异步化时回填真实耗时
        repo.complete(running.id(), result.winRate(), result.avgDps(), result.avgGameTime(),
                resultJson, durationMs);

        Experiment done = repo.findById(running.id()).orElseThrow();
        return new ExperimentResult(done, false);
    }

    /**
     * 全部 3 build 聚合：串行预分裂 SplittableRandom，每 build 跑 count 局，
     * 统一喂入一个 Aggregator（容量 count*3），得到数学正确的聚合 SimulationResult。
     */
    private SimulationResult runAllBuilds(GameConfig config, long seed, int count) {
        Aggregator agg = new Aggregator(count * ALL_BUILDS.size());
        SplittableRandom master = new SplittableRandom(seed);
        for (String build : ALL_BUILDS) {
            for (int i = 0; i < count; i++) {
                GameOutcome o = new GameSimulator(config, build, master.split()).simulate();
                agg.add(o);
            }
        }
        return agg.result();
    }

    private GameConfig parseConfig(ConfigVersion v) {
        try {
            return MAPPER.readValue(v.configJson(), GameConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("配置 JSON 反序列化失败: " + v.version(), e);
        }
    }

    /**
     * P5-1 返回载体：区分"新建"与"幂等复用"。
     */
    public record ExperimentResult(Experiment experiment, boolean reused) {
    }
}
