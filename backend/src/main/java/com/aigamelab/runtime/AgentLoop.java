package com.aigamelab.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Step;
import com.aigamelab.domain.Task;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.llm.LlmGateway;
import com.aigamelab.llm.dto.LlmMessage;
import com.aigamelab.llm.dto.LlmRequest;
import com.aigamelab.llm.dto.LlmResponse;
import com.aigamelab.llm.dto.LlmToolSpec;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.tool.GameTool;
import com.aigamelab.tool.ToolRegistry;

/**
 * P1-2：Agent 主循环（蓝图 §9.3 骨架完整实现）。
 *
 * Evaluate→Observe→LLM→Parse→Act 闭环：
 * RuntimeConversation persists the visible protocol, call IDs, frozen inputs and tool batches.
 * Resume drains queued calls before another model turn; unknown in-flight writes fail safely.
 * Normal stop completes a Run; truncation, exhausted budget and loops do not certify completion.
 * Database terminal guards and per-Run serialization protect cancellation and resume races.
 */
@Component
public class AgentLoop {

    private final RunRepository runRepo;
    private final StepRepository stepRepo;
    private final ToolExecutor toolExecutor;
    private final AgentEventPublisher publisher;
    private final RunStateMachine stateMachine;
    /** 可选：P1 阶段由测试注入 Fake；P2-2 起由 DeepSeekClient 补位 */
    private final LlmGateway llmGateway;
    /** P1-3：三重预算防线 */
    private final BudgetGuard budgetGuard;
    /** P1-5：无效循环检测 */
    private final LoopDetector loopDetector;
    /** Default total budget, frozen per Run by RuntimeConversation. */
    @org.springframework.beans.factory.annotation.Value("${agent.runtime.timeout-ms:900000}")
    private long timeoutMs = 900000;
    @org.springframework.beans.factory.annotation.Autowired private RuntimeConversation conversation;
    private final java.util.concurrent.ConcurrentHashMap<Long,java.util.concurrent.locks.ReentrantLock> locks=new java.util.concurrent.ConcurrentHashMap<>();

    /** 懒加载：避免与 Designer WIP（sim 包）构造期循环依赖风险，且 Tester 工具注册表已就绪即可用 */
    private final org.springframework.beans.factory.ObjectProvider<ToolRegistry> registryProvider;

    /** R0-1：Agent 类型解析器，替换原有 isTesterRun() 二值判断 */
    private final AgentTypeResolver agentTypeResolver;

    public AgentLoop(RunRepository runRepo, StepRepository stepRepo,
            org.springframework.beans.factory.ObjectProvider<LlmGateway> llmGateway,
            ToolExecutor toolExecutor,
            AgentEventPublisher publisher, RunStateMachine stateMachine,
            com.aigamelab.repository.TaskRepository taskRepo,
            BudgetGuard budgetGuard,
            LoopDetector loopDetector,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            org.springframework.beans.factory.ObjectProvider<com.aigamelab.tool.ToolRegistry> registryProvider,
            AgentTypeResolver agentTypeResolver) {
        this.runRepo = runRepo;
        this.stepRepo = stepRepo;
        this.llmGateway = llmGateway.getIfAvailable();
        this.toolExecutor = toolExecutor;
        this.publisher = publisher;
        this.stateMachine = stateMachine;
        this.taskRepo = taskRepo;
        this.budgetGuard = budgetGuard;
        this.loopDetector = loopDetector;
        this.jdbc = jdbc;
        this.registryProvider = registryProvider;
        this.agentTypeResolver = agentTypeResolver;
    }

    /**
     * 主循环入口。挂起/终态返回后线程即释放；恢复场景直接再次调用 run()。
     */
    public void run(long runId) {
        var lock=locks.computeIfAbsent(runId,id->new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            runInner(runId);
        } catch (com.aigamelab.tool.ToolException e) {
            if (e.isCritical()) {
                // 蓝图 §9.4：关键工具失败 → Run FAILED
                finalizeRun(runId, RunStatus.FAILED, null, e.getMessage());
                return;
            }
            finalizeRun(runId,RunStatus.FAILED,null,e.getMessage());
        } catch (RuntimeException e) {
            // 不可恢复运行时异常 → FAILED（蓝图 §9.4 兜底）
            finalizeRun(runId, RunStatus.FAILED, null, String.valueOf(e.getMessage()));
            return;
        } finally {
            lock.unlock();
            runRepo.findById(runId).filter(r->RunStateMachine.isTerminal(RunStatus.valueOf(r.status()))).ifPresent(r->locks.remove(runId,lock));
        }
    }

    private void runInner(long runId) {
        Run run = runRepo.findById(runId).orElseThrow();
        // PENDING→RUNNING（恢复扫描时已是 RUNNING 则跳过迁移）
        if (run.status().equals("PENDING")) {
            stateMachine.transition(RunStatus.PENDING, RunStatus.RUNNING);
            if(!runRepo.transitionIf(runId,"PENDING",RunStatus.RUNNING))return;
        }
        if(!Set.of("PENDING","RUNNING").contains(run.status()))return;
        run=runRepo.findById(runId).orElseThrow();
        conversation.freeze(run,taskOf(run));
        long budgetMs=conversation.context(runId).path("timeoutMs").asLong(timeoutMs);
        conversation.assertRecoverable(runId);
        jdbc.update("UPDATE run SET current_step=MAX(current_step,COALESCE((SELECT MAX(s.seq) FROM runtime_turn t JOIN step s ON s.id=t.step_id WHERE t.run_id=?),0)) WHERE id=? AND status='RUNNING'",runId,runId);

        while (true) {
            run = runRepo.findById(runId).orElseThrow();
            if(!"RUNNING".equals(run.status())||cancelled(runId))return;
            // Resume unexecuted batch members before starting another model turn.
            for(var pending:conversation.pending(runId)) {
                if(!"RUNNING".equals(runRepo.findById(runId).orElseThrow().status()))return;
                if(budgetMs>0&&elapsedMs(run)>=budgetMs){finalizeRun(runId,RunStatus.TIMEOUT,null,"已到总超时预算");return;}
                if("PENDING".equals(pending.approvalStatus())) {
                    runRepo.transitionIf(runId,"RUNNING",RunStatus.WAITING_APPROVAL);return;
                }
                if(!"QUEUED".equals(pending.status()))throw new IllegalStateException("Tool execution outcome unknown; inspect tool_call #"+pending.rowId()+" before retry");
                var rowStep=jdbc.queryForObject("SELECT step_id FROM tool_call WHERE id=?",Long.class,pending.rowId());
                if(toolExecutor.executePrepared(run,stepRepo.findById(rowStep).orElseThrow(),pending.call(),pending.rowId()))return;
            }

            // ---- Evaluate：三重预算防线（P1-3） ----
            var verdict = budgetGuard.check(run, elapsedMs(run), budgetMs);
            if (verdict.hit()) {
                finalizeRun(runId, verdict.target(), null, verdict.reason());
                return;
            }
            if (cancelled(runId)) {
                return; // P1-4：协作式中断，状态由 Cancel 方负责
            }

            // ---- Observe：从 DB 重建上下文 ----
            Task task = taskOf(run);
            List<LlmMessage> messages = conversation.messages(runId);

            // ---- Act：一次 LLM 调用 = 一个 Step ----
            long t0 = System.currentTimeMillis();
            Step step = stepRepo.insert(new Step(null, runId, run.currentStep() + 1,
                    "LLM_CALL", null, 0, 0, null, null));
            publisher.publish("STEP_STARTED", runId, "{\"seq\":" + step.seq() + "}");
            LlmResponse resp;
            if (llmGateway == null) {
                throw new IllegalStateException(
                        "LlmGateway 未配置：P1 阶段请在测试中注入 FakeLlmGateway；P2-2 落地 DeepSeekClient 后生产可用");
            }
            resp = llmGateway.complete(request(run, messages, task));
            stepRepo.updateLlmResult(step.id(), resp.finishReason(), resp.content(),
                    usage(resp).promptTokens(), usage(resp).completionTokens(),
                    System.currentTimeMillis() - t0);
            runRepo.addTokensUsed(runId, usage(resp).totalTokens());
            if(!"RUNNING".equals(runRepo.findById(runId).orElseThrow().status())||cancelled(runId))return;
            if(budgetMs>0&&elapsedMs(runRepo.findById(runId).orElseThrow())>=budgetMs){finalizeRun(runId,RunStatus.TIMEOUT,null,"已到总超时预算");return;}

            // ---- Parse ----
            if ("tool_calls".equals(resp.finishReason())) {
                conversation.persistBatch(run,step,resp);
                // P8-2 修复：在 ToolExecutor 可能将 status 改为 WAITING_APPROVAL 之前推进 currentStep，
                // 否则 resume 时 Step INSERT 与已有 seq 冲突（UNIQUE constraint on run_id+seq）。
                if (!runRepo.advanceStepIfRunning(runId, step.seq())) {
                    return;
                }
                var fresh = runRepo.findById(runId).orElseThrow();
                boolean suspended = false;
                for (var pending : conversation.pending(runId)) {
                    if(!"RUNNING".equals(runRepo.findById(runId).orElseThrow().status()))return;
                    if(budgetMs>0&&elapsedMs(run)>=budgetMs){finalizeRun(runId,RunStatus.TIMEOUT,null,"已到总超时预算");return;}
                    suspended = toolExecutor.executePrepared(fresh, step, pending.call(),pending.rowId());
                    if (suspended) {
                        break; // 蓝图 §9.3：已挂起，释放线程
                    }
                }
                if (suspended) {
                    return;
                }
            } else if ("stop".equals(resp.finishReason())) {
                finalizeRun(runId, RunStatus.COMPLETED, resp.content(), null);
                return;
            } else { // length → 兜底总结 COMPLETED（蓝图 §9.4：非 FAILED）
                finalizeRun(runId, RunStatus.FAILED,null,
                        "Model output truncated or unsupported finish reason: " + resp.finishReason());
                return;
            }

            // ---- P1-5：无效循环检测（最近 3 次同参同工具 → 强制收口） ----
            var lastCall = jdbcLastToolCall(runId);
            if (lastCall != null && loopDetector.detect(runId, lastCall[0], lastCall[1])) {
                finalizeRun(runId, RunStatus.FAILED,null,"检测到无效循环，目标未验证完成");
                return;
            }
        }
    }

    /** finalize：统一终态收口（COMPLETED/FAILED/TIMEOUT/CANCELLED 共用） */
    private void finalizeRun(long runId, RunStatus status, String finalResult, String error) {
        if(runRepo.finishIfActive(runId, status, finalResult, error))publisher.publish("RUN_" + status.name(), runId, null);
    }

    /** P1-4：协作式取消检测——由 RunControlService 注入判断器 */
    private java.util.function.LongPredicate cancelChecker = id -> false;

    public void setCancelChecker(java.util.function.LongPredicate checker) {
        this.cancelChecker = checker;
    }

    private boolean cancelled(long runId) {
        return cancelChecker.test(runId);
    }

    /** P1-3：距 run.startedAt 至今毫秒（驱动时间预算） */
    private long elapsedMs(Run run) {
        return run.startedAt() == null ? 0
                : java.time.Duration.between(run.startedAt(), java.time.LocalDateTime.now()).toMillis();
    }

    /** P1-5：最近一次 tool_call 的 (toolName,argsJson)；无调用返回 null */
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String[] jdbcLastToolCall(long runId) {
        var rows = jdbc.query(
                "SELECT tool_name, arguments_json FROM tool_call WHERE run_id=? ORDER BY id DESC LIMIT 1",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---- Observe 组装（蓝图 §9.3 底部规则） ----

    private final com.aigamelab.repository.TaskRepository taskRepo;

    private Task taskOf(Run run) {
        return taskRepo.findById(run.taskId()).orElseThrow(
                () -> new IllegalStateException("Run " + run.id() + " 引用的 Task 不存在: "
                        + run.taskId()));
    }

    private LlmRequest request(Run run, List<LlmMessage> messages, Task task) {
        List<LlmToolSpec> specs=conversation.specs(run.id());
        return new LlmRequest("rq-" + run.id() + "-" + (run.currentStep() + 1),
                run.id(), run.model(), messages, specs.isEmpty() ? null : specs,
                conversation.context(run.id()).path("temperature").asDouble(0.4), conversation.context(run.id()).path("maxTokens").asInt(4096), conversation.context(run.id()).path("promptSha256").asText());
    }

    private static com.aigamelab.llm.dto.TokenUsage usage(LlmResponse resp) {
        return resp.usage() != null ? resp.usage()
                : new com.aigamelab.llm.dto.TokenUsage(0, 0, 0, null, null);
    }
}
