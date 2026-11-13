# AI-Game-Lab 文档治理规范

版本：v1.0

本文档定义 AI-Game-Lab 项目的文档分类、命名、职责边界、
引用关系以及 AI Agent 修改文档时必须遵守的规则。

本文档属于项目级规范。

---

# 1. 核心原则

AI-Game-Lab 是一个多 Agent、多 Project 的统一代码仓库。

所有 Agent / Project 默认属于同一个 AI-Game-Lab。

禁止因为新增一个 Agent 或 Project 而创建新的独立后端服务，
除非 ARCHITECTURE_RULES.md 明确批准。

---

# 2. 文档分层

所有文档必须属于以下五个层级之一：

1. Architecture
2. Workflow
3. Platform
4. Project
5. State / Decision

禁止创建无法归类的顶层文档。

---

# 3. Architecture 层

目录：

docs/architecture/

职责：

定义整个 AI-Game-Lab 的长期架构规则。

例如：

- 系统总体架构
- Agent 如何运行
- Agent 如何协作
- Project 如何组织
- 部署方式
- 架构不可违反规则

Architecture 文档：

- 不描述某一个具体 Agent 的业务实现
- 不记录某一个 Task 的开发过程
- 不保存临时测试结果
- 不直接替代 Project 技术设计

如果某个规则会影响整个 AI-Game-Lab，
才应该进入 Architecture。

---

# 4. Workflow 层

目录：

docs/workflow/

职责：

定义 AI Agent 如何进行开发。

例如：

- Task 生命周期
- 开发流程
- 自检流程
- 人工验收流程
- 失败修复流程
- 长任务实时汇报
- 中断恢复
- Git 操作规则

Workflow 是“怎么开发”。

Architecture 是“系统应该是什么”。

两者不得混淆。

---

# 5. Platform 层

目录：

docs/platform/

职责：

定义所有 Project 可以共享的平台能力。

例如：

- LLM Gateway
- Agent Runtime
- Tool System
- EventBus
- ProjectContext
- Experiment System
- Simulation
- Config Version
- Proposal / Approval
- Dashboard

Platform 文档描述：

“这个能力是什么，以及所有 Agent 如何使用它。”

禁止在 Platform 文档中加入某个具体 Agent 的业务规则。

---

# 6. Project 层

目录：

docs/projects/

每个 Project 必须拥有独立目录。

例如：

docs/projects/
├── 01_AI_Game_Designer/
├── 02_AI_Game_Tester/
├── 03_AI_Research_Agent/
├── 04_AI_Game_Agent_Team/
└── 05_AI_Auto_Game_Developer/

---

# 7. Project 文档标准结构

每个 Project 原则上使用以下结构：

README.md

产品定义.md

技术设计.md

Agent设计.md

Tool设计.md

Prompt设计.md

开发任务树.md

验收标准.md

并不是所有 Project 必须一次性创建全部文件。

只有真正需要时才创建。

禁止为了“结构完整”创建空文档。

---

# 8. Project README

README.md 只负责回答：

- 这个 Agent 是干什么的
- 解决什么问题
- 输入是什么
- 输出是什么
- 当前完成度
- 如何启动
- 依赖哪些 Platform 能力

README 不记录详细技术实现。

---

# 9. 产品定义

产品定义.md 负责：

- 产品目标
- 用户
- 使用场景
- 核心能力
- MVP
- 非目标
- Future

禁止写具体 Java 类名、数据库字段、API。

---

# 10. 技术设计

技术设计.md 负责：

- 模块结构
- 数据流
- API
- 数据模型
- Runtime 集成
- Tool 集成
- LLM 调用
- 错误处理
- 并发
- 持久化

技术设计必须引用 Platform 文档。

如果已有 Platform 能力可以复用：

禁止重新设计一套。

---

# 11. Agent设计

Agent设计.md 负责：

- Agent Persona
- System Prompt
- 输入上下文
- 输出结构
- Decision Loop
- 状态
- Stop 条件
- 循环保护
- Human Approval

Agent 不应在这里定义基础设施。

---

# 12. Tool设计

Tool设计.md 负责：

- Tool 清单
- Tool 名称
- 输入 Schema
- 输出 Schema
- 权限
- 是否需要审批
- 副作用
- 错误处理

Tool 必须遵守 Platform Tool System。

禁止 Project 自己实现第二套 Tool Registry。

---

# 13. Prompt设计

Prompt设计.md 负责：

- System Prompt
- Developer Prompt
- Task Prompt
- 输出 Schema
- Few-shot
- Prompt Version

Prompt 必须可版本化。

禁止把长期 Prompt 只存在 Java 字符串中。

---

# 14. 开发任务树

开发任务树.md 负责：

- Task ID
- Task 目标
- 前置依赖
- 修改文件
- 实现内容
- 验收标准
- 测试方式
- 并行关系

Task Tree 是开发计划。

它不是代码实现记录。

---

# 15. 验收标准

验收标准.md 负责：

定义 Project 完成后的：

- 功能验收
- API 验收
- Agent 行为验收
- 性能验收
- 安全验收
- 回归验收

必须尽可能可以自动化。

---

# 16. State 文档

目录：

docs/state/

用于记录项目当前状态。

例如：

task-state.md

project-state.md

这里只记录：

- 当前进行中的 Task
- 已完成 Task
- 阻塞 Task
- 技术债
- 当前版本
- 下一步

禁止把设计内容复制到 State。

---

# 17. ADR

目录：

docs/decisions/

ADR = Architecture Decision Record。

只有发生重要架构决策时才创建。

例如：

ADR-001 SQLite 而不是 PostgreSQL

ADR-002 Agent 作为模块而不是微服务

ADR-003 不使用 Kafka

ADR-004 DeepSeek 使用 OpenAI Compatible API

ADR 必须说明：

- 背景
- 选项
- 最终决策
- 原因
- 后果

---

# 18. 技术债

技术债统一登记：

docs/state/tech-debt.md

禁止每个 Project 创建自己的 tech-debt.md，
除非未来确实出现 Project 独立生命周期需求。

---

# 19. 文件命名

Architecture：

00_xxx.md
01_xxx.md

Project：

01_AI_Game_Designer/
02_AI_Game_Tester/

文件名优先使用：

英文项目名 + 中文说明

例如：

01_AI_Game_Designer/
    技术设计.md

避免：

最终版.md
最终版2.md
最新版.md
新版.md
临时.md
TODO.md

---

# 20. 文档优先级

当多个文档冲突时：

1. ARCHITECTURE_RULES.md
2. Architecture 文档
3. Platform 文档
4. Project 技术设计
5. Project 产品定义
6. Task Tree
7. State / 汇报文档

但是：

具体 Task 的明确冻结契约，
优先于同一文档中的旧摘要。

---

# 21. 禁止重复定义

以下内容只能存在一个权威来源：

错误码
Agent Runtime 状态
Tool Registry 契约
LLM Gateway 契约
Event 类型
Project Context
数据库公共模型
API Response Envelope

其他文档必须引用，而不是复制。

---

# 22. Agent 新建文档规则

AI Agent 在创建新文档之前必须判断：

1. 是否已经存在相同职责的文档？
2. 是否应该修改已有文档？
3. 是否属于 Architecture？
4. 是否属于 Platform？
5. 是否属于 Project？
6. 是否只是 Task 状态？

如果无法判断：

停止创建文件，并向人工请求确认。

---

# 23. 修改范围

执行 Task 时：

默认只允许修改：

- Task 明确要求的文件
- Task 必要的测试文件
- 必要的文档同步文件

禁止顺手整理整个 docs。

禁止顺手重命名文件。

禁止顺手重构目录。

---

# 24. 跨 Project 依赖

Project 可以依赖 Platform。

Project 可以调用其他 Project 暴露的标准能力。

但：

Project 不允许直接 import 另一个 Project 的内部实现。

正确：

Project A
    ↓
Platform Interface
    ↓
Project B

错误：

Project A
    ↓
Project B 内部 Java Class

如果发现两个 Project 需要共享能力：

优先将能力上移到 Platform。

---

# 25. Project 不得复制 Platform

如果 AI Game Tester 需要：

LLM

Agent Runtime

Tool Registry

EventBus

ProjectContext

Simulation

则直接复用 Platform。

禁止创建：

TesterLlmGateway
TesterAgentRuntime
TesterToolRegistry

除非经过 ADR 批准。

---

# 26. 数据库原则

所有 Project 默认共享 AI-Game-Lab 数据库。

Project 不得自行创建：

project.db
tester.db
research.db

除非有明确架构决策。

Project 数据通过：

project_id
agent_id
run_id

进行隔离。

---

# 27. 服务原则

默认整个 AI-Game-Lab：

一个 Backend

一个 Frontend

一个 SQLite

多个 Agent Module

多个 Project

而不是：

一个 Agent 一个 Spring Boot

一个 Agent 一个端口

一个 Agent 一个数据库

---

# 28. Git 原则

每个 Project 不创建独立 Git Repository。

统一使用：

AI-Game-Lab

作为 Git Repository。

Project 通过目录隔离。

---

# 29. GitHub 原则

GitHub 保存：

源代码

配置模板

文档

测试

脚本

禁止提交：

真实 API Key

真实数据库

本地缓存

日志

IDE 私有配置

大规模生成数据

---

# 30. 最终原则

AI-Game-Lab 的目标不是：

“做五个互不相关的 AI 项目”。

而是：

“构建一个共享 AI Agent 平台，
然后让多个 Game AI Project 运行在这个平台之上。”

因此：

共享能力进入 Platform。

业务能力进入 Project。

架构规则进入 Architecture。

开发过程进入 Workflow。

状态进入 State。

重大决策进入 ADR。

技术债进入 tech-debt。

任何新文件都必须能够明确回答：

“我属于哪一层？为什么不能放到已有文件？”