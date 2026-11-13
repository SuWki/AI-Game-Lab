package com.aigamelab;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P0-1 验收测试：
 * 1. Spring 上下文可启动（数据源/Schema 初始化成功）
 * 2. schema.sql 建出全部 10 张业务表
 * 3. /api/dashboard/stats 返回包络 JSON 且 DB 可读
 * 4. JDBC URL 的 PRAGMA 参数真实生效（WAL / foreign_keys）
 * 5. UNIQUE 冲突翻译为 DuplicateKeyException，其余约束冲突不误判
 *
 * 每次运行使用 @TempDir 下唯一的临时 SQLite 文件（遵循 03 文档"临时库、测后删除"
 * 测试策略），测试间完全隔离。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ApplicationTests {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate rest;

    /** 唯一临时库文件：每个 JVM/构建各不相同，从根上消除共享文件互踩 */
    static Path tempDb() {
        try {
            return Files.createTempFile("agl-test-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException("cannot create temp db", e);
        }
    }

    private static final Path DB = tempDb();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        // 与生产 application.yml 相同的 PRAGMA 参数，验证参数经 URL 真实生效
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    /** 蓝图 §7 全部业务表 + T-DB1 批准的 Tester 两表（test_plan/finding）+ R0-3 Research 两表 + R5 Phase 2 QA 两表 */
    private static final List<String> EXPECTED_TABLES = List.of(
            "task", "run", "step", "tool_call", "experiment",
            "game_config_version", "change_proposal", "agent_event",
            "design_document", "prompt_version",
            "test_plan", "finding",
            "research_source", "research_claim",
            "qa_role_assignment", "qa_release_gate", "local_test_run",
            "runtime_context", "runtime_turn", "local_test_context", "local_change_proposal", "production_artifact", "production_job");

    @Test
    void contextLoads() {
        assertThat(jdbc).isNotNull();
    }

    @Test
    void schemaCreatesExactlyExpectedTables() {
        // 精确集合断言：排除 sqlite_% 内部表（如 AUTOINCREMENT 自动生成的 sqlite_sequence）
        List<String> names = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                String.class);
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void journalModeIsWalAndForeignKeysOn() {
        // P0-1 验收标准 (c)：URL PRAGMA 参数必须生效——WAL 是对抗单写者 SQLITE_BUSY 的关键配置
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualTo("wal");
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardStatsReturnsEnvelope() {
        Map<String, Object> body = rest.getForObject("/api/dashboard/stats", Map.class);
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(0);
        assertThat(body.get("message")).isEqualTo("ok");
        assertThat(body.get("data")).isInstanceOf(Map.class);
    }

    @Test
    void uniqueViolationTranslatesToDuplicateKey() {
        jdbc.update("DELETE FROM experiment");
        jdbc.update("""
                INSERT INTO experiment(config_version, seed, simulation_count, scenario)
                VALUES('v001', 12345, 10000, 'ALL_BUILDS')
                """);
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update("""
                        INSERT INTO experiment(config_version, seed, simulation_count, scenario)
                        VALUES('v001', 12345, 10000, 'ALL_BUILDS')
                        """));
    }

    @Test
    void nonUniqueConstraintViolationIsNotDuplicateKey() {
        // step.phase 无默认值且 NOT NULL：插入缺失该列应触发 NOT NULL 冲突，
        // 必须是 DataIntegrityViolationException 而【不是】其子类 DuplicateKeyException
        jdbc.update("INSERT INTO task(title, goal) VALUES('t','g')");
        Long runId = jdbc.queryForObject(
                "INSERT INTO run(task_id) VALUES(?) RETURNING id", Long.class, 1L);
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO step(run_id, seq) VALUES(?, ?)", runId, 1));
        // 若被误判为 DuplicateKeyException 则上面会通过但此处断言失败：
        try {
            jdbc.update("INSERT INTO step(run_id, seq) VALUES(?, ?)", runId, 1);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            assertThat(e).isNotInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            return;
        }
        throw new AssertionError("expected DataIntegrityViolationException");
    }
}
