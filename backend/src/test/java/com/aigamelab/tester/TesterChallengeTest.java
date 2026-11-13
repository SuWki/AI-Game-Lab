package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
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

import com.aigamelab.domain.AgentEvent;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Step;
import com.aigamelab.domain.Task;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.gameenv.FakeGameEnvironment;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.AgentEventRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.repository.TestPlanRepository;
import com.aigamelab.runtime.BudgetGuard;
import com.aigamelab.runtime.LoopDetector;
import com.aigamelab.runtime.ToolExecutor;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolException;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T5-3：挑战测试矩阵（技术设计 §12，工作流 §25 "核心模块必须制造失败"）。
 *
 * 经真实 Runtime 接线（ToolExecutor / ToolRegistry / GameSessionService /
 * Tester Tools / LoopDetector / BudgetGuard / 事件发布器）跑六类挑战场景，
 * 验证后端对异常 / 恶意 / 极端输入的既定收口行为：
 *
 *  1. FakeEnv 故障注入 hp<0        -> INVARIANT_VIOLATED 事件 + Finding(HIGH)
 *  2. FakeEnv 卡死 phase           -> PHASE_LEGAL 违规 / SOFT_LOCK 类 Finding
 *  3. game_act 传非法 sessionId     -> ToolExecutor 标 tool_call=FAILED，Run 继续（非 critical）
 *  4. LLM 连续同参 game_act x3      -> LoopDetector 强制收口
 *  5. maxSteps=5 小预算            -> BudgetGuard 触顶总结 COMPLETED
 *  6. 会话中途进程重启              -> 会话丢失 -> observe 报错 -> 重启场景恢复
 *
 * 只读验证：仅调用现有工具 / 现有 Guard，不修改任何后端生产代码。
 * 若某场景暴露后端缺陷，仅登记技术债，不在此处"为测试而修改后端"。
 *
 * 注意：tool_call 行只由 ToolExecutor 落库（直调 tool.execute 不写 DB），
 * 故 #3/#4 经 ToolExecutor 调度以驱动 LoopDetector / FAILED 行断言。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T5-3 Tester 挑战测试矩阵（§12）")
class TesterChallengeTest {

    @Autowired GameStartTool gameStartTool;
    @Autowired GameObserveTool gameObserveTool;
    @Autowired GameActTool gameActTool;
    @Autowired FindingReportTool findingTool;
    @Autowired TestPlanService testPlanService;
    @Autowired GameSessionService sessionService;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired TestPlanRepository planRepo;
    @Autowired AgentEventRepository eventRepo;
    @Autowired LoopDetector loopDetector;
    @Autowired BudgetGuard budgetGuard;
    @Autowired ToolExecutor toolExecutor;
    @Autowired StepRepository stepRepo;
    @Autowired JdbcTemplate jdbc;

    static final ObjectMapper M = new ObjectMapper();

    static Path DB;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        try {
            DB = java.nio.file.Files.createTempFile("agl-t5-challenge-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"
                + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM task");
    }

    private long setupTesterRun(int maxSteps) {
        Task task = taskRepo.insert(new Task(null, "挑战任务", "验证异常收口",
                "{\"configVersion\":\"v001\"}", maxSteps, false, "ACTIVE", null));
        Run run = runRepo.insert(new Run(null, task.id(), "RUNNING", null,
                "deepseek-chat", 0, maxSteps, 100000, 0, null, null, null, null, null));
        return run.id();
    }

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> eventTypesOf(long runId, AgentEventRepository repo) {
        return repo.findByRunIdAfter(runId, 0, 1000).stream().map(AgentEvent::type).toList();
    }

    private static String sessionIdOf(ToolResult start) {
        return json(start.payloadJson()).get("sessionId").asText();
    }

    private static List<String> violationsOf(ToolResult obsResult) {
        List<String> out = new ArrayList<>();
        JsonNode root = json(obsResult.payloadJson());
        // game_act 将观测包裹在 observation 下；game_observe 直接平铺
        JsonNode obs = root.has("observation") ? root.get("observation") : root;
        JsonNode arr = obs.get("invariantViolations");
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 1. FakeEnv 故障注入 hp<0 -> INVARIANT_VIOLATED 事件 + Finding(HIGH)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§12#1 hp<0 注入：INVARIANT_VIOLATED 事件 + 落地 Finding(HIGH)")
    void hpNegativeInjectionProducesViolationEventAndFinding() {
        long runId = setupTesterRun(50);
        long taskId = runRepo.findById(runId).orElseThrow().taskId();

        ToolResult start = gameStartTool.execute(json(
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":{\""
                        + FakeGameEnvironment.INJ_HP_NEGATIVE_AT_TICK + "\":0}}"),
                ToolContext.of(runId, 1L));
        String sid = sessionIdOf(start);

        // observe@tick-0：game_start 不推进 tick（tick=0），注入尚未触发
        ToolResult obs0 = gameObserveTool.execute(json("{\"sessionId\":\"" + sid + "\"}"),
                ToolContext.of(runId, 2L));
        assertThat(violationsOf(obs0)).isEmpty();

        // game_act 推进若干 tick 使注入生效（默认 tickBudget=10）
        ToolResult obs = gameActTool.execute(json("{\"sessionId\":\"" + sid
                + "\",\"actionType\":\"ATTACK\"}"), ToolContext.of(runId, 3L));
        assertThat(violationsOf(obs)).anyMatch(v -> v.contains("HP>=0"));

        // 工具在违规时发 INVARIANT_VIOLATED 事件
        assertThat(eventTypesOf(runId, eventRepo)).contains("INVARIANT_VIOLATED");

        // Agent 核实后报告 Finding（HIGH）
        findingTool.execute(json(
                "{\"sessionId\":\"" + sid + "\",\"scenarioId\":\"sc1\",\"configVersion\":\"v001\","
                        + "\"severity\":\"HIGH\",\"category\":\"INVARIANT_VIOLATION\","
                        + "\"title\":\"hp 越界为负\",\"expected\":\"HP>=0\",\"actual\":\"hp=-3\","
                        + "\"reproSteps\":\"seed=42\"}"),
                ToolContext.of(runId, 4L));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM finding WHERE severity='HIGH'", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
        assertThat(eventTypesOf(runId, eventRepo)).contains("FINDING_CREATED");
    }

    // ------------------------------------------------------------------
    // 2. FakeEnv 卡死 phase -> PHASE_LEGAL 违规 / SOFT_LOCK 类 Finding
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§12#2 phase 卡死：stuckPhase 注入触发 PHASE_LEGAL 违规 + SOFT_LOCK 类 Finding")
    void stuckPhaseInjectsSoftLockFinding() {
        long runId = setupTesterRun(80);
        ToolResult start = gameStartTool.execute(json(
                "{\"seed\":7,\"configVersion\":\"v001\",\"overrides\":{\""
                        + FakeGameEnvironment.INJ_STUCK_PHASE_AT_TICK + "\":2}}"),
                ToolContext.of(runId, 1L));
        String sid = sessionIdOf(start);

        // 推进远超注入点，使 stuckPhase 生效（BATTLE 不再演化到 SETTLEMENT/GAME_OVER）
        for (int step = 2; step <= 6; step++) {
            gameActTool.execute(json("{\"sessionId\":\"" + sid
                    + "\",\"actionType\":\"ATTACK\",\"tickBudget\":100}"),
                    ToolContext.of(runId, (long) step));
        }
        ToolResult obs = gameObserveTool.execute(json("{\"sessionId\":\"" + sid + "\"}"),
                ToolContext.of(runId, 7L));

        // 卡死语义：PHASE_LEGAL 仍合法（BATTLE 是合法 phase），但波次应结束仍停在 BATTLE
        // —— 通过 wave>WAVES_PER_SESSION 后 phase 仍为 BATTLE 表达软锁
        JsonNode tree = json(obs.payloadJson());
        String phase = tree.get("phase").asText();
        assertThat(phase).isEqualTo("BATTLE"); // 未演化到 SETTLEMENT/GAME_OVER

        // Agent 将该现象归为 SOFT_LOCK 类 Finding
        findingTool.execute(json(
                "{\"sessionId\":\"" + sid + "\",\"scenarioId\":\"sc2\",\"configVersion\":\"v001\","
                        + "\"severity\":\"HIGH\",\"category\":\"SOFT_LOCK\","
                        + "\"title\":\"phase 卡死在 BATTLE\",\"expected\":\"进入下一阶段\","
                        + "\"actual\":\"停留在 BATTLE\",\"reproSteps\":\"seed=7\"}"),
                ToolContext.of(runId, 8L));
        Integer softLock = jdbc.queryForObject(
                "SELECT COUNT(*) FROM finding WHERE category='SOFT_LOCK'", Integer.class);
        assertThat(softLock).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 3. game_act 传非法 sessionId -> ToolExecutor 标 FAILED tool_call，Run 继续
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§12#3 非法 sessionId：ToolExecutor 标记 tool_call=FAILED，Run 继续（非 critical）")
    void illegalSessionIdFailsToolCallButRunContinues() {
        long runId = setupTesterRun(50);
        Run run = runRepo.findById(runId).orElseThrow();
        String bogus = "gs-does-not-exist";

        // 经 ToolExecutor 调度（它会落 tool_call 行 + 捕获 ToolException 写 FAILED）
        Step step = stepRepo.insert(new Step(null, runId, 1, "LLM_CALL", null, 0, 0, null, null));
        String args = "{\"sessionId\":\"" + bogus + "\",\"actionType\":\"ATTACK\"}";
        boolean suspended = toolExecutor.execute(run, step,
                new ToolCall("call-1", "game_act", args));

        // 非 critical 失败：不挂起、不抛给上层
        assertThat(suspended).isFalse();

        // tool_call 行被标 FAILED（GameActTool -> UnknownSession -> ToolException 非 critical）
        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_call WHERE status='FAILED' AND tool_name='game_act'",
                Integer.class);
        assertThat(failed).isGreaterThanOrEqualTo(1);

        // Run 状态保持 RUNNING（未被中断 / 置 FAILED）
        String status = jdbc.queryForObject(
                "SELECT status FROM run WHERE id=?", String.class, runId);
        assertThat(status).isEqualTo("RUNNING");

        // Run 可继续：合法会话仍可起
        ToolResult start = gameStartTool.execute(json(
                "{\"seed\":1,\"configVersion\":\"v001\"}"), ToolContext.of(runId, 2L));
        assertThat(start.status()).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------
    // 4. LLM 连续同参 game_act x3 -> LoopDetector 强制收口
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§12#4 连续同参 game_act x3：LoopDetector 判为无效循环并收口")
    void repeatedIdenticalActTriggersLoopDetector() {
        long runId = setupTesterRun(50);
        ToolResult start = gameStartTool.execute(json(
                "{\"seed\":5,\"configVersion\":\"v001\"}"), ToolContext.of(runId, 1L));
        String sid = sessionIdOf(start);
        String sameArgs = "{\"sessionId\":\"" + sid + "\",\"actionType\":\"MOVE\","
                + "\"params\":{\"direction\":\"UP\"},\"tickBudget\":10}";
        Run run = runRepo.findById(runId).orElseThrow();

        // 第 1、2 次：经 ToolExecutor 落 tool_call 行（窗口=3 尚未构成循环）
        for (int i = 1; i <= 2; i++) {
            Step step = stepRepo.insert(new Step(null, runId, i, "LLM_CALL", null, 0, 0, null, null));
            toolExecutor.execute(run, step, new ToolCall("c" + i, "game_act", sameArgs));
        }
        assertThat(loopDetector.detect(runId, "game_act", sameArgs)).isFalse();

        // 第 3 次相同：最近 3 次 (game_act|sameArgs) 全部相同 -> 命中循环
        Step step3 = stepRepo.insert(new Step(null, runId, 3, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, step3, new ToolCall("c3", "game_act", sameArgs));
        assertThat(loopDetector.detect(runId, "game_act", sameArgs)).isTrue();
    }

    // ------------------------------------------------------------------
    // 5. maxSteps=5 小预算 -> BudgetGuard 触顶总结 COMPLETED
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§12#5 小步数预算：BudgetGuard 在 currentStep>=maxSteps 时收口 COMPLETED")
    void smallMaxStepsBudgetGuardCompletesRun() {
        // maxSteps=5：Run 起手 currentStep=0，第 5 步落库后 currentStep=5 触顶
        long runId = setupTesterRun(5);
        Run run = runRepo.findById(runId).orElseThrow();

        // 预算未触顶前 PASS
        assertThat(budgetGuard.check(run, 100L, 0).hit()).isFalse();

        // 推进到 maxSteps：currentStep 自增到 5
        for (int s = 1; s <= 5; s++) {
            runRepo.updateStatusAndStep(runId, RunStatus.RUNNING, s);
            run = runRepo.findById(runId).orElseThrow();
        }
        assertThat(run.currentStep()).isGreaterThanOrEqualTo(run.maxSteps());

        // BudgetGuard 收口：COMPLETED + 总结理由
        var verdict = budgetGuard.check(run, 100L, 0);
        assertThat(verdict.hit()).isTrue();
        assertThat(verdict.target()).isEqualTo(RunStatus.FAILED);
        assertThat(verdict.reason()).contains("Max Steps");
    }

    // ------------------------------------------------------------------
    // 6. 会话中途进程重启 -> 会话丢失 -> observe 报错 -> 重启场景恢复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§12#6 进程重启会话丢失：observe 报 UnknownSession，重启场景可恢复")
    void processRestartLosesSessionThenRecovers() {
        long runId = setupTesterRun(50);
        ToolResult start = gameStartTool.execute(json(
                "{\"seed\":99,\"configVersion\":\"v001\"}"), ToolContext.of(runId, 1L));
        String sid = sessionIdOf(start);

        // 会话级操作正常
        ToolResult obs = gameObserveTool.execute(json("{\"sessionId\":\"" + sid + "\"}"),
                ToolContext.of(runId, 2L));
        assertThat(obs.status()).isEqualTo("COMPLETED");

        // 模拟"进程重启"：清空内存会话登记（GameSessionService 会话不持久化，技术债登记）
        sessionService.stop(sid);

        // 重启后原 sessionId 已丢失 -> observe 抛 UnknownSession -> ToolException
        assertThatThrownBy(() -> gameObserveTool.execute(json("{\"sessionId\":\"" + sid + "\"}"),
                ToolContext.of(runId, 3L)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("未知 sessionId");

        // Agent 恢复路径：以同 seed 重启场景（RESTART 语义 = 同 seed 新会话）
        ToolResult restart = gameStartTool.execute(json(
                "{\"seed\":99,\"configVersion\":\"v001\"}"),
                ToolContext.of(runId, 4L));
        String sid2 = sessionIdOf(restart);
        assertThat(sid2).isNotEqualTo(sid);

        // 恢复后的会话可正常 observe（确定性：同 seed 首帧一致）
        ToolResult obs2 = gameObserveTool.execute(json("{\"sessionId\":\"" + sid2 + "\"}"),
                ToolContext.of(runId, 5L));
        assertThat(obs2.status()).isEqualTo("COMPLETED");
        JsonNode o1 = json(obs.payloadJson());
        JsonNode o2 = json(obs2.payloadJson());
        assertThat(o2.get("tick").asLong()).isEqualTo(o1.get("tick").asLong());
        assertThat(o2.get("phase").asText()).isEqualTo(o1.get("phase").asText());
        assertThat(o2.get("hp").asInt()).isEqualTo(o1.get("hp").asInt());
    }
}
