package com.aigamelab.runtime;

/**
 * Agent 类型枚举（R0-1 + QA P1-1 + R5 Phase 2）。
 *
 * 定义 AgentRuntime 支持的 Agent 类型：
 * - DESIGNER：游戏设计 Agent（默认）
 * - TESTER：游戏测试 Agent
 * - RESEARCH：研究 Agent（R0-2+ 实现）
 * - QA：质量验证 Agent（QA P1-1，最小闭环）
 * - QA_LEAD：QA 编排角色（R5 Phase 2，仅编排权限）
 * - QA_TESTER：QA 测试角色（Player/Exploit，复用 Tester 工具集+Web）
 * - QA_ANALYST：QA 分析角色（Combat/Numerical，复用 Designer 分析工具）
 * - QA_RESEARCHER：QA 研究角色（UX Reviewer，复用 Research 工具集+游戏观察能力）
 *
 * 类型解析由 {@link AgentTypeResolver} 负责，
 * AgentLoop 根据解析结果选择对应的 Prompt 和 Tool 子集。
 *
 * R5 §5：8 Role 对应 4 种 AgentType（QA_LEAD/TESTER/ANALYST/RESEARCHER），
 * 每个 Role 的 Prompt 版本由 constraintsJson.prompt_version 决定，
 * 由 DefaultAgentTypeResolver 通过 ThreadLocal 传递给 systemPrompt()。
 */
public enum AgentType {
    DESIGNER,
    TESTER,
    RESEARCH,
    QA,
    QA_LEAD,
    QA_TESTER,
    QA_ANALYST,
    QA_RESEARCHER,
    BUILDER
}
