package com.aigamelab.qa.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * QA 测试自包含 fixture（PF-002-A，方案 A：每测试类独立临时 DB）。
 *
 * <p>与 {@code AbstractRepositoryTest} 彻底隔离——QA 测试禁止继承、禁止共享其
 * static 临时 DB 文件。本工具采用组合方式（static 方法），每个 QA 测试类自行声明
 * {@code private static final Path DB = QaTestFixture.tempDb();} 并在
 * {@code @DynamicPropertySource} 中调用 {@link #datasourceProps}，从根上实现
 * per-class 独立库、确定性、可重复、与 Designer/Tester/Research 测试零交集。
 *
 * <p>生命周期约定（QA-S1 所有测试遵守）：
 * <pre>
 * Before Test   → @BeforeEach 调 {@link #resetDbToCleanState(JdbcTemplate)}
 * Create clean  → 全表 FK-safe 清理 + v001 显式播种（不依赖生产 @PostConstruct）
 * Execute       → 测试体
 * Cleanup       → 临时 DB 文件由 OS temp 目录回收；用例间由下一 @BeforeEach 全清
 * </pre>
 *
 * <p>验证矩阵见 {@code QaTestFixtureTest}：清理拓扑、播种幂等、per-class DB 独立、
 * insert 后 0 残留、与既有 AbstractRepositoryTest 共享文件互不可见。
 */
public final class QaTestFixture {

    private QaTestFixture() {
    }

    /** 每个 QA 测试类调用一次，得到唯一临时 SQLite 文件（per-class 隔离）。 */
    public static Path tempDb() {
        try {
            return Files.createTempFile("agl-qa-test-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException("cannot create qa temp db", e);
        }
    }

    /**
     * 注册数据源：PRAGMA 与生产 application.yml 完全一致
     * （WAL / busy_timeout=5000 / synchronous=NORMAL / foreign_keys=ON）。
     */
    public static void datasourceProps(DynamicPropertyRegistry registry, Path db) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"
                + db.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    /**
     * 全量 FK-safe 清理 + v001 显式幂等播种。
     * 删除顺序按 FK 拓扑：子表优先（引用 run/task 的表先删），核心表最后。
     * R5 Phase 2：qa_release_gate / qa_role_assignment 引用 design_document/run/task，
     * 放在引用 run 的表之前、核心表之前清理。
     * 不依赖 {@code ConfigVersionService.@PostConstruct} —— v001 由本方法显式保证。
     */
    public static void resetDbToCleanState(JdbcTemplate jdbc) {
        // 第一层：引用 run/step 的叶子表
        jdbc.update("DELETE FROM tool_call");
        // 第二层：引用 run 的表
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM research_claim");
        jdbc.update("DELETE FROM research_source");
        jdbc.update("DELETE FROM finding");
        // R5 Phase 2：新表 —— qa_release_gate 引用 design_document / run / task
        jdbc.update("DELETE FROM qa_release_gate");
        jdbc.update("DELETE FROM design_document");
        // R5 Phase 2：qa_role_assignment 引用 run / task
        jdbc.update("DELETE FROM qa_role_assignment");
        // 第三层：引用 task 的表（test_plan 仅引用 task）
        jdbc.update("DELETE FROM test_plan");
        // 第四层：agent_event（run_id 为 TEXT，非 FK，逻辑清理）
        jdbc.update("DELETE FROM agent_event");
        // 第五层：核心表
        jdbc.update("DELETE FROM prompt_version");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");

        // v001 显式幂等播种（对齐 AbstractRepositoryTest.ensureSeedV001 语义，但自包含实现）
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_config_version", Integer.class);
        if (count == null || count == 0) {
            String seed = readSeed();
            jdbc.update(
                    "INSERT INTO game_config_version"
                            + "(version, config_json, parent_version, change_proposal_id, comment)"
                            + " VALUES(?,?,?,?,?)",
                    "v001", seed, null, null, "initial seed");
        }
    }

    private static String readSeed() {
        try (var in = new ClassPathResource("config/v001.json").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read config/v001.json seed", e);
        }
    }
}