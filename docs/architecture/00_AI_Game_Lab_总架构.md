# AI Game Lab 总架构

## 1. 文档目的

AI Game Lab 是一个面向 AI 辅助游戏研发的统一 Agent 平台。

产品包括已有游戏分析改进与同类型新游戏设计制造两条主线。统一 Agent 基础设施服务于“研究—设计—制造—测试—交付”及“问题—证据—修改—验证”。范围、模块职责与成功标准见 [产品总设计](GAME_LAB_PRODUCT_DESIGN.md)。下文 ProjectContext、多项目和 Builder 包含目标架构，不能视为全部已实现；状态见 [task-state](../state/task-state.md)。

本项目不是多个相互独立的 AI Demo，而是一个可以持续扩展 Agent 能力的统一研发平台。

平台的核心目标：

1. 让不同 AI Agent 共享同一套基础设施。
2. 让 Agent 可以访问同一个 Game Project 的设计、配置、实验、测试和研究数据。
3. 支持多个 Agent 之间协作。
4. 支持人类对关键决策进行审批。
5. 支持真实游戏接入，以版本化证据验证测试与经授权的修改。
6. 尽量避免微服务化和重复基础设施。

---

# 2. 核心架构原则

## 2.1 一个仓库

所有 Agent 默认属于同一个 AI Game Lab Repository。

禁止为了新增 Agent 而复制整个项目。

错误：

AI-Game-Designer/
AI-Game-Tester/
AI-Research-Agent/

正确：

AI-Game-Lab/
├── agents/
├── core/
├── backend/
├── frontend/
├── projects/
└── docs/

---

## 2.2 Agent 是模块，不是服务

第一阶段所有 Agent 默认运行在同一个 Backend JVM 中。

例如：

DesignerAgent
TesterAgent
ResearchAgent

都复用：

AgentRuntime
LlmGateway
ToolRegistry
EventSystem
Repository
ProjectContext

禁止默认“一 Agent 一 Spring Boot”。

只有出现明确的性能、隔离或部署需求时，才允许将 Agent 拆成独立 Worker。

---

# 3. 平台总体结构

                    AI GAME LAB
                         |
          +--------------+--------------+
          |              |              |
      Designer        Tester        Research
       Agent           Agent          Agent
          |              |              |
          +--------------+--------------+
                         |
                  Agent Runtime
                         |
        +----------------+----------------+
        |                |                |
   LLM Gateway      Tool Registry      Event Bus
        |                |
    DeepSeek       Shared Tools
                         |
        +----------------+----------------+
        |                |                |
   Simulation       Experiment       Game Config
        |                |                |
        +----------------+----------------+
                         |
                    Project Data
                         |
                      SQLite

---

# 4. Agent 分类

## 4.1 AI Game Designer

负责：

- 游戏设计
- 数值分析
- Build 分析
- 实验设计
- 配置修改提案
- 设计文档生成

---

## 4.2 AI Game Tester

负责：

- 自动测试
- 回归测试
- 边界测试
- Bug 复现
- 日志分析

---

## 4.3 AI Game Test Team

不是简单复制 Tester。

它是多个角色 Agent 的协作系统。

例如：

Designer
QA
Balance Analyst
Exploit Hunter
Regression Tester

通过共享 Project Context 协作。

---

## 4.4 AI Research Agent

负责：

- Web Research
- 游戏竞品研究
- 市场资料整理
- 机制分析
- 文献和资料归档

---

## 4.5 AI Game Builder

负责：

- 根据设计生成代码
- 修改游戏
- 构建游戏
- 自动测试
- 根据测试结果继续修改

它是平台后期最复杂的 Agent。

已有游戏受控修改已提供共享基础。新作生产按 [Builder 正式设计](../projects/05_AI_Auto_Game_Developer/README.md) 补模板、规格消费、生产执行与候选交付；复用 Runtime 与审批，不建设独立后端服务。

---

# 5. Project 是平台核心概念

所有 Agent 不应该直接围绕“自己拥有的数据”工作。

所有 Agent 都围绕：

Project

工作。

例如：

PROJECT ORBITAL

可以包含：

- Design Documents
- Game Config
- Experiments
- Test Results
- Research
- Proposals
- Build
- Logs
- Agent Runs

因此：

Designer → Project A

Tester → Project A

Research → Project A

Builder → Project A

它们看到的是同一个项目。

---

# 6. 数据共享原则

Agent 不直接共享内存状态。

Agent 之间通过：

1. Project 数据
2. Repository
3. Agent Event
4. Tool
5. Agent Run
6. Proposal

共享信息。

禁止：

Agent A 直接持有 Agent B 的内部对象。

---

# 7. Agent 协作

第一阶段：

Agent A
    |
    v
共享 Service / Tool
    |
    v
Project Data
    |
    v
Agent B

第二阶段：

Agent A
    |
    v
Agent Runtime
    |
    v
Agent B

第三阶段：

多个 Agent 可以形成 Workflow。

例如：

Research
→ Designer
→ Simulation
→ Tester
→ Designer
→ Human Approval
→ Builder
→ Regression Test

---

# 8. 服务数量原则

开发阶段默认：

Frontend
+
Backend
+
SQLite

其中 SQLite 不视为独立服务。

Agent 不单独启动服务。

后期如果需要：

Backend
├── API
├── Agent Runtime
└── Worker

仍然优先保持最少服务数量。

---

# 9. 部署演进

## Phase 1

本地：

Browser
↓
Frontend
↓
Spring Boot
↓
SQLite

---

## Phase 2

单服务器：

Browser
↓
Nginx
↓
Spring Boot
↓
SQLite

---

## Phase 3

多人/高并发：

Browser
↓
Nginx
↓
Backend
↓
PostgreSQL

Worker 可独立扩展。

---

# 10. 数据库迁移原则

SQLite 是 MVP 默认数据库。

不要因为未来可能使用 PostgreSQL 而提前引入：

- Redis
- Kafka
- PostgreSQL
- Elasticsearch

只有实际出现需求时再升级。

Repository 必须保持数据库访问抽象。

目标：

SQLite
→ PostgreSQL

尽量不改变业务层。

---

# 11. 核心原则

以后新增任何 Agent，都必须回答：

1. 能否复用 AgentRuntime？
2. 能否复用 LlmGateway？
3. 能否复用 ToolRegistry？
4. 能否复用 ProjectContext？
5. 能否复用 Event？
6. 能否复用 Experiment？
7. 能否复用 Repository？
8. 是否真的需要新服务？

如果答案是“可以复用”，禁止复制实现。
