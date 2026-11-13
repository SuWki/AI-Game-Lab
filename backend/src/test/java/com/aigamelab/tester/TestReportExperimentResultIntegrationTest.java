package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.domain.Experiment;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Step;
import com.aigamelab.domain.Task;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.experiment.EvaluationService;
import com.aigamelab.experiment.ExperimentService;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.ExperimentRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.repository.TestPlanRepository;
import com.aigamelab.runtime.AgentLoop;
import com.aigamelab.runtime.RunControlService;
import com.aigamelab.runtime.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T6-4 验收：Test Report 与 Experiment Result 汇总验证。
 *
 * 验证 Tester（Test Report on configVersion）与 Designer（Experiment Result on 同一 configVersion）
 * 两条产物线通过 configVersion 关联汇总：
 *  - 同一 configVersion 下，Experiment（平衡目标评估）与 Test Report（缺陷清单）可并列查询；
 *  - Test Report 汇总 Finding 计数与 finding_report 落库数一致（Tester 内部汇总正确性）；
 *  - Tester Run 可在链路上调用 ExperimentService 将实验指标带入 Test Report 上下文。
 *
 * 复用 T4-2 双轨接线与 T6-1 start() 端点；不复制 AgentLoop / 不引入第二套 Run 模型。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T6-4 Test Report 与 Experiment Result 汇总")
class TestReportExperimentResultIntegrationTest {

    @Autowired RunControlService runControl;
    @Autowired AgentLoop agentLoop;
    @Autowired RunRepository runRepo;
    @Autowired StepRepository stepRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired TestPlanRepository planRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;
    @Autowired ToolExecutor toolExecutor;
    @Autowired ExperimentService experimentService;
    @Autowired EvaluationService evaluationService;
    @Autowired ExperimentRepository experimentRepo;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t64-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Path DB = tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"
                + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM task");
        llm.reset();
    }

    private Task newTaskWithConfig(String configVersion) {
        return taskRepo.insert(new Task(null, "Tester 任务", "测试配置 " + configVersion,
                "{\"configVersion\":\"" + configVersion + "\"}", 50, false, "ACTIVE", null));
    }

    private TestPlan activatePlanFor(long taskId, String configVersion) {
        TestPlan draft = planRepo.insert(new TestPlan(null, taskId, configVersion,
                TestPlan.STATUS_DRAFT, "[]", null));
        planRepo.updateStatus(draft.id(), TestPlan.STATUS_ACTIVE);
        return planRepo.findById(draft.id()).orElseThrow();
    }

    // ---- T6-4 S1：Experiment 与 Test Report 通过 configVersion 关联汇总 ----

    @Test
    @DisplayName("S1 同一 configVersion 下 Experiment 评估与 Test Report 可并列查询")
    void experimentAndTestReportCorrelatedByConfigVersion() {
        String cfg = "v001";
        // Designer 线：运行一次 Experiment（平衡目标评估）
        Experiment exp = experimentService.create(cfg, 42L, 100, "ALL_BUILDS", null).experiment();
        assertThat(exp.status()).isEqualTo("DONE");
        assertThat(exp.configVersion()).isEqualTo(cfg);

        // Tester 线：同 configVersion 起 Tester Run 并产出 Test Report
        Task task = newTaskWithConfig(cfg);
        activatePlanFor(task.id(), cfg);
        llm.thenStop("测试完成");
        Run completed = runControl.start(task.id());
        awaitTerminal(completed.id());
        Run run = ActiveTestRun.after(runRepo,completed);

        // 驱动 game_start + finding_report + test_report_create
        int step = 9;
        Step s2 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s2, new ToolCall("c2", "game_start",
                "{\"seed\":42,\"configVersion\":\"" + cfg + "\",\"overrides\":{}}"));
        String sid = jdbc.queryForObject(
                "SELECT json_extract(result_json,'$.sessionId') FROM tool_call WHERE run_id=? AND tool_name='game_start'",
                String.class, run.id());
        Step s7 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s7, new ToolCall("c7", "finding_report",
                "{\"sessionId\":\"" + sid + "\",\"scenarioId\":\"sc1\",\"configVersion\":\"" + cfg + "\","
                        + "\"severity\":\"HIGH\",\"category\":\"BALANCE_ANOMALY\","
                        + "\"title\":\"hp 越界\",\"expected\":\"HP>=0\",\"actual\":\"hp=999\","
                        + "\"reproSteps\":\"seed=42\"}"));
        Step s8 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s8, new ToolCall("c8", "test_report_create",
                "{\"taskId\":" + task.id() + ",\"configVersion\":\"" + cfg + "\",\"summary\":\"汇总\"}"));

        // 两条产物线均引用同一 configVersion
        Integer expCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment WHERE config_version=?", Integer.class, cfg);
        assertThat(expCount).isGreaterThanOrEqualTo(1);
        Integer reportCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM design_document WHERE title LIKE '[TEST REPORT]%' AND title LIKE '%"
                        + cfg + "%'",
                Integer.class);
        assertThat(reportCount).isGreaterThanOrEqualTo(1);
        // Findings 也引用同一 configVersion
        Integer findings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM finding WHERE config_version=?", Integer.class, cfg);
        assertThat(findings).isGreaterThanOrEqualTo(1);
    }

    // ---- T6-4 S2：Test Report 汇总 Finding 计数与落库一致 ----

    @Test
    @DisplayName("S2 Test Report 汇总 Finding 计数与 finding_report 落库数一致")
    void testReportAggregatesFindingsCorrectly() {
        String cfg = "v001";
        Task task = newTaskWithConfig(cfg);
        activatePlanFor(task.id(), cfg);
        llm.thenStop("测试完成");
        Run completed = runControl.start(task.id());
        awaitTerminal(completed.id());
        Run run = ActiveTestRun.after(runRepo,completed);

        // 起会话并上报 2 条 Finding
        int step = 9;
        Step s2 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s2, new ToolCall("c2", "game_start",
                "{\"seed\":42,\"configVersion\":\"" + cfg + "\",\"overrides\":{}}"));
        String sid = jdbc.queryForObject(
                "SELECT json_extract(result_json,'$.sessionId') FROM tool_call WHERE run_id=? AND tool_name='game_start'",
                String.class, run.id());
        for (int k = 1; k <= 2; k++) {
            Step sk = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
            toolExecutor.execute(run, sk, new ToolCall("cF" + k, "finding_report",
                    "{\"sessionId\":\"" + sid + "\",\"scenarioId\":\"sc" + k + "\",\"configVersion\":\""
                            + cfg + "\",\"severity\":\"HIGH\",\"category\":\"BALANCE_ANOMALY\","
                            + "\"title\":\"hp 越界" + k + "\",\"expected\":\"HP>=0\",\"actual\":\"hp=999\","
                            + "\"reproSteps\":\"seed=42\"}"));
        }
        Step s8 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s8, new ToolCall("c8", "test_report_create",
                "{\"taskId\":" + task.id() + ",\"configVersion\":\"" + cfg + "\",\"summary\":\"汇总\"}"));

        // finding 表落库 2 条
        Integer findings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM finding WHERE config_version=?", Integer.class, cfg);
        assertThat(findings).isEqualTo(2);
        // Test Report 汇总 findingCount=2
        Integer reportFindingCount = jdbc.queryForObject(
                "SELECT CAST(json_extract(result_json,'$.findingCount') AS INTEGER) FROM tool_call WHERE run_id=? AND tool_name='test_report_create'",
                Integer.class, run.id());
        assertThat(reportFindingCount).isEqualTo(2);
    }

    // ---- T6-4 S3：Tester Run 链路可调 ExperimentService 将实验指标带入上下文 ----

    @Test
    @DisplayName("S3 Tester Run 内 ExperimentService 复算与 Test Report 同源 configVersion")
    void testerRunInvokesExperimentServiceConsistency() {
        String cfg = "v001";
        Task task = newTaskWithConfig(cfg);
        activatePlanFor(task.id(), cfg);
        llm.thenStop("测试完成");
        Run completed = runControl.start(task.id());
        awaitTerminal(completed.id());
        Run run = ActiveTestRun.after(runRepo,completed);

        // 在 Tester Run 上下文内，ExperimentService 复算同 configVersion 实验（幂等复用）
        Experiment repeated = experimentService.create(cfg, 42L, 100, "ALL_BUILDS", run.id())
                .experiment();
        assertThat(repeated.configVersion()).isEqualTo(cfg);
        assertThat(repeated.runId()).isEqualTo(run.id());

        // EvaluationService 对同 configVersion 结果可评估
        com.aigamelab.sim.SimulationResult sr;
        try {
            sr = new ObjectMapper()
                    .readValue(repeated.resultJson(), com.aigamelab.sim.SimulationResult.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var report = evaluationService.evaluate(cfg, sr);
        assertThat(report).isNotNull();
        assertThat(report.items()).isNotEmpty();
    }

    // ---- helpers ----

    private void awaitTerminal(long runId) {
        for (int i = 0; i < 200; i++) {
            Run r = runRepo.findById(runId).orElseThrow();
            if (Set.of(RunStatus.COMPLETED.name(), RunStatus.FAILED.name(),
                    RunStatus.CANCELLED.name(), RunStatus.TIMEOUT.name()).contains(r.status())) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
