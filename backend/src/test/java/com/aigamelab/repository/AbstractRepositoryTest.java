package com.aigamelab.repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Repository 集成测试基类。
 * 每个 JVM 构建一个唯一临时 SQLite 文件（与 ApplicationTests 同策略），
 * PRAGMA 参数与生产一致，验证真实读写而非仅上下文启动。
 * <p>
 * 注意：本基类不声明 webEnvironment=NONE 之外的定制——Repository 测试
 * 不需要 HTTP 服务，但保留 RANDOM_PORT 会拖慢启动；NONE 即可。
 * <p>
 * TD-T7-001 修复：所有子类共享同一 static 临时 DB 文件，个别子类的
 * {@code @BeforeEach} 会部分 DELETE 行但遗漏某些引用 run_id 的子表，
 * 导致后续依赖 v001 或干净 DB 的测试报 FK 违反 / 40401。
 * 此处做完整 FK-safe 全表清理 + v001 幂等补种，保证每个用例前 DB 状态干净。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
public abstract class AbstractRepositoryTest {

    private static final Path DB = tempDb();

    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-repo-test-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException("cannot create temp db", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    /**
     * 完整 FK-safe 清理 + v001 幂等补种。
     * <p>
     * JUnit 5 执行顺序：父类 {@code @BeforeEach} → 子类 {@code @BeforeEach} → 测试方法。
     * 因此父类先做全表清理（保证无残留行），再补种 v001，之后子类可按需插入自己的测试数据。
     * <p>
     * 删除顺序：子表优先，按 FK 依赖拓扑排序。
     * game_config_version 不在此处删除，由 ensureSeedV001 做幂等管理。
     */
    @BeforeEach
    void resetDbToCleanState() {
        // 第一层：引用 run.step 的子表
        jdbc.update("DELETE FROM tool_call");
        // 第二层：引用 run 的表
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM research_claim");
        jdbc.update("DELETE FROM research_source");
        jdbc.update("DELETE FROM finding");
        // 第三层：引用 task 的表（test_plan 无 run FK，但引用 task）
        jdbc.update("DELETE FROM test_plan");
        // 第四层：agent_event（run_id 为 TEXT，非 FK，但逻辑清理）
        jdbc.update("DELETE FROM agent_event");
        // 第五层：核心表
        jdbc.update("DELETE FROM prompt_version");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");

        // 幂等补种 v001
        ensureSeedV001();
    }

    private void ensureSeedV001() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_config_version", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        String seed = readSeed();
        jdbc.update(
                "INSERT INTO game_config_version"
                        + "(version, config_json, parent_version, change_proposal_id, comment)"
                        + " VALUES(?,?,?,?,?)",
                "v001", seed, null, null, "initial seed");
    }

    private static String readSeed() {
        try (var in = new ClassPathResource("config/v001.json").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read config/v001.json seed", e);
        }
    }
}
