package com.aigamelab.runtime;

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

import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.StubTool;

/**
 * P1-2 验收测试：FakeLlmGateway 剧本下 Run 从 PENDING 到 COMPLETED，
 * steps/tool_calls 落库正确，tokens 累加正确（03 文档验收标准）。
 * 场景：3 步 tool_calls → 第 4 步 stop（P1-0 验收标准剧本）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@org.springframework.context.annotation.Import(RuntimeTestTools.class)
@DisplayName("P1-2 AgentLoop 主循环")
class AgentLoopTest {

    @Autowired AgentLoop loop;
    @Autowired RunRepository runRepo;
    @Autowired StepRepository stepRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p12-", ".db");
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
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    private Task newTask(int maxSteps) {
        return taskRepo.insert(new Task(null, "平衡三 Build", "crit/penetration/as 胜率差距<5%",
                "{}", maxSteps, false, "ACTIVE", null));
    }

    /** FakeLlmGateway 是 test 源码替身，通过配置注入为主 LlmGateway Bean 使用 */
    @org.springframework.boot.test.context.TestConfiguration
    static class FakeConfig {
        @org.springframework.context.annotation.Bean
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @Autowired FakeLlmGateway llm;

    @Test
    void threeToolCallsThenStopCompletesRun() {
        // ---- 编排剧本：tool_calls ×3 → stop ----
        llm.thenToolCalls(new ToolCall("c1", "stub_read", "{\"k\":1}"))
                .thenToolCalls(new ToolCall("c2", "stub_read", "{\"k\":2}"))
                .thenToolCalls(new ToolCall("c3", "stub_read", "{\"k\":3}"))
                .thenStop("三步完成，结论达标");

        StubTool stubRead = new StubTool("stub_read");
        stubRead.defaultSuccess("{\"value\":\"ok\"}", "读取成功");

        Run run = runRepo.insert(new Run(null, newTask(10).id(), "PENDING", null,
                null, 0, 10, 100000, 0, null, null, null, null, null));

        // 注册工具后执行主循环（registryBean 已注入容器 Registry）
        registryBean.register(stubRead);
        try {
            loop.run(run.id());
        } finally {
            // 无需清理：每用例独立临时库
        }

        // ---- 断言终态 ----
        Run done = runRepo.findById(run.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.finalResult()).isEqualTo("三步完成，结论达标");
        assertThat(done.finishedAt()).isNotNull();

        // steps 落库：4 个 Step（3 次 tool_calls + 1 次 stop）
        var steps = stepRepo.findByRunId(run.id());
        assertThat(steps).hasSize(4);

        // tool_calls 落库：3 条 COMPLETED
        Integer tcCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_call WHERE run_id=? AND status='COMPLETED'",
                Integer.class, run.id());
        assertThat(tcCount).isEqualTo(3);

        // tokens 累加：Fake 每步 usage=prompt10+completion5=15，4 步共 60
        assertThat(done.tokensUsed()).isEqualTo(60L);
    }

    @Autowired com.aigamelab.tool.ToolRegistry registryBean;

    @Test
    void approvalRequiredToolSuspendsRun() {
        llm.thenToolCalls(new ToolCall("c1", "stub_write", "{\"patch\":{}}"));

        StubTool writeTool = new StubTool("stub_write",
                Map.of("type", "object"), true); // requiresApproval
        registryBean.register(writeTool);

        Run run = runRepo.insert(new Run(null, newTask(10).id(), "PENDING", null,
                null, 0, 10, 100000, 0, null, null, null, null, null));
        loop.run(run.id());

        Run suspended = runRepo.findById(run.id()).orElseThrow();
        assertThat(suspended.status()).isEqualTo("WAITING_APPROVAL");
        String approval = jdbc.queryForObject(
                "SELECT approval_status FROM tool_call WHERE run_id=?",
                String.class, run.id());
        assertThat(approval).isEqualTo("PENDING");
        // 挂起时 Step 已落库（1 步）
        assertThat(stepRepo.findByRunId(run.id())).hasSize(1);
    }

    @Test
    void lengthFinishReasonCannotClaimCompletion() {
        llm.thenLength("半截输出...");
        Run run = runRepo.insert(new Run(null, newTask(10).id(), "PENDING", null,
                null, 0, 10, 100000, 0, null, null, null, null, null));
        loop.run(run.id());

        Run done = runRepo.findById(run.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(done.errorMessage()).contains("truncated");
    }

    @Test
    void criticalToolFailureFailsRun() {
        llm.thenToolCalls(new ToolCall("c1", "stub_boom", "{}"));
        StubTool boom = new StubTool("stub_boom");
        boom.onArgsThrow("{}", new com.aigamelab.tool.ToolException("关键失败", true));
        registryBean.register(boom);

        Run run = runRepo.insert(new Run(null, newTask(10).id(), "PENDING", null,
                null, 0, 10, 100000, 0, null, null, null, null, null));
        try {
            loop.run(run.id());
        } catch (RuntimeException expected) {
            // critical 异常向上传播后由循环转 FAILED——本实现直接在循环外层处理
        }

        Run failed = runRepo.findById(run.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        String tcStatus = jdbc.queryForObject(
                "SELECT status FROM tool_call WHERE run_id=?", String.class, run.id());
        assertThat(tcStatus).isEqualTo("FAILED");
    }
}
