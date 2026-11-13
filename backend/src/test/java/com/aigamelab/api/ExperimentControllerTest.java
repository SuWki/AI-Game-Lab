package com.aigamelab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
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

import com.aigamelab.experiment.AsyncSimulationRunner;
import com.aigamelab.experiment.ExperimentService;
import com.aigamelab.repository.ExperimentRepository;

/**
 * P5-3 验收（03 文档 P5-3）：ExperimentController 四端点 + AsyncSimulationRunner。
 *
 * 使用真实 Spring 上下文（含 AsyncSimulationRunner），实验逻辑走 ExperimentService。
 * POST /run 提交后通过 Thread.sleep + detail 查询确认异步完成。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P5-3 ExperimentController REST + 异步化")
class ExperimentControllerTest {

    @Autowired ExperimentController controller;
    @Autowired ExperimentService experimentService;
    @Autowired ExperimentRepository experimentRepo;
    @Autowired AsyncSimulationRunner runner;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p53-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Path DB = tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"
                + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    private void insertExperiment(String scenario, long seed, double winRate) {
        double wr = Math.max(0.0, Math.min(1.0, winRate));
        int wins = (int) Math.round(wr * 200);
        String resultJson = "{\"totalGames\":200,\"winCount\":" + wins
                + ",\"winRate\":" + wr
                + ",\"buildDistribution\":{\"crit\":{\"games\":200,\"wins\":" + wins
                + ",\"winRate\":" + wr + ",\"ciLow\":0.0,\"ciHigh\":1.0}}}";
        jdbc.update("INSERT INTO experiment"
                + " (run_id,config_version,seed,simulation_count,scenario,status,"
                + " win_rate,avg_dps,avg_game_time_s,result_json,duration_ms)"
                + " VALUES (null,'v001',?,?,?,'DONE',?,0.0,0.0,?,0)",
                seed, 200, scenario, wr, resultJson);
    }

    // ---- list ----

    @Test
    void listEndpointReturnsExperiments() {
        insertExperiment("crit", 1L, 0.55);
        insertExperiment("penetration", 2L, 0.45);

        var resp = controller.list(null, null);
        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).isNotNull();
        assertThat(resp.data().size()).isGreaterThanOrEqualTo(2);
    }

    // ---- detail ----

    @Test
    void detailEndpointReturnsParsedResultJson() {
        insertExperiment("crit", 10L, 0.62);
        long id = jdbc.queryForObject(
                "SELECT id FROM experiment WHERE seed=10", Long.class);

        var resp = controller.detail(id);
        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).containsKey("result");
        assertThat(resp.data().get("configVersion")).isEqualTo("v001");
    }

    @Test
    void detailNonexistentIdThrows() {
        boolean threw = false;
        try {
            controller.detail(999999L);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertThat(threw).isTrue();
    }

    // ---- run (async) ----

    @Test
    void runEndpointReturns202AndCompletes() throws Exception {
        var body = new ExperimentController.RunRequest("v001", 5555L, 50, "crit", null);
        var resp = controller.run(body);
        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).isNotNull();
        assertThat(resp.data().get("status")).isEqualTo("ACCEPTED");

        // 异步等待：最多 30s
        Thread.sleep(20000);

        var list = experimentRepo.findByFilter("v001", "crit");
        assertThat(list).isNotEmpty();
        boolean anyDone = list.stream().anyMatch(e -> "DONE".equals(e.status()));
        assertThat(anyDone).isTrue();
    }

    // ---- compare ----

    @Test
    void compareEndpointCalculatesZSignificance() {
        insertExperiment("crit", 100L, 0.80);
        insertExperiment("penetration", 200L, 0.50);
        long idA = jdbc.queryForObject(
                "SELECT id FROM experiment WHERE seed=100", Long.class);
        long idB = jdbc.queryForObject(
                "SELECT id FROM experiment WHERE seed=200", Long.class);

        var resp = controller.compare(
                new ExperimentController.CompareRequest(List.of(idA, idB)));
        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
        Map<String, Object> pair = resp.data().get(0);
        assertThat((Double) pair.get("deltaWinRate")).isCloseTo(0.30,
                org.assertj.core.data.Offset.offset(0.01));
        assertThat((Double) pair.get("zScore")).isGreaterThan(1.96);
        assertThat((Boolean) pair.get("significant")).isTrue();
    }

    @Test
    void compareEndpointDetectsInsignificantDifference() {
        insertExperiment("crit", 300L, 0.50);
        insertExperiment("crit", 301L, 0.51);
        long idA = jdbc.queryForObject(
                "SELECT id FROM experiment WHERE seed=300", Long.class);
        long idB = jdbc.queryForObject(
                "SELECT id FROM experiment WHERE seed=301", Long.class);

        var resp = controller.compare(
                new ExperimentController.CompareRequest(List.of(idA, idB)));
        assertThat(resp.code()).isEqualTo(0);
        Map<String, Object> pair = resp.data().get(0);
        assertThat((Boolean) pair.get("significant")).isFalse();
    }

    @Test
    void compareWithSingleIdThrows() {
        insertExperiment("crit", 400L, 0.60);
        long idA = jdbc.queryForObject(
                "SELECT id FROM experiment WHERE seed=400", Long.class);

        boolean threw = false;
        try {
            controller.compare(new ExperimentController.CompareRequest(List.of(idA)));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertThat(threw).isTrue();
    }
}
