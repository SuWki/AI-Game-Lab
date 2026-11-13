package com.aigamelab.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.common.ErrorCodes;
import com.aigamelab.llm.PromptService;
import com.aigamelab.qa.support.QaTestFixture;
import com.aigamelab.runtime.AgentType;
import com.aigamelab.runtime.AgentTypeResolver;
import com.aigamelab.tool.GameTool;
import com.aigamelab.tool.ToolRegistry;

/**
 * QA P1-6 Contract Matrix（R5 Phase 2 扩展）— 冻结断言。
 *
 * <p>将 AF-001 冻结矩阵固化为测试断言：硬编码矩阵 == Resolver 输出，
 * 任何 P1+ 变更若触碰冻结契约，此类测试即编译/运行期失败。
 *
 * <p>验证维度（§P1-6 审计要求 + R5 Phase 2 扩展）：
 * <ul>
 *   <li>C1: AgentType 枚举值集合 — 8 值（P1 4 + R5 QA 团队 4）</li>
 *   <li>C2: Tool 白名单矩阵 — 8 型各自精确匹配（C2a–C2h）</li>
 *   <li>C3: Tool 注册总数 — 29（P1 24 + R5 QA_LEAD 编排工具 5）</li>
 *   <li>C4: finding_report 隔离矩阵 — QA 家族=ALLOW, DESIGNER/RESEARCH=DENY</li>
 *   <li>C5: Prompt 注册 — 13 个 version（designer_p001 active, 12 inactive）</li>
 *   <li>C6: Prompt 文件 — 13 个 classpath resource</li>
 *   <li>C7: ErrorCodes — 6 码不变</li>
 *   <li>C8: 白名单互斥 + 子集关系（回归守卫）</li>
 *   <li>C9: System Prompt 三线匹配（AgentType → Prompt version → 非空）</li>
 * </ul>
 *
 * <p>注意：P1 Sprint 落地为最小闭环 4 AgentType / 5 Prompt / 24 Tool；
 * R5 Phase 2 扩展为 8 AgentType / 13 Prompt / 29 Tool（QA_LEAD 编排白名单按
 * R5 §6，与 freeze §B 的差异已在实现报告中记录）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("QA P1-6 Contract Matrix — 冻结断言")
class ContractMatrixTest {

    @Autowired AgentTypeResolver resolver;
    @Autowired ToolRegistry registry;
    @Autowired PromptService promptService;
    @Autowired JdbcTemplate jdbc;

    static final Path DB = QaTestFixture.tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        QaTestFixture.datasourceProps(registry, DB);
    }

    // ==================================================================
    // C1: AgentType 枚举 — 8 值冻结（R5 Phase 2 扩展：+4 QA 团队类型）
    // ==================================================================

    @Test
    @DisplayName("C1: AgentType 枚举恰好 8 值：DESIGNER/TESTER/RESEARCH/QA/QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER")
    void agentTypeEnumExactly8() {
        Set<AgentType> actual = Set.of(AgentType.values());
        assertThat(actual).hasSize(9);
        assertThat(actual).containsExactlyInAnyOrder(
                AgentType.DESIGNER, AgentType.TESTER, AgentType.RESEARCH, AgentType.QA,
                AgentType.QA_LEAD, AgentType.QA_TESTER, AgentType.QA_ANALYST, AgentType.QA_RESEARCHER, AgentType.BUILDER);
    }

    // ==================================================================
    // C2: Tool 白名单矩阵 — 4 型各自精确匹配
    // ==================================================================

    @Test
    @DisplayName("C2a: DESIGNER 白名单 = 10 个工具（冻结精确集合）")
    void designerWhitelistExact() {
        Set<String> expected = Set.of(
                "get_game_config", "modify_game_config", "simulate_game",
                "analyze_experiment", "compare_experiments",
                "get_game_design", "create_design_document", "create_change_proposal",
                "get_research_context", "get_research_claims");
        assertThat(resolver.toolWhitelist(AgentType.DESIGNER))
                .hasSize(10)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("C2b: TESTER 白名单 = 8 个工具（冻结精确集合）")
    void testerWhitelistExact() {
        Set<String> expected = Set.of(
                "test_plan_get", "game_start", "game_observe", "game_act",
                "game_collect_events", "game_collect_metrics",
                "finding_report", "test_report_create");
        assertThat(resolver.toolWhitelist(AgentType.TESTER))
                .hasSize(8)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("C2c: RESEARCH 白名单 = 6 个工具（冻结精确集合）")
    void researchWhitelistExact() {
        Set<String> expected = Set.of(
                "web_search", "fetch_page", "save_source",
                "extract_claims", "compare_sources", "generate_report");
        assertThat(resolver.toolWhitelist(AgentType.RESEARCH))
                .hasSize(6)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("C2d: QA 白名单 = 1 个工具 finding_report（冻结精确集合）")
    void qaWhitelistExact() {
        Set<String> expected = Set.of("finding_report");
        assertThat(resolver.toolWhitelist(AgentType.QA))
                .hasSize(1)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    // ==================== C2e–C2h: R5 Phase 2 QA 团队白名单 ====================

    @Test
    @DisplayName("C2e: QA_LEAD 白名单 = 6 个编排工具（R5 §6）")
    void qaLeadWhitelistExact() {
        Set<String> expected = Set.of(
                "qa_create_subtask", "qa_start_subrun", "qa_check_subrun_status",
                "qa_collect_findings", "finding_report", "qa_complete_orchestration");
        assertThat(resolver.toolWhitelist(AgentType.QA_LEAD))
                .hasSize(6)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("C2f: QA_TESTER 白名单 = 10 个工具（Tester 8 + web_search + fetch_page）")
    void qaTesterWhitelistExact() {
        Set<String> expected = Set.of(
                "test_plan_get", "game_start", "game_observe", "game_act",
                "game_collect_events", "game_collect_metrics",
                "finding_report", "test_report_create",
                "web_search", "fetch_page");
        assertThat(resolver.toolWhitelist(AgentType.QA_TESTER))
                .hasSize(10)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("C2g: QA_ANALYST 白名单 = 7 个工具（Designer 分析子集 + finding_report + create_design_document）")
    void qaAnalystWhitelistExact() {
        Set<String> expected = Set.of(
                "simulate_game", "analyze_experiment", "compare_experiments",
                "game_collect_metrics", "game_collect_events",
                "finding_report", "create_design_document");
        assertThat(resolver.toolWhitelist(AgentType.QA_ANALYST))
                .hasSize(7)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("C2h: QA_RESEARCHER 白名单 = 10 个工具（Research 6 + finding_report + game_observe + game_collect_events + analyze_experiment）")
    void qaResearcherWhitelistExact() {
        Set<String> expected = Set.of(
                "web_search", "fetch_page", "save_source",
                "extract_claims", "compare_sources", "generate_report",
                "finding_report", "game_observe", "game_collect_events", "analyze_experiment");
        assertThat(resolver.toolWhitelist(AgentType.QA_RESEARCHER))
                .hasSize(10)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    // ==================================================================
    // C3: Tool 注册总数 — 29（24 P1 + 5 新 QA_LEAD 编排工具）
    // ==================================================================

    @Test
    @DisplayName("C3: ToolRegistry 注册总数 = 29（24 P1 + 5 QA_LEAD 编排工具）")
    void toolRegistryCount29() {
        assertThat(registry.size()).isEqualTo(37);
        assertThat(registry.contains("local_change_propose")).isTrue();
    }

    // ==================================================================
    // C4: finding_report 隔离矩阵 — QA/TESTER=ALLOW, DESIGNER/RESEARCH=DENY
    // ==================================================================

    @Test
    @DisplayName("C4: finding_report 隔离矩阵 — QA/QEAD/QAT/QAA/QAR=ALLOW, DESIGNER/RESEARCH=DENY")
    void findingReportIsolationMatrix() {
        Set<String> qa = resolver.toolWhitelist(AgentType.QA);
        Set<String> tester = resolver.toolWhitelist(AgentType.TESTER);
        Set<String> designer = resolver.toolWhitelist(AgentType.DESIGNER);
        Set<String> research = resolver.toolWhitelist(AgentType.RESEARCH);

        assertThat(qa).contains("finding_report");
        assertThat(tester).contains("finding_report");
        assertThat(designer).doesNotContain("finding_report");
        assertThat(research).doesNotContain("finding_report");

        // R5 Phase 2：QA 团队 4 型全部持有 finding_report（共享 Finding 域）
        assertThat(resolver.toolWhitelist(AgentType.QA_LEAD)).contains("finding_report");
        assertThat(resolver.toolWhitelist(AgentType.QA_TESTER)).contains("finding_report");
        assertThat(resolver.toolWhitelist(AgentType.QA_ANALYST)).contains("finding_report");
        assertThat(resolver.toolWhitelist(AgentType.QA_RESEARCHER)).contains("finding_report");
    }

    // ==================================================================
    // C5: Prompt 注册 — 5 个 version, designer_p001 active, 4 inactive
    // ==================================================================

    @Test
    @DisplayName("C5: prompt_version 表 = 13 行（designer_p001 active，12 inactive；R5 +8 QA Role）")
    void promptVersionCount() {
        int total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prompt_version", Integer.class);
        assertThat(total).isEqualTo(14);

        int activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prompt_version WHERE is_active=1", Integer.class);
        assertThat(activeCount).isEqualTo(1);

        String activeVersion = jdbc.queryForObject(
                "SELECT version FROM prompt_version WHERE is_active=1", String.class);
        assertThat(activeVersion).isEqualTo("p001");
    }

    // ==================================================================
    // C6: Prompt 文件 — 13 个 classpath resource 存在
    // ==================================================================

    @Test
    @DisplayName("C6: 13 个 prompt md 文件存在于 classpath（5 P1 + 8 R5 QA Role）")
    void promptFilesExistOnClasspath() {
        for (String file : new String[]{
                "prompts/designer_p001.md",
                "prompts/designer_p002.md",
                "prompts/tester_p001.md",
                "prompts/research_p001.md",
                "prompts/qa_p001.md",
                "prompts/qa_lead_p001.md",
                "prompts/qa_player_novice_p001.md",
                "prompts/qa_player_aggressive_p001.md",
                "prompts/qa_player_build_specialist_p001.md",
                "prompts/qa_combat_analyst_p001.md",
                "prompts/qa_numerical_analyst_p001.md",
                "prompts/qa_exploit_hunter_p001.md",
                "prompts/qa_ux_reviewer_p001.md"}) {
            assertThat(ContractMatrixTest.class.getClassLoader()
                    .getResource(file) != null)
                    .as("classpath resource %s should exist", file)
                    .isTrue();
        }
    }

    // ==================================================================
    // C7: ErrorCodes — 6 码不变
    // ==================================================================

    @Test
    @DisplayName("C7: ErrorCodes 冻结 6 码（40001/40401/40901/42901/50201/50000）")
    void errorCodesFrozen() {
        assertThat(ErrorCodes.INVALID_ARGUMENT).isEqualTo(40001);
        assertThat(ErrorCodes.NOT_FOUND).isEqualTo(40401);
        assertThat(ErrorCodes.CONFLICT).isEqualTo(40901);
        assertThat(ErrorCodes.RATE_LIMITED).isEqualTo(42901);
        assertThat(ErrorCodes.UPSTREAM_ERROR).isEqualTo(50201);
        assertThat(ErrorCodes.INTERNAL_ERROR).isEqualTo(50000);
    }

    // ==================================================================
    // C8: 白名单互斥 + 子集关系（回归守卫）
    // ==================================================================

    @Test
    @DisplayName("C8a: QA ⊆ TESTER（finding_report 共享）")
    void qaSubsetOfTester() {
        assertThat(resolver.toolWhitelist(AgentType.QA))
                .isSubsetOf(resolver.toolWhitelist(AgentType.TESTER));
    }

    @Test
    @DisplayName("C8b: DESIGNER ∩ TESTER = ∅（互斥）")
    void designerTesterDisjoint() {
        assertThat(resolver.toolWhitelist(AgentType.DESIGNER))
                .doesNotContainAnyElementsOf(resolver.toolWhitelist(AgentType.TESTER));
    }

    @Test
    @DisplayName("C8c: DESIGNER ∩ RESEARCH = ∅（互斥）")
    void designerResearchDisjoint() {
        assertThat(resolver.toolWhitelist(AgentType.DESIGNER))
                .doesNotContainAnyElementsOf(resolver.toolWhitelist(AgentType.RESEARCH));
    }

    @Test
    @DisplayName("C8d: TESTER ∩ RESEARCH = ∅（互斥）")
    void testerResearchDisjoint() {
        assertThat(resolver.toolWhitelist(AgentType.TESTER))
                .doesNotContainAnyElementsOf(resolver.toolWhitelist(AgentType.RESEARCH));
    }

    // ==================================================================
    // C9: System Prompt 三线匹配（AgentType → Prompt version → 非空）
    // ==================================================================

    @Test
    @DisplayName("C9: 每个 AgentType 的 System Prompt 非空且唯一")
    void allPromptsNonNull() {
        for (AgentType type : AgentType.values()) {
            String prompt = resolver.systemPrompt(type);
            assertThat(prompt)
                    .as("SystemPrompt(%s) should be non-null and non-empty", type)
                    .isNotBlank();
        }
    }
}
