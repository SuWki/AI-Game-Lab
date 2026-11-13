package com.aigamelab.runtime;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;

/**
 * P1-3：三重预算防线（蓝图 §9.6）。
 * 每轮 LLM 调用前依次检查：步数 → token → 时间。
 * 步数/token 触顶 → FAILED（未证明目标完成）；超时 → TIMEOUT。
 */
@Component
public class BudgetGuard {

    /** 预算检查结果：无触发 / 触发后应转入的状态与说明 */
    public record Verdict(boolean hit, RunStatus target, String reason) {
        public static Verdict pass() {
            return new Verdict(false, null, null);
        }

        public static Verdict stop(RunStatus target, String reason) {
            return new Verdict(true, target, reason);
        }
    }

    /**
     * 每轮 Evaluate 阶段调用。
     *
     * @param elapsedMs now-startedAt 毫秒（由调用方传入，便于测试注入时钟）
     * @param timeoutMs 总超时预算；<=0 表示不启用时间预算
     */
    public Verdict check(Run run, long elapsedMs, long timeoutMs) {
        // 1. 步数预算
        if (run.currentStep() >= run.maxSteps()) {
            return Verdict.stop(RunStatus.FAILED, "已到 Max Steps，目标未验证完成");
        }
        // 2. token 预算
        if (run.tokensUsed() >= run.tokenBudget()) {
            return Verdict.stop(RunStatus.FAILED, "已到 Token 预算，目标未验证完成");
        }
        // 3. 时间预算
        if (timeoutMs > 0 && elapsedMs >= timeoutMs) {
            return Verdict.stop(RunStatus.TIMEOUT, "已到总超时预算");
        }
        return Verdict.pass();
    }
}
