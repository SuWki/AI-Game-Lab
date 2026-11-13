package com.aigamelab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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

import com.aigamelab.common.ApiResp;

/**
 * P7-1 Dashboard 端点验收：/api/dashboard/stats 返回真实 Run 统计 + Experiment 计数。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P7-1 DashboardController /stats")
class DashboardControllerTest {

    @Autowired DashboardController controller;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p71-dash-", ".db");
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
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    /** 空库 → 全部字段为 0/null，无异常。 */
    @Test
    void emptyDbReturnsZeroes() {
        ApiResp<Map<String, Object>> resp = controller.stats();
        assertThat(resp.code()).isZero();
        Map<String, Object> d = resp.data();
        assertThat((long) d.get("totalRuns")).isZero();
        assertThat((long) d.get("completedRuns")).isZero();
        assertThat((long) d.get("experimentCount")).isZero();
        assertThat((long) d.get("totalTokens")).isZero();
    }

    /** 插入 Run + Experiment 后 stats 返回真实值。 */
    @Test
    void statsReflectInsertedData() {
        // 建 task + 两行 run（一 COMPLETED 一 FAILED）+ 两行 experiment（一 DONE 一 RUNNING）
        jdbc.update("INSERT INTO task(id, title, goal, constraints_json) VALUES(1, 't', 'g', '{}')");
        jdbc.update("INSERT INTO run(id, task_id, status, model, current_step, max_steps, token_budget, tokens_used) "
                + "VALUES(1, 1, 'COMPLETED', 'deepseek-chat', 5, 10, 200000, 1200)");
        jdbc.update("INSERT INTO run(id, task_id, status, model, current_step, max_steps, token_budget, tokens_used) "
                + "VALUES(2, 1, 'FAILED', 'deepseek-chat', 3, 10, 200000, 500)");
        jdbc.update("INSERT INTO experiment(run_id, config_version, seed, simulation_count, scenario, status) "
                + "VALUES(1, 'v001', 100, 500, 'crit', 'DONE')");
        jdbc.update("INSERT INTO experiment(run_id, config_version, seed, simulation_count, scenario, status) "
                + "VALUES(2, 'v001', 200, 500, 'crit', 'RUNNING')");

        ApiResp<Map<String, Object>> resp = controller.stats();
        Map<String, Object> d = resp.data();
        assertThat((long) d.get("totalRuns")).isEqualTo(2);
        assertThat((long) d.get("completedRuns")).isEqualTo(1);
        assertThat((long) d.get("failedRuns")).isEqualTo(1);
        assertThat((long) d.get("experimentCount")).isEqualTo(2);
        assertThat((long) d.get("doneExperiments")).isEqualTo(1);
        assertThat((long) d.get("totalTokens")).isEqualTo(1700L);
    }
}
