# AI Game Lab 文档索引

首次使用先看 [README](../README.md) 和[构建环境](BUILD_ENVIRONMENT.md)。开发从[进度摘要](state/task-state.md)进入，后续排期以[开发路线](design/DEVELOPMENT_ROADMAP.md)顶部为准。最近一次[整体设计与代码审查](reports/PLATFORM_REVIEW_2026-11-19.md)记录五模块的实际交付边界、风险和关闭条件。

## 按工作任务导航

| 我要做什么 | 从哪里开始 |
| --- | --- |
| 理解平台定位与分工 | [两条产品主线与五模块](architecture/GAME_LAB_PRODUCT_DESIGN.md) · [生产交接契约](platform/GAME_PRODUCTION_CONTRACT.md) |
| 分析和改进已有游戏 | [接入范围](architecture/EXISTING_GAME_PRODUCT_DESIGN.md) · [本地测试执行](platform/LOCAL_GAME_TEST_EXECUTION.md) · [可信验证设计](platform/IMPROVEMENT_VALIDATION_DESIGN.md) · [game-demo 样例](reports/GAME_DEMO_IMPLEMENTATION_2026-11-12.md) |
| 从规格制作新游戏 | [版本化产物](platform/PRODUCTION_ARTIFACTS.md) · [规格编辑](reports/SPECIFICATION_EDITOR_2026-11-17.md) · [可玩模板](platform/PLAYABLE_TEMPLATE.md) · [生产任务](platform/PRODUCTION_JOBS.md) |
| 运行 Builder 与审批修改 | [身份与工具](platform/BUILDER_RUNTIME.md) · [跨 Run 会话与预算](platform/BUILDER_SESSIONS.md) · [生产修复链](platform/PRODUCTION_ITERATIONS.md) · [独立引擎验收](platform/PRODUCTION_SOURCE_REVIEW.md) |
| 判断能否交付 | [固定候选与预览](platform/PRODUCTION_CANDIDATES.md) · [浏览器验收](platform/CANDIDATE_BROWSER_EVIDENCE.md) · [逐项人工评审](platform/CANDIDATE_HUMAN_REVIEW.md) · [本地验收记录](reports/SELF_ACCEPTANCE_2026-11-17.md) |
| 运行平台或查看公开预览 | [共享运行时与恢复](platform/AGENT_RUNTIME_RECOVERY.md) · [GitHub Pages 静态预览](platform/PAGES_PREVIEW.md) |
| 查模块设计与历史证据 | [Designer](projects/01_AI_Game_Designer/README.md) · [Tester](projects/02_AI_Game_Tester/README.md) · [Research](projects/03_AI_Research_Agent/README.md) · [QA](projects/04_AI_Game_Agent_Team/README.md) · [Builder](projects/05_AI_Auto_Game_Developer/README.md) · [历次报告目录](reports/) |

### 治理与流程

| 文档 | 性质 | 说明 |
|---|---|---|
| [BUILD_ENVIRONMENT.md](BUILD_ENVIRONMENT.md) | 运行与构建 | 工具链、JAVA_HOME、依赖安装、测试命令及环境自检的覆盖边界。 |
| [architecture/DOCUMENTATION_RULES.md](architecture/DOCUMENTATION_RULES.md) | **文档治理（主规范）** | 文档五分层（Architecture/Workflow/Platform/Project/State）、命名、引用、优先级、禁止重复定义。新建任何文档前必读。 |
| [architecture/06_文档与代码归属规范.md](architecture/06_文档与代码归属规范.md) | 治理细则 | "什么东西放在哪里"判断表 + 代码包归属 + **文档迁移执行记录（2026-09-01 已完成）**。 |
| [state/工作流.md](state/工作流.md) | 开发流程 v2 | AI 自主连续执行 + Sprint Checkpoint。Task 生命周期 / Autonomy Boundary / 自动修复循环 / task-state 断点恢复。 |
| [state/task-state.md](state/task-state.md) | 进度状态 | AI 自主开发的唯一进度事实来源；断点恢复从这里开始。 |
| [state/tech-debt.md](state/tech-debt.md) | 技术债登记 | 全项目唯一技术债登记处。 |

### 平台架构

| 文档 | 性质 | 说明 |
|---|---|---|
| [architecture/00_AI_Game_Lab_总架构.md](architecture/00_AI_Game_Lab_总架构.md) | 架构 / 平台愿景 | 统一 Agent 平台总架构：一个仓库、Agent 是模块不是服务、Project 核心概念、部署演进。 |
| [architecture/01_Agent_平台架构.md](architecture/01_Agent_平台架构.md) | 架构规范 | Agent 定义/生命周期/统一 Runtime/LLM Gateway/Tool/Memory/Human in the loop。 |
| [architecture/02_共享基础设施规范.md](architecture/02_共享基础设施规范.md) | 架构规范 | Platform 共享模块边界：llm/tool/event/experiment/project，禁止复制实现。 |
| [architecture/03_Agent_之间协作规范.md](architecture/03_Agent_之间协作规范.md) | 架构规范 | 跨 Agent 协作链、Artifact 传递、循环保护、失败处理。 |
| [architecture/04_Project_项目空间规范.md](architecture/04_Project_项目空间规范.md) | 架构规范 | Project 一级对象、ProjectContext、多项目隔离。当前阶段禁止因此改库。 |
| [architecture/05_部署与运行架构.md](architecture/05_部署与运行架构.md) | 架构规范 | 单体优先、SQLite→PostgreSQL 演进、Worker 化条件。 |
| [architecture/ARCHITECTURE_RULES.md](architecture/ARCHITECTURE_RULES.md) | **架构红线** | 12 条不可违反规则：一个仓库/模块非服务/统一入口/单体优先/SQLite 优先等。 |

### Project：01 — AI Game Designer

| 文档 | 性质 | 说明 |
|---|---|---|
| [projects/01_AI_Game_Designer/产品定义.md](projects/01_AI_Game_Designer/产品定义.md) | 需求概述 | AI Game Designer 设计要点：8 个 Tool、Observe→Plan→Act 循环、决策六问、数值目标配置化、修改审计。 |
| [projects/01_AI_Game_Designer/技术设计.md](projects/01_AI_Game_Designer/技术设计.md) | **工程蓝图（主文档）** | 23 节完整技术设计：架构、目录、数据模型、10 张表 DDL、REST/SSE 端点、Agent Runtime 状态机、LLM Gateway、Tool Registry、模拟器、实验系统、Config 版本、事件/审批、前端页面、System Prompt、任务树、MVP 验收。**直接指导编码。** |
| [projects/01_AI_Game_Designer/开发任务树.md](projects/01_AI_Game_Designer/开发任务树.md) | 执行计划 | P0–P8 全部 Task 逐个展开（ID/依赖/文件/实现/验收/测试/可否并行）+ 7 项独立性专项检查 + 完整依赖图与并行泳道。 |

### Project：02 — AI Game Tester

| 文档 | 性质 | 说明 |
|---|---|---|
| [projects/02_AI_Game_Tester/产品定义.md](projects/02_AI_Game_Tester/产品定义.md) | 需求 | AI Game Tester 需求文档。 |
| [projects/02_AI_Game_Tester/架构设计.md](projects/02_AI_Game_Tester/架构设计.md) | 总体设计 | Tester 总体设计（需求展开为可实施架构）。 |
| [projects/02_AI_Game_Tester/技术设计.md](projects/02_AI_Game_Tester/技术设计.md) | **工程蓝图（主文档）** | Tester 技术设计：GameAdapter SPI、FakeGameEnvironment、InvariantEngine、TestPlan/Finding 数据模型、8 个 Tool、与 Designer 共享 Runtime。 |
| [projects/02_AI_Game_Tester/开发任务树.md](projects/02_AI_Game_Tester/开发任务树.md) | 执行计划 | T1–T7 全部 Task 逐个展开。 |
| [projects/02_AI_Game_Tester/T-DB1_DDL_Review.md](projects/02_AI_Game_Tester/T-DB1_DDL_Review.md) | DDL 评审 | T-DB1 两表 DDL + 列明细/映射/索引/外键论证/SQLite 兼容/Migration 方式。 |

### Project：03 — AI Research Agent

| 文档 | 性质 | 说明 |
|---|---|---|
| [projects/03_AI_Research_Agent/产品定义.md](projects/03_AI_Research_Agent/产品定义.md) | 需求 | AI Research Agent 愿景文档。 |
| [projects/03_AI_Research_Agent/开发任务树.md](projects/03_AI_Research_Agent/开发任务树.md) | 执行计划 | R0 Research Agent Infrastructure 开发任务树（R0-1 ~ R0-8）。 |

Research 后续实现记录见 [R1](design/R1_Completion_Report.md)、[R2](design/R2_Completion_Report.md) 和 [R2 收口](design/R2_Closure_Report.md)；R0 任务树只覆盖初始基础设施。

### QA 系统：设计、实现与审计

| 文档 | 用途 |
|---|---|
| [QA_SYSTEM_ARCHITECTURE_PROPOSAL.md](QA_SYSTEM_ARCHITECTURE_PROPOSAL.md) / [QA_ARCHITECTURE_FREEZE.md](QA_ARCHITECTURE_FREEZE.md) | 提案与冻结边界，理解 QA 各阶段的来源 |
| [QA_PHASE2_ARCHITECTURE.md](QA_PHASE2_ARCHITECTURE.md) / [QA_PHASE2_ARCHITECTURE_DECISIONS.md](QA_PHASE2_ARCHITECTURE_DECISIONS.md) | Phase 2 架构与决策；注意文内历史审计发现的时点 |
| [architecture/07_QA_Phase2_Architecture.md](architecture/07_QA_Phase2_Architecture.md) | Phase 2 平台架构补充 |
| [reports/R5_QA_PHASE2_IMPLEMENTATION_REPORT.md](reports/R5_QA_PHASE2_IMPLEMENTATION_REPORT.md) / [reports/R6_QA_PHASE2_CLOSURE_AUDIT.md](reports/R6_QA_PHASE2_CLOSURE_AUDIT.md) | Phase 2 实现与收口审计 |
| [QA_PHASE3_ARCHITECTURE_DESIGN.md](QA_PHASE3_ARCHITECTURE_DESIGN.md) | Phase 3 冻结决策 HD-1..7 与 share 派生设计 |
| [QA_PHASE3_IMPLEMENTATION_REPORT.md](QA_PHASE3_IMPLEMENTATION_REPORT.md) | Phase 3 接线与实现说明 |
| [QA_PHASE3_EXPERIMENT_SELECTION_CONTRACT_AUDIT.md](QA_PHASE3_EXPERIMENT_SELECTION_CONTRACT_AUDIT.md) | latest-DONE 实验选择语义与 NO_CONTRACT 审计结论 |
| [reports/QA_PHASE3_CLOSURE_AUDIT.md](reports/QA_PHASE3_CLOSURE_AUDIT.md) | Phase 3 收口审计：16/16 PASS，未自动启动下一阶段 |
| [reports/R7_POST_QA_PHASE2_ARCHITECTURE_ROADMAP_AUDIT.md](reports/R7_POST_QA_PHASE2_ARCHITECTURE_ROADMAP_AUDIT.md) / [reports/R8_DOCUMENTATION_INFRASTRUCTURE_HOUSEKEEPING.md](reports/R8_DOCUMENTATION_INFRASTRUCTURE_HOUSEKEEPING.md) | 架构风险、路线候选与文档整理的历史依据 |

### Sprint 报告（历史）

| 文档 | 说明 |
|---|---|
| [reports/FINAL_RELEASE_CANDIDATE_REPORT.md](reports/FINAL_RELEASE_CANDIDATE_REPORT.md) | Final Release Candidate 审计报告 |
| [reports/P8_Dependency_Scope_Audit.md](reports/P8_Dependency_Scope_Audit.md) | P8 依赖范围审计 |
| [reports/Sprint_P8-2_Checkpoint_Report.md](reports/Sprint_P8-2_Checkpoint_Report.md) | Sprint P8-2 Checkpoint |
| [reports/Sprint_P8-1_Checkpoint_Report.md](reports/Sprint_P8-1_Checkpoint_Report.md) | Sprint P8-1 Checkpoint |
| [reports/Sprint_P7_Checkpoint_Report.md](reports/Sprint_P7_Checkpoint_Report.md) | Sprint P7 Checkpoint |
| [reports/Sprint_P6-4_Checkpoint_Report.md](reports/Sprint_P6-4_Checkpoint_Report.md) | Sprint P6-4 Checkpoint |
| [reports/Sprint_T7_Checkpoint_Report.md](reports/Sprint_T7_Checkpoint_Report.md) | Sprint T7 Checkpoint（Tester 线收口） |
| [reports/P5_experiment_service_report.md](reports/P5_experiment_service_report.md) | P5 Experiment Service 实验报告 |
| [reports/P3_incremental_report.md](reports/P3_incremental_report.md) | P3 增量实验报告 |
| [reports/P3-4_experiment_document_tools_report.md](reports/P3-4_experiment_document_tools_report.md) | P3-4 文档工具实验报告 |

### 归档（愿景原稿，当前实现以专项设计与代码为准）

| 文档 | 说明 |
|---|---|
| [archive/00_总架构与中台设计.md](archive/00_总架构与中台设计.md) | 总体愿景原稿；Tester、Research 与 QA 后续已分别实现，仍采用 Maven+SQLite 单体。 |
| [archive/03_AI游戏测试团队.md](archive/03_AI游戏测试团队.md) | QA Team 愿景原稿；现有团队能力请读上方 QA 专项文档。 |
| [archive/05_AI自动做游戏Agent.md](archive/05_AI自动做游戏Agent.md) | Autonomous Builder 愿景。 |

### Project 模板

| 文档 | 说明 |
|---|---|
| [projects/PROJECT_TEMPLATE/](projects/PROJECT_TEMPLATE/README.md) | 新 Project 从此复制起步：README/产品定义/技术设计/Agent设计/Tool设计/任务树/验收标准模板。 |

## 阅读顺序建议

1. 先看 `architecture/DOCUMENTATION_RULES.md` 和 `architecture/ARCHITECTURE_RULES.md` 理解治理红线。
2. 再看 `state/工作流.md` 理解开发流程。
3. 读 `state/task-state.md` 顶部当前摘要，再按所需模块阅读设计；下方 Sprint 记录保留历史验收语境。
4. 五模块先读各自 README、产品定义和演进/执行设计，再查历史技术基线与任务树。
5. QA 先读正式模块目录，再按需追溯 Phase 2/3 决策；Builder 从正式目录进入，归档概念稿不再作为实施蓝图。
6. `archive/` 仅作方向与来源参考；入口文档描述当前代码，不能作为新阶段自动授权。

## 关键设计约束（第一阶段红线）

- 语言/后端：Java 21 + Spring Boot 3.3 + Maven。
- 前端：React + TypeScript + Vite；当前 package.json 未引入 ECharts。
- 持久化：**SQLite**（禁止 PostgreSQL/Redis）。
- AI：统一 `LlmGateway` → DeepSeek（OpenAI 兼容 REST，**不引 SDK**）。
- 实时：Spring Event + SSE + SQLite 事件表（**禁止 Kafka/消息队列**）。
- 禁止：K8s / 微服务 / 多租户 / IAM / 云原生 / 复杂 DevOps。
- 四条不可妥协的红线：**可复现、可观测、可追踪、可回滚**。

## 与两份源文档的冲突裁决

| 源文档说法 | 本阶段裁决 | 理由 |
|---|---|---|
| PostgreSQL / Redis | 改 SQLite | 个人开发者零部署优先 |
| 微服务 / K8s | 单 Jar | 禁止过度工程化 |
| Build 列表含 mobility | 用 crit / attack_speed / penetration | 以需求描述为准 |
| 流式输出 | 第一阶段不做 | 无逐 token 消费方 |

## 目录结构（当前）

```
ai-game-lab/
├── docs/                # 文档索引 + 治理 + 各 Project 文档
│   ├── index.md         # 本文件
│   ├── architecture/    # 平台架构规范（00-07 + 规则）
│   ├── projects/        # 模板 + Designer / Tester / Research / QA / Builder
│   ├── design/          # R0-R3 设计、实现与收口记录
│   ├── state/           # 工作流 / task-state / tech-debt
│   ├── reports/         # Sprint Checkpoint 与实验报告（历史）
│   └── archive/         # 远期愿景归档
├── scripts/             # 启动、环境自检与数据库重置
├── data/                # 从根目录启动后端时的相对数据库位置
├── backend/             # Spring Boot 单 Jar（Java 21 / Maven）
└── frontend/            # React + TS + Vite
```

开发脚本在 `backend/` 启动后端，默认数据库为 `backend/data/agl.db`；相对路径与重置范围详见 [README](../README.md)。根目录 `docs/QA_*.md` 保留原位置，由本索引提供入口。
