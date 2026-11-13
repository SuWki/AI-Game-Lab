package com.aigamelab.domain;

/**
 * Run 状态（蓝图 §9.2 七态）。
 */
public enum RunStatus {
    PENDING, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, TIMEOUT
}
