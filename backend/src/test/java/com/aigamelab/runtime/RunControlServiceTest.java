package com.aigamelab.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.StubTool;

/**
 * P1-4 验收测试：Cancel 协作中断 / Resume 状态校验 / Retry 新建复制 /
 * 恢复扫描（测试内模拟：直接调 recoverOnStartup）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@org.springframework.context.annotation.Import(RuntimeTestTools.class)
@DisplayName("P1-4 Cancel / Resume / Retry / Recovery")
class RunControlServiceTest {

    @Autowired RunControlService control;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;
    @Autowired com.aigamelab.tool.ToolRegistry registry;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p14-", ".db");
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
        llm.reset();
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    private Task newTask() {
        return taskRepo.insert(new Task(null, "t", "g", "{}", 50, false, "ACTIVE", null));
    }

    private Run newRun(long taskId) {
        return runRepo.insert(new Run(null, taskId, "PENDING", null, null,
                0, 50, 1000000, 0, null, null, null, null, null));
    }

    @Test
    void cancelRunningRunCooperatively() throws Exception {
        // 剧本：无限 tool_calls（预算不会触发，等待取消）
        StubTool x = new StubTool("x");
        x.defaultSuccess("{}", "ok");
        if (!registry.contains("x")) {
            registry.register(x);
        }
        llm.onExhaustedThrow(new RuntimeException("stop"));
        // P1-5 后同参数连发会触发循环检测，故每次参数不同以绕开第四重防线
        for (int i = 0; i < 200; i++) {
            llm.thenToolCalls(new ToolCall("c" + i, "x", "{\"i\":" + i + "}"));
        }

        Run run = newRun(newTask().id());
        Thread worker = new Thread(() -> {
            try {
                agentLoopFromContext.run(run.id());
            } catch (RuntimeException ignored) {
                // 取消竞态下的预期异常
            }
        });
        worker.start();

        // 等 RUNNING 后取消
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && !"RUNNING".equals(runRepo.findById(run.id()).orElseThrow().status())) {
            Thread.sleep(20);
        }
        control.cancel(run.id());
        worker.join(10_000);

        Run cancelled = runRepo.findById(run.id()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.errorMessage()).contains("取消");
        // 取消时已执行的 steps 存在且 < maxSteps
        Integer steps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM step WHERE run_id=?", Integer.class, run.id());
        assertThat(steps).isGreaterThan(0);
    }

    /** 线程内安全执行 loop（异常吞掉不影响断言）——已内联到用例中 */

    @Autowired AgentLoop agentLoopFromContext;

    @Test
    void resumeRejectsNonWaitingApprovalRun() {
        Run run = newRun(newTask().id()); // PENDING
        assertThatThrownBy(() -> control.resume(run.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态机违规");
    }

    @Test
    void retryCreatesAndExecutesNewRunCopyingParams() throws Exception {
        Task t = newTask();
        Run original = newRun(t.id());
        runRepo.finish(original.id(), com.aigamelab.domain.RunStatus.COMPLETED, "done", null);

        llm.thenStop("retry executed");
        Run retry = control.retry(original.id());

        assertThat(retry.id()).isNotEqualTo(original.id());
        assertThat(retry.status()).isEqualTo("PENDING");
        assertThat(retry.taskId()).isEqualTo(t.id());
        assertThat(retry.maxSteps()).isEqualTo(original.maxSteps());
        assertThat(retry.tokenBudget()).isEqualTo(original.tokenBudget());
        assertThat(retry.model()).isEqualTo(original.model());
        // 原 Run 终态不变
        assertThat(runRepo.findById(original.id())).map(Run::status).contains("COMPLETED");
        long end=System.currentTimeMillis()+5000;while("PENDING".equals(runRepo.findById(retry.id()).orElseThrow().status())||"RUNNING".equals(runRepo.findById(retry.id()).orElseThrow().status())){if(System.currentTimeMillis()>end)throw new AssertionError("Retry was not executed");Thread.sleep(20);}
        assertThat(runRepo.findById(retry.id()).orElseThrow().finalResult()).isEqualTo("retry executed");
    }

    @Test
    void cancelTerminalRunRejected() {
        Task t = newTask();
        Run run = newRun(t.id());
        runRepo.finish(run.id(), com.aigamelab.domain.RunStatus.COMPLETED, "done", null);

        assertThatThrownBy(() -> control.cancel(run.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(40901);
    }

    @Test
    void recoverOnStartupResumesOnlyRunningRuns() throws Exception {
        // RUNNING Run → 续跑；WAITING_APPROVAL → 保持挂起；COMPLETED → 跳过
        StubTool x = new StubTool("x2");
        x.defaultSuccess("{}", "ok");
        if (!registry.contains("x2")) {
            registry.register(x);
        }
        int baseCalls = llm.callCount();
        llm.thenStop("恢复后立即完成");

        Run running = newRun(newTask().id());
        runRepo.updateStatusAndStep(running.id(), com.aigamelab.domain.RunStatus.RUNNING, 1);
        Run waiting = newRun(newTask().id());
        runRepo.updateStatusAndStep(waiting.id(), com.aigamelab.domain.RunStatus.WAITING_APPROVAL, 1);
        Run completed = newRun(newTask().id());
        runRepo.finish(completed.id(), com.aigamelab.domain.RunStatus.COMPLETED, "done", null);

        int resumed = control.recoverOnStartup();

        // 只有 RUNNING 被续跑（本测试上下文中 RUNNING 的只有这一个新 Run；
        // 其他用例遗留的终态 Run 不受影响）
        assertThat(resumed).isGreaterThanOrEqualTo(1);
        // WAITING_APPROVAL 保持挂起
        assertThat(runRepo.findById(waiting.id())).map(Run::status)
                .contains("WAITING_APPROVAL");
        // COMPLETED 未被触碰
        assertThat(runRepo.findById(completed.id())).map(Run::status)
                .contains("COMPLETED");

        // 续跑的 Run 异步完成（剧本 stop）
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && !"COMPLETED".equals(runRepo.findById(running.id()).orElseThrow().status())) {
            Thread.sleep(20);
        }
        assertThat(runRepo.findById(running.id())).map(Run::status).contains("COMPLETED");
        assertThat(runRepo.findById(running.id())).map(Run::finalResult)
                .contains("恢复后立即完成");
        assertThat(llm.callCount()).isGreaterThan(baseCalls);
    }
}
