package com.aigamelab.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aigamelab.common.ApiResp;
import com.aigamelab.domain.Experiment;
import com.aigamelab.experiment.AsyncSimulationRunner;
import com.aigamelab.repository.ExperimentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P5-3：Experiment REST + 异步化（蓝图 §8 / 03 文档 P5-3）。
 *
 * <p>四个端点：
 * <ul>
 *   <li>GET /api/experiments — 列表（含冗余指标列）</li>
 *   <li>GET /api/experiments/{id} — 详情（result_json 解析）</li>
 *   <li>POST /api/experiments/run — 手动触发（202 异步）</li>
 *   <li>POST /api/experiments/compare — 多实验对比（Δ + z 显著性，§13）</li>
 * </ul>
 *
 * <p>compare 中 z 显著性公式（02 技术设计 §13）：
 * z = Δ / √(pA(1−pA)/nA + pB(1−pB)/nB)，|z| &gt; 1.96 为显著。
 */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentRepository repo;
    private final AsyncSimulationRunner runner;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double Z_THRESHOLD = 1.96;

    public ExperimentController(ExperimentRepository repo, AsyncSimulationRunner runner) {
        this.repo = repo;
        this.runner = runner;
    }

    /**
     * 实验列表（含冗余指标列）。
     * 02 §8 列表接口：按 configVersion / scenario 可选过滤。
     */
    @GetMapping
    public ApiResp<List<Experiment>> list(
            @RequestParam(required = false) String configVersion,
            @RequestParam(required = false) String scenario) {
        List<Experiment> list = repo.findByFilter(configVersion, scenario);
        return ApiResp.ok(list);
    }

    /**
     * 详情（result_json 解析为 Map 返回）。
     */
    @GetMapping("/{id}")
    public ApiResp<Map<String, Object>> detail(@PathVariable long id) {
        Experiment e = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("实验不存在: " + id));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", e.id());
        data.put("runId", e.runId());
        data.put("configVersion", e.configVersion());
        data.put("seed", e.seed());
        data.put("simulationCount", e.simulationCount());
        data.put("scenario", e.scenario());
        data.put("status", e.status());
        data.put("winRate", e.winRate());
        data.put("avgDps", e.avgDps());
        data.put("avgGameTimeS", e.avgGameTimeS());
        data.put("durationMs", e.durationMs());
        data.put("createdAt", e.createdAt() != null ? e.createdAt().toString() : null);
        if (e.resultJson() != null) {
            try {
                data.put("result", MAPPER.readValue(e.resultJson(), Map.class));
            } catch (Exception ex) {
                data.put("resultJsonRaw", e.resultJson());
            }
        }
        return ApiResp.ok(data);
    }

    /**
     * 手动触发一次模拟（02 §8：202 异步）。
     * POST body: { configVersion?, seed?, count?, scenario?, runId? }
     * ① 立即创建 RUNNING 行并返回 202（异步执行模拟）；
     * ② 完成后 SIMULATION_COMPLETED 事件通过 runId 路由到 SSE。
     */
    @PostMapping("/run")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResp<Map<String, Object>> run(@RequestBody(required = false) RunRequest body) {
        if (body == null) {
            body = new RunRequest();
        }
        String cv = body.configVersion();
        long seed = body.seed();
        int count = body.count();
        String scenario = body.scenario();
        Long runId = body.runId();

        runner.submit(cv, seed, count, scenario, runId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ACCEPTED");
        resp.put("message", "实验已提交，完成后 SIMULATION_COMPLETED 事件将推送到 SSE");
        return ApiResp.ok(resp);
    }

    /**
     * 多实验对比（02 §8 + §13 z 显著性）。
     * POST body: { ids: [idA, idB, …] }
     * 对相邻两两做 ΔwinRate + z 检验。
     */
    @PostMapping("/compare")
    public ApiResp<List<Map<String, Object>>> compare(@RequestBody CompareRequest body) {
        List<Long> ids = body.ids();
        if (ids == null || ids.size() < 2) {
            throw new IllegalArgumentException("compare 至少需要 2 个实验 id");
        }
        List<Experiment> experiments = repo.findByIds(ids);
        if (experiments.size() < 2) {
            throw new IllegalArgumentException("未找到足够实验（需要 2+，找到 " + experiments.size() + "）");
        }

        List<Map<String, Object>> pairs = new java.util.ArrayList<>();
        for (int i = 0; i < experiments.size() - 1; i++) {
            Experiment a = experiments.get(i);
            Experiment b = experiments.get(i + 1);
            pairs.add(comparePair(a, b));
        }
        return ApiResp.ok(pairs);
    }

    /** 两两对比：ΔwinRate + z 值 + 显著性判定。 */
    private Map<String, Object> comparePair(Experiment a, Experiment b) {
        double pA = a.winRate();
        double nA = a.simulationCount();
        double pB = b.winRate();
        double nB = b.simulationCount();
        double delta = pA - pB;
        double se = Math.sqrt(pA * (1 - pA) / nA + pB * (1 - pB) / nB);
        double z = se == 0.0 ? 0.0 : delta / se;
        boolean significant = Math.abs(z) > Z_THRESHOLD;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("experimentA", a.id());
        result.put("experimentB", b.id());
        result.put("winRateA", pA);
        result.put("winRateB", pB);
        result.put("deltaWinRate", delta);
        result.put("zScore", z);
        result.put("significant", significant);
        return result;
    }

    /** POST /run 请求体。 */
    public record RunRequest(
            String configVersion,
            long seed,
            int count,
            String scenario,
            Long runId) {
        public RunRequest() {
            this(null, 0L, 1000, null, null);
        }
    }

    /** POST /compare 请求体。 */
    public record CompareRequest(List<Long> ids) {
    }
}
