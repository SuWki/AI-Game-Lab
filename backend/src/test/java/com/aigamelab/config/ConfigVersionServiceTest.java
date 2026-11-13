package com.aigamelab.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.repository.ConfigVersionRepository;

/**
 * P6-1 验收测试（03 文档）：连续创建得到严格递增 v002/v003；findLatest 正确；
 * 当前版本语义（=latest）；重启后 latest 正确；append-only 不修改历史快照。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P6-1 ConfigVersionService")
class ConfigVersionServiceTest {

    @Autowired ConfigVersionService service;
    @Autowired ConfigVersionRepository repo;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p61-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Path DB = tempDb();

    private static String dbUrl() {
        return "jdbc:sqlite:" + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON";
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ConfigVersionServiceTest::dbUrl);
    }

    @BeforeEach
    void reseedV001() {
        // 每个用例前恢复"启动后"的干净状态：仅含种子 v001
        jdbc.update("DELETE FROM game_config_version");
        String seed = readClasspathSeed();
        jdbc.update("INSERT INTO game_config_version(version, config_json, parent_version, change_proposal_id, comment) VALUES(?,?,?,?,?)",
                "v001", seed, null, null, "initial seed");
    }

    private String readClasspathSeed() {
        try (var in = new org.springframework.core.io.ClassPathResource("config/v001.json").getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void seedImportedOnceAndIdempotentAcrossRestarts() {
        // 第一次"启动"：context 构造时已导入 v001
        assertThat(service.findLatest()).isPresent();
        assertThat(service.findLatest().get().version()).isEqualTo("v001");
        assertThat(countRows()).isEqualTo(1);

        // 第二次"启动"：独立 context 复用同一 DB → 不重复导入
        SpringApplication app = new SpringApplication(com.aigamelab.Application.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx2 = app.run(
                "--spring.datasource.url=" + dbUrl(),
                "--spring.sql.init.mode=always")) {
            ConfigVersionService svc2 = ctx2.getBean(ConfigVersionService.class);
            assertThat(svc2.findLatest()).isPresent();
            assertThat(svc2.findLatest().get().version()).isEqualTo("v001");
        }
        assertThat(countRows()).isEqualTo(1); // 不重复导入
    }

    @Test
    void createVersionProducesStrictlyIncreasingSequence() {
        // v001 已由 seed 提供
        ConfigVersion v2 = service.createVersion("{\"a\":2}", "v001", null, "second");
        ConfigVersion v3 = service.createVersion("{\"a\":3}", "v002", null, "third");

        assertThat(v2.version()).isEqualTo("v002");
        assertThat(v3.version()).isEqualTo("v003");
        assertThat(ConfigVersionService.seqOf(v2.version())).isEqualTo(2);
        assertThat(ConfigVersionService.seqOf(v3.version())).isEqualTo(3);
    }

    @Test
    void findLatestReturnsNewestByLexicalVersion() {
        service.createVersion("{\"a\":2}", "v001", null, "second");
        service.createVersion("{\"a\":3}", "v002", null, "third");
        assertThat(service.findLatest()).isPresent();
        assertThat(service.findLatest().get().version()).isEqualTo("v003");
        assertThat(service.getCurrent().version()).isEqualTo("v003");
    }

    @Test
    void currentSemanticsEqualsLatestAndNotHistory() {
        ConfigVersion first = service.findLatest().orElseThrow(); // v001
        ConfigVersion latest = service.createVersion("{\"a\":2}", "v001", null, "second");

        ConfigVersion current = service.getCurrent();
        assertThat(current.version()).isEqualTo("v002");
        assertThat(current.version()).isNotEqualTo(first.version());
        ConfigVersion historical = service.findByVersion("v001").orElseThrow();
        assertThat(historical.configJson()).isEqualTo(first.configJson());
    }

    @Test
    void appendOnlyDoesNotMutateHistoricalSnapshot() {
        ConfigVersion v1 = service.findLatest().orElseThrow();
        String originalJson = v1.configJson();
        service.createVersion("{\"changed\":true}", "v001", null, "evolve");

        ConfigVersion v1After = service.findByVersion("v001").orElseThrow();
        assertThat(v1After.configJson()).isEqualTo(originalJson);
        assertThat(v1After.parentVersion()).isNull();
    }

    @Test
    void createFromEmptyTableStartsAtV001SeedThenV002() {
        jdbc.update("DELETE FROM game_config_version"); // 覆盖 reseed，验证空表自 v001 起
        ConfigVersion a = service.createVersion("{\"x\":1}", null, null, "first-ever");
        assertThat(a.version()).isEqualTo("v001");
        ConfigVersion b = service.createVersion("{\"x\":2}", "v001", null, "second");
        assertThat(b.version()).isEqualTo("v002");
    }

    @Test
    void rejectsBlankConfigJson() {
        assertThatThrownBy(() -> service.createVersion("   ", "v001", null, "blank"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentCreationsDoNotDuplicateVersionNumbers() throws Exception {
        int threads = 8;
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        var barrier = new java.util.concurrent.CyclicBarrier(threads);
        var tasks = new java.util.ArrayList<Thread>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    barrier.await();
                    service.createVersion("{\"t\":" + Thread.currentThread().getId() + "}", "v001", null, "concurrent");
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            tasks.add(t);
            t.start();
        }
        for (Thread t : tasks) t.join();
        assertThat(errors).isEmpty();
        // 8 次并发创建 + 1 seed = 9 行，且版本号唯一无重号
        assertThat(countRows()).isEqualTo(9);
        assertThat(repo.findAll().stream().map(ConfigVersion::version).distinct().count())
                .isEqualTo(9L);
    }

    private int countRows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM game_config_version", Integer.class);
        return n == null ? 0 : n;
    }
}
