package com.aigamelab.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.TestPlan;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T-DB1 落地验收测试（裁决 §七 九项要求）：
 * 新库完整初始化、12 表存在、test_plan/finding CRUD、nullable 外键行为、
 * JSON TEXT 读写、source 默认值、索引存在、现有表不受影响。
 */
@DisplayName("T-DB1 test_plan/finding Repository 落地验收")
class TesterRepositoryTest extends AbstractRepositoryTest {

    @Autowired TestPlanRepository planRepo;
    @Autowired FindingRepository findingRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired RunRepository runRepo;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void cleanAll() {
        // 子表先删，再清被引用的 run/task，保证用例间隔离
        jdbc.update("DELETE FROM research_claim");
        jdbc.update("DELETE FROM research_source");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM game_config_version");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM prompt_version");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    private long newTaskId() {
        return taskRepo.insert(new com.aigamelab.domain.Task(null, "Tester 验收任务",
                "验证 test_plan/finding", "{}", 30, true, "ACTIVE", null)).id();
    }

    private long newRunId(long taskId) {
        return runRepo.insert(new com.aigamelab.domain.Run(null, taskId, "PENDING", null,
                null, 0, 25, 100000, 0, null, null, null, null, null)).id();
    }

    // ==================== 裁决 §七.2：12 张表均存在 ====================

    @Test
    void schemaHasExactlyTwentyOneTables() {
        List<String> names = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                String.class);
        assertThat(names).containsExactlyInAnyOrder(
                "task", "run", "step", "tool_call", "experiment",
                "game_config_version", "change_proposal", "agent_event",
                "design_document", "prompt_version",
                "test_plan", "finding",
                "research_source", "research_claim",
                "qa_role_assignment", "qa_release_gate", "local_test_run",
                "runtime_context", "runtime_turn", "local_test_context", "local_change_proposal", "production_artifact", "production_job");
    }

    // ==================== 裁决 §七.3：test_plan CRUD ====================

    @Test
    void testPlan_crudRoundtrip() throws Exception {
        long taskId = newTaskId();
        String scenariosJson = """
                [{"id":"smoke","type":"SMOKE","goal":"存活","seed":42,"repeat":1,
                  "playerProfile":"SCRIPTED","invariants":["HP>=0"],
                  "overrides":{"player.maxHp":1}}]""";

        // Create + DB 默认值回填
        TestPlan created = planRepo.insert(new TestPlan(null, taskId, "v001",
                null, scenariosJson, null));
        assertThat(created.id()).isNotNull();
        assertThat(created.status()).isEqualTo(TestPlan.STATUS_DRAFT); // DEFAULT 'DRAFT'
        assertThat(created.createdAt()).isNotNull();

        // Read
        TestPlan loaded = planRepo.findById(created.id()).orElseThrow();
        assertThat(loaded.taskId()).isEqualTo(taskId);
        assertThat(loaded.configVersion()).isEqualTo("v001");

        // JSON TEXT 字段读写（裁决 §七.6）
        TestPlan.Scenario[] scs = mapper.readValue(loaded.scenariosJson(),
                TestPlan.Scenario[].class);
        assertThat(scs).hasSize(1);
        assertThat(scs[0].overrides()).containsEntry("player.maxHp", 1);

        // Update: status 流转 DRAFT → ACTIVE → COMPLETED
        planRepo.updateStatus(created.id(), TestPlan.STATUS_ACTIVE);
        assertThat(planRepo.findById(created.id()).orElseThrow().status())
                .isEqualTo(TestPlan.STATUS_ACTIVE);
        planRepo.updateStatus(created.id(), TestPlan.STATUS_COMPLETED);

        // Update: scenarios 编辑
        String edited = "[{\"id\":\"edited\",\"type\":\"BOUNDARY\",\"goal\":\"1HP\","
                + "\"seed\":43,\"repeat\":1,\"playerProfile\":\"SCRIPTED\","
                + "\"invariants\":[],\"overrides\":{}}]";
        planRepo.updateScenarios(created.id(), edited);
        assertThat(planRepo.findById(created.id()).orElseThrow().scenariosJson())
                .isEqualTo(edited);

        // findByTaskId（命中 idx_tp_task）
        TestPlan second = planRepo.insert(new TestPlan(null, taskId, "v002",
                TestPlan.STATUS_DRAFT, "[]", null));
        List<TestPlan> byTask = planRepo.findByTaskId(taskId);
        assertThat(byTask).extracting(TestPlan::id)
                .containsExactly(created.id(), second.id()); // 按 id 升序

        // Not found
        assertThat(planRepo.findById(999999L)).isEmpty();
    }

    @Test
    void testPlan_taskForeignKeyEnforced() {
        // foreign_keys=ON 下指向不存在 task 的 Plan 必须被拒绝
        assertThatThrownBy(() -> planRepo.insert(new TestPlan(null, 999999L, "v001",
                null, "[]", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ==================== 裁决 §七.4~§七.7：finding ====================

    @Test
    void finding_crudRoundtrip_withNullableFks() throws Exception {
        long taskId = newTaskId();
        long runId = newRunId(taskId);

        String evidenceJson = mapper.writeValueAsString(new Finding.Evidence(
                "gs-abc-123", runId, List.of(101L, 102L),
                Map.of("hp", -3, "phase", "BATTLE"),
                512L, 42L, Map.of("inject.hpNegativeAtTick", 512)));

        // 全字段插入（含可空外键有值形态 + reproductionRate）
        Finding full = findingRepo.insert(new Finding(null, runId, null, taskId,
                "smoke", "v001", "HIGH", "INVARIANT_VIOLATION",
                Finding.SOURCES.get(0), "HP 为负", "HP>=0", "hp=-3",
                "game_start(seed=42)→…", evidenceJson, 0.75, null, null));
        assertThat(full.id()).isNotNull();
        assertThat(full.status()).isEqualTo("OPEN");       // DEFAULT 'OPEN'
        assertThat(full.createdAt()).isNotNull();

        Finding loadedFull = findingRepo.findById(full.id()).orElseThrow();
        assertThat(loadedFull.runId()).isEqualTo(runId);
        assertThat(loadedFull.source()).isEqualTo("INVARIANT");
        assertThat(loadedFull.reproductionRate()).isEqualTo(0.75);

        // Evidence JSON round-trip：sessionId 主锚点在库里原样保留
        Finding.Evidence back = mapper.readValue(loadedFull.evidenceJson(),
                Finding.Evidence.class);
        assertThat(back.sessionId()).isEqualTo("gs-abc-123");
        assertThat(back.toolCallIds()).containsExactly(101L, 102L);
        assertThat(back.seed()).isEqualTo(42L);

        // 可空外键全 NULL 形态（环境级/人工登记 Finding）
        Finding bare = findingRepo.insert(new Finding(null, null, null, null,
                "manual-1", "v002", "LOW", "OTHER", Finding.SOURCES.get(2),
                "手感异常", null, null, "人工观察", "{}", null,
                Finding.STATUSES.get(0), null));
        Finding loadedBare = findingRepo.findById(bare.id()).orElseThrow();
        assertThat(loadedBare.runId()).isNull();
        assertThat(loadedBare.testPlanId()).isNull();
        assertThat(loadedBare.taskId()).isNull();
        assertThat(loadedBare.expected()).isNull();          // 可空列 NULL 保持
        assertThat(loadedBare.reproductionRate()).isNull();

        // source 默认值（裁决 §七.7）：source=null 插入落 DEFAULT 'LLM'
        Finding defaulted = findingRepo.insert(new Finding(null, null, null, null,
                "manual-2", "v002", "INFO", "UNEXPECTED_STATE", null,
                "标题", "期望", "实际", "步骤", "{}", null,
                Finding.STATUSES.get(0), null));
        assertThat(findingRepo.findById(defaulted.id()).orElseThrow().source())
                .isEqualTo("LLM");

        // Update status：OPEN → CONFIRMED
        findingRepo.updateStatus(full.id(), "CONFIRMED");
        assertThat(findingRepo.findById(full.id()).orElseThrow().status())
                .isEqualTo("CONFIRMED");

        // Not found
        assertThat(findingRepo.findById(999999L)).isEmpty();
    }

    @Test
    void finding_findByFilter_hitsIndexes() {
        long taskId = newTaskId();
        long runId = newRunId(taskId);

        findingRepo.insert(new Finding(null, runId, null, taskId, "s1", "v001",
                "HIGH", "CRASH", "LLM", "t1", null, null, "r", "{}", null, null, null));
        findingRepo.insert(new Finding(null, runId, null, taskId, "s1", "v001",
                "BLOCKER", "SOFT_LOCK", "INVARIANT", "t2", null, null, "r", "{}", null, null, null));
        findingRepo.insert(new Finding(null, null, null, taskId, "s1", "v002",
                "HIGH", "BALANCE_ANOMALY", "MANUAL", "t3", null, null, "r", "{}", null, null, null));

        // 版本回归对比主路径：configVersion+severity（idx_finding_ver_sev）
        List<Finding> v001High = findingRepo.findByFilter("v001", "HIGH", null, null);
        assertThat(v001High).hasSize(1);
        assertThat(v001High.get(0).title()).isEqualTo("t1");

        // 按 run 过滤（idx_finding_run）
        assertThat(findingRepo.findByFilter(null, null, runId, null)).hasSize(2);

        // 按 plan 过滤 / 组合无命中
        assertThat(findingRepo.findByFilter(null, null, null, 777L)).isEmpty();
        assertThat(findingRepo.findByFilter(null, null, null, null)).hasSize(3); // 全量
    }

    @Test
    void finding_foreignKeysEnforced_whenPresent() {
        long taskId = newTaskId();
        // 有值的可空外键必须通过存在性校验：指向不存在的 run/test_plan/task 均拒绝
        assertThatThrownBy(() -> findingRepo.insert(new Finding(null, 999999L, null,
                null, "s", "v001", "HIGH", "CRASH", "LLM", "t", null, null, "r",
                "{}", null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> findingRepo.insert(new Finding(null, null, 888888L,
                null, "s", "v001", "HIGH", "CRASH", "LLM", "t", null, null, "r",
                "{}", null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> findingRepo.insert(new Finding(null, null, null,
                777777L, "s", "v001", "HIGH", "CRASH", "LLM", "t", null, null, "r",
                "{}", null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ==================== 裁决 §七.8：索引存在 ====================

    @Test
    void testerIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'idx_%'",
                String.class);
        assertThat(indexes).contains(
                "idx_tp_task", "idx_finding_ver_sev", "idx_finding_run");
    }

    // ==================== 裁决 §七.9：既有表行为未破坏 ====================

    @Test
    void legacyTablesStillWork() {
        // 既有核心链路抽查：task → run → step → tool_call 照常工作
        long taskId = newTaskId();
        long runId = newRunId(taskId);
        assertThat(runId).isPositive();
        jdbc.update("""
                INSERT INTO step(run_id, seq, phase) VALUES(?, 1, 'LLM_CALL')
                """, runId);
        Long stepId = jdbc.queryForObject(
                "SELECT id FROM step WHERE run_id=?", Long.class, runId);
        jdbc.update("""
                INSERT INTO tool_call(step_id, run_id, tool_name) VALUES(?,?, 'game_start')
                """, stepId, runId);
        Integer tcCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_call WHERE run_id=?", Integer.class, runId);
        assertThat(tcCount).isEqualTo(1);
        // 既有 UNIQUE 幂等键路径抽查（experiment）
        assertThat(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table'", String.class))
                .contains("experiment");
    }
}
