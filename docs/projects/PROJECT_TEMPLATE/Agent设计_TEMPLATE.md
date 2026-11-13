# Agent 设计

> 职责边界（DOCUMENTATION_RULES §11）：Agent Persona / Prompt 索引 / Decision Loop / 停止条件。
> 不在这里定义基础设施；一个 Project 可包含多个 Agent。

## Agent 清单

| Agent ID | 职责 | 使用 Tool | 需要审批的行为 |
|---|---|---|---|
| <agent_id> | | | |

## Agent Persona（每个 Agent 一节）

### <Agent 名称>

- **角色**：<一句话 persona>
- **System Prompt**：存放位置与版本号（引用 Prompt设计.md，禁止把长期 Prompt 只存在 Java 字符串中）
- **输入上下文**：<ProjectContext 中加载什么>
- **输出结构**：<输出 Schema 概要>
- **Decision Loop**：<Observe→Plan→Act 的循环方式>
- **Stop 条件**：<何时认为任务完成>
- **循环保护**：最大步数 / 最大 Token / 最大运行时间（必须设置，见 03_Agent_之间协作规范 §10）
- **Human Approval**：哪些行为必须等待人工批准
