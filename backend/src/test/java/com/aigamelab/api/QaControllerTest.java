package com.aigamelab.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingFilter;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.qa.support.QaTestFixture;
import com.aigamelab.repository.FindingRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tester.FindingService;

/**
 * R3-B1 QA P1-3 控制器级验收：GET /api/qa/findings MockMvc 覆盖。
 *
 * <p>验证：请求参数到 Service 层的完整映射路径（空串→null 归一化、多值 runIds 绑定、
 * 枚举越界 40001 包络、空过滤返回全部、既有 /api/findings 不受影响）。
 *
 * <p>使用 QaTestFixture per-class 隔离（QA 架构冻结规则）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("R3-B1 QaController /api/qa/findings MockMvc")
class QaControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired FindingService findingService;
    @Autowired FindingRepository findingRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired RunRepository runRepo;
    @Autowired JdbcTemplate jdbc;

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

    /** 播种 v001/v002（PF-003：report 要求 ConfigVersion 真实存在）。 */
    private void seedConfigVersions() {
        jdbc.update("DELETE FROM game_config_version");
        for (String v : new String[] {"v001", "v002"}) {
            jdbc.update("INSERT INTO game_config_version(version, config_json, parent_version, comment)"
                    + " VALUES(?,?,?,?)", v, "{\"seed\":\"" + v + "\"}", null, "test seed " + v);
        }
    }

    private long newTask() {
        Task t = taskRepo.insert(new Task(null, "t", "g",
                "{\"configVersion\":\"v001\"}", 50, false, "ACTIVE", null));
        return t.id();
    }

    private long newRun(long taskId) {
        Run r = runRepo.insert(new Run(null, taskId, "COMPLETED", null,
                "test-model", 1, 5, 100, 0, null, "ok", null, null, null));
        return r.id();
    }

    private Finding report(Long runId, Long testPlanId, Long taskId, String configVersion,
            String severity, String category, String source, String status) {
        Finding saved = findingService.report(new Finding(null, runId, testPlanId, taskId,
                "sc-1", configVersion, severity, category, source, "title-" + severity,
                null, null, "seed=1 → start", null, null, null, null));
        if (status != null && !"OPEN".equals(status)) {
            findingService.updateStatus(saved.id(), status);
        }
        return saved;
    }

    private long newTestPlan(long taskId) {
        jdbc.update("INSERT INTO test_plan(task_id, config_version, scenarios_json)"
                + " VALUES(?,?,?)", taskId, "v001", "[]");
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void seed() {
        long taskA = newTask();
        long taskB = newTask();
        long run1 = newRun(taskA);
        long run2 = newRun(taskB);
        long planA = newTestPlan(taskA);
        report(run1, null, taskA, "v001", "HIGH", "CRASH", "INVARIANT", "OPEN");
        report(run2, planA, taskB, "v002", "MEDIUM", "SOFT_LOCK", "LLM", "CONFIRMED");
        report(run1, null, taskA, "v001", "LOW", "CRASH", "MANUAL", "OPEN");
    }

    // ==================================================================
    // 正常单条件
    // ==================================================================

    @Test
    @DisplayName("severity 单条件过滤 → 2 条")
    void severityFilter() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings").param("severity", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].severity").value("HIGH"));
    }

    @Test
    @DisplayName("configVersion 单条件过滤 → 2 条 v001")
    void configVersionFilter() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings").param("configVersion", "v001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("status 单条件过滤 → CONFIRMED 1 条")
    void statusFilter() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings").param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("category+source 多条件 AND → CRASH+MANUAL → 1 条")
    void multiCondition() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings")
                        .param("category", "CRASH")
                        .param("source", "MANUAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].category").value("CRASH"))
                .andExpect(jsonPath("$.data[0].source").value("MANUAL"));
    }

    // ==================================================================
    // runIds 多值
    // ==================================================================

    @Test
    @DisplayName("runIds 多值绑定 → union 2 条")
    void multiRunIdsBinding() throws Exception {
        long taskA = newTask();
        long run1 = newRun(taskA);
        long run2 = newRun(taskA);
        report(run1, null, taskA, "v001", "HIGH", "CRASH", "INVARIANT", "OPEN");
        report(run2, null, taskA, "v001", "MEDIUM", "CRASH", "LLM", "OPEN");
        mockMvc.perform(get("/api/qa/findings")
                        .param("runIds", String.valueOf(run1))
                        .param("runIds", String.valueOf(run2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ==================================================================
    // 空串归一化（blank → null）
    // ==================================================================

    @Test
    @DisplayName("severity 空串 → 归一化为 null，等价空过滤")
    void blankParamNormalized() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings")
                        .param("severity", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    // ==================================================================
    // 空过滤
    // ==================================================================

    @Test
    @DisplayName("无参数 → 空过滤返回全部")
    void emptyFilterAll() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    // ==================================================================
    // 非法枚举
    // ==================================================================

    @Test
    @DisplayName("severity=URGENT 越界 → 40001")
    void invalidSeverity() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings")
                        .param("severity", "URGENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("status=WONTFIX 越界 → 40001")
    void invalidStatus() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings")
                        .param("status", "WONTFIX"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("category=NOPE 越界 → 40001")
    void invalidCategory() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings")
                        .param("category", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("source=HUMAN 越界 → 40001")
    void invalidSource() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings")
                        .param("source", "HUMAN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ==================================================================
    // 无结果
    // ==================================================================

    @Test
    @DisplayName("configVersion=v999 → 空列表（合法条件，结果为空）")
    void noResult() throws Exception {
        seed();
        mockMvc.perform(get("/api/qa/findings")
                        .param("configVersion", "v999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ==================================================================
    // 既有 /api/findings 回归
    // ==================================================================

    @Test
    @DisplayName("原有 TesterController /api/findings 未受影响（severity=HIGH 仍返回 1 条）")
    void legacyEndpointRegression() throws Exception {
        seed();
        mockMvc.perform(get("/api/findings").param("severity", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1));
    }
}