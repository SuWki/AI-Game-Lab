# Agent 协作规范

## 1. 协作目标

AI Game Lab 最终不是多个独立 Agent，而是多个 Agent 围绕同一个 Game Project 协同工作。

---

# 2. 标准协作链

Research
↓
Designer
↓
Simulation
↓
Tester
↓
Designer
↓
Human Approval
↓
Builder
↓
Regression Tester

---

# 3. 示例：平衡性调整

Designer：

发现：

Crit Build 胜率过高。

↓

创建：

Experiment

↓

Simulation：

运行 10,000 次。

↓

结果：

Crit win rate = 68%

↓

Designer：

创建：

ChangeProposal

↓

Human：

Approve

↓

Tester：

执行 Regression Test

↓

Tester：

发现某 Boss 战异常。

↓

Designer：

重新分析。

---

# 4. Agent 不直接“命令”另一个 Agent

错误：

Designer
→ Tester.runInternal()

正确：

Designer
→ 创建 Test Task
→ Tester 读取 Task
→ Tester 执行
→ 写入 Test Result

这样可以保证：

- 可追踪
- 可恢复
- 可审计
- 可重试

---

# 5. Agent Event

所有重要协作行为写入 AgentEvent。

例如：

Designer：

DESIGN_CREATED

Tester：

TEST_REQUESTED

Tester：

TEST_COMPLETED

Designer：

TEST_RESULT_RECEIVED

---

# 6. Artifact 传递

Agent 之间主要通过 Artifact 传递结果。

例如：

Designer
→ DesignDocument

Tester
→ TestReport

Research
→ ResearchReport

Builder
→ BuildArtifact

---

# 7. 异步协作

第一阶段可以同步调用 Service。

未来如果任务耗时较长：

Agent
↓
Task
↓
Worker
↓
AgentEvent

不需要第一阶段引入 Kafka。

---

# 8. Agent Workflow

未来可以定义：

Workflow：

Research → Design → Test → Build

Workflow 不是新的 Agent Runtime。

Workflow 负责：

- 编排 Agent
- 条件判断
- 重试
- 人工审批

AgentRuntime 负责：

- 单个 Agent 的执行。

---

# 9. 协作失败

如果 Agent B 失败：

Agent A 不应该直接崩溃。

应产生：

Task FAILED

并由 Workflow 决定：

- Retry
- Resume
- Escalate
- Human Approval

---

# 10. 循环保护

禁止无限：

Designer
→ Tester
→ Designer
→ Tester

必须存在：

- 最大循环次数
- 最大 Token
- 最大运行时间
- 最大 Task 数

超过限制进入：

BLOCKED