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
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;

/**
 * P1-3 验收测试（03 文档）：注入 maxSteps=2 的 Task，第 3 轮前强制结束
 * 且 finalResult 含 "Max Steps"；token 预算触顶同样 COMPLETED 总结。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@org.springframework.context.annotation.Import(RuntimeTestTools.class)
@DisplayName("P1-3 BudgetGuard 三重预算")
class BudgetGuardTest {

    @Autowired AgentLoop loop;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;
    @Autowired com.aigamelab.tool.ToolRegistry registry;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p13-", ".db");
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

    /** 与 AgentLoopTest 相同的 Fake 注入配置 */
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
        llm.onExhaustedThrow(new IllegalStateException("剧本耗尽不应发生：预算应先触发"));
        // 注册剧本引用的替身工具
        com.aigamelab.tool.StubTool x = new com.aigamelab.tool.StubTool("x");
        x.defaultSuccess("{\"ok\":true}", "ok");
        if (!registry.contains("x")) {
            registry.register(x);
        }
    }

    private Task newTask(int maxSteps) {
        return taskRepo.insert(new Task(null, "预算任务", "g", "{}", maxSteps, false, "ACTIVE", null));
    }

    private Run newRun(long taskId, int maxSteps, long tokenBudget) {
        return runRepo.insert(new Run(null, taskId, "PENDING", null, null,
                0, maxSteps, (int) tokenBudget, 0, null, null, null, null, null));
    }

    @Test
    void maxStepsBudgetForcesCompletionBeforeThirdRound() {
        // 无限 tool_calls 剧本：若预算失效，第 4 次 complete 会抛"剧本耗尽"
        // 注意：Fake 是 Spring 单例，callCount 跨用例累计——断言用增量
        int baseCalls = llm.callCount();
        llm.thenToolCalls(new ToolCall("c1", "x", "{}"))
                .thenToolCalls(new ToolCall("c2", "x", "{}"))
                .thenToolCalls(new ToolCall("c3", "x", "{}"));

        Run run = newRun(newTask(2).id(), 2, 1_000_000);
        loop.run(run.id());

        Run done = runRepo.findById(run.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(done.errorMessage()).contains("Max Steps");
        // 只跑了 2 步，第 3 轮 LLM 调用前终止
        Integer stepCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM step WHERE run_id=?", Integer.class, run.id());
        assertThat(stepCount).isEqualTo(2);
        assertThat(llm.callCount() - baseCalls).isEqualTo(2);
    }

    @Test
    void tokenBudgetForcesCompletion() {
        // Fake 每步 usage totalTokens=15；budget=20 → 第 2 步后触发
        // 注意：Fake 是 Spring 单例，callCount 跨用例累计——断言用增量
        int baseCalls = llm.callCount();
        llm.thenToolCalls(new ToolCall("c1", "x", "{}"))
                .thenToolCalls(new ToolCall("c2", "x", "{}"))
                .thenToolCalls(new ToolCall("c3", "x", "{}"));

        Run run = newRun(newTask(100).id(), 100, 20);
        loop.run(run.id());

        Run done = runRepo.findById(run.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(done.errorMessage()).contains("Token 预算");
        assertThat(llm.callCount() - baseCalls).isEqualTo(2);
    }

    @Test
    void guardVerdictUnitChecks() {
        var g = new BudgetGuard();
        long tid = newTask(5).id();
        Run r = newRun(tid, 5, 100);

        assertThat(g.check(r, 0, -1).hit()).isFalse();
        // 步数触顶
        runRepo.updateStatusAndStep(r.id(), com.aigamelab.domain.RunStatus.RUNNING, 5);
        var v1 = g.check(runRepo.findById(r.id()).orElseThrow(), 0, -1);
        assertThat(v1.hit()).isTrue();
        assertThat(v1.target()).isEqualTo(com.aigamelab.domain.RunStatus.FAILED);
        assertThat(v1.reason()).contains("Max Steps");
        // 时间超时
        var v2 = g.check(r, 61_000, 60_000);
        assertThat(v2.target()).isEqualTo(com.aigamelab.domain.RunStatus.TIMEOUT);
    }
}
