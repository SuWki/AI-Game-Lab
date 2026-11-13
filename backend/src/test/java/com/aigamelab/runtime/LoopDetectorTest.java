package com.aigamelab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.Step;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.StubTool;

/**
 * P1-5 验收测试（03 文档）：FakeLlmGateway 连发 3 次同参 tool call 触发终止，
 * finalResult 含"检测到无效循环"；不同参数不误判。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@org.springframework.context.annotation.Import(RuntimeTestTools.class)
@DisplayName("P1-5 LoopDetector 防无限循环")
class LoopDetectorTest {

    @Autowired AgentLoop loop;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired StepRepository stepRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;
    @Autowired com.aigamelab.tool.ToolRegistry registry;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p15-", ".db");
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

    @org.springframework.boot.test.context.TestConfiguration
    static class FakeConfig {
        @org.springframework.context.annotation.Bean
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    private Task newTask() {
        return taskRepo.insert(new Task(null, "t", "g", "{}", 100, false, "ACTIVE", null));
    }

    private Run newRun(long taskId) {
        return runRepo.insert(new Run(null, taskId, "PENDING", null, null,
                0, 100, 10_000_000, 0, null, null, null, null, null));
    }

    private void ensureTool(String name) {
        if (!registry.contains(name)) {
            StubTool t = new StubTool(name);
            t.defaultSuccess("{\"ok\":true}", "ok");
            registry.register(t);
        }
    }

    @Test
    void threeIdenticalCallsTriggerLoopDetection() {
        ensureTool("x");
        int base = llm.callCount();
        // 同参 ×4：第 3 次落库后下一轮 Evaluate 即触发，第 4 次调用不应发生
        llm.thenToolCalls(new ToolCall("c1", "x", "{\"k\":7}"))
                .thenToolCalls(new ToolCall("c2", "x", "{\"k\":7}"))
                .thenToolCalls(new ToolCall("c3", "x", "{\"k\":7}"))
                .thenToolCalls(new ToolCall("c4", "x", "{\"k\":7}"));

        Run run = newRun(newTask().id());
        loop.run(run.id());

        Run done = runRepo.findById(run.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(done.errorMessage()).contains("无效循环");
        // 只发生 3 次 LLM 调用：第 3 次同参落库后、第 4 次调用前终止
        assertThat(llm.callCount() - base).isEqualTo(3);
        Long identical = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT tool_name, arguments_json FROM tool_call
                    WHERE run_id=? ORDER BY id DESC LIMIT 3
                ) WHERE tool_name='x' AND arguments_json='{"k":7}'
                """, Long.class, run.id());
        assertThat(identical).isEqualTo(3L);
    }

    @Test
    void varyingArgsDoNotTriggerFalsePositive() {
        ensureTool("y");
        int base = llm.callCount();
        llm.thenToolCalls(new ToolCall("d1", "y", "{\"k\":1}"))
                .thenToolCalls(new ToolCall("d2", "y", "{\"k\":2}"))
                .thenToolCalls(new ToolCall("d3", "y", "{\"k\":3}"))
                .thenStop("正常结束");

        Run run = newRun(newTask().id());
        loop.run(run.id());

        Run done = runRepo.findById(run.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.finalResult()).isEqualTo("正常结束"); // 未被循环检测截断
        assertThat(llm.callCount() - base).isEqualTo(4);
    }

    @Test
    void countQueryUnitCheck() {
        Run r = newRun(newTask().id());
        Step step = stepRepo.insert(new Step(null, r.id(), 1, "LLM_CALL",
                null, 1, 1, null, null));

        String insert = """
                INSERT INTO tool_call(step_id, run_id, tool_name, arguments_json, status, approval_status)
                VALUES(?, ?, ?, ?, 'COMPLETED', 'NOT_REQUIRED')
                """;
        // 直接构造 tool_call 行验证窗口查询语义
        jdbc.update(insert, step.id(), r.id(), "z", "{\"a\":1}");
        jdbc.update(insert, step.id(), r.id(), "z", "{\"a\":1}");
        jdbc.update(insert, step.id(), r.id(), "w", "{\"b\":2}");

        assertThat(stepRepo.countIdenticalRecentToolCalls(r.id(), "z", "{\"a\":1}", 3))
                .isEqualTo(2); // 最近 3 次中 z/{a:1} 出现 2 次
        assertThat(stepRepo.countIdenticalRecentToolCalls(r.id(), "w", "{\"b\":2}", 3))
                .isEqualTo(1);
        assertThat(stepRepo.countIdenticalRecentToolCalls(r.id(), "nope", "{}", 3))
                .isZero();
        assertThat(stepRepo.countIdenticalRecentToolCalls(r.id(), "z", "{\"a\":1}", 1))
                .isZero(); // 窗口=1 时最近一条是 w，不含 z
        assertThat(stepRepo.countIdenticalRecentToolCalls(r.id(), "w", "{\"b\":2}", 1))
                .isEqualTo(1);
    }
}
