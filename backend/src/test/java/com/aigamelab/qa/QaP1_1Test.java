package com.aigamelab.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.PromptService;
import com.aigamelab.llm.dto.LlmMessage;
import com.aigamelab.llm.dto.LlmToolSpec;
import com.aigamelab.qa.support.QaTestFixture;
import com.aigamelab.repository.FindingRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.AgentLoop;
import com.aigamelab.runtime.AgentType;
import com.aigamelab.runtime.AgentTypeResolver;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tester.FindingReportTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * QA P1-1 验收测试：最小闭环 AgentType → Route → Prompt → Tool → Finding。
 *
 * <p>验证矩阵（P1-1 §16 完成判定）：
 * - AgentType.QA 存在且 resolves 正确
 * - qa_p001 通过 PromptService 可获取
 * - QA Tool 白名单隔离（QA/TESTER ALLOW，DESIGNER/RESEARCH DENY）
 * - finding_report → FindingService → DB（含 Evidence）持久化验证
 * - 既有 DESIGNER/TESTER/RESEARCH 路由回归不受影响
 *
 * <p>使用 QaTestFixture 独立 DB 隔离（per-class），不共享 AbstractRepositoryTest 文件。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("QA P1-1 最小闭环验收")
class QaP1_1Test {

    @Autowired AgentTypeResolver resolver;
    @Autowired PromptService promptService;
    @Autowired TaskRepository taskRepo;
    @Autowired RunRepository runRepo;
    @Autowired FindingRepository findingRepo;
    @Autowired FindingReportTool findingReportTool;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentLoop loop;
    @Autowired FakeLlmGateway llm;

    static final ObjectMapper MAPPER = new ObjectMapper();

    @TestConfiguration
    static class FakeConfig {
        @Bean
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    // ——— DB 隔离（QaTestFixture per-class 模式）———

    static final java.nio.file.Path DB = QaTestFixture.tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        QaTestFixture.datasourceProps(registry, DB);
    }

    /**
     * 不使用 QaTestFixture.resetDbToCleanState（它会清 prompt_version，
     * 导致 qa_p001 种子丢失）。手动清理用到的表即可。
     * prompt_version / game_config_version 保持 context 启动时的种子状态。
     */
    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    // ——— A. AgentType 测试 ———

    @Test
    @DisplayName("AgentType.QA 存在且为枚举值")
    void qaAgentTypeExists() {
        AgentType qa = AgentType.valueOf("QA");
        assertThat(qa).isEqualTo(AgentType.QA);
    }

    @Test
    @DisplayName("既有 AgentType 不受影响（回归）")
    void legacyAgentTypesUnchanged() {
        assertThat(AgentType.valueOf("DESIGNER")).isEqualTo(AgentType.DESIGNER);
        assertThat(AgentType.valueOf("TESTER")).isEqualTo(AgentType.TESTER);
        assertThat(AgentType.valueOf("RESEARCH")).isEqualTo(AgentType.RESEARCH);
    }

    // ——— B. Resolver 路由测试 ———

    private Task newTask(String constraintsJson) {
        return taskRepo.insert(new Task(null, "qa-test-task", "qa test goal",
                constraintsJson, 10, false, "ACTIVE", null));
    }

    private Run newRunFor(long taskId) {
        return runRepo.insert(new Run(null, taskId, "RUNNING", null,
                "deepseek-chat", 0, 10, 100000, 0, null, null, null, null, null));
    }

    @Test
    @DisplayName("QA Task（agent_type=QA）→ AgentType.QA")
    void qaTaskResolvesToQa() {
        Task task = newTask("{\"agent_type\":\"QA\"}");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.QA);
    }

    @Test
    @DisplayName("DESIGNER Task 仍路由到 DESIGNER（回归）")
    void designerTaskStillResolvesToDesigner() {
        Task task = newTask("{\"agent_type\":\"DESIGNER\"}");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.DESIGNER);
    }

    @Test
    @DisplayName("TESTER Task 仍路由到 TESTER（回归）")
    void testerTaskStillResolvesToTester() {
        Task task = newTask("{\"agent_type\":\"TESTER\"}");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.TESTER);
    }

    @Test
    @DisplayName("RESEARCH Task 仍路由到 RESEARCH（回归）")
    void researchTaskStillResolvesToResearch() {
        Task task = newTask("{\"agent_type\":\"RESEARCH\"}");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.RESEARCH);
    }

    // ——— C. Prompt 测试 ———

    @Test
    @DisplayName("qa_p001 可通过 PromptService.getByVersion 获取")
    void qaP001RetrievableViaPromptService() {
        var qaPrompt = promptService.getByVersion("qa_p001");
        assertThat(qaPrompt).isPresent();
        assertThat(qaPrompt.get().version()).isEqualTo("qa_p001");
        assertThat(qaPrompt.get().active()).isFalse(); // inactive，不抢 p001 active 位
        assertThat(qaPrompt.get().systemPrompt()).contains("QA Agent");
    }

    @Test
    @DisplayName("systemPrompt(QA) 返回 qa_p001 内容")
    void resolverReturnsQaPrompt() {
        String prompt = resolver.systemPrompt(AgentType.QA);
        assertThat(prompt).contains("QA Agent");
        assertThat(prompt).contains("ANALYZE_TASK");
        assertThat(prompt).contains("finding_report");
    }

    @Test
    @DisplayName("designer p001 仍为 active，不被 qa_p001 抢占")
    void designerPromptStillActive() {
        var active = promptService.getActive();
        assertThat(active.version()).isEqualTo("p001");
        assertThat(active.active()).isTrue();
    }

    // ——— D. Tool Isolation 白名单矩阵 ———

    @Test
    @DisplayName("toolWhitelist(QA) 包含 finding_report")
    void qaWhitelistContainsFindingReport() {
        Set<String> qaTools = resolver.toolWhitelist(AgentType.QA);
        assertThat(qaTools).contains("finding_report");
    }

    @Test
    @DisplayName("toolWhitelist(TESTER) 包含 finding_report（共享验证）")
    void testerWhitelistContainsFindingReport() {
        Set<String> testerTools = resolver.toolWhitelist(AgentType.TESTER);
        assertThat(testerTools).contains("finding_report");
    }

    @Test
    @DisplayName("toolWhitelist(DESIGNER) 不包含 finding_report（隔离验证）")
    void designerWhitelistExcludesFindingReport() {
        Set<String> designerTools = resolver.toolWhitelist(AgentType.DESIGNER);
        assertThat(designerTools).doesNotContain("finding_report");
    }

    @Test
    @DisplayName("toolWhitelist(RESEARCH) 不包含 finding_report（隔离验证）")
    void researchWhitelistExcludesFindingReport() {
        Set<String> researchTools = resolver.toolWhitelist(AgentType.RESEARCH);
        assertThat(researchTools).doesNotContain("finding_report");
    }

    @Test
    @DisplayName("QA 工具子集仅有 finding_report（最小闭环）")
    void qaWhitelistOnlyContainsFindingReport() {
        Set<String> qaTools = resolver.toolWhitelist(AgentType.QA);
        assertThat(qaTools).containsExactly("finding_report");
    }

    @Test
    @DisplayName("QA 工具与 TESTER 共享 finding_report，与 DESIGNER/RESEARCH 互斥")
    void qaToolOverlapWithTesterAndExclusionWithOthers() {
        Set<String> qa = resolver.toolWhitelist(AgentType.QA);
        Set<String> tester = resolver.toolWhitelist(AgentType.TESTER);
        Set<String> designer = resolver.toolWhitelist(AgentType.DESIGNER);
        Set<String> research = resolver.toolWhitelist(AgentType.RESEARCH);

        // QA ∩ TESTER = {finding_report}
        assertThat(qa).isSubsetOf(tester);
        // QA ∩ DESIGNER = ∅
        assertThat(qa).doesNotContainAnyElementsOf(designer);
        // QA ∩ RESEARCH = ∅
        assertThat(qa).doesNotContainAnyElementsOf(research);
    }

    // ——— E. Finding 创建测试（QA Tool → FindingService → DB + Evidence 持久化）———

    @Test
    @DisplayName("QA 路径 finding_report 创建 Finding + Evidence 落库验证")
    void qaToolCreatesFindingWithEvidence() throws Exception {
        // Arrange：Task + Run（QA Agent 路径创建的 Run）
        Task task = newTask("{\"agent_type\":\"QA\"}");
        Run run = newRunFor(task.id());

        String sessionId = "qa-session-abc-123";

        // Act：QA 调用 finding_report（经 FindingReportTool → FindingService → DB）
        JsonNode args = MAPPER.readTree("""
                {"sessionId":"%s","scenarioId":"qa-sc-1","configVersion":"v001",
                 "severity":"MEDIUM","category":"INVARIANT_VIOLATION",
                 "title":"QA Review: 潜在异常状态",
                 "expected":"正常状态","actual":"异常状态",
                 "reproSteps":"seed=42 → game_start → observe",
                 "seed":42,"tick":10,"toolCallIds":[1,2]}""".formatted(sessionId));
        findingReportTool.execute(args, ToolContext.of(run.id(), 1L));

        // Assert：Finding 落库且字段完整
        List<Finding> findings = findingRepo.findByFilter("v001", null, null, null);
        assertThat(findings).hasSize(1);

        Finding f = findings.get(0);
        assertThat(f.severity()).isEqualTo("MEDIUM");
        assertThat(f.category()).isEqualTo("INVARIANT_VIOLATION");
        assertThat(f.title()).isEqualTo("QA Review: 潜在异常状态");
        assertThat(f.configVersion()).isEqualTo("v001");
        assertThat(f.runId()).isEqualTo(run.id());
        assertThat(f.status()).isEqualTo("OPEN");
        assertThat(f.source()).isEqualTo("LLM");
        assertThat(f.scenarioId()).isEqualTo("qa-sc-1");
        assertThat(f.reproSteps()).isEqualTo("seed=42 → game_start → observe");

        // Evidence 反序列化验证
        Finding.Evidence ev = MAPPER.readValue(f.evidenceJson(), Finding.Evidence.class);
        assertThat(ev.sessionId()).isEqualTo(sessionId);
        assertThat(ev.runId()).isEqualTo(run.id());
        assertThat(ev.toolCallIds()).containsExactly(1L, 2L);
        assertThat(ev.seed()).isEqualTo(42L);
        assertThat(ev.tick()).isEqualTo(10L);
    }

    // ——— F. AgentLoop 真实闭环（QA Task → AgentLoop → finding_report → Finding 落库）———

    @Test
    @DisplayName("QA Task 经 AgentLoop 执行 finding_report 并持久化 Finding（最小闭环）")
    void qaTaskThroughAgentLoopCreatesFinding() {
        // Arrange：QA Task + PENDING Run
        Task task = newTask("{\"agent_type\":\"QA\"}");
        Run run = runRepo.insert(new Run(null, task.id(), "PENDING", null,
                "deepseek-chat", 0, 10, 100000, 0, null, null, null, null, null));

        String argsJson = """
                {"sessionId":"qa-loop-session-1","scenarioId":"qa-loop-sc","configVersion":"v001",
                 "severity":"HIGH","category":"UNEXPECTED_STATE",
                 "title":"QA Loop: 状态异常",
                 "expected":"正常","actual":"异常",
                 "reproSteps":"seed=7 → game_start → observe",
                 "seed":7,"tick":5,"toolCallIds":[11,12]}""";

        // Fake LLM 剧本：第一轮调用 finding_report，第二轮收尾
        llm.thenToolCalls(new com.aigamelab.llm.dto.ToolCall("c1", "finding_report", argsJson))
                .thenStop("QA 复核完成");

        // Act：跑通 AgentLoop
        loop.run(run.id());

        // Assert 1：QA 路由生效 → System Prompt = qa_p001，工具子集 = 仅 finding_report
        var firstReq = llm.recordedRequests().get(0);
        String systemPrompt = firstReq.messages().stream()
                .filter(m -> "system".equals(m.role()))
                .findFirst().orElseThrow().content();
        assertThat(systemPrompt).contains("QA Agent");
        List<String> toolNames = firstReq.tools() == null
                ? java.util.List.of()
                : firstReq.tools().stream().map(LlmToolSpec::name).toList();
        assertThat(toolNames).containsExactly("finding_report");

        // Assert 2：Finding 经 finding_report → FindingService → DB 持久化（含 Evidence）
        List<Finding> findings = findingRepo.findByFilter("v001", null, null, null);
        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.title()).isEqualTo("QA Loop: 状态异常");
        assertThat(f.severity()).isEqualTo("HIGH");
        assertThat(f.runId()).isEqualTo(run.id());
        assertThat(f.source()).isEqualTo("LLM");
        assertThat(f.status()).isEqualTo("OPEN");

        Finding.Evidence ev;
        try {
            ev = MAPPER.readValue(f.evidenceJson(), Finding.Evidence.class);
        } catch (Exception e) {
            throw new IllegalStateException("Evidence 反序列化失败", e);
        }
        assertThat(ev.sessionId()).isEqualTo("qa-loop-session-1");
        assertThat(ev.seed()).isEqualTo(7L);
        assertThat(ev.toolCallIds()).containsExactly(11L, 12L);
    }
}
