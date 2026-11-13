package com.aigamelab.event;

/**
 * 蓝图 §16：16 类 Agent 事件枚举。
 * 发布链路以字符串 type 传递（兼容历史 publish(type,...) 签名），
 * 本枚举作为合法值域的唯一定义。
 */
public enum AgentEventType {
    TASK_CREATED,
    RUN_STARTED,
    STEP_STARTED,
    LLM_REQUEST_SENT,
    LLM_RESPONSE_RECEIVED,
    TOOL_CALLED,
    TOOL_RETURNED,
    SIMULATION_COMPLETED,
    PROPOSAL_CREATED,
    APPROVAL_REQUIRED,
    CONFIG_VERSION_CREATED,
    PROPOSAL_REJECTED,
    RUN_RESUMED,
    RUN_CANCELLED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_TIMEOUT,

    // T4-3：AI Game Tester 事件类型（追加不改旧值；技术设计 §53 / §255-256）
    TEST_PLAN_CREATED,
    TEST_PLAN_ACTIVATED,
    GAME_SESSION_STARTED,
    INVARIANT_VIOLATED,
    FINDING_CREATED,
    TEST_REPORT_CREATED,

    // R0-8：AI Game Research Agent 事件类型（追加不改旧值）
    SOURCE_FETCHED,
    CLAIM_EXTRACTED,
    CLAIM_VALIDATED,
    RESEARCH_REPORT_CREATED;

    /** 校验 type 是否为合法事件类型（未知类型发布时拒绝，防脏数据入库）。 */
    public static boolean isValid(String type) {
        for (AgentEventType t : values()) {
            if (t.name().equals(type)) {
                return true;
            }
        }
        return false;
    }
}
