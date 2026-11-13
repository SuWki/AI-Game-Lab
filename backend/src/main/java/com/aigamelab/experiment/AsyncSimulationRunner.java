package com.aigamelab.experiment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.experiment.ExperimentService.ExperimentResult;

/**
 * P5-3：异步模拟执行器（03 文档 P5-3）。
 *
 * <p>单线程 Executor 运行 ExperimentService.create()，
 * 完成后向关联 Run 发布 SIMULATION_COMPLETED 事件。
 * POST /api/experiments/run 端点委托本类提交任务。
 *
 * <p>Executor 生命周期：shutdown 在 Spring 关闭时由 JVM 兜底（Daemon 线程）。
 */
@Component
public class AsyncSimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncSimulationRunner.class);

    private final ExperimentService experimentService;
    private final AgentEventPublisher publisher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "exp-sim-async");
        t.setDaemon(true);
        return t;
    });

    public AsyncSimulationRunner(ExperimentService experimentService, AgentEventPublisher publisher) {
        this.experimentService = experimentService;
        this.publisher = publisher;
    }

    /**
     * 提交一次异步实验。
     *
     * @param configVersion 配置版本（null/空 → 当前生效版本）
     * @param seed          RNG 种子
     * @param count         模拟局数
     * @param scenario      buildType（null/空 → ALL_BUILDS）
     * @param runId         关联 Run（用于事件路由；可 null → 不发布事件）
     */
    public void submit(String configVersion, long seed, int count, String scenario, Long runId) {
        executor.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                ExperimentResult result = experimentService.create(configVersion, seed, count, scenario, runId);
                long durationMs = System.currentTimeMillis() - start;
                log.info("[async-sim] 完成 experimentId={} duration={}ms reused={}",
                        result.experiment().id(), durationMs, result.reused());
                if (runId != null) {
                    String payload = "{\"experimentId\":" + result.experiment().id()
                            + ",\"status\":\"DONE\",\"reused\":" + result.reused()
                            + ",\"durationMs\":" + durationMs + "}";
                    publisher.publish("SIMULATION_COMPLETED", runId, payload);
                }
            } catch (Exception e) {
                log.error("[async-sim] 异步模拟失败: configVersion={} seed={} scenario={}",
                        configVersion, seed, scenario, e);
                if (runId != null) {
                    String payload = "{\"error\":\"" + e.getMessage() + "\",\"status\":\"FAILED\"}";
                    publisher.publish("SIMULATION_COMPLETED", runId, payload);
                }
            }
        });
    }

    /** 优雅关闭（Spring shutdown hook 不保证时序，此处仅 best-effort）。 */
    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
