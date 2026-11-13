package com.aigamelab.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;

/**
 * P1-4：Run 生命周期控制（蓝图 §9.5/§9.7）。
 *
 * - Cancel：数据库条件写入 CANCELLED，循环与工具执行边界查询终态；
 * - Resume：WAITING_APPROVAL→RUNNING→重提交 loop；
 * - Retry：新建 Run 复制参数并提交有界执行队列。
 * - T6-1 Start：从 Task 启动新 Run（POST /api/tasks/{id}/runs 后端接线）。
 */
@Service
public class RunControlService {

    private static final Logger log = LoggerFactory.getLogger(RunControlService.class);

    private final RunRepository runRepo;
    private final TaskRepository taskRepo;
    private final RunStateMachine stateMachine;
    private final AgentLoop agentLoop;
    @org.springframework.beans.factory.annotation.Autowired private ToolExecutor toolExecutor;
    /** 循环执行器：resume/recovery 提交用（守护线程，不阻塞 JVM 退出） */
    private final java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(4,4,0,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(64),r -> {
        Thread t = new Thread(r, "agent-loop-worker");
        t.setDaemon(true);
        return t;
    });
    @org.springframework.beans.factory.annotation.Value("${llm.gateway.model:deepseek-chat}") private String model="deepseek-chat";

    public RunControlService(RunRepository runRepo, TaskRepository taskRepo,
            RunStateMachine stateMachine,
            AgentLoop agentLoop, AgentEventPublisher events) {
        this.runRepo = runRepo;
        this.taskRepo = taskRepo;
        this.stateMachine = stateMachine;
        this.agentLoop = agentLoop;
        this.events = events;
        // 协作式取消接线：查询持久化状态，不依赖进程内取消集合。
        agentLoop.setCancelChecker(this::isCancelRequested);
    }

    private final AgentEventPublisher events;

    // ==================== Cancel ====================

    /** 取消一个 Run：仅 PENDING/RUNNING/WAITING_APPROVAL 可取消 */
    public void cancel(long runId) {
        Run run = runRepo.findById(runId).orElseThrow(
                () -> BusinessException.notFound("Run 不存在: " + runId));
        RunStatus from = RunStatus.valueOf(run.status());
        if (!stateMachine.canTransition(from, RunStatus.CANCELLED)) {
            throw BusinessException.conflict("状态机违规: " + from + "→CANCELLED");
        }
        if(!runRepo.finishIfActive(runId, RunStatus.CANCELLED,run.finalResult(), "用户取消"))throw BusinessException.conflict("Run 已终结");
        events.publish("RUN_CANCELLED", runId, "{\"reason\":\"用户取消\"}");
        log.info("run {} cancellation persisted", runId);
    }

    // ==================== Resume ====================

    /** 审批后恢复：WAITING_APPROVAL→RUNNING→异步重提交 loop */
    public void resume(long runId) {
        Run run = requireRunnable(runId);
        stateMachine.transition(RunStatus.WAITING_APPROVAL, RunStatus.RUNNING);
        if(!runRepo.transitionIf(runId,"WAITING_APPROVAL",RunStatus.RUNNING))throw BusinessException.conflict("Run 已被恢复或终结");
        events.publish("RUN_RESUMED", runId,
                "{\"from\":\"WAITING_APPROVAL\",\"step\":" + run.currentStep() + "}");
        submit(runId,() -> {
            try {
                agentLoop.run(runId);
            } catch (RuntimeException e) {
                log.error("resume loop for run {} crashed", runId, e);
            }
        });
    }

    // ==================== Retry ====================

    /** 新建 Run 重试：复制 Task 参数，原 Run 保持终态不变 */
    public Run retry(long runId) {
        Run old = runRepo.findById(runId).orElseThrow(
                () -> BusinessException.notFound("Run 不存在: " + runId));
        if(!RunStateMachine.isTerminal(RunStatus.valueOf(old.status())))throw BusinessException.conflict("仅终态 Run 可以重试");
        Run retried=runRepo.insert(new Run(null, old.taskId(), "PENDING", old.promptVersionId(),
                old.model(), 0, old.maxSteps(), old.tokenBudget(), 0,
                null, null, null, null, null));
        submit(retried.id(),()->agentLoop.run(retried.id()));
        return retried;
    }

    /**
     * T6-1：从 Task 启动新 Run（POST /api/tasks/{id}/runs 的后端接线）。
     *
     * 创建 PENDING Run → 提交 AgentLoop 异步执行。
     * Tester/Designer 双轨判定由 AgentLoop.isTesterRun(Task) 在运行时完成
     * （Task 存在 ACTIVE TestPlan → Tester Run，否则 Designer Run）。
     *
     * 默认值：model="deepseek-chat"，maxSteps=50，tokenBudget=100000。
     * promptVersionId 传 null（由 AgentLoop.selectSystemPrompt 按 Task 动态选择）。
     */
    public Run start(long taskId) {
        var task=taskRepo.findById(taskId).orElseThrow(
                () -> BusinessException.notFound("Task 不存在: " + taskId));
        Run run = runRepo.insert(new Run(null, taskId, "PENDING", null,
                model, 0, task.maxSteps(), 100000, 0,
                null, null, null, null, null));
        log.info("Task {} → 新建 Run {}（待 AgentLoop 执行）", taskId, run.id());
        submit(run.id(),() -> {
            try {
                agentLoop.run(run.id());
            } catch (RuntimeException e) {
                log.error("Run {} 执行失败", run.id(), e);
            }
        });
        return run;
    }

    // ==================== 恢复扫描（蓝图 §9.5） ====================

    /**
     * 启动时调用：RUNNING 续跑；WAITING_APPROVAL 保持挂起。
     * 返回续跑的 Run 数量。
     */
    public int recoverOnStartup() {
        for(Run waiting:runRepo.findByStatus("WAITING_APPROVAL"))toolExecutor.reconcileSuspendedRun(waiting.id());
        var running = jdbcFindRunning();
        int resumed = 0;
        for (Long id : running) {
            events.publish("RUN_RESUMED", id, "{\"from\":\"RECOVERY\"}");
            long capture = id;
            try {submit(capture,() -> {
                try {
                    agentLoop.run(capture);
                } catch (RuntimeException e) {
                    log.error("recovery loop for run {} crashed", capture, e);
                }
            });resumed++;}catch(BusinessException rejected){log.warn("Recovery admission rejected for Run {}",capture);}
        }
        return resumed;
    }

    private java.util.List<Long> jdbcFindRunning() {
        return java.util.stream.Stream.concat(runRepo.findByStatus("RUNNING").stream(),runRepo.findByStatus("PENDING").stream()).map(Run::id).toList();
    }

    private void submit(long runId,Runnable action) {
        try {executor.execute(action);}catch(java.util.concurrent.RejectedExecutionException e) {
            if(runRepo.finishIfActive(runId,RunStatus.FAILED,null,"Agent execution queue full or shutting down"))events.publish("RUN_FAILED",runId,"{\"reason\":\"Agent execution queue full or shutting down\"}");
            throw BusinessException.conflict("Agent execution queue full or shutting down; Run #"+runId+" recorded as FAILED");
        }
    }
    @jakarta.annotation.PreDestroy public void close() {
        executor.shutdown();
        try {if(!executor.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS))executor.shutdownNow();}
        catch(InterruptedException e){executor.shutdownNow();Thread.currentThread().interrupt();}
    }

    // ==================== AgentLoop 集成点 ====================

    /** AgentLoop.cancelled(runId) 委托到此 */
    boolean isCancelRequested(long runId) {
        return runRepo.findById(runId).map(r->"CANCELLED".equals(r.status())).orElse(true);
    }

    private Run requireRunnable(long runId) {
        Run run = runRepo.findById(runId).orElseThrow(
                () -> BusinessException.notFound("Run 不存在: " + runId));
        if (!"WAITING_APPROVAL".equals(run.status())) {
            throw BusinessException.conflict("状态机违规: " + run.status() + "→RUNNING");
        }
        return run;
    }
}
