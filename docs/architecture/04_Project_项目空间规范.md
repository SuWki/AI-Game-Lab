# Project 项目空间规范

> 本文定义目标架构。当前实现尚无贯穿全域的 project_id，不能据此宣称多游戏数据隔离已完成。这里的 Project 指用户接入的游戏；docs/projects 下 Designer/Tester/Research 等目录是 Lab 能力模块设计，两者不同。一个游戏可以有多个测试目标，targetId 不替代 projectId。产品语义见 [产品设计](EXISTING_GAME_PRODUCT_DESIGN.md)。

## 1. Project 是 AI Game Lab 的一级对象

所有 Agent 围绕 Project 工作。

---

# 2. 一个 Project

例如：

PROJECT ORBITAL

可以包含：

Design
Config
Experiment
Test
Research
Build
Logs
Agent Runs

---

# 3. Agent 与 Project 的关系

一个 Agent 可以服务多个 Project。

例如：

DesignerAgent
├── Project A
├── Project B
└── Project C

---

# 4. Project Context

Agent 启动时获得：

Project ID

Runtime 根据 Project ID 加载：

- 当前设计
- 当前 Config
- 最近 Experiment
- 最近 Test
- 最近 Proposal
- 相关 Artifact

---

# 5. Project 隔离

Agent A 处理：

Project A

不能读取：

Project B

除非明确授予权限。

---

# 6. Project 数据结构

逻辑结构：

Project
├── Documents
├── Configs
├── Experiments
├── Tests
├── Research
├── Builds
├── Proposals
└── Agent Runs

---

# 7. 跨 Agent 数据

Designer 创建：

Experiment #100

Tester 可以读取：

Experiment #100

Research 可以关联：

Experiment #100

Builder 可以读取：

Approved Config #101

因此 Agent 之间不需要复制数据。

---

# 8. 多项目

平台最终可以：

Project A
Project B
Project C

共享：

Agent Runtime
LLM Gateway
Tool Registry

但数据完全隔离。

---

# 9. 当前阶段

当前 AI Game Designer 已有：

Run
Step
Experiment
Config
Proposal
DesignDocument

未来需要正式 Project Entity 时，再通过独立 Task 扩展。

禁止因为本文件而直接修改当前数据库结构。
