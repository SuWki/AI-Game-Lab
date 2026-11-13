package com.aigamelab.runtime;

import java.util.Set;

import com.aigamelab.domain.Task;

/**
 * Agent 类型解析接口（R0-1 + QA P1-1）。
 *
 * 负责：
 * 1. 根据 Task 解析出应使用的 {@link AgentType}
 * 2. 根据 AgentType 返回对应的 System Prompt
 * 3. 根据 AgentType 返回对应的 Tool 白名单
 *
 * 实现类 {@link DefaultAgentTypeResolver} 按 constraints_json 中的 agent_type 字段
 * 显式指定类型（DESIGNER/TESTER/RESEARCH/QA），无指定时回退到现有 Designer/Tester 二值逻辑。
 */
public interface AgentTypeResolver {

    /**
     * 根据 Task 解析 Agent 类型。
     *
     * 解析规则：
     * 1. 优先检查 task.constraintsJson 中的 "agent_type" 字段
     * 2. 允许值：DESIGNER, TESTER, RESEARCH, QA（大小写敏感）
     * 3. 非法值或缺失时，回退到二值逻辑：
     *    - 有 ACTIVE TestPlan → TESTER
     *    - 否则 → DESIGNER
     *    （QA 任务必须显式指定 agent_type=QA，不通过回退路由）
     *
     * @param task 当前任务
     * @return 解析出的 Agent 类型
     */
    AgentType resolve(Task task);

    /**
     * 根据 Agent 类型返回 System Prompt。
     *
     * @param type Agent 类型
     * @return System Prompt 内容
     */
    String systemPrompt(AgentType type);

    /**
     * 根据 Agent 类型返回 Tool 白名单。
     *
     * @param type Agent 类型
     * @return 允许使用的工具名集合；返回 null 表示使用全部已注册工具
     */
    Set<String> toolWhitelist(AgentType type);
}
