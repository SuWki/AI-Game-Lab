package com.aigamelab.runtime;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.aigamelab.domain.RunStatus;

/**
 * P1-1：Run 状态机（蓝图 §9.2 冻结语义，不修改）。
 *
 * - 静态迁移表 = 蓝图 §9.2 转换表全部合法对；
 * - 终态（COMPLETED/FAILED/CANCELLED/TIMEOUT）不可迁出；
 * - 所有转换经统一入口 transition()；非法迁移抛 BusinessException(40901)；
 * - 纯静态校验器：落库由调用方（AgentLoop/RunControlService）负责。
 */
@Component
public class RunStateMachine {

    /** 蓝图 §9.2 合法迁移对（源 → 允许的目标集合） */
    private static final Map<RunStatus, Set<RunStatus>> TRANSITIONS = Map.of(
            RunStatus.PENDING, Set.of(RunStatus.RUNNING,RunStatus.CANCELLED),
            RunStatus.RUNNING, Set.of(RunStatus.WAITING_APPROVAL, RunStatus.COMPLETED,
                    RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TIMEOUT),
            RunStatus.WAITING_APPROVAL, Set.of(RunStatus.RUNNING, RunStatus.CANCELLED));

    public boolean canTransition(RunStatus from, RunStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 校验并返回目标状态；非法迁移抛 40901。
     * 注意蓝图语义："终态→任意"非法——含终态→自身（终态写入 finished_at 后不再接受转换）。
     */
    public RunStatus transition(RunStatus from, RunStatus to) {
        if (from == to) {
            // PENDING→PENDING、RUNNING→RUNNING 同态重入也视为无操作合法？不：
            // 蓝图转换表未列同态对，按冻结语义一律拒绝，避免恢复扫描误判推进。
            throw BusinessException.conflict("状态机违规: " + from + "→" + to);
        }
        if (!canTransition(from, to)) {
            throw BusinessException.conflict("状态机违规: " + from + "→" + to);
        }
        return to;
    }

    public static boolean isTerminal(RunStatus status) {
        return TRANSITIONS.containsKey(status) == false;
    }
}
