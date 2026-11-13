# AI Game Lab Architecture Rules

## Rule 1：一个平台

所有 AI Agent 属于同一个 AI Game Lab。

禁止复制项目。

---

## Rule 2：Agent 是模块，不是服务

默认所有 Agent 运行在同一个 Backend。

禁止无理由增加 Spring Boot 服务。

---

## Rule 3：基础设施共享

必须优先复用：

- AgentRuntime
- LlmGateway
- ToolRegistry
- Event
- Repository
- Experiment
- Project
- Approval

---

## Rule 4：数据共享通过 Project

Agent 不直接共享内部状态。

通过：

Project
Task
Artifact
Event
Repository

协作。

---

## Rule 5：LLM 统一入口

所有模型调用必须经过：

LlmGateway。

---

## Rule 6：Tool 统一入口

所有工具必须经过：

ToolRegistry。

---

## Rule 7：人类负责关键决策

Agent 可以：

分析
提出方案
执行实验
创建 Proposal

但关键修改必须：

Human Approval。

---

## Rule 8：先单体，后拆分

没有真实需求：

不引入微服务
不引入 Kafka
不引入 Redis
不引入 Kubernetes

---

## Rule 9：SQLite 优先

MVP 使用 SQLite。

只有真实需求出现后才迁移 PostgreSQL。

---

## Rule 10：新增 Agent 必须复用平台

新增 Agent 前必须回答：

1. 我复用了哪些现有能力？
2. 我为什么需要新的 Tool？
3. 我为什么需要新的数据库表？
4. 我为什么需要新的服务？
5. 我为什么不能复用现有 AgentRuntime？

如果无法回答，不允许开始开发。

---

## Rule 11：未来 Agent 必须能协作

所有 Agent 都应该围绕 Project 工作。

最终目标：

Research
→ Design
→ Simulation
→ Test
→ Build
→ Regression

形成完整闭环。

---

## Rule 12：禁止为了未来而过度设计

未来架构是方向，不是当前 Task。

只实现当前 Task 必需的能力。


# File Creation Rule

AI 在创建任何新文件之前必须回答：

1. 这个文件属于哪个 Architecture Layer？
2. 是否已经存在承担相同职责的文件？
3. 为什么不能修改已有文件？
4. 这个文件属于 Platform 还是 Project？
5. 是否会被其他 Project 复用？
6. 如果会复用，为什么不进入 Platform？
7. 是否属于当前 Task 的必要修改范围？

如果以上问题无法回答：

禁止创建文件。

---

# One Responsibility Rule

一个文件只能承担一个明确职责。

禁止：

一个 md 同时承担产品定义 + 技术设计 + Task 状态。

---

# Single Source of Truth

同一事实只能有一个权威来源。

例如：

错误码 → ErrorCodes.java / Error Contract

Agent 状态 → Agent Runtime Design

Tool 契约 → Tool System

Task 状态 → task-state.md

技术债 → tech-debt.md

架构决策 → ADR

其他文档只能引用。

---

# No Duplicate Infrastructure

禁止 Project 复制：

LlmGateway
AgentRuntime
ToolRegistry
EventBus
ApiResp
GlobalExceptionHandler
ProjectContext

---

# No Premature Abstraction

没有实际消费者时：

不要为了“未来可能使用”创建抽象层。

至少出现两个明确消费者，
或者架构设计明确要求共享，
才考虑提升到 Platform。