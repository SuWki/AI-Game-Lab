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

import com.aigamelab.common.ApiResp;
import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.Run;

/**
 * P7-3 Run Trace 只读端点验收：GET /api/runs 与 GET /api/runs/{id}。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P7-3 RunController 只读端点")
class RunControllerReadTest {

    @Autowired RunController controller;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p73-run-", ".db");
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
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        jdbc.update("INSERT INTO task(id, title, goal, constraints_json) VALUES(1, 't', 'g', '{}')");
    }

    @Test
    void listReturnsInsertedRuns() {
        jdbc.update("INSERT INTO run(id, task_id, status, model, current_step, max_steps, token_budget, tokens_used) "
                + "VALUES(1, 1, 'COMPLETED', 'deepseek-chat', 5, 10, 200000, 1200)");
        jdbc.update("INSERT INTO run(id, task_id, status, model, current_step, max_steps, token_budget, tokens_used) "
                + "VALUES(2, 1, 'WAITING_APPROVAL', 'deepseek-chat', 3, 10, 200000, 500)");

        ApiResp<List<Run>> resp = controller.list();
        assertThat(resp.code()).isZero();
        assertThat(resp.data()).hasSize(2);
        // 倒序：id 大的在前
        assertThat(resp.data().get(0).id()).isEqualTo(2L);
    }

    @Test
    void detailReturnsRunWithStepsAndToolCalls() {
        jdbc.update("INSERT INTO run(id, task_id, status, model, current_step, max_steps, token_budget, tokens_used) "
                + "VALUES(1, 1, 'COMPLETED', 'deepseek-chat', 5, 10, 200000, 1200)");
        jdbc.update("INSERT INTO step(id, run_id, seq, phase, llm_content, prompt_tokens, completion_tokens, duration_ms) "
                + "VALUES(1, 1, 0, 'LLM_CALL', '思考中', 100, 200, 500)");
        jdbc.update("INSERT INTO tool_call(id, step_id, run_id, tool_name, arguments_json, result_json, status, approval_status) "
                + "VALUES(1, 1, 1, 'simulate_game', '{\"seed\":1}', '{\"ok\":true}', 'COMPLETED', 'NOT_REQUIRED')");

        ApiResp<Map<String, Object>> resp = controller.detail(1L);
        assertThat(resp.code()).isZero();
        Map<String, Object> d = resp.data();
        assertThat(((Run) d.get("run")).id()).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) d.get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).get("step")).isNotNull();
        assertThat(((List<?>) steps.get(0).get("toolCalls"))).hasSize(1);
    }

    @Test
    void detailMissingRunThrows404() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.aigamelab.common.BusinessException.class,
                () -> controller.detail(999L));
    }
}
