package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;

/**
 * run 表仓储（蓝图 §7 接口清单）。
 */
public interface RunRepository {

    /** 插入 PENDING Run 并回填 id/createdAt。 */
    Run insert(Run run);

    Optional<Run> findById(long id);

    List<Run> findByTask(long taskId);

    /** P1-4 恢复扫描：按状态查询（RUNNING 续跑 / WAITING_APPROVAL 保持挂起）。 */
    List<Run> findByStatus(String status);

    /** 状态机迁移 + current_step 同步推进（合法性校验属 P1-1 状态机）。 */
    void updateStatusAndStep(long id, RunStatus status, int currentStep);

    /**
     * P1-4 取消竞态修复：仅当行仍为 RUNNING 时才推进 current_step。
     * 返回是否实际更新（false = 控制方已写终态，循环必须静默退出）。
     */
    boolean advanceStepIfRunning(long id, int currentStep);

    /**
     * 原子收尾：status/finalResult/errorMessage/finishedAt 一次 UPDATE，
     * 避免多次往返造成半成品状态。
     */
    void finish(long id, RunStatus status, String finalResult, String errorMessage);

    /** tokens_used 累加（P2-4 每步调用）。 */
    void addTokensUsed(long id, long delta);

    boolean transitionIf(long id, String expectedStatus, RunStatus next);
    boolean finishIfActive(long id, RunStatus status, String result, String error);

    /**
     * Dashboard 聚合：一条 SQL 出总数/按状态计数/成功率/平均时长/token 总和。
     * 返回列：totalRuns, completedRuns, failedRuns, runningRuns,
     * waitingApprovalRuns, cancelledRuns, timeoutRuns, avgDurationMs, totalTokens。
     */
    AggregateStats aggregateStats();

    record AggregateStats(
            long totalRuns,
            long completedRuns,
            long failedRuns,
            long runningRuns,
            long waitingApprovalRuns,
            long cancelledRuns,
            long timeoutRuns,
            Double avgDurationMs,
            long totalTokens) {
    }
}
