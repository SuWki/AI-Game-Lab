package com.aigamelab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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

import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.PromptService;
import com.aigamelab.llm.dto.LlmMessage;
import com.aigamelab.llm.dto.LlmToolSpec;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.repository.TestPlanRepository;
import com.aigamelab.tool.StubTool;
import com.aigamelab.tool.ToolRegistry;

/**
 * T4-2 验收：AgentLoop 按 Run→Task→ACTIVE TestPlan 双轨接线。
 *
 * - Tester Run（Task 存在 ACTIVE TestPlan）：System Prompt = tester_p001（保持 inactive）；
 *   LLM 工具仅暴露 8 个白名单 Tester Tool，其余工具（如测试桩 stub_read）被隔离。
 * - Designer Run（Task 无 ACTIVE TestPlan）：沿用既有 Designer Prompt 与全部已注册工具。
 * - tester_p001 始终保持 inactive，不抢占设计师 p001 的 active 位。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T4-2 Tester/Designer Run 双轨接线")
class TesterRunWiringTest {

    @Autowired AgentLoop loop;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired TestPlanRepository planRepo;
    @Autowired ToolRegistry registryBean;
    @Autowired PromptService promptService;
    @Autowired JdbcTemplate jdbc;

    @Autowired FakeLlmGateway llm;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t42-", ".db");
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

    /** T4-2 冻结白名单（技术设计 §4 / 任务树 T4-2） */
    static final Set<String> EXPECTED_TESTER_TOOLS = Set.of(
            "test_plan_get", "game_start", "game_observe", "game_act",
            "game_collect_events", "game_collect_metrics", "finding_report", "test_report_create");

    /** R0-8：Designer 白名单（显式隔离）+ R2-1 additive 研究消费工具 */
    static final Set<String> EXPECTED_DESIGNER_TOOLS = Set.of(
            "get_game_config", "modify_game_config", "simulate_game",
            "analyze_experiment", "compare_experiments",
            "get_game_design", "create_design_document", "create_change_proposal",
            "get_research_context", "get_research_claims");

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM task");
        llm.reset();
    }

    private Task newTask() {
        return taskRepo.insert(new Task(null, "测试任务", "验证 game_start→observe 链路",
                "{}", 10, false, "ACTIVE", null));
    }

    private TestPlan activePlanFor(long taskId) {
        return planRepo.insert(new TestPlan(null, taskId, "v001",
                TestPlan.STATUS_ACTIVE, "[]", null));
    }

    private Run newRunFor(long taskId) {
        return runRepo.insert(new Run(null, taskId, "PENDING", null,
                "deepseek-chat", 0, 10, 100000, 0, null, null, null, null, null));
    }

    /** 抓取某 Run 首条请求的 system 消息内容与工具名集合 */
    private LlmMessage firstSystemMessage() {
        return llm.recordedRequests().get(0).messages().get(0);
    }

    private Set<String> firstRequestToolNames() {
        List<LlmToolSpec> specs = llm.recordedRequests().get(0).tools();
        return specs == null ? Set.of() : Set.copyOf(specs.stream().map(LlmToolSpec::name).toList());
    }

    @Test
    void testerRunUsesTesterPromptAndWhitelistedTools() {
        // 布置：Task + ACTIVE TestPlan + 一个非白名单测试桩（应被隔离）
        Task task = newTask();
        activePlanFor(task.id());
        StubTool stray = new StubTool("stub_quarantine_a");
        registryBean.register(stray);

        llm.thenStop("测试完成");
        Run run = newRunFor(task.id());
        loop.run(run.id());

        // System Prompt = tester_p001 内容（逐字冻结 §8 标记）
        String prompt = firstSystemMessage().content();
        assertThat(prompt).contains("AI Game Tester");
        assertThat(prompt).doesNotContain("AGL game designer agent");

        // 工具子集 = 恰好 8 个白名单；stub_read 不在其中
        assertThat(firstRequestToolNames()).containsExactlyInAnyOrderElementsOf(EXPECTED_TESTER_TOOLS);
        assertThat(firstRequestToolNames()).doesNotContain("stub_read");
    }

    @Test
    @DisplayName("Designer Run 保持 Designer Prompt，工具子集为显式 8 个 Designer 工具（R0-8 隔离）")
    void designerRunKeepsDesignerPromptAndAllTools() {
        // 布置：Task 无 TestPlan（纯 Designer Run）+ 一个测试桩
        Task task = newTask();
        StubTool stray = new StubTool("stub_quarantine_b");
        registryBean.register(stray);

        llm.thenStop("设计完成");
        Run run = newRunFor(task.id());
        loop.run(run.id());

        // System Prompt = 既有硬编码 Designer 文本
        String prompt = firstSystemMessage().content();
        assertThat(prompt).isEqualTo("You are AGL game designer agent.");
        assertThat(prompt).doesNotContain("AI Game Tester");

        // R0-8：工具子集 = 显式 8 个 Designer 工具；测试桩与 Tester/Research 工具均被隔离
        Set<String> names = firstRequestToolNames();
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_DESIGNER_TOOLS);
        assertThat(names).doesNotContain("stub_quarantine_b");
        for (String t : EXPECTED_TESTER_TOOLS) {
            assertThat(names).doesNotContain(t);
        }
        assertThat(names).doesNotContain("web_search", "fetch_page", "save_source",
                "extract_claims", "compare_sources", "generate_report");
    }

    @Test
    void testerRunPromptSelectionIsByActivePlanNotByNameOrCount() {
        // 边界：Task 仅有 DRAFT Plan（非 ACTIVE）→ 仍按 Designer 处理
        Task task = newTask();
        planRepo.insert(new TestPlan(null, task.id(), "v001",
                TestPlan.STATUS_DRAFT, "[]", null));

        llm.thenStop("设计完成");
        Run run = newRunFor(task.id());
        loop.run(run.id());

        assertThat(firstSystemMessage().content())
                .isEqualTo("You are AGL game designer agent.");
    }

    @Test
    void testerPromptRemainsInactiveAfterTesterRun() {
        // tester_p001 始终 inactive，active 位仍属设计师 p001
        Task task = newTask();
        activePlanFor(task.id());
        llm.thenStop("测试完成");
        loop.run(newRunFor(task.id()).id());

        var active = promptService.getActive();
        assertThat(active.version()).isEqualTo("p001");
        assertThat(active.active()).isTrue();
        var tester = promptService.getByVersion("tester_p001").orElseThrow();
        assertThat(tester.active()).isFalse();
    }
}
