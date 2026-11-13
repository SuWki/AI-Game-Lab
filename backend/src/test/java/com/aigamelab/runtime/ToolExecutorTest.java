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
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.AgentEventRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.StubTool;

/**
 * P3-5 验收测试（03 文档 + 蓝图 §11/§16）：经真实 AgentLoop + ToolExecutor 接线验证：
 *  - Schema 校验拦截非法参数（缺 required → tool_call=FAILED，Run 继续）
 *  - 工具成功 / 失败（FAILED 结果）/ 抛异常（ToolException）三类结局落库
 *  - requiresApproval 工具挂起 Run（WAITING_APPROVAL）
 *  - P1-6 遗留 TOOL_CALLED / TOOL_RETURNED 事件接线：每次调用均落 agent_event
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@org.springframework.context.annotation.Import(RuntimeTestTools.class)
@DisplayName("P3-5 ToolExecutor 编排 + TOOL_* 事件接线")
class ToolExecutorTest {

    @Autowired AgentLoop loop;
    @Autowired RunRepository runRepo;
    @Autowired StepRepository stepRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentEventRepository eventRepo;
    @Autowired com.aigamelab.tool.ToolRegistry registryBean;
    @Autowired FakeLlmGateway llm;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p35-", ".db");
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
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    private Task newTask(int maxSteps) {
        return taskRepo.insert(new Task(null, "平衡三 Build", "胜率差距<5%",
                "{}", maxSteps, false, "ACTIVE", null));
    }

    private Run newRun(Task task) {
        return runRepo.insert(new Run(null, task.id(), "PENDING", null,
                null, 0, 10, 100000, 0, null, null, null, null, null));
    }

    private int eventCount(long runId, String type) {
        String rid = String.valueOf(runId);
        return (int) eventRepo.findRecent(10000).stream()
                .filter(e -> rid.equals(e.runId()) && e.type().equals(type))
                .count();
    }

    @Test
    void successToolEmitsToolCalledAndReturnedEvents() {
        llm.thenToolCalls(new ToolCall("c1", "stub_ok", "{\"k\":1}")).thenStop("完成");
        StubTool ok = new StubTool("stub_ok",
                Map.of("type", "object",
                        "properties", Map.of("k", Map.of("type", "integer")),
                        "required", List.of("k")),
                false);
        ok.defaultSuccess("{\"value\":1}", "成功");
        registryBean.register(ok);

        Run run = newRun(newTask(10));
        loop.run(run.id());

        assertThat(runRepo.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(eventCount(run.id(), "TOOL_CALLED")).isEqualTo(1);
        assertThat(eventCount(run.id(), "TOOL_RETURNED")).isEqualTo(1);
        Integer tc = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_call WHERE run_id=? AND status='COMPLETED'",
                Integer.class, run.id());
        assertThat(tc).isEqualTo(1);
    }

    @Test
    void invalidSchemaArgsFailsToolCallButKeepsRunAlive() {
        // 缺 required 字段 k → Schema 校验拦截
        llm.thenToolCalls(new ToolCall("c1", "stub_need", "{}")).thenStop("改走 stop");
        StubTool need = new StubTool("stub_need",
                Map.of("type", "object",
                        "properties", Map.of("k", Map.of("type", "integer")),
                        "required", List.of("k")),
                false);
        need.defaultSuccess("{}", "不应到达");
        registryBean.register(need);

        Run run = newRun(newTask(10));
        loop.run(run.id());

        // Run 继续到 stop（未被致命）
        assertThat(runRepo.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");
        String tcStatus = jdbc.queryForObject(
                "SELECT status FROM tool_call WHERE run_id=?", String.class, run.id());
        assertThat(tcStatus).isEqualTo("FAILED"); // 参数非法 → FAILED 落库
        assertThat(eventCount(run.id(), "TOOL_RETURNED")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void failedResultToolCallMarksFailedNotFatal() {
        llm.thenToolCalls(new ToolCall("c1", "stub_fail", "{\"k\":1}")).thenStop("兜底");
        StubTool fail = new StubTool("stub_fail",
                Map.of("type", "object",
                        "properties", Map.of("k", Map.of("type", "integer")),
                        "required", List.of("k")),
                false);
        fail.onArgsFailure("{\"k\":1}", "业务校验未通过");
        registryBean.register(fail);

        Run run = newRun(newTask(10));
        loop.run(run.id());

        assertThat(runRepo.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");
        String tcStatus = jdbc.queryForObject(
                "SELECT status FROM tool_call WHERE run_id=?", String.class, run.id());
        assertThat(tcStatus).isEqualTo("FAILED");
    }

    @Test
    void thrownToolExceptionFailsToolCallButRunContinues() {
        llm.thenToolCalls(new ToolCall("c1", "stub_throw", "{\"k\":1}")).thenStop("绕过");
        StubTool thr = new StubTool("stub_throw",
                Map.of("type", "object",
                        "properties", Map.of("k", Map.of("type", "integer")),
                        "required", List.of("k")),
                false);
        thr.onArgsThrow("{\"k\":1}", new com.aigamelab.tool.ToolException("内部异常"));
        registryBean.register(thr);

        Run run = newRun(newTask(10));
        loop.run(run.id());

        assertThat(runRepo.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");
        String tcStatus = jdbc.queryForObject(
                "SELECT status FROM tool_call WHERE run_id=?", String.class, run.id());
        assertThat(tcStatus).isEqualTo("FAILED");
    }

    /**
     * TD-P3-001：requiresApproval 工具先执行再挂起——StubTool.execute() 被调用，
     * Run 进入 WAITING_APPROVAL + TOOL_RETURNED{SUSPENDED}。
     */
    @Test
    void approvalRequiredToolSuspendsAndEmitsReturnedSuspended() {
        llm.thenToolCalls(new ToolCall("c1", "stub_appr", "{\"k\":1}"));
        StubTool appr = new StubTool("stub_appr",
                Map.of("type", "object",
                        "properties", Map.of("k", Map.of("type", "integer")),
                        "required", List.of("k")),
                true);
        appr.defaultSuccess("{\"ok\":true}", "appr done");
        registryBean.register(appr);

        Run run = newRun(newTask(10));
        loop.run(run.id());

        assertThat(runRepo.findById(run.id()).orElseThrow().status())
                .isEqualTo("WAITING_APPROVAL");
        assertThat(eventCount(run.id(), "TOOL_CALLED")).isEqualTo(1);
        // TD-P3-001：execute() 已被调用（先执行再挂起）
        assertThat(appr.callCount()).isEqualTo(1);
        // TOOL_RETURNED status=SUSPENDED
        boolean suspended = eventRepo.findRecent(10000).stream()
                .filter(e -> String.valueOf(run.id()).equals(e.runId())
                        && e.type().equals("TOOL_RETURNED"))
                .anyMatch(e -> e.payloadJson() != null && e.payloadJson().contains("SUSPENDED"));
        assertThat(suspended).isTrue();
    }
}
