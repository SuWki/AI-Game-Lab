# Agent 平台架构规范

## 1. Agent 定义

Agent 是一个具有：

- 目标
- System Prompt
- Tools
- Runtime
- Memory
- Project Context

的 AI 工作单元。

Agent 不是独立服务。

---

# 2. Agent 生命周期

Agent Run：

CREATED
→ RUNNING
→ WAITING_TOOL
→ WAITING_APPROVAL
→ COMPLETED

异常：

RUNNING
→ FAILED

可恢复：

FAILED
→ RESUMED
→ RUNNING

---

# 3. Agent Runtime

所有 Agent 默认使用统一 AgentRuntime。

AgentRuntime 负责：

- 状态机
- LLM 调用
- Tool 调用
- Step
- Event
- Token Usage
- Resume
- 错误处理
- 最大循环限制

Agent 本身只负责定义：

- Prompt
- Tool Set
- Goal
- Agent-specific policy

---

# 4. Agent 不允许重复实现 Runtime

禁止：

DesignerAgentRuntime
TesterAgentRuntime
ResearchAgentRuntime

正确：

AgentRuntime
    ↑
    |
DesignerAgent
TesterAgent
ResearchAgent

---

# 5. LLM Gateway

所有 Agent 通过：

LlmGateway

访问模型。

Agent 禁止：

- 直接 HTTP 请求 DeepSeek
- 直接依赖 DeepSeek SDK
- 自己实现 Retry
- 自己实现 Token 统计

---

# 6. Tool

Agent 通过 ToolRegistry 使用能力。

例如：

Designer：

simulation.run
experiment.compare
game_config.propose

Tester：

game.launch
test.run
log.collect

Research：

web.search
page.fetch
research.save

Builder：

file.read
file.write
build.run
test.run

---

# 7. Agent Context

每次 Agent Run 必须绑定：

projectId
agentId
runId

必要时：

taskId
stepId

Agent 不允许在没有 Project Context 的情况下修改项目数据。

---

# 8. Agent Memory

Memory 分为：

## Short-term Memory

当前 Run 的：

- Messages
- Tool Calls
- Steps

## Project Memory

持久化：

- Design Documents
- Experiments
- Config Versions
- Test Results
- Research
- Proposals

## Long-term Memory

未来可加入：

- Agent learned preferences
- Historical decisions
- Project patterns

MVP 不实现 Long-term Memory。

---

# 9. Agent 间协作

Agent 不直接修改其他 Agent 的内部状态。

使用：

Project Data
+
Events
+
Tasks
+
Artifacts

进行协作。

---

# 10. Human in the Loop

以下行为必须支持人工审批：

- 修改核心 GameConfig
- 删除数据
- 修改生产游戏
- 发布 Build
- 高风险代码修改

Agent 负责提出 Proposal。

Human 负责批准。

---

# 11. Agent 扩展标准

新增 Agent 必须提供：

1. Agent ID
2. Description
3. System Prompt
4. Tool List
5. Input Contract
6. Output Contract
7. Project Context
8. Approval Policy
9. Error Policy
10. Test Strategy