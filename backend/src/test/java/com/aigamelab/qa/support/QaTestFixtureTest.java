package com.aigamelab.qa.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * PF-002-A：QA fixture 自隔离性验证。
 *
 * <p>覆盖验证矩阵：
 * <ol>
 *   <li>清理拓扑正确性：插入覆盖全部 FK 关系表的含外键数据后调
 *       {@link QaTestFixture#resetDbToCleanState} → 14 张业务表 0 残留 + v001 存在；</li>
 *   <li>播种幂等：连续二次 reset 后 game_config_version 仅 1 行 v001（不依赖 @PostConstruct）；</li>
 *   <li>per-class DB 独立：tempDb() 每次返回不同文件；当前 context 的 DB 文件
 *       以 {@code agl-qa-test-} 前缀（绝不共享 AbstractRepositoryTest 的 {@code agl-repo-test-}）；</li>
 * </ol>
 *
 * <p>本测试不继承 {@code AbstractRepositoryTest}，是唯一要求（PF-002 指令）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("PF-002-A QA fixture 自隔离")
class QaTestFixtureTest {

    private static final Path DB = QaTestFixture.tempDb();

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        QaTestFixture.datasourceProps(registry, DB);
    }

    @BeforeEach
    void clean() {
        QaTestFixture.resetDbToCleanState(jdbc);
    }

    private long insertTask() {
        return jdbc.queryForObject(
                "INSERT INTO task(title, goal) VALUES('qt','qg') RETURNING id", Long.class);
    }

    private long insertRun(long taskId) {
        return jdbc.queryForObject(
                "INSERT INTO run(task_id, status) VALUES(?, 'RUNNING') RETURNING id", Long.class,
                taskId);
    }

    /** 插入覆盖全部 FK 关系的含外键数据。 */
    private long insertFullFkChain(long taskId, long runId) {
        // step → run；tool_call → step+run
        long stepId = jdbc.queryForObject(
                "INSERT INTO step(run_id, seq, phase) VALUES(?,1,'LLM_CALL') RETURNING id",
                Long.class, runId);
        jdbc.update("INSERT INTO tool_call(step_id, run_id, tool_name, status)"
                + " VALUES(?,?,'probe','COMPLETED')", stepId, runId);
        // experiment → run
        jdbc.update("INSERT INTO experiment(run_id, config_version, seed, simulation_count, status)"
                + " VALUES(?, 'v001', 7, 50, 'DONE')", runId);
        // change_proposal → run
        jdbc.update("INSERT INTO change_proposal(run_id, base_version, title, reason, diff_json,"
                + " after_config_json) VALUES(?, 'v001','t','r','[]','{}')", runId);
        // design_document → run + task
        jdbc.update("INSERT INTO design_document(run_id, task_id, title, content_md)"
                + " VALUES(?,?, 'd','c')", runId, taskId);
        // test_plan → task
        long planId = jdbc.queryForObject(
                "INSERT INTO test_plan(task_id, config_version, status, scenarios_json)"
                        + " VALUES(?, 'v001','DRAFT','[]') RETURNING id", Long.class, taskId);
        // finding → run + test_plan + task
        jdbc.update("INSERT INTO finding(run_id, test_plan_id, task_id, scenario_id,"
                + " config_version, severity, category, title, repro_steps)"
                + " VALUES(?,?,?, 's1','v001','LOW','OTHER','ft','steps')",
                runId, planId, taskId);
        // research_source / research_claim → run + task
        jdbc.update("INSERT INTO research_source(run_id, task_id, url, source_type, title)"
                + " VALUES(?,?, 'http://x','Official','rs')", runId, taskId);
        jdbc.update("INSERT INTO research_claim(run_id, task_id, claim)"
                + " VALUES(?,?, 'rc')", runId, taskId);
        return planId;
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    /** 1) 清理拓扑正确性：全 FK 链数据 → reset 后 0 残留 + v001 存在。 */
    @Test
    void resetClearsFullFkChainLeavingOnlyV001Seed() {
        long taskId = insertTask();
        long runId = insertRun(taskId);
        insertFullFkChain(taskId, runId);

        // 落库证据：全部 11 个业务表非空
        assertThat(count("finding")).isEqualTo(1);
        assertThat(count("research_claim")).isEqualTo(1);

        QaTestFixture.resetDbToCleanState(jdbc);

        for (String table : List.of(
                "tool_call", "step", "experiment", "change_proposal", "design_document",
                "research_claim", "research_source", "finding", "test_plan",
                "agent_event", "prompt_version", "run", "task")) {
            assertThat(count(table))
                    .as("reset 后 %s 应 0 残留", table)
                    .isZero();
        }
        // v001 被保留（seed 幂等，不删除）
        assertThat(count("game_config_version")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_config_version WHERE version='v001'",
                Long.class)).isEqualTo(1);
    }

    /** 2) 播种幂等：超过二次 reset 仍只有 1 行 v001（不依赖 @PostConstruct 触发）。 */
    @Test
    void seedingIsIdempotentAcrossResets() {
        QaTestFixture.resetDbToCleanState(jdbc);
        QaTestFixture.resetDbToCleanState(jdbc);
        assertThat(count("game_config_version")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_config_version WHERE version='v001'",
                Long.class)).isEqualTo(1);
    }

    /** 3a) tempDb() 每次调用返回不同文件（per-class DB 独立）。 */
    @Test
    void tempDbReturnsDistinctFilePerClass() {
        Path another = QaTestFixture.tempDb();
        assertThat(another).isNotEqualTo(DB);
    }

    /** 3b) 当前 context 使用独立的 agl-qa-test-* 文件，绝不共享 AbstractRepositoryTest 的 agl-repo-test-*。 */
    @Test
    @SuppressWarnings("unchecked")
    void usesDedicatedQaDbFileNotSharedRepositoryFile() {
        List<Map<String, Object>> dbs = jdbc.queryForList("PRAGMA database_list");
        String file = dbs.stream()
                .filter(r -> "main".equals(String.valueOf(r.get("name"))))
                .map(r -> String.valueOf(r.get("file")))
                .findFirst()
                .orElse("");
        assertThat(file).contains("agl-qa-test-");
        assertThat(file).doesNotContain("agl-repo-test-");
    }
}