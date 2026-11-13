package com.aigamelab.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
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
 * R3-B1（QA P1-3）多维过滤验收测试。
 *
 * <p>验证矩阵（P1-3 完成判定 + R3-B1 要求）：
 * - A. 单维过滤：severity / category / source / status / configVersion / runIds / taskId / testPlanId
 * - B. 多维组合：全维度 AND 语义（多条件同时收紧）
 * - C. 空过滤：返回全部（空过滤合法，不报错）
 * - D. 非法过滤：枚举维度越界 → 40001；configVersion blank → 40001
 * - E. 无结果：条件不命中 → 空列表
 * - F. 既有查询回归：4 参 findByFilter 行为不被破坏（同一维度结果与 findByQaFilter 一致）
 *
 * <p>使用 QaTestFixture per-class DB 隔离；run/testPlan/task 外键依赖先行播种。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("R3-B1 QA P1-3 多维过滤")
class QaP1_3Test {

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

    /** 播种 v001/v002/v003（PF-003：report 要求 ConfigVersion 真实存在）。 */
    private void seedConfigVersions() {
        jdbc.update("DELETE FROM game_config_version");
        for (String v : new String[] {"v001", "v002", "v003"}) {
            jdbc.update("INSERT INTO game_config_version(version, config_json, parent_version, comment)"
                    + " VALUES(?,?,?,?)", v, "{\"seed\":\"" + v + "\"}", null, "test seed " + v);
        }
    }

    // ——— 测试数据工厂 ———

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

    /** 落一条 FK-valid test_plan（挂 task 下，供 finding.test_plan_id 引用）。 */
    private long newTestPlan(long taskId) {
        jdbc.update("INSERT INTO test_plan(task_id, config_version, scenarios_json)"
                + " VALUES(?,?,?)", taskId, "v001", "[]");
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /** 经 FindingService.report 落库一条基础 Finding（无 Evidence 的 MANUAL 登记路径）。 */
    private Finding report(long runId, long taskId, String severity, String category,
            String source, String status, String configVersion, Long testPlanId) {
        Finding saved = findingService.report(new Finding(null, runId, testPlanId, taskId,
                "sc-1", configVersion, severity, category, source, "title-" + severity,
                null, null, "seed=1 → start", null, null, null, null));
        // 状态迁移走合法边：OPEN→CONFIRMED→FIXED / OPEN→DISMISSED
        if (status != null && !"OPEN".equals(status)) {
            if ("FIXED".equals(status)) {
                findingService.updateStatus(saved.id(), "CONFIRMED");
                saved = findingService.updateStatus(saved.id(), "FIXED");
            } else {
                saved = findingService.updateStatus(saved.id(), status);
            }
        }
        return saved;
    }

    /**
     * 播种标准数据集（8 条，覆盖 3 版本 × 2 严重级 × 2 状态 × 2 来源 × 2 类别 × 2 Run）：
     * 每条各维度分布交错，使任意单维过滤都能区分布尔条件。
     */
    private long seedStandardSet() {
        long taskA = newTask();
        long taskB = newTask();
        long run1 = newRun(taskA);
        long run2 = newRun(taskB);
        long planA = newTestPlan(taskA);
        long planB = newTestPlan(taskB);

        report(run1, taskA, "HIGH", "CRASH", "INVARIANT", "OPEN", "v001", null);
        report(run1, taskA, "HIGH", "CRASH", "INVARIANT", "CONFIRMED", "v002", null);
        report(run1, taskA, "HIGH", "SOFT_LOCK", "LLM", "OPEN", "v003", null);
        report(run2, taskB, "HIGH", "SOFT_LOCK", "LLM", "DISMISSED", "v001", planA);
        report(run2, taskB, "MEDIUM", "CRASH", "MANUAL", "OPEN", "v002", planA);
        report(run2, taskB, "MEDIUM", "CRASH", "MANUAL", "FIXED", "v003", null);
        report(run1, taskA, "LOW", "BALANCE_ANOMALY", "LLM", "OPEN", "v001", planB);
        report(run2, taskB, "INFO", "OTHER", "INVARIANT", "CONFIRMED", "v002", planB);
        return run1;
    }

    private List<Finding> qa(FindingFilter f) {
        return findingService.findByQaFilter(f);
    }

    // ==================================================================
    // A. 单维过滤
    // ==================================================================

    @Test
    @DisplayName("A1 severity 单维：HIGH 命中 4 条")
    void singleSeverity() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter(null, "HIGH", null, null, null,
                null, null, null));
        assertThat(r).hasSize(4);
        assertThat(r).allMatch(f -> "HIGH".equals(f.severity()));
    }

    @Test
    @DisplayName("A2 status 单维：CONFIRMED 命中 2 条")
    void singleStatus() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter(null, null, null, null, "CONFIRMED",
                null, null, null));
        assertThat(r).hasSize(2);
        assertThat(r).allMatch(f -> "CONFIRMED".equals(f.status()));
    }

    @Test
    @DisplayName("A3 category 单维：CRASH 命中 4 条")
    void singleCategory() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter(null, null, "CRASH", null, null,
                null, null, null));
        assertThat(r).hasSize(4);
        assertThat(r).allMatch(f -> "CRASH".equals(f.category()));
    }

    @Test
    @DisplayName("A4 source 单维：MANUAL 命中 2 条")
    void singleSource() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter(null, null, null, "MANUAL", null,
                null, null, null));
        assertThat(r).hasSize(2);
        assertThat(r).allMatch(f -> "MANUAL".equals(f.source()));
    }

    @Test
    @DisplayName("A5 configVersion 单维：v003 命中 2 条")
    void singleConfigVersion() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter("v003", null, null, null, null,
                null, null, null));
        assertThat(r).hasSize(2);
        assertThat(r).allMatch(f -> "v003".equals(f.configVersion()));
    }

    @Test
    @DisplayName("A6 runIds 单值：run1 命中 4 条")
    void singleRunIds() {
        long run1 = seedStandardSet();
        List<Finding> r = qa(new FindingFilter(null, null, null, null, null,
                List.of(run1), null, null));
        assertThat(r).hasSize(4);
        assertThat(r).allMatch(f -> run1 == f.runId());
    }

    @Test
    @DisplayName("A7 runIds 多值：run1+run2 命中全部 8 条")
    void multiRunIds() {
        long run1 = seedStandardSet();
        List<Finding> r = qa(new FindingFilter(null, null, null, null, null,
                List.of(run1, run1 + 1), null, null));
        assertThat(r).hasSize(8);
    }

    @Test
    @DisplayName("A8 taskId 单维：taskA 命中 4 条")
    void singleTaskId() {
        long run1 = seedStandardSet();
        long taskA = jdbc.queryForObject(
                "SELECT task_id FROM run WHERE id=?", Long.class, run1);
        List<Finding> r = qa(new FindingFilter(null, null, null, null, null,
                null, null, taskA));
        assertThat(r).hasSize(4);
        assertThat(r).allMatch(f -> taskA == f.taskId());
    }

    @Test
    @DisplayName("A9 testPlanId 单维：planA 命中 2 条")
    void singleTestPlanId() {
        long run1 = seedStandardSet();
        long taskA = jdbc.queryForObject(
                "SELECT task_id FROM run WHERE id=?", Long.class, run1);
        long planA = jdbc.queryForObject(
                "SELECT id FROM test_plan WHERE task_id=? ORDER BY id LIMIT 1",
                Long.class, taskA);
        List<Finding> r = qa(new FindingFilter(null, null, null, null, null,
                null, planA, null));
        assertThat(r).hasSize(2);
        assertThat(r).allMatch(f -> f.testPlanId() != null && f.testPlanId() == planA);
    }

    // ==================================================================
    // B. 多维组合（AND 语义）
    // ==================================================================

    @Test
    @DisplayName("B1 severity+status+configVersion 三条件 AND")
    void multiAnd() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter("v001", "HIGH", null, null, "OPEN",
                null, null, null));
        assertThat(r).hasSize(1); // v001+HIGH+OPEN：CRASH/INVARIANT 那条
    }

    @Test
    @DisplayName("B2 status+source+category 三条件 AND")
    void multiAndSourceCategory() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter(null, null, "CRASH", "MANUAL", "FIXED",
                null, null, null));
        assertThat(r).hasSize(1); // v003+MEDIUM+CRASH+MANUAL+FIXED
    }

    @Test
    @DisplayName("B3 runIds+taskId+status 组合：AND 交集")
    void multiRunTaskStatus() {
        long run1 = seedStandardSet();
        long taskA = jdbc.queryForObject(
                "SELECT task_id FROM run WHERE id=?", Long.class, run1);
        List<Finding> r = qa(new FindingFilter(null, null, null, null, "OPEN",
                List.of(run1), null, taskA));
        assertThat(r).hasSize(3); // run1 内 taskA 的 OPEN（HIGH+CRASH / HIGH+SOFT_LOCK / LOW）
    }

    // ==================================================================
    // C. 空过滤
    // ==================================================================

    @Test
    @DisplayName("C1 空过滤返回全部 8 条")
    void emptyFilterReturnsAll() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter(null, null, null, null, null,
                null, null, null));
        assertThat(r).hasSize(8);
    }

    @Test
    @DisplayName("C2 全 null 构造（含 runIds null 归一化）不抛错")
    void nullRunIdsNormalized() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter(null, null, null, null, null,
                null, null, null));
        assertThat(r).hasSize(8);
    }

    // ==================================================================
    // D. 非法过滤
    // ==================================================================

    @Test
    @DisplayName("D1 severity 越界 → 40001")
    void invalidSeverity() {
        seedStandardSet();
        assertThatThrownBy(() -> qa(new FindingFilter(null, "URGENT", null, null,
                null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCodes.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("D2 status 越界 → 40001")
    void invalidStatus() {
        seedStandardSet();
        assertThatThrownBy(() -> qa(new FindingFilter(null, null, null, null,
                "WONTFIX", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCodes.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("D3 category 越界 → 40001")
    void invalidCategory() {
        seedStandardSet();
        assertThatThrownBy(() -> qa(new FindingFilter(null, null, "NOPE", null,
                null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCodes.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("D4 source 越界 → 40001")
    void invalidSource() {
        seedStandardSet();
        assertThatThrownBy(() -> qa(new FindingFilter(null, null, null, "HUMAN",
                null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCodes.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("D5 configVersion blank → 40001")
    void blankConfigVersion() {
        seedStandardSet();
        assertThatThrownBy(() -> qa(new FindingFilter("  ", null, null, null,
                null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCodes.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("D6 非法过滤不产生 DB 副作用（只读路径）")
    void invalidFilterNoSideEffect() {
        seedStandardSet();
        assertThatThrownBy(() -> qa(new FindingFilter(null, "URGENT", null, null,
                null, null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThat(findingCount()).isEqualTo(8);
    }

    // ==================================================================
    // E. 无结果
    // ==================================================================

    @Test
    @DisplayName("E1 条件不命中 → 空列表")
    void noResult() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter("v999", null, null, null, null,
                null, null, null));
        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("E2 合法枚举但无交集组合 → 空列表")
    void noResultCombo() {
        seedStandardSet();
        List<Finding> r = qa(new FindingFilter(null, "INFO", null, null, "FIXED",
                null, null, null));
        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("E3 空库空过滤 → 空列表")
    void emptyDbEmptyFilter() {
        List<Finding> r = qa(new FindingFilter(null, null, null, null, null,
                null, null, null));
        assertThat(r).isEmpty();
    }

    // ==================================================================
    // F. 既有查询回归（4 参 findByFilter 不破坏 + 与 QA 过滤同构）
    // ==================================================================

    @Test
    @DisplayName("F1 既有 findByFilter 4 参结果不变（v001+HIGH+run1 等价子集）")
    void legacyFindByFilterRegression() {
        long run1 = seedStandardSet();
        List<Finding> legacy = findingRepo.findByFilter("v001", "HIGH", run1, null);
        List<Finding> qaSubset = qa(new FindingFilter("v001", "HIGH", null, null,
                null, List.of(run1), null, null));
        assertThat(legacy).hasSize(1);
        // QA 过滤必须与既有回归路径同构（同一批行的子集）
        assertThat(qaSubset).containsExactlyElementsOf(legacy);
    }

    @Test
    @DisplayName("F2 既有 findByFilter 全 null = 空过滤全量，不被 QA 路径影响")
    void legacyNullFilterRegression() {
        seedStandardSet();
        List<Finding> legacyAll = findingRepo.findByFilter(null, null, null, null);
        assertThat(legacyAll).hasSize(8);
    }

    @Test
    @DisplayName("F3 既有 findByFilter 无结果语义保持（版本不命中 → 空）")
    void legacyNoResultRegression() {
        seedStandardSet();
        assertThat(findingRepo.findByFilter("v999", null, null, null)).isEmpty();
    }

    private int findingCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM finding", Integer.class);
        return n == null ? 0 : n;
    }
}