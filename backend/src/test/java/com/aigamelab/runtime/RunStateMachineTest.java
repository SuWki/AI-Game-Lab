package com.aigamelab.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.RunStatus;

/**
 * P1-1 验收测试：参数化枚举全部 7×7=49 个状态对。
 * 合法对（蓝图 §9.2 转换表 8 对）通过；其余全部被拒（含终态→任意、同态重入）。
 */
@DisplayName("P1-1 Run 状态机：全状态对枚举")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunStateMachineTest {

    private final RunStateMachine sm = new RunStateMachine();

    /** 蓝图 §9.2 转换表全部合法对 */
    private static final java.util.Set<String> LEGAL = java.util.Set.of(
            "PENDING->RUNNING", "PENDING->CANCELLED",
            "RUNNING->WAITING_APPROVAL", "RUNNING->COMPLETED", "RUNNING->FAILED",
            "RUNNING->CANCELLED", "RUNNING->TIMEOUT",
            "WAITING_APPROVAL->RUNNING", "WAITING_APPROVAL->CANCELLED");

    @ParameterizedTest(name = "{0} -> {1}")
    @EnumSource(RunStatus.class)
    void allPairs_From(RunStatus from) {
        for (RunStatus to : RunStatus.values()) {
            if (LEGAL.contains(from + "->" + to)) {
                assertDoesNotThrow(() -> sm.transition(from, to),
                        "合法迁移被误拒: " + from + "->" + to);
                assert sm.canTransition(from, to);
            } else {
                BusinessException e = assertThrows(BusinessException.class,
                        () -> sm.transition(from, to),
                        "非法迁移未被拒绝: " + from + "->" + to);
                org.junit.jupiter.api.Assertions.assertEquals(40901, e.getCode(),
                        "非法迁移错误码应为 40901: " + from + "->" + to);
            }
        }
    }

    /** 终态判定：四个终态不可再迁出（canTransition 恒 false） */
    @ParameterizedTest(name = "terminal: {0}")
    @EnumSource(value = RunStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"})
    void terminalStatesCannotLeave(RunStatus terminal) {
        org.junit.jupiter.api.Assertions.assertFalse(sm.canTransition(terminal, RunStatus.RUNNING));
        assertThrows(BusinessException.class, () -> sm.transition(terminal, RunStatus.RUNNING));
    }

    /** PENDING 直接到终态/挂起均非法（必须先 RUNNING） */
    @ParameterizedTest(name = "pending reject: {0}")
    @EnumSource(value = RunStatus.class, names = {"WAITING_APPROVAL", "COMPLETED", "FAILED",
            "TIMEOUT"})
    void pendingOnlyToRunning(RunStatus target) {
        assertThrows(BusinessException.class, () -> sm.transition(RunStatus.PENDING, target));
    }
}
