package com.aigamelab.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.aigamelab.common.ErrorCodes;
import com.aigamelab.domain.Finding;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.qa.support.QaTestFixture;
import com.aigamelab.repository.FindingRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.AgentType;
import com.aigamelab.runtime.AgentTypeResolver;
import com.aigamelab.tester.FindingReportTool;
import com.aigamelab.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * QA P1-5 Isolation & Regression Deepening — 隔离边界回归测试。
 *
 * <p>本轮不增加新的 QA 产品能力，聚焦补真正能够证明隔离边界的测试：
 * <ul>
 *   <li>A. Agent 隔离：QA + ACTIVE TestPlan → QA（证明显式指定优先于回退）</li>
 *   <li>B. Tool 隔离：QA 白名单与 DESIGNER/RESEARCH 互斥的完整矩阵</li>
 *   <li>C. Finding 共享域：QA 工具创建的 Finding 在 /api/qa/findings 和 /api/findings 均可见</li>
 *   <li>D. REST 端点隔离：/api/findings 3 参契约不变（忽略 QA-only 参数）</li>
 *   <li>E. Lifecycle REST 层回归：OPEN→FIXED 非法迁移 40901，Missing Finding 40401</li>
 *   <li>F. ErrorCodes 回归守卫</li>
 * </ul>
 *
 * <p>使用 QaTestFixture per-class DB 隔离（合规 PF-002）。
 * 与 QaP1_1Test / QaP1_2Test / QaP1_3Test / QaControllerTest 无重复覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("QA P1-5 Isolation & Regression Deepening")
class QaP1_5Test {

    @Autowired AgentTypeResolver resolver;
    @Autowired FindingReportTool findingReportTool;
    @Autowired FindingRepository findingRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired RunRepository runRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;

    static final ObjectMapper MAPPER = new ObjectMapper();

    // ——— DB 隔离 ———
    static final Path DB = QaTestFixture.tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        QaTestFixture.datasourceProps(registry, DB);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        seedConfigVersions();
    }

    private void seedConfigVersions() {
        jdbc.update("DELETE FROM game_config_version");
        for (String v : new String[]{"v001", "v002"}) {
            jdbc.update("INSERT INTO game_config_version(version, config_json, parent_version, comment)"
                    + " VALUES(?,?,?,?)", v, "{\"seed\":\"" + v + "\"}", null, "test seed " + v);
        }
    }

    private Task newTask(String constraintsJson) {
        return taskRepo.insert(new Task(null, "p15-task", "p15 goal",
                constraintsJson, 10, false, "ACTIVE", null));
    }

    /** 直接落库一条 ACTIVE TestPlan（findActiveByTask 按 status='ACTIVE' 过滤）。 */
    private void insertActiveTestPlan(long taskId) {
        jdbc.update("INSERT INTO test_plan(task_id, config_version, status, scenarios_json)"
                + " VALUES(?,?,?,?)", taskId, "v001", "ACTIVE", "[]");
    }

    private Run newRun(long taskId) {
        return runRepo.insert(new Run(null, taskId, "RUNNING", null,
                "deepseek-chat", 0, 10, 100000, 0, null, null, null, null, null));
    }

    // ==================================================================
    // A. Agent Type Isolation — QA + ACTIVE TestPlan → QA（非 TESTER）
    // ==================================================================

    /**
     * A1: 显式 agent_type=QA + 存在 ACTIVE TestPlan → 仍解析为 QA，不回退到 TESTER。
     *
     * <p>DefaultAgentTypeResolver 回退逻辑：ACTIVE TestPlan → TESTER；
     * 但显式指定优先于回退。此测试证明 QA 永远不会通过回退路径被误路由。
     *
     * <p>覆盖场景：Task 创建为 QA 类型，同时有关联的 ACTIVE TestPlan。
     * 回退逻辑会返回 TESTER，但显式 agent_type=QA 覆盖回退。
     */
    @Test
    @DisplayName("A1: QA + ACTIVE TestPlan → QA（显式指定优先于 TestPlan 回退）")
    void explicitQaWithActivePlan_resolvesToQa_notTester() {
        Task task = newTask("{\"agent_type\":\"QA\"}");
        // 创建 ACTIVE TestPlan → 回退逻辑会返回 TESTER，但显式指定优先
        insertActiveTestPlan(task.id());

        AgentType resolved = resolver.resolve(task);
        assertThat(resolved).isEqualTo(AgentType.QA);
        assertThat(resolved).isNotEqualTo(AgentType.TESTER);
    }

    /**
     * A2: 默认回退（无 agent_type）+ ACTIVE TestPlan → TESTER（永远不是 QA）。
     * 强化 AgentTypeResolverTest Case 4，显式断言不是 QA。
     */
    @Test
    @DisplayName("A2: 默认回退 + ACTIVE TestPlan → TESTER（永远不是 QA）")
    void defaultFallback_activePlan_neverQa() {
        Task task = newTask("{}");
        insertActiveTestPlan(task.id());

        AgentType resolved = resolver.resolve(task);
        assertThat(resolved).isEqualTo(AgentType.TESTER);
        assertThat(resolved).isNotEqualTo(AgentType.QA);
    }

    /**
     * A3: 默认回退（无 agent_type）+ 无 TestPlan → DESIGNER（永远不是 QA/RESEARCH）。
     * 强化 AgentTypeResolverTest Case 5，显式断言不是 QA 和 RESEARCH。
     */
    @Test
    @DisplayName("A3: 默认回退 + 无 TestPlan → DESIGNER（永远不是 QA/RESEARCH）")
    void defaultFallback_noPlan_neverQaNorResearch() {
        Task task = newTask("{}");

        AgentType resolved = resolver.resolve(task);
        assertThat(resolved).isEqualTo(AgentType.DESIGNER);
        assertThat(resolved).isNotEqualTo(AgentType.QA);
        assertThat(resolved).isNotEqualTo(AgentType.RESEARCH);
    }

    // ==================================================================
    // B. Tool Isolation — 完整 4 型白名单矩阵
    // ==================================================================

    /**
     * B1: QA 工具白名单隔离矩阵（单测试覆盖全边界）：
     * - QA = {finding_report} exactly
     * - QA ⊆ TESTER（共享 finding_report）
     * - QA ∩ DESIGNER = ∅（互斥）
     * - QA ∩ RESEARCH = ∅（互斥）
     * - QA 最小闭环：仅 1 个工具
     */
    @Test
    @DisplayName("B1: QA 工具白名单矩阵——最小闭环、共享、互斥")
    void qaToolWhitelistMatrix() {
        Set<String> qa = resolver.toolWhitelist(AgentType.QA);
        Set<String> tester = resolver.toolWhitelist(AgentType.TESTER);
        Set<String> designer = resolver.toolWhitelist(AgentType.DESIGNER);
        Set<String> research = resolver.toolWhitelist(AgentType.RESEARCH);

        // QA = {finding_report} exactly（最小闭环）
        assertThat(qa).containsExactly("finding_report");

        // QA ⊆ TESTER（共享 finding_report）
        assertThat(qa).isSubsetOf(tester);
        assertThat(tester).contains("finding_report");

        // QA ∩ DESIGNER = ∅（互斥）
        assertThat(qa).doesNotContainAnyElementsOf(designer);
        assertThat(designer).doesNotContain("finding_report");

        // QA ∩ RESEARCH = ∅（互斥）
        assertThat(qa).doesNotContainAnyElementsOf(research);
        assertThat(research).doesNotContain("finding_report");
    }

    // ==================================================================
    // C. Finding 共享域 — 跨端点可见性证明
    // ==================================================================

    /**
     * C1: QA 工具创建的 Finding 在 /api/qa/findings 和 /api/findings 均可见。
     *
     * <p>证明 Finding 为共享域（单表），QA 和 Tester 使用不同端点查询同一张表，
     * 而非各自维护独立表。这是 P1-5 §6 数据隔离的核心断言。
     */
    @Test
    @DisplayName("C1: QA Finding 跨端点可见——/api/qa/findings 和 /api/findings 均可查到")
    void qaCreatedFinding_visibleViaBothEndpoints() throws Exception {
        // QA Task → Run → finding_report → Finding in shared table
        Task task = newTask("{\"agent_type\":\"QA\"}");
        Run run = newRun(task.id());

        JsonNode args = MAPPER.readTree("""
                {"sessionId":"p15-ses-1","scenarioId":"sc-1","configVersion":"v001",
                 "severity":"MEDIUM","category":"CRASH",
                 "title":"P1-5 QA Finding","expected":"HP>=0","actual":"hp=-3",
                 "reproSteps":"seed=42 → game_start → observe",
                 "seed":42,"tick":10,"toolCallIds":[1]}""");
        findingReportTool.execute(args, ToolContext.of(run.id(), 1L));

        // QA 端点可见
        mockMvc.perform(get("/api/qa/findings").param("severity", "MEDIUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("P1-5 QA Finding"));

        // Tester 端点也可见（共享表）
        mockMvc.perform(get("/api/findings").param("severity", "MEDIUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("P1-5 QA Finding"));
    }

    /**
     * C2: QA 和 Tester Finding 共存——各端点过滤正确、互不污染。
     *
     * <p>QA Finding (configVersion=v001) 和 Tester Finding (configVersion=v002)
     * 写入同一张表；QA 端点按 configVersion 过滤只返回 v001，Tester 端点过滤只返回 v002，
     * 空过滤返回全部 2 条。
     */
    @Test
    @DisplayName("C2: QA + Tester Finding 共存——各端点过滤正确、互不污染")
    void qaAndTesterFindings_coexist_noCrossContamination() throws Exception {
        // QA Finding
        Task qaTask = newTask("{\"agent_type\":\"QA\"}");
        Run qaRun = newRun(qaTask.id());
        JsonNode qaArgs = MAPPER.readTree("""
                {"sessionId":"qa-ses-1","scenarioId":"sc-1","configVersion":"v001",
                 "severity":"HIGH","category":"CRASH",
                 "title":"QA Finding","expected":"HP>=0","actual":"hp=-3",
                 "reproSteps":"seed=42 → start"}""");
        findingReportTool.execute(qaArgs, ToolContext.of(qaRun.id(), 1L));

        // Tester Finding (via direct report, simulating Tester agent path)
        Task testerTask = newTask("{\"agent_type\":\"TESTER\"}");
        Run testerRun = newRun(testerTask.id());
        JsonNode testerArgs = MAPPER.readTree("""
                {"sessionId":"tester-ses-1","scenarioId":"sc-2","configVersion":"v002",
                 "severity":"MEDIUM","category":"SOFT_LOCK",
                 "title":"Tester Finding","expected":"正常","actual":"异常",
                 "reproSteps":"seed=7 → start"}""");
        findingReportTool.execute(testerArgs, ToolContext.of(testerRun.id(), 1L));

        // QA 端点按 configVersion 过滤 → 只返回 v001
        mockMvc.perform(get("/api/qa/findings").param("configVersion", "v001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].configVersion").value("v001"));

        // Tester 端点按 configVersion 过滤 → 只返回 v002
        mockMvc.perform(get("/api/findings").param("configVersion", "v002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].configVersion").value("v002"));

        // 空过滤 → 2 条全返回（共享表）
        mockMvc.perform(get("/api/qa/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ==================================================================
    // D. REST 端点隔离 — /api/findings 3 参契约不变
    // ==================================================================

    /**
     * D1: /api/findings（Tester）仅按 configVersion/severity/runId 过滤，
     * QA-only 参数（category/status/runIds/testPlanId/taskId）被 Spring 忽略。
     *
     * <p>传入 category=CRASH + severity=HIGH → 只有 severity=HIGH 生效（2 条 HIGH），
     * 不会因 category=CRASH 而缩小到 1 条——证明 Tester 端点契约不变。
     */
    @Test
    @DisplayName("D1: /api/findings 3 参契约——QA-only 参数被忽略")
    void testerEndpoint_ignoresQaOnlyParams() throws Exception {
        // Seed: 2 HIGH findings (不同 category)，1 MEDIUM
        Task task = newTask("{\"agent_type\":\"QA\"}");
        Run run = newRun(task.id());
        findingReportTool.execute(MAPPER.readTree("""
                {"sessionId":"s1","scenarioId":"sc-1","configVersion":"v001",
                 "severity":"HIGH","category":"CRASH",
                 "title":"H1","expected":"e","actual":"a","reproSteps":"s"}"""),
                ToolContext.of(run.id(), 1L));
        findingReportTool.execute(MAPPER.readTree("""
                {"sessionId":"s2","scenarioId":"sc-2","configVersion":"v001",
                 "severity":"HIGH","category":"SOFT_LOCK",
                 "title":"H2","expected":"e","actual":"a","reproSteps":"s"}"""),
                ToolContext.of(run.id(), 1L));
        findingReportTool.execute(MAPPER.readTree("""
                {"sessionId":"s3","scenarioId":"sc-3","configVersion":"v001",
                 "severity":"MEDIUM","category":"CRASH",
                 "title":"M1","expected":"e","actual":"a","reproSteps":"s"}"""),
                ToolContext.of(run.id(), 1L));

        // /api/findings 只接受 severity（3 参），category 参数被忽略 → 返回 2 条 HIGH
        mockMvc.perform(get("/api/findings")
                        .param("severity", "HIGH")
                        .param("category", "CRASH"))  // QA-only param，应被忽略
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // /api/qa/findings 接受 category + severity → 返回 1 条 (HIGH + CRASH)
        mockMvc.perform(get("/api/qa/findings")
                        .param("severity", "HIGH")
                        .param("category", "CRASH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ==================================================================
    // E. Lifecycle REST 层回归 — P1-2 矩阵在 Controller 层的断言
    // ==================================================================

    /**
     * E1: 非法 lifecycle OPEN→FIXED → 40901 包络 + DB 状态不变。
     *
     * <p>TesterControllerTest 仅测了 OPEN→CONFIRMED（合法）和 NONSENSE→40001（非法值），
     * 未测 P1-2 关键非法边 OPEN→FIXED（合法值但非法迁移）。本轮补此缺口。
     */
    @Test
    @DisplayName("E1: OPEN→FIXED → 40901（P1-2 非法迁移 REST 回归）+ DB 状态不变")
    void openToFixed_viaRest_returns40901() throws Exception {
        // 直接落库一条 OPEN Finding（scenario_id NOT NULL 约束）
        jdbc.update("INSERT INTO finding(run_id,scenario_id,config_version,severity,category,"
                + "source,title,expected,actual,repro_steps,evidence_json,status)"
                + " VALUES(null,'sc-1','v001','HIGH','CRASH','LLM','test','HP>=0','hp=-3','seed=1','{}','OPEN')");
        long fid = jdbc.queryForObject("SELECT id FROM finding LIMIT 1", Long.class);

        // OPEN → FIXED 非法迁移 → 40901
        mockMvc.perform(post("/api/findings/{id}/status", fid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FIXED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCodes.CONFLICT));

        // DB 状态不变
        String dbStatus = jdbc.queryForObject("SELECT status FROM finding WHERE id=?", String.class, fid);
        assertThat(dbStatus).isEqualTo("OPEN");
    }

    /**
     * E2: Missing Finding status update → 40401。
     *
     * <p>TesterControllerTest 测了 GET /api/findings/{id} 不存在返回 40401，
     * 但未测 POST /api/findings/{id}/status 不存在的 Finding → 40401。
     * 本轮补此缺口。
     */
    @Test
    @DisplayName("E2: Missing Finding status update → 40401")
    void updateStatus_missingFinding_returns40401() throws Exception {
        mockMvc.perform(post("/api/findings/999999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.NOT_FOUND));
    }

    // ==================================================================
    // F. ErrorCodes 回归守卫
    // ==================================================================

    /**
     * F1: ErrorCodes 仍为 6 个既定值，无新增/修改。
     * 本轮不得新增 ErrorCode（§11 + §17 冻结）。
     */
    @Test
    @DisplayName("F1: ErrorCodes 6 码不变（回归守卫）")
    void errorCodesUnchanged() {
        assertThat(ErrorCodes.INVALID_ARGUMENT).isEqualTo(40001);
        assertThat(ErrorCodes.NOT_FOUND).isEqualTo(40401);
        assertThat(ErrorCodes.CONFLICT).isEqualTo(40901);
        assertThat(ErrorCodes.RATE_LIMITED).isEqualTo(42901);
        assertThat(ErrorCodes.UPSTREAM_ERROR).isEqualTo(50201);
        assertThat(ErrorCodes.INTERNAL_ERROR).isEqualTo(50000);
    }
}
