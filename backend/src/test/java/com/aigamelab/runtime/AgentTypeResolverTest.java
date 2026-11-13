package com.aigamelab.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.domain.Task;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.llm.PromptService;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.repository.TestPlanRepository;
import com.aigamelab.tester.TestPlanService;

/**
 * R0-1 验收测试：AgentTypeResolver 路由逻辑。
 *
 * 覆盖场景：
 * - Case 1: constraints_json = {"agent_type":"RESEARCH"} → RESEARCH
 * - Case 2: constraints_json = {"agent_type":"TESTER"} → TESTER
 * - Case 3: constraints_json = {"agent_type":"DESIGNER"} → DESIGNER
 * - Case 4: 无 agent_type + ACTIVE TestPlan → TESTER
 * - Case 5: 无 agent_type + 无 ACTIVE TestPlan → DESIGNER
 * - Case 6: 非法 agent_type → IllegalArgumentException
 * - Case 7: 空/null constraints_json → 回退二值逻辑
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("R0-1 AgentTypeResolver 路由逻辑")
class AgentTypeResolverTest {

    @Autowired AgentTypeResolver resolver;
    @Autowired TaskRepository taskRepo;
    @Autowired TestPlanRepository planRepo;
    @Autowired TestPlanService testPlanService;
    @Autowired PromptService promptService;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-r01-", ".db");
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

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM task");
    }

    private Task newTask(String constraintsJson) {
        return taskRepo.insert(new Task(null, "test-task", "goal",
                constraintsJson, 10, false, "ACTIVE", null));
    }

    private TestPlan activePlanFor(long taskId) {
        return planRepo.insert(new TestPlan(null, taskId, "v001",
                TestPlan.STATUS_ACTIVE, "[]", null));
    }

    // ==================== resolve() ====================

    @Test
    @DisplayName("Case 1: constraints_json 指定 agent_type=RESEARCH → RESEARCH")
    void resolveResearchFromConstraints() {
        Task task = newTask("{\"agent_type\":\"RESEARCH\"}");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.RESEARCH);
    }

    @Test
    @DisplayName("Case 2: constraints_json 指定 agent_type=TESTER → TESTER")
    void resolveTesterFromConstraints() {
        Task task = newTask("{\"agent_type\":\"TESTER\"}");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.TESTER);
    }

    @Test
    @DisplayName("Case 3: constraints_json 指定 agent_type=DESIGNER → DESIGNER")
    void resolveDesignerFromConstraints() {
        Task task = newTask("{\"agent_type\":\"DESIGNER\"}");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.DESIGNER);
    }

    @Test
    @DisplayName("Case 4: 无 agent_type + ACTIVE TestPlan → TESTER")
    void fallbackToTesterWithActivePlan() {
        Task task = newTask("{}");
        activePlanFor(task.id());
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.TESTER);
    }

    @Test
    @DisplayName("Case 5: 无 agent_type + 无 ACTIVE TestPlan → DESIGNER")
    void fallbackToDesignerWithoutActivePlan() {
        Task task = newTask("{}");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.DESIGNER);
    }

    @Test
    @DisplayName("Case 6: 非法 agent_type → IllegalArgumentException")
    void invalidAgentTypeThrowsException() {
        Task task = newTask("{\"agent_type\":\"INVALID_TYPE\"}");
        assertThatThrownBy(() -> resolver.resolve(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法")
                .hasMessageContaining("INVALID_TYPE");
    }

    @Test
    @DisplayName("Case 7a: 空 constraints_json → 回退二值逻辑（无 Plan → DESIGNER）")
    void emptyConstraintsJsonFallsBack() {
        Task task = newTask("");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.DESIGNER);
    }

    @Test
    @DisplayName("Case 7b: null constraints_json → 回退二值逻辑（无 Plan → DESIGNER）")
    void nullConstraintsJsonFallsBack() {
        Task task = newTask(null);
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.DESIGNER);
    }

    @Test
    @DisplayName("Case 7c: agent_type 值为空字符串 → 回退二值逻辑")
    void blankAgentTypeValueFallsBack() {
        Task task = newTask("{\"agent_type\":\"\"}");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.DESIGNER);
    }

    @Test
    @DisplayName("Case 7d: agent_type 值为 null → 回退二值逻辑")
    void nullAgentTypeValueFallsBack() {
        Task task = newTask("{\"agent_type\":null}");
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.DESIGNER);
    }

    @Test
    @DisplayName("Case 7e: DRAFT TestPlan（非 ACTIVE）→ 回退到 DESIGNER")
    void draftPlanDoesNotTriggerTester() {
        Task task = newTask("{}");
        planRepo.insert(new TestPlan(null, task.id(), "v001",
                TestPlan.STATUS_DRAFT, "[]", null));
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.DESIGNER);
    }

    // ==================== systemPrompt() ====================

    @Test
    @DisplayName("systemPrompt(DESIGNER) 返回 Designer 硬编码 Prompt")
    void designerPromptIsHardcoded() {
        String prompt = resolver.systemPrompt(AgentType.DESIGNER);
        assertThat(prompt).isEqualTo("You are AGL game designer agent.");
    }

    @Test
    @DisplayName("systemPrompt(TESTER) 返回 tester_p001 内容")
    void testerPromptFromPromptService() {
        String prompt = resolver.systemPrompt(AgentType.TESTER);
        assertThat(prompt).contains("AI Game Tester");
    }

    @Test
    @DisplayName("systemPrompt(RESEARCH) 返回 research_p001 真实内容")
    void researchPromptFromPromptService() {
        String prompt = resolver.systemPrompt(AgentType.RESEARCH);
        assertThat(prompt).contains("AI Game Researcher");
        assertThat(prompt).contains("QUESTION");
        assertThat(prompt).contains("generate_report");
    }

    // ==================== toolWhitelist() ====================

    @Test
    @DisplayName("toolWhitelist(DESIGNER) 返回 10 个白名单工具（R0-8 显式隔离 + R2-1 additive 研究消费）")
    void designerToolWhitelistHas10Tools() {
        Set<String> whitelist = resolver.toolWhitelist(AgentType.DESIGNER);
        assertThat(whitelist).hasSize(10);
        assertThat(whitelist).containsExactlyInAnyOrder(
                "get_game_config", "modify_game_config", "simulate_game",
                "analyze_experiment", "compare_experiments",
                "get_game_design", "create_design_document", "create_change_proposal",
                // R2-1：Designer 只读研究消费工具（additive）
                "get_research_context", "get_research_claims");
    }

    @Test
    @DisplayName("toolWhitelist(TESTER) 返回 8 个白名单工具")
    void testerToolWhitelistHas8Tools() {
        Set<String> whitelist = resolver.toolWhitelist(AgentType.TESTER);
        assertThat(whitelist).hasSize(8);
        assertThat(whitelist).containsExactlyInAnyOrder(
                "test_plan_get", "game_start", "game_observe", "game_act",
                "game_collect_events", "game_collect_metrics", "finding_report", "test_report_create");
    }

    @Test
    @DisplayName("toolWhitelist(RESEARCH) 返回 6 个白名单工具（R0-8 落地）")
    void researchToolWhitelistHas6Tools() {
        Set<String> whitelist = resolver.toolWhitelist(AgentType.RESEARCH);
        assertThat(whitelist).hasSize(6);
        assertThat(whitelist).containsExactlyInAnyOrder(
                "web_search", "fetch_page", "save_source",
                "extract_claims", "compare_sources", "generate_report");
    }

    @Test
    @DisplayName("R0-8：三个 AgentType 白名单互不重叠（能力隔离）")
    void whitelistsAreMutuallyExclusive() {
        Set<String> designer = resolver.toolWhitelist(AgentType.DESIGNER);
        Set<String> tester = resolver.toolWhitelist(AgentType.TESTER);
        Set<String> research = resolver.toolWhitelist(AgentType.RESEARCH);

        // Designer 与 Tester 不重叠
        assertThat(designer).doesNotContainAnyElementsOf(tester);
        // Designer 与 Research 不重叠
        assertThat(designer).doesNotContainAnyElementsOf(research);
        // Tester 与 Research 不重叠
        assertThat(tester).doesNotContainAnyElementsOf(research);
    }

    // ==================== 显式指定优先于回退 ====================

    @Test
    @DisplayName("显式 agent_type=RESEARCH 优先于 ACTIVE TestPlan")
    void explicitTypeTakesPrecedenceOverActivePlan() {
        Task task = newTask("{\"agent_type\":\"RESEARCH\"}");
        activePlanFor(task.id()); // 有 ACTIVE TestPlan，但显式指定 RESEARCH
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.RESEARCH);
    }

    @Test
    @DisplayName("显式 agent_type=DESIGNER 优先于 ACTIVE TestPlan")
    void explicitDesignerTakesPrecedenceOverActivePlan() {
        Task task = newTask("{\"agent_type\":\"DESIGNER\"}");
        activePlanFor(task.id()); // 有 ACTIVE TestPlan，但显式指定 DESIGNER
        assertThat(resolver.resolve(task)).isEqualTo(AgentType.DESIGNER);
    }
}
