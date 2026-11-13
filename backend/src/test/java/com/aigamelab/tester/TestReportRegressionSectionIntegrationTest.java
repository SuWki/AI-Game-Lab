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

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Step;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.RunControlService;
import com.aigamelab.runtime.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T7-5 验收：Regression Report（test_report_create 可选 baselineVersion 回归对比摘要）。
 *
 * 验证：
 *  - Case 1：无 baselineVersion 时报告正常，Markdown 无回归对比节；
 *  - Case 2：有 baselineVersion 时报告附回归对比摘要（JSON result 含回归计数）；
 *  - Case 3：回归 Markdown 内容正确（含 NEW/FIXED/PERSISTED/CHANGED 标记）。
 *
 * 注意：TestReportCreateTool.findByFilter 按 runId 过滤，故 Finding 必须带 runId。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T7-5 Regression Report（test_report_create baselineVersion）")
class TestReportRegressionSectionIntegrationTest {

    @Autowired RunControlService runControl;
    @Autowired RunRepository runRepo;
    @Autowired StepRepository stepRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired FindingService findingService;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;
    @Autowired ToolExecutor toolExecutor;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t75-", ".db");
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
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        // PF-003：回归报告需真实 v002（report 现在校验 configVersion 存在）
        TestConfigSeeds.seedV002FromV001(jdbc);
        llm.reset();
    }

    private Task createTask(String cfg) {
        return taskRepo.insert(new Task(null, "Tester 任务", "测试 " + cfg,
                "{\"agent_type\":\"TESTER\",\"configVersion\":\"" + cfg + "\"}", 50, false, "ACTIVE", null));
    }

    private Run startRun(long taskId) {
        return runRepo.insert(new Run(null,taskId,"RUNNING",null,"test-model",0,50,100000,0,null,null,null,null,null));
    }

    private void awaitTerminal(long runId) {
        for (int i = 0; i < 200; i++) {
            Run r = runRepo.findById(runId).orElseThrow();
            if (Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT").contains(r.status())) return;
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** 创建带 runId 的 Finding（匹配 TestReportCreateTool 的 runId 过滤语义）。 */
    private Finding makeFinding(Long runId, String scenarioId, String category, String severity,
            String title, String configVersion) {
        return new Finding(null, runId, null, null,
                scenarioId, configVersion, severity, category,
                "LLM", title, "expect", "actual",
                "seed=42", "{}", 0.5, "OPEN", null);
    }

    /** 执行 test_report_create 并返回 result_json。 */
    private JsonNode execReport(long runId, long taskId, String cfg, String baselineVersion) throws Exception {
        Step step = stepRepo.insert(new Step(null, runId, 10, "LLM_CALL", null, 0, 0, null, null));
        String args = "{\"taskId\":" + taskId
                + ",\"configVersion\":\"" + cfg
                + "\",\"summary\":\"报告摘要\""
                + (baselineVersion != null ? ",\"baselineVersion\":\"" + baselineVersion + "\"" : "")
                + "}";
        toolExecutor.execute(runRepo.findById(runId).orElseThrow(), step,
                new ToolCall("c1", "test_report_create", args));
        String json = jdbc.queryForObject(
                "SELECT result_json FROM tool_call WHERE run_id=? AND tool_name='test_report_create' LIMIT 1",
                String.class, runId);
        return M.readTree(json);
    }

    private String reportContent(long runId) {
        return jdbc.queryForObject(
                "SELECT content_md FROM design_document WHERE run_id=? AND title LIKE '[TEST REPORT]%' LIMIT 1",
                String.class, runId);
    }

    // ---- Case 1：无 baselineVersion 时无回归对比节 ----

    @Test
    @DisplayName("Case 1 无 baselineVersion → 报告无回归对比节")
    void noBaselineNoRegressionSection() throws Exception {
        Task task = createTask("v001");
        Run run = startRun(task.id());
        awaitTerminal(run.id());
        findingService.report(makeFinding(run.id(), "scA", "CRASH", "HIGH", "crash", "v001"));

        JsonNode result = execReport(run.id(), task.id(), "v001", null);
        assertThat(result.get("baselineVersion").isNull()).isTrue();
        assertThat(result.get("findingCount").asInt()).isEqualTo(1);

        String content = reportContent(run.id());
        assertThat(content).doesNotContain("回归对比");
        assertThat(content).contains("Finding 清单");
    }

    // ---- Case 2：有 baselineVersion 时报告附回归对比摘要 ----

    @Test
    @DisplayName("Case 2 有 baselineVersion → JSON result 含回归计数")
    void withBaselineJsonContainsRegressionCounts() throws Exception {
        Task task = createTask("v002");
        Run run = startRun(task.id());
        awaitTerminal(run.id());

        // v001 baseline 有 1 Finding，v002 有 1 NEW Finding（均带 runId 关联当前 Run）
        findingService.report(makeFinding(run.id(), "scA", "CRASH", "HIGH", "crash on wave", "v001"));
        findingService.report(makeFinding(run.id(), "scB", "SOFT_LOCK", "MEDIUM", "phase 卡死", "v002"));

        JsonNode result = execReport(run.id(), task.id(), "v002", "v001");
        assertThat(result.get("baselineVersion").asText()).isEqualTo("v001");
        // v001 的 crash 在 v002 无 → FIXED；v002 的 phase 卡死 在 v001 无 → NEW
        assertThat(result.get("regressionFixed").asInt()).isEqualTo(1);
        assertThat(result.get("regressionNew").asInt()).isEqualTo(1);
        assertThat(result.get("regressionPersisted").asInt()).isEqualTo(0);
        assertThat(result.get("regressionChanged").asInt()).isEqualTo(0);
    }

    // ---- Case 3：回归 Markdown 内容正确 ----

    @Test
    @DisplayName("Case 3 回归 Markdown 含 NEW/FIXED/PERSISTED/CHANGED 标记")
    void regressionMarkdownContainsAllCategories() throws Exception {
        Task task = createTask("v002");
        Run run = startRun(task.id());
        awaitTerminal(run.id());

        findingService.report(makeFinding(run.id(), "scA", "CRASH", "HIGH", "crash", "v001"));
        findingService.report(makeFinding(run.id(), "scB", "BALANCE_ANOMALY", "MEDIUM", "hp 越界", "v001"));
        findingService.report(makeFinding(run.id(), "scB", "BALANCE_ANOMALY", "HIGH", "hp 越界", "v002"));
        findingService.report(makeFinding(run.id(), "scC", "SOFT_LOCK", "LOW", "lock", "v002"));

        execReport(run.id(), task.id(), "v002", "v001");
        String content = reportContent(run.id());

        assertThat(content).contains("回归对比（v001 → v002）");
        assertThat(content).contains("→ FIXED");
        assertThat(content).contains("→ CHANGED");
        assertThat(content).contains("→ NEW");
        assertThat(content).contains("crash");
        assertThat(content).contains("hp 越界");
        assertThat(content).contains("lock");
    }
}
