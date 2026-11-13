# QA System Architecture Proposal — AI Game Lab Phase 2 QA Team

> 2026-11-12 范围与状态说明：本文件保留对应阶段的设计/决策与准入条件，不能以其中历史表数、无编排、无回放或预算成功描述判断当前实现。当前模块入口见 [QA 设计](projects/04_AI_Game_Agent_Team/README.md)，共享 Runtime 的已授权变更见 [ADR](decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)。新生产评估以模块演进设计为准，旧 Gate 指标不因此重解释。

> **文档定位**：Phase 2 QA/AI Game Test Team 的完整系统架构设计提案，供人工审查决策。
> **约束**：本文档是纯设计文档，严禁直接编写 QA 生产代码、创建 QA Tool/Agent、修改 AgentLoop、修改 Designer/Tester/Research、修改 schema.sql 或 Repository 或 Error Contract、创建实际 Sprint 文件、启动 Sprint、修改任何已有冻结契约。
> **产出物**：`docs/QA_SYSTEM_ARCHITECTURE_PROPOSAL.md`
> **日期**：2026-09-01
> **状态**：DRAFT — WAITING FOR HUMAN REVIEW

---

## 目录

1. [执行摘要](#1-执行摘要)
2. [Frozen 基线](#2-frozen-基线)
3. [Gap Analysis：现有平台 vs QA Team 需求](#3-gap-analysis)
4. [架构一致性报告](#4-架构一致性报告)
5. [设计文档一致性报告](#5-设计文档一致性报告)
6. [核心架构方案：Intra-Run LLM Orchestration](#6-核心架构方案)
7. [基础设施改造方案](#7-基础设施改造方案)
8. [AgentType / AgentTypeResolver 扩展方案](#8-agenttype--agenttyperesolver-扩展方案)
9. [Prompt 设计方案](#9-prompt-设计方案)
10. [Tool 设计方案](#10-tool-设计方案)
11. [DB Schema 扩展方案](#11-db-schema-扩展方案)
12. [API 扩展方案](#12-api-扩展方案)
13. [前端扩展方案](#13-前端扩展方案)
14. [七角色详细设计](#14-七角色详细设计)
15. [数据流设计](#15-数据流设计)
16. [依赖图与 Sprint Task Tree](#16-依赖图与-sprint-task-tree)
17. [E2E 验收剧本](#17-e2e-验收剧本)
18. [技术债影响分析](#18-技术债影响分析)
19. [非阻塞建议：Phase 2 Builder Agent](#19-非阻塞建议)
20. [附录](#20-附录)

---

## 1. 执行摘要

### 1.1 项目目标

在 AGL Phase 1 冻结基线之上，设计一个多 Agent 游戏测试团队（QA Team），由 QA Lead 统一协调，支持以下工作流：

```text
用户创建 QA Task（指定 configVersion + 期望 Build 配置）
    → QA Lead 编译测试计划（为每个角色分配子任务）
    → 各角色并行执行（Player Agent 操作游戏 / Analyst 分析数据）
    → QA Lead 汇总结果（Finding 集合 + 数值分析 + UI 评估）
    → Release Gate 判定（PASS / CONDITIONAL_PASS / FAIL）
    → 生成 Release Report
    → 人工审阅/批准
```

### 1.2 关键设计决策

| # | 决策 | 方案 | 理由 |
|---|------|------|------|
| D1 | 多 Agent 编排方式 | **Intra-Run LLM Orchestration**（QA Lead 在单个 Run 内通过 Tool 调用分发子任务） | 零 Runtime 修改，复用现有 AgentLoop，最大兼容冻结契约 |
| D2 | QA 角色实现 | **AgentType 扩展**（QA_LEAD / QA_TESTER / QA_RESEARCHER / QA_ANALYST） | 严格隔离 prompt+tool，符合 01_Agent 平台架构 §6 Agent Tool Set 设计 |
| D3 | 子任务间数据共享 | **通过共享 Task/Run 的数据库表**（Finding/Experiment/Research Claim） | 复用既有 Repository + SSE，无需新建通信通道 |
| D4 | Player Agent 三人格（MVP） | **PlayerProfile 差异经 Prompt 驱动**（NOVICE / AGGRESSIVE / BUILD_SPECIALIST），不扩展 gameenv 枚举 | 复用现有 GameAdapter SPI + FakeGameEnvironment，**零后端改动** |
| D5 | Release Gate | **QA Lead Prompt 驱动 + Server-side 校验**（Lead 在 Prompt 中执行阈值判定，Server 验证一致性） | 双重保障，LLM 语义判断 + 确定性校验 |
| D6 | UX Reviewer 范围 | **纯文本/JSON 分析**（不依赖截图/CV，基于 Finding + Experiment 数据评估 UX 问题） | 第一阶段不做 CV/OCR，与 Tester 架构设计 §2 对齐 |

### 1.3 预计范围

| 维度 | 数量 |
|------|------|
| 新增 AgentType | 4（QA_LEAD / QA_TESTER / QA_ANALYST / QA_RESEARCHER） |
| 新增 Tool | 4（仅编排工具：qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings；其余角色全部复用既有白名单；Release Manager ×2 为幽灵项——职责已并入 QA Lead，无专属工具） |
| 新增 DB 表 | 2（qa_role_assignment + qa_release_gate） |
| 新增 Prompt | 8（每角色 1 个：qa_lead + 3 player + combat + numerical + exploit + ux） |
| 新增前端页 | 1（QA Dashboard） |
| 新增 REST 端点 | ~8 |
| 预计 Sprint 数 | 5（QA-S1 基础设施 / QA-S2 Tools / QA-S3 Lead / QA-S4 E2E / QA-S5 前端+收尾） |

### 1.4 关键约束与不可变项

以下项目在 QA 设计中**绝对不可修改**：

| 类别 | 冻结项 |
|------|--------|
| Runtime | AgentLoop / RunStateMachine / BudgetGuard / LoopDetector / ToolExecutor / AgentTypeResolver（仅追加，不改现有路由逻辑） |
| LLM | LlmGateway / DeepSeekClient / GatewayRetry / PromptService（仅追加 import 方法） |
| Tool | ToolRegistry / GameTool 接口 / ToolResult / ToolContext / ToolException |
| Domain | Run / Step / ToolCall / Task / AgentEvent / Experiment / ChangeProposal / ConfigVersion |
| DB | schema.sql 现有 14 张表的 DDL 不可修改；ErrorCodes 常量不可修改 |
| Events | AgentEventType 现有 26 枚举不可修改（仅追加） |
| API | 现有 28 端点不可修改（仅追加） |
| Design | Tester V1 冻结契约（T4/T5/T6/T7） / Designer P0-P8 冻结契约 / Research R0 冻结契约 |
| Frontend | 现有 6 个页面不可修改（仅追加） |

---

## 2. Frozen 基线

### 2.1 代码状态

| 指标 | 值 |
|------|---|
| 最终状态 | RELEASE CANDIDATE — WAITING_FOR_FINAL_ACCEPTANCE |
| Designer Sprints | P0-P8：全部 DONE |
| Tester Sprints | T1-T7：全部 FROZEN |
| Research | R0：FULLY COMPLETE |
| 后端测试 | 403 tests / 0 failures / 25 errors（pre-existing） |
| 前端测试 | 16/16 PASS + tsc clean + build OK |
| 生产代码文件 | ~66 个 Java 文件 |
| 前端文件 | ~15 个 TSX/TS 文件 |

### 2.2 数据库状态（14 张表）

| 表名 | 来源 | 用途 |
|------|------|------|
| task | P0-3 | 用户目标 |
| run | P0-3 | Agent 执行 |
| step | P0-3 | LLM 循环 |
| tool_call | P0-3 | 工具调用 |
| experiment | P0-3 | 可复现实验 |
| game_config_version | P0-3 | 配置版本（append-only） |
| change_proposal | P0-3 | 变更提案 |
| agent_event | P0-3 | 事件流 |
| design_document | P0-3 | 设计文档/报告 |
| prompt_version | P0-3 | System Prompt 版本 |
| test_plan | T-DB1 | 测试计划 |
| finding | T-DB1 | 缺陷/发现 |
| research_source | R0-3 | 研究来源 |
| research_claim | R0-3 | 研究断言 |

### 2.3 工具注册（22 个）

| 类别 | 工具 | AgentType |
|------|------|-----------|
| Designer (8) | get_game_design, get_game_config, modify_game_config, create_change_proposal, simulate_game, analyze_experiment, compare_experiments, create_design_document | DESIGNER |
| Tester (8) | test_plan_get, game_start, game_observe, game_act, game_collect_events, game_collect_metrics, finding_report, test_report_create | TESTER |
| Research (6) | web_search, fetch_page, save_source, extract_claims, compare_sources, generate_report | RESEARCH |

### 2.4 事件枚举（26 个）

原始 16 个（RUN_STARTED / STEP_STARTED / TOOL_CALLED / TOOL_RETURNED / LLM_CALLED / LLM_RESPONSE / RUN_COMPLETED / RUN_FAILED / RUN_CANCELLED / PROPOSAL_CREATED / APPROVAL_REQUIRED / CONFIG_VERSION_CREATED / SIMULATION_STARTED / SIMULATION_COMPLETED / RUN_RESUMED / RUN_RETRIED）+ Tester 6 个（TEST_PLAN_CREATED / TEST_PLAN_ACTIVATED / GAME_SESSION_STARTED / INVARIANT_VIOLATED / FINDING_CREATED / TEST_REPORT_CREATED）+ Research 4 个（SOURCE_FETCHED / CLAIM_EXTRACTED / CLAIM_VALIDATED / RESEARCH_REPORT_CREATED）= 26。

### 2.5 AgentType 枚举

```java
public enum AgentType { DESIGNER, TESTER, RESEARCH }
```

路由逻辑（`DefaultAgentTypeResolver`）：
- constraints_json 含 `"agent_type":"RESEARCH"` → RESEARCH
- task 有 ACTIVE TestPlan → TESTER
- 默认 → DESIGNER

### 2.6 Prompt 版本

| 版本 | 内容 | is_active |
|------|------|-----------|
| p001 | AI Game Designer（designer_p001.md） | 1 |
| tester_p001 | AI Game Tester（tester_p001.md） | 0 |
| research_p001 | AI Researcher（research_p001.md） | 0 |

---

## 3. Gap Analysis

### 3.1 需求 vs 现状对照表

| # | QA 需求（03 文档） | 现状 | Gap 类型 | 优先级 |
|---|-------------------|------|----------|--------|
| G1 | 多 Agent 并行执行 | 单 Agent per Run，无 orchestration | **架构缺失** | P0 |
| G2 | QA Lead 分配任务给 6 个角色 | 无子任务/分发机制 | **架构缺失** | P0 |
| G3 | 各角色独立 System Prompt | AgentTypeResolver 仅支持 3 种 | 需扩展 | P0 |
| G4 | 各角色独立 Tool Set | 白名单机制已有，需新增集合 | 需扩展 | P0 |
| G5 | Release Gate 三态判定 | 无 Release Gate 概念 | **架构缺失** | P0 |
| G6 | 玩家人格（MVP 3 种：NOVICE/AGGRESSIVE/BUILD_SPECIALIST） | FakeGameEnvironment 仅 Scripted/Random | 人格差异经 Prompt ROLE_FOCUS 驱动，不扩展 gameenv | P1（V2 可评估枚举扩展） |
| G7 | Numerical Analyst 调用 Experiment | ExperimentService/EvaluationService 已有 | **可复用** | P1 |
| G8 | Combat Analyst 分析战斗数据 | Finding/Experiment/agent_event 已有 | **可复用** | P1 |
| G9 | Exploit Hunter 主动寻找漏洞 | InvariantEngine + game_* 工具已有 | **可复用** | P1 |
| G10 | UX Reviewer 评估 UI/交互 | 无截图/CV 能力，纯数据评估 | 需设计 | P2 |
| G11 | Release Report 格式化输出 | design_document 表已有，TestReportCreateTool 模式已验证 | **可复用** | P1 |
| G12 | QA Dashboard 前端 | 现有 6 页可扩展 | 需新增页 | P2 |
| G13 | 跨 Run 结果汇总 | 无按 Task 汇总多 Run Finding 的 API | 需新增 | P1 |
| G14 | QA 事件 SSE 实时流 | AgentEvent + SSE 全局流已有，需追加 QA_* 事件 | 需追加 | P1 |

### 3.2 Gap 分类汇总

| 类型 | 数量 | 说明 |
|------|------|------|
| 架构缺失（需新增机制） | 3 | G1 多 Agent 编排、G2 子任务分发、G5 Release Gate |
| 需扩展（追加/增强现有机制） | 7 | G3 AgentType、G4 Tool 白名单、G6 PlayerProfile、G12 前端、G13 API、G14 事件 |
| 可直接复用 | 4 | G7 Experiment、G8 Finding、G9 InvariantEngine、G11 design_document |

### 3.3 核心架构缺口详细分析

#### G1：多 Agent 编排

**现状**：`AgentLoop.run()` 驱动单个 Agent 执行。一个 Run 绑定一个 Task，一个 Task 可以有多个 Run，但每个 Run 只有一个 AgentType。

**QA 需求**：QA Lead 需要同时协调 6 个角色的执行。

**候选方案**：

| 方案 | 描述 | 优点 | 缺点 | 推荐 |
|------|------|------|------|------|
| A. Intra-Run LLM Orchestration | QA Lead 在一个 Run 内通过 Tool 调用来创建/监控子 Run | 零 Runtime 修改；复用现有 Run/Step/ToolCall；Lead 通过 Tool 观察子 Run 状态 | Lead Run 步数消耗大；子 Run 并行度受限于单线程 | **推荐** |
| B. Runtime Orchestrator | 在 AgentLoop 中增加多 Agent 编排层 | 原生支持并行；编排逻辑更可靠 | 改动 AgentLoop（冻结契约）；增加复杂度；违反 §4 不允许重复实现 Runtime | 否 |
| C. 外部调度器 | 新建 QaOrchestratorService 由用户/API 触发 | 解耦 Lead 和执行；可并行调度 | 需新服务；需新 API；LLM 无法主动编排 | 可选 |

**最终推荐：方案 A**。QA Lead 的 System Prompt 被设计为"协调者"，通过以下 Tool 驱动编排：

1. `qa_create_subtask` — 为指定角色创建子 Task
2. `qa_start_subrun` — 为子 Task 启动 Run（注入角色专用 Prompt + Tools）
3. `qa_check_subrun_status` — 查询子 Run 状态和产出
4. `qa_collect_findings` — 汇总所有子 Run 的 Finding

QA Lead 自身的 Run 不直接操作游戏或分析数据，只负责编排和汇总。

#### G2：子任务分发

**现状**：`TaskController.createTask()` 创建 Task，`RunController.startRun()` 启动 Run。

**QA 方案**：QA Lead 通过 `qa_create_subtask` Tool 在 AgentLoop 内部创建子 Task，通过 `qa_start_subrun` 启动子 Run。子 Run 与 Lead Run 共享同一 Task（通过 `task_id` 关联），但各自有独立的 Run 记录。

```text
QA Task (task_id=100, constraints_json={"agent_type":"QA_LEAD","configVersion":"v001"})
    │
    ├── QA Lead Run (run_id=1000, agent_type=QA_LEAD)
    │       ├── qa_create_subtask → SubTask(id=101, role="PLAYER_AGENT")
    │       ├── qa_start_subrun → SubRun(id=1001, agent_type=QA_TESTER, playerProfile="NOVICE")
    │       ├── qa_create_subtask → SubTask(id=102, role="NUMERICAL_ANALYST")
    │       ├── qa_start_subrun → SubRun(id=1002, agent_type=QA_ANALYST, role="NUMERICAL")
    │       ├── ... (更多角色)
    │       ├── qa_collect_findings → 汇总所有 SubRun 产出
    │       └── Release Gate 判定 + Release Report
```

#### G5：Release Gate

**现状**：无 Release Gate 概念。

**QA 方案**：

1. **Prompt 驱动**：QA Lead System Prompt 定义 Release Gate 规则（Critical Bug > 0 → FAIL 等），Lead 在汇总后按规则判定。
2. **Server-side 校验**：新增 `QaReleaseGateService`，从数据库读取 Finding 集合，执行确定性阈值校验，与 Lead 判定结果对比。
3. **持久化**：新增 `qa_release_gate` 表，记录判定结果、阈值配置、Lead 判定与 Server 校验的一致性。

---

## 4. 架构一致性报告

### 4.1 平台架构规则检查（01_Agent_平台架构.md）

| 规则 | 检查结果 | 说明 |
|------|----------|------|
| §3 统一 AgentRuntime | ✅ 一致 | QA 复用现有 AgentLoop/RunStateMachine/BudgetGuard/LoopDetector |
| §4 不允许重复实现 Runtime | ✅ 一致 | 不新建 QaAgentRuntime |
| §5 统一 LlmGateway | ✅ 一致 | QA Agent 通过 LlmGateway 访问模型 |
| §6 Tool via ToolRegistry | ✅ 一致 | QA Tool 实现 GameTool 接口并注册到 ToolRegistry |
| §7 Agent Context | ✅ 一致 | 子 Run 绑定 projectId/agentId/runId |
| §9 Agent 间协作 via Project Data | ✅ 一致 | 子 Run 通过共享 Finding/Experiment 表协作 |
| §10 Human in the Loop | ✅ 一致 | Release Gate 需人工审阅 |
| §11 Agent 扩展标准（10 项） | ✅ 一致 | 每个 QA AgentType 均按 §11 提供完整清单 |

### 4.2 工作流规则检查（工作流.md v2）

| 规则 | 检查结果 | 说明 |
|------|----------|------|
| §2.2 Autonomy Boundary | ✅ 一致 | QA 设计不修改产品目标/核心架构/冻结契约 |
| §5.2 不得自主决定 | ✅ 一致 | QA 提案需人工批准后才执行 |
| §18.1 遵循既有架构 | ✅ 一致 | 复用 AgentLoop/ToolRegistry/EventPublish |
| §30 并行开发原则 | ✅ 一致 | QA Sprint 模块独立，不修改 Designer/Tester/Research |
| §40 AI 不得自行改变架构 | ✅ 一致 | 本提案为设计文档，不直接实施 |

### 4.3 架构约束总结

| 约束 | 状态 | 影响 |
|------|------|------|
| 单 Agent per Run | 需绕过 | 通过 Intra-Run LLM Orchestration 方案绕过，子 Run 各自独立 |
| AgentTypeResolver 仅 3 种 | 需扩展 | 追加 QA_LEAD/QA_TESTER/QA_RESEARCHER/QA_ANALYST |
| Tool 白名单隔离 | 需扩展 | 每个 QA AgentType 有独立白名单 |
| 现有 14 表不可修改 | 遵守 | 新增 2 张 QA 专用表 |
| AgentEventType 26 枚不可修改 | 遵守 | 追加 QA_* 枚举 |
| PromptService 只有 3 个种子 | 需扩展 | 追加 8 个 QA Prompt 种子（Lead + 3 Player 人格 + Combat + Numerical + Exploit + UX） |

---

## 5. 设计文档一致性报告

### 5.1 原始 QA 设计（03_AI游戏测试团队.md）与本提案对照

| 03 文档定义 | 本提案实现 | 一致性 |
|-------------|-----------|--------|
| 7 个角色（QA Lead + 6 个） | 8 个 Prompt 驱动身份（QA Lead + 3 Player 人格 + Combat + Numerical + Exploit + UX），映射 4 个 QA AgentType | ⚠️ 差异（Player 拆 3 人格，Prompt 驱动，零后端改动） |
| QA Lead 分配任务 | qa_create_subtask Tool | ✅ 一致 |
| Release Gate PASS/CONDITIONAL/FAIL | QaReleaseGateService + Prompt | ✅ 一致 |
| 禁止简单多数投票 | QA Lead Prompt 明确禁止 | ✅ 一致 |
| 最终报告格式 | design_document（title 前缀 [RELEASE REPORT]） | ✅ 一致 |

### 5.2 03 文档需要扩展的项

| 项 | 03 文档状态 | 本提案补充 |
|----|------------|-----------|
| 具体 Tool 定义 | 未定义 | G10-G22 详细定义 |
| 数据库表设计 | 未定义 | qa_role_assignment + qa_release_gate |
| AgentType 路由 | 未定义 | constraints_json.agent_type="QA_LEAD" |
| 玩家人格参数 | 未定义 | 3 人格（NOVICE/AGGRESSIVE/BUILD_SPECIALIST）经 Prompt ROLE_FOCUS 元数据驱动；playerProfile 为自由 String 字段（`GameLaunchSpec` L17），**零后端扩展**（不扩展 gameenv/PlayerProfile 白名单） |
| 前端页面 | 未定义 | QA Dashboard |

### 5.3 与 Tester 技术设计的一致性

| Tester 设计项 | QA 是否冲突 | 说明 |
|--------------|------------|------|
| T4/T5 冻结的 8 个 Tester Tool | 不冲突 | QA Player Agent 通过 QA_TESTER AgentType 调用同一组工具，不同 Prompt |
| AgentLoop 双轨选择（isTesterRun） | **零修改【A1】** | QA_* AgentType 经既有委托链分流（`selectSystemPrompt`/`selectToolSpecs` → `DefaultAgentTypeResolver`），无需 AgentLoop 判定 |
| InvariantEngine 白名单 | 不冲突 | QA 复用同一引擎 |
| TestPlan/Finding DDL | 不冲突 | QA 复用同一表结构 |
| Tester REST 8 端点 | 不冲突 | QA 追加新端点，不修改既有端点 |

---

## 6. 核心架构方案

### 6.1 Intra-Run LLM Orchestration 原理

```text
┌─────────────────────────────────────────────────────────────┐
│                    QA Lead Run (run_id=1000)                 │
│  AgentType: QA_LEAD                                         │
│  Prompt: qa_lead_p001.md (编排+汇总+Release Gate)          │
│  Tools: qa_create_subtask, qa_start_subrun,                 │
│         qa_check_subrun_status, qa_collect_findings,        │
│         get_game_config, create_design_document             │
│                                                             │
│  LLM 决策流：                                               │
│  Step 1: 读取 configVersion → 规划测试策略                   │
│  Step 2: qa_create_subtask(PLAYER_AGENT) → SubTask 101      │
│  Step 3: qa_start_subrun(SubTask 101, NOVICE) → SubRun 1001│
│  Step 4: qa_create_subtask(NUMERICAL_ANALYST) → SubTask 102 │
│  Step 5: qa_start_subrun(SubTask 1002, NUMERICAL)           │
│  ... (更多角色)                                              │
│  Step N-2: qa_collect_findings → 汇总所有产出               │
│  Step N-1: Release Gate 判定                                 │
│  Step N: create_design_document(RELEASE REPORT)             │
└─────────────────────────────────────────────────────────────┘
         │ 创建              │ 创建              │ 创建
         ▼                   ▼                   ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ SubRun 1001  │  │ SubRun 1002  │  │ SubRun 1003  │
│ QA_TESTER    │  │ QA_ANALYST   │  │ QA_RESEARCHER│
│ Player Agent │  │ Numerical    │  │ Exploit Hunt │
│ 8 tester     │  │ 6 analyst    │  │ 6 research   │
│ tools        │  │ tools        │  │ tools        │
└──────────────┘  └──────────────┘  └──────────────┘
```

### 6.2 与现有 Runtime 的交互

```text
用户 API 调用：
POST /api/tasks (constraints_json={"agent_type":"QA_LEAD","configVersion":"v001"})
  → TaskController.createTask()
  → task 行创建

POST /api/tasks/{id}/runs
  → RunController.startRun()
  → AgentLoop.run(task) 
  → AgentTypeResolver.resolve(task) → QA_LEAD
  → selectSystemPrompt(QA_LEAD) → qa_lead_p001.md
  → selectToolSpecs(QA_LEAD) → QA_LEAD_TOOLS

AgentLoop 主循环（标准执行，无修改）：
  while (not done) {
    Step N = LLM.call(messages)
    for each tool_call in Step.tool_calls {
      ToolExecutor.execute(run, step, tool_call)
      // qa_create_subtask → 创建子 Task 行
      // qa_start_subrun → 异步提交子 AgentLoop.run(subTask)
    }
  }
```

### 6.3 子 Run 执行方式

子 Run 通过 `RunControlService.start(subTaskId)` 异步创建，与 Lead Run 并行执行（共享线程池，受 BudgetGuard 约束）。子 Run 的状态更新写入各自的 Run 行，Lead 通过 `qa_check_subrun_status` Tool 查询。

**关键约束**：子 Run 完成后不会自动通知 Lead Run。Lead 需要主动轮询状态（在 Prompt 中约定"检查所有子 Run 后再汇总"）。这意味着 Lead 的步数消耗较大——这是方案 A 的已知代价，可通过 BudgetGuard 的 `maxSteps` 配置缓解。

---

## 7. 基础设施改造方案

### 7.1 改造清单（按优先级排序）

| # | 改造项 | 修改文件 | 风险 | 依赖 |
|---|--------|----------|------|------|
| I1 | AgentType 枚举追加 | `AgentType.java` | 低（仅追加枚举值） | 无 |
| I2 | AgentTypeResolver 路由扩展 | `DefaultAgentTypeResolver.java` | 中（修改路由逻辑） | I1 |
| I3 | Tool 白名单扩展 | `DefaultAgentTypeResolver.java` | 低（仅追加常量） | I1 |
| I4 | PromptService QA 种子导入 | `PromptService.java` | 低（追加 import 方法） | 无 |
| I5 | AgentEventType 追加 | `AgentEventType.java` | 低（仅追加枚举值） | 无 |
| I6 | RunController 支持 subTaskId | `RunController.java` | 中（需保持向后兼容） | I1 |
| I7 | QaOrchestratorService | 新增 `qa/QaOrchestratorService.java` | 中（新服务） | I1, I2 |
| I8 | QaReleaseGateService | 新增 `qa/QaReleaseGateService.java` | 中（新服务） | I1 |
| I9 | schema.sql 追加 2 表 | `schema.sql` | 低（IF NOT EXISTS） | 无 |

### 7.2 对冻结契约的影响评估

| 冻结项 | 影响 | 评估 |
|--------|------|------|
| AgentLoop.java | **零修改【A1】** | QA_* AgentType 经既有委托链 `selectSystemPrompt`/`selectToolSpecs` → `DefaultAgentTypeResolver` 完成 Prompt/Tool 分流，AgentLoop 不加任何 QA 判定分支 |
| RunStateMachine | 无修改 | ✅ 零影响 |
| BudgetGuard | 无修改 | ✅ 零影响 |
| LoopDetector | 无修改 | ✅ 零影响 |
| ToolExecutor | 无修改 | ✅ 零影响 |
| ToolRegistry | 无修改（新 Tool 通过 @Component 自动注册） | ✅ 零影响 |
| GameTool 接口 | 无修改 | ✅ 零影响 |
| ToolResult / ToolContext | 无修改 | ✅ 零影响 |
| 现有 14 张表 | 无修改 | ✅ 零影响 |
| ErrorCodes | 无修改 | ✅ 零影响 |
| AgentEventType 26 枚 | 无修改（仅追加） | ✅ 零影响 |
| Tester V1 冻结契约 | 无修改 | ✅ 零影响 |

### 7.3 风险缓解

**最高风险项是 I7（QaOrchestratorService 顺序编排）**，其次是 I2/I3（Resolver 扩展）。AgentLoop **零修改**【A1】是绝对红线，不作为改造项，也不存在"最小侵入"余地。缓解措施：

1. **编排层 deadline 兜底**：QaOrchestratorService 设 wallClockBudget（默认 300s）→ poll 检测超时 → `qa_role_assignment.status = TIMEOUT`（子 Run 行不改）；**绝不**因子 Run 超时让 Release Gate 自动 PASS（红线，负例见 QA-S1-10）。
2. **Resolver 穷举一致性**：新增 `QaRunWiringTest`（QA-S1-4 归属）验证 QA 四 AgentType 的 Prompt + Tool 选择正确，且 Designer/Tester/Research 三既有分支不受影响（switch 穷举编译保证）。
3. **全量回归**：QA-S1 内每 Task 合并后执行全量 `mvn test`，确保既有用例零回归（基线=PF-004）。

---

## 8. AgentType / AgentTypeResolver 扩展方案

### 8.1 新增 AgentType

```java
public enum AgentType { 
    DESIGNER, 
    TESTER, 
    RESEARCH,
    // QA Team (Phase 2)
    QA_LEAD,      // 编排协调
    QA_TESTER,    // Player Agent / Exploit Hunter
    QA_ANALYST,   // Numerical Analyst / Combat Analyst
    QA_RESEARCHER // UX Reviewer
}
```

### 8.2 路由逻辑扩展

```java
// DefaultAgentTypeResolver.resolve() 扩展
public AgentType resolve(Task task) {
    String agentType = extractAgentType(task.constraintsJson());
    
    // 显式指定（最高优先级）
    if (agentType != null) {
        return AgentType.valueOf(agentType); // "QA_LEAD" / "QA_TESTER" / etc.
    }
    
    // 回退逻辑（保持现有）
    if (hasActiveTestPlan(task)) return AgentType.TESTER;
    return AgentType.DESIGNER;
}
```

### 8.3 Tool 白名单（QA 四种 AgentType）

```java
case QA_LEAD -> Set.of(
    "qa_create_subtask", "qa_start_subrun", 
    "qa_check_subrun_status", "qa_collect_findings",
    "get_game_config", "create_design_document"
);
case QA_TESTER -> Set.of( // A8 方案 A：Tester 8 + web_search + fetch_page = 10（Player 3 人格 + Exploit Hunter 共用）
    "test_plan_get", "game_start", "game_observe", "game_act",
    "game_collect_events", "game_collect_metrics", 
    "finding_report", "test_report_create",
    "web_search", "fetch_page"
);
case QA_ANALYST -> Set.of(
    "simulate_game", "analyze_experiment", "compare_experiments",
    "game_collect_metrics", "game_collect_events",
    "finding_report", "create_design_document"
);
case QA_RESEARCHER -> Set.of( // A8 方案 A：Research 6 + finding_report + game_observe/game_collect_events/analyze_experiment = 10（UX Reviewer 共用）
    "web_search", "fetch_page", "save_source",
    "extract_claims", "compare_sources", "generate_report",
    "finding_report", "game_observe", "game_collect_events",
    "analyze_experiment"
);
```

---

## 9. Prompt 设计方案

### 9.1 QA Lead System Prompt（qa_lead_p001.md）大纲

```text
身份：你是 AI Game QA Lead，负责协调 QA 测试团队对 PROJECT ORBITAL 构建版本进行完整测试。

工作流程：
1. 读取 task 的 constraints_json，确认 configVersion。
2. 通过 get_game_config 读取当前配置。
3. 为每个角色创建子任务：
   - Player Agent（3 个人格：NOVICE / AGGRESSIVE / BUILD_SPECIALIST）
   - Numerical Analyst
   - Combat Analyst
   - Exploit Hunter
   - UX Reviewer
4. 为每个子任务启动子 Run。
5. 等待所有子 Run 完成（轮询 qa_check_subrun_status）。
6. 通过 qa_collect_findings 汇总所有产出。
7. 执行 Release Gate 判定：
   - Critical Bug > 0 → FAIL
   - Soft Lock > 1% → FAIL
   - Dominant Build > 50% → FAIL
   - Crash Rate > 0.5% → FAIL
   - 其他 → PASS 或 CONDITIONAL_PASS
8. 生成 Release Report（调用 create_design_document）。
9. 禁止简单多数投票——出现冲突必须通过证据和实验解决。

铁律：
- 不直接操作游戏环境。
- 不代替角色做分析判断。
- Release Gate 判定必须基于子任务产出的事实数据。
- 出现证据冲突时，要求补充实验而非自行裁决。
```

### 9.2 各角色 Prompt 策略

| 角色 | AgentType | Prompt 核心 | 与现有 Prompt 的关系 |
|------|-----------|------------|---------------------|
| QA Lead | QA_LEAD | 编排+汇总+Release Gate | 全新 |
| Player Agent | QA_TESTER | 按人格操作游戏+报告 Finding | 基于 tester_p001 改写，增加人格指令 |
| Combat Analyst | QA_ANALYST | 分析战斗节奏/敌人压力/Build 克制 | 全新 |
| Numerical Analyst | QA_ANALYST | 调用 simulate_game + analyze_experiment 输出数值分析 | 全新 |
| Exploit Hunter | QA_TESTER | 主动寻找漏洞（无限资源/卡死/负数/NaN） | 基于 tester_p001 改写 + 白名单补 web_search/fetch_page（A8 方案 A） |
| UX Reviewer | QA_RESEARCHER | 基于 Finding+Experiment 数据评估 UX 问题 | 全新 |

### 9.3 角色区分机制

同一个 AgentType（如 QA_ANALYST）承载多个角色，通过 Prompt 中的 `ROLE_FOCUS` 字段区分：

```text
你是 AI Game QA Analyst。

你的具体角色是：NUMERICAL_ANALYST

角色职责：
- 调用 simulate_game 获取批量模拟数据
- 调用 analyze_experiment 分析实验结果
- 调用 compare_experiments 对比不同 Build 的数值差异
- 输出 DPS/TTK/收益曲线/成长曲线/Build 强度分析
- 所有结论必须引用实验数据
```

---

## 10. Tool 设计方案

### 10.1 QA 编排工具（QA Lead 专用）

| # | Tool Name | 权限 | 需审批 | 输入 | 输出 |
|---|-----------|------|--------|------|------|
| G10 | qa_create_subtask | EXECUTE | 否 | `{role: String, title: String, constraints: JsonObject}` | SubTask id |
| G11 | qa_start_subrun | EXECUTE | 否 | `{subTaskId: Long, playerProfile?: String}` | SubRun id + 初始状态 |
| G12 | qa_check_subrun_status | READ | 否 | `{subRunId: Long}` | status + currentStep + tokensUsed |
| G13 | qa_collect_findings | READ | 否 | `{subTaskIds: Long[]}` | 汇总 Finding 列表 + Experiment 结果 + Research Claim |

### 10.2 Player Agent 工具

Player Agent 使用 **QA_TESTER** AgentType，工具 = Tester V1 8 个 + web_search/fetch_page（A8 方案 A = 10 个）。

**人格机制（零后端改动）**：`GameLaunchSpec.playerProfile` **已是既有自由 String 字段**（`gameenv/GameLaunchSpec.java` L17，Javadoc 注明 MVP 仅 RANDOM/SCRIPTED 两种，其余为二期）。QA 侧不做任何字段/枚举扩展：MVP 3 人格（NOVICE/AGGRESSIVE/BUILD_SPECIALIST）由对应 System Prompt 的 ROLE_FOCUS 元数据驱动，QA 编排直接将人格字符串传入 `qa_start_subrun.playerProfile` → `RunControlService` 传给 `GameLaunchSpec`。

**约束校验边界**：`TestPlanService.validateScenario()` 的 `PLAYER_PROFILES = ["SCRIPTED", "RANDOM"]`（`TestPlanService.java` L34-35）仅拦截**测试骨架解析出的场景**；QA 子 Run 不经 TestPlanService 骨架路径，是经 `RunControlService.start` 直接驱动的，因此**不受该白名单约束**。据此：**不扩展 PlayerProfile 白名单、不修改 FakeGameEnvironment、不做任何 gameenv 改动**（冻结边界，B 节 Contract Matrix 已对齐）。

**设计要点**：Player 的人格差异**完全由 Prompt 驱动**（LLM 根据人格选择不同的 game_act 策略），FakeGameEnvironment 本身不感知人格——它只接收 GameAction。这与架构设计 §2 "FakeGameEnvironment 是确定性状态机，不做智能判断"一致。

### 10.3 Analyst 工具（QA_ANALYST）

| # | Tool Name | 来源 | 说明 |
|---|-----------|------|------|
| - | simulate_game | Designer 既有 | 复用，调 ExperimentService |
| - | analyze_experiment | Designer 既有 | 复用 |
| - | compare_experiments | Designer 既有 | 复用 |
| - | game_collect_metrics | Tester 既有 | 复用 |
| - | game_collect_events | Tester 既有 | 复用 |
| - | finding_report | Tester 既有 | 复用 |
| - | create_design_document | Designer 既有 | 复用 |

### 10.4 Researcher 工具（QA_RESEARCHER）

| # | Tool Name | 来源 | 说明 |
|---|-----------|------|------|
| - | web_search | Research 既有 | 复用 |
| - | fetch_page | Research 既有 | 复用 |
| - | save_source | Research 既有 | 复用 |
| - | extract_claims | Research 既有 | 复用 |
| - | compare_sources | Research 既有 | 复用 |
| - | generate_report | Research 既有 | 复用 |
| - | finding_report | Tester 既有 | 复用（Researcher 也能报 Finding） |

### 10.5 新增 Tool 实现方式

所有新增 Tool 遵循已有模式：

```java
@Component
public class QaCreateSubtaskTool extends AbstractTesterTool {
    // 与 AbstractTesterTool 相同的异常翻译模式
    // 通过 QaOrchestratorService 创建子 Task
}
```

**禁止**：创建 AbstractQaTool 基类（与 AbstractTesterTool 重复）。QA Tool 复用 `AbstractTesterTool` 的异常翻译模式，或在 ToolRegistry 层面统一处理。

---

## 11. DB Schema 扩展方案

### 11.1 新增 2 张表

```sql
-- QA 角色分配（子任务记录）
CREATE TABLE IF NOT EXISTS qa_role_assignment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_run_id INTEGER NOT NULL REFERENCES run(id),     -- Lead Run
    sub_task_id INTEGER NOT NULL REFERENCES task(id),       -- 子 Task
    sub_run_id INTEGER REFERENCES run(id),                  -- 子 Run（启动后回填）
    role TEXT NOT NULL,                                     -- PLAYER_AGENT/NUMERICAL_ANALYST/COMBAT_ANALYST/EXPLOIT_HUNTER/UX_REVIEWER
    player_profile TEXT,                                    -- NOVICE/AGGRESSIVE/BUILD_SPECIALIST/etc.
    status TEXT NOT NULL DEFAULT 'PENDING',                 -- PENDING/RUNNING/COMPLETED/FAILED
    result_summary TEXT,                                    -- 子 Run 完成后的摘要
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_qra_parent ON qa_role_assignment(parent_run_id);
CREATE INDEX IF NOT EXISTS idx_qra_subtask ON qa_role_assignment(sub_task_id);

-- Release Gate 记录
CREATE TABLE IF NOT EXISTS qa_release_gate (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id INTEGER NOT NULL REFERENCES task(id),
    run_id INTEGER NOT NULL REFERENCES run(id),             -- Lead Run
    config_version TEXT NOT NULL,
    decision TEXT NOT NULL,                                  -- PASS/CONDITIONAL_PASS/FAIL
    critical_bugs INTEGER NOT NULL DEFAULT 0,
    high_bugs INTEGER NOT NULL DEFAULT 0,
    medium_bugs INTEGER NOT NULL DEFAULT 0,
    soft_lock_count INTEGER NOT NULL DEFAULT 0,
    crash_rate REAL,
    dominant_build_share REAL,
    server_decision TEXT NOT NULL,                           -- Server-side 校验结果
    lead_decision TEXT NOT NULL,                             -- QA Lead LLM 判定
    is_consistent INTEGER NOT NULL DEFAULT 1,               -- Lead 与 Server 判定是否一致
    report_doc_id INTEGER REFERENCES design_document(id),
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_qrg_task ON qa_release_gate(task_id);
```

### 11.2 为什么需要这 2 张表

| 候选方案 | 为什么不用 |
|----------|-----------|
| 复用 agent_event 存角色分配 | agent_event 是 append-only 日志，无法更新 status/result_summary |
| 复用 change_proposal 存 Release Gate | 语义不匹配（提案 = 配置变更请求，Gate = 发布判定） |
| 复用 design_document 存 Release Gate | Markdown 无法做结构化查询（WHERE decision='FAIL'） |
| 不建表，全在内存 | 进程重启即失；前端无法查询历史 Release Gate |

### 11.3 对现有表的影响

**零影响**。新增 2 张表使用 `IF NOT EXISTS`，不修改任何现有 14 张表的 DDL。

---

## 12. API 扩展方案

### 12.1 新增端点

| 方法 | 路径 | 说明 | 依赖 |
|------|------|------|------|
| POST | /api/tasks/{id}/qa-run | 创建 QA Lead Run（注入 QA_LEAD AgentType + 角色配置） | QaOrchestratorService |
| GET | /api/qa/role-assignments?runId= | 查询角色分配列表 | qa_role_assignment 表 |
| GET | /api/qa/role-assignments/{id} | 查询角色分配详情 | 同上 |
| GET | /api/qa/release-gates?taskId= | 查询 Release Gate 记录 | qa_release_gate 表 |
| GET | /api/qa/release-gates/{id} | 查询 Release Gate 详情（含 Finding 汇总） | 同上 |
| GET | /api/qa/dashboard/stats | QA 概览统计（角色完成率/Finding 分布/Gate 结果） | 多表聚合 |
| POST | /api/qa/release-gates/{id}/approve | 人工审阅 Release Gate | 同上 |
| GET | /api/tasks/{id}/qa-summary | 该 Task 下所有 QA Run 的汇总视图 | 多表聚合 |

### 12.2 向后兼容

所有现有 28 端点**零修改**。新端点均在 `/api/qa/` 路径下，与现有路径完全隔离。

---

## 13. 前端扩展方案

### 13.1 新增页面

**QA Dashboard** (`/qa`)：

```text
┌──────────────────────────────────────────────────────────────────┐
│  QA Dashboard                                                     │
│                                                                    │
│  [Task Filter] [ConfigVersion Filter] [Gate Result Filter]         │
│                                                                    │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │
│  │ Total QA    │  │ Pass Rate   │  │ Open Critical│               │
│  │ Runs: 12    │  │ 83%         │  │ 2            │               │
│  └─────────────┘  └─────────────┘  └─────────────┘               │
│                                                                    │
│  Recent QA Runs                                                    │
│  ┌────────┬──────────┬──────────┬────────┬────────┐               │
│  │ Run ID │ Config   │ Gate     │ Critical│ Status │               │
│  ├────────┼──────────┼──────────┼────────┼────────┤               │
│  │ 1000   │ v001     │ PASS     │ 0      │ DONE   │               │
│  │ 1005   │ v002     │ FAIL     │ 3      │ DONE   │               │
│  └────────┴──────────┴──────────┴────────┴────────┘               │
│                                                                    │
│  Role Completion (for selected Run)                                │
│  ┌────────────────┬──────────┬──────────┐                         │
│  │ Player Agent   │ ✓ DONE   │ 0 findings│                         │
│  │ Numerical      │ ✓ DONE   │ 2 findings│                         │
│  │ Combat         │ ⏳ RUNNING│ —        │                         │
│  │ Exploit Hunter │ ✓ DONE   │ 1 finding │                         │
│  │ UX Reviewer    │ ✗ FAILED │ —        │                         │
│  └────────────────┴──────────┴──────────┘                         │
└──────────────────────────────────────────────────────────────────┘
```

### 13.2 复用现有组件

| 组件 | 来源 | 复用方式 |
|------|------|----------|
| Badge | T5-1 | 直接复用（Gate 结果着色） |
| Drawer | T5-1 | 直复用（Finding 详情） |
| DefinitionList | T5-1 | 直接复用（Role Assignment 详情） |
| useGlobalSse | T5-1 | 直接复用（QA_* 事件信号） |
| React Query 模式 | T5-1 | 复用 useQuery/useMutation 模式 |

### 13.3 前端类型扩展

```typescript
// types.ts 追加
interface QaRoleAssignment {
  id: number;
  parentRunId: number;
  subTaskId: number;
  subRunId: number | null;
  role: 'PLAYER_AGENT' | 'NUMERICAL_ANALYST' | 'COMBAT_ANALYST' | 'EXPLOIT_HUNTER' | 'UX_REVIEWER';
  playerProfile: string | null;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  resultSummary: string | null;
  createdAt: string;
}

interface QaReleaseGate {
  id: number;
  taskId: number;
  runId: number;
  configVersion: string;
  decision: 'PASS' | 'CONDITIONAL_PASS' | 'FAIL';
  criticalBugs: number;
  highBugs: number;
  mediumBugs: number;
  softLockCount: number;
  crashRate: number | null;
  dominantBuildShare: number | null;
  serverDecision: string;
  leadDecision: string;
  isConsistent: boolean;
  reportDocId: number | null;
  createdAt: string;
}
```

---

## 14. 七角色详细设计

### 14.1 QA Lead

| 项目 | 内容 |
|------|------|
| AgentType | QA_LEAD |
| 核心职责 | 编排测试计划、分发子任务、汇总结果、Release Gate 判定、生成报告 |
| Prompt 文件 | `qa_lead_p001.md` |
| Tool 集合 | qa_create_subtask, qa_start_subrun, qa_check_subrun_status, qa_collect_findings, get_game_config, create_design_document |
| 输入约束 | task.constraints_json 必须含 configVersion |
| 输出约束 | design_document（title 前缀 [RELEASE REPORT]）+ qa_release_gate 行 |
| 审批策略 | 无（Lead 不直接修改配置） |
| 错误策略 | 子 Run 失败 → 记录失败角色，继续汇总已完成的角色结果 |

### 14.2 Player Agent

| 项目 | 内容 |
|------|------|
| AgentType | QA_TESTER |
| 核心职责 | 按人格操作游戏环境，发现异常，上报 Finding |
| 人格变体 | NOVICE（保守操作）、AGGRESSIVE（激进攻击）、BUILD_SPECIALIST（专精特定 Build） |
| Prompt 文件 | `qa_player_novice_p001.md` / `qa_player_aggressive_p001.md` / `qa_player_build_specialist_p001.md` |
| Tool 集合 | 与 Tester V1 相同的 8 个工具 |
| 输入约束 | constraints_json 必须含 configVersion + playerProfile |
| 输出约束 | Finding（severity/category/repro_steps） |
| 审批策略 | 无 |
| 与 Tester 的关系 | 共享同一组 Tool，不同 Prompt（Tester = 系统化测试；Player Agent = 模拟真实玩家行为） |

### 14.3 Combat Analyst

| 项目 | 内容 |
|------|------|
| AgentType | QA_ANALYST |
| 核心职责 | 分析战斗节奏、敌人压力、玩家操作空间、关卡难度、Boss 设计、Build 克制关系 |
| Prompt 文件 | `qa_combat_analyst_p001.md` |
| Tool 集合 | simulate_game, analyze_experiment, game_collect_metrics, game_collect_events, finding_report |
| 输入约束 | configVersion |
| 输出约束 | combat_analysis（Markdown 格式）+ Finding（BALANCE_ANOMALY 类） |
| 审批策略 | 无 |
| 分析方法 | 通过 simulate_game 生成大量战斗数据，分析 DPS 分布/TTK/生存率/Build 胜率差异 |

### 14.4 Numerical Analyst

| 项目 | 内容 |
|------|------|
| AgentType | QA_ANALYST |
| 核心职责 | 检查 DPS、TTK、收益曲线、成长曲线、属性相关性、Build 强度、经济系统 |
| Prompt 文件 | `qa_numerical_analyst_p001.md` |
| Tool 集合 | simulate_game, analyze_experiment, compare_experiments, create_design_document |
| 输入约束 | configVersion |
| 输出约束 | numerical_analysis（Markdown 格式）+ 所有结论必须引用实验数据 |
| 审批策略 | 无 |
| 分析方法 | 对 3 个 Build 各跑 10000+ 模拟，统计 DPS/胜率/CI，对比不同 ConfigVersion 差异 |

### 14.5 Exploit Hunter

| 项目 | 内容 |
|------|------|
| AgentType | QA_TESTER |
| 核心职责 | 主动寻找无限收益、无限资源、无敌、卡死、负数、NaN、浮点异常、边界条件漏洞 |
| Prompt 文件 | `qa_exploit_hunter_p001.md` |
| Tool 集合 | game_start, game_observe, game_act, game_collect_events, game_collect_metrics, finding_report, web_search, fetch_page |
| 输入约束 | configVersion |
| 输出约束 | Finding（severity=BLOCKER/HIGH，category=CRASH/INVARIANT_VIOLATION） |
| 审批策略 | 无 |
| 探索方法 | 极端参数注入 + 边界操作序列 + 参考已知漏洞模式（web_search） |

### 14.6 UX Reviewer

| 项目 | 内容 |
|------|------|
| AgentType | QA_RESEARCHER |
| 核心职责 | 评估玩家是否理解规则、升级是否清晰、信息是否足够、UI 是否误导、反馈是否及时 |
| Prompt 文件 | `qa_ux_reviewer_p001.md` |
| Tool 集合 | game_observe, game_collect_events, analyze_experiment, web_search, fetch_page, finding_report |
| 输入约束 | configVersion |
| 输出约束 | ux_review（Markdown 格式）+ Finding（category=OTHER，severity=LOW/MEDIUM） |
| 审批策略 | 无 |
| 评估方法 | 基于 Observation 数据结构 + Experiment 指标 + 竞品参考（web_search），不依赖截图/CV |

### 14.7 Release Manager

| 项目 | 内容 |
|------|------|
| 实现方式 | **合并到 QA Lead**（不作为独立 AgentType） |
| 理由 | 03 文档的 Release Manager 职责（汇总风险 + 给出 Release Recommendation）与 QA Lead 高度重叠；独立 Agent 增加编排复杂度但无增量价值 |
| Gate 判定 | QA Lead Prompt 中内嵌 Release Gate 规则 + Server-side QaReleaseGateService 校验 |

---

## 15. 数据流设计

### 15.1 端到端数据流

```text
用户
  │ POST /api/tasks (constraints_json={"agent_type":"QA_LEAD","configVersion":"v001"})
  ▼
TaskController
  │ task 行创建 (id=100)
  ▼
用户 POST /api/tasks/100/runs
  ▼
RunController → AgentLoop.run(task=100)
  │ AgentTypeResolver → QA_LEAD
  │ selectSystemPrompt → qa_lead_p001.md
  │ selectToolSpecs → QA_LEAD_TOOLS (6 个工具)
  ▼
QA Lead AgentLoop 主循环
  │
  │ Step 1: get_game_config → 读取 v001 配置
  │ Step 2: qa_create_subtask(PLAYER_AGENT, NOVICE) → task 101
  │ Step 3: qa_start_subrun(101, NOVICE) → run 1001 (QA_TESTER)
  │ Step 4: qa_create_subtask(NUMERICAL_ANALYST) → task 102
  │ Step 5: qa_start_subrun(102) → run 1002 (QA_ANALYST)
  │ ... (更多角色)
  │
  │ [子 Run 并行执行中]
  │   run 1001: game_start → game_act × N → finding_report × 2 → COMPLETED
  │   run 1002: simulate_game → analyze_experiment → COMPLETED
  │   run 1003: game_start → game_act × N → finding_report × 1 → COMPLETED
  │   run 1004: web_search → fetch_page → generate_report → COMPLETED
  │
  │ Step N-5: qa_check_subrun_status(1001) → COMPLETED
  │ Step N-4: qa_check_subrun_status(1002) → COMPLETED
  │ ... (检查所有子 Run)
  │ Step N-3: qa_collect_findings([101,102,103,104]) → 汇总 Finding 列表
  │ Step N-2: Release Gate 判定 (Critical=0, High=2, Medium=5 → PASS)
  │ Step N-1: create_design_document([RELEASE REPORT] ...) → design_document 行
  │ Step N:   stop → COMPLETED
  │
  ▼
qa_release_gate 行创建 (decision=PASS, isConsistent=true)
  │
  ▼
SSE: QA_RELEASE_GATE_CREATED 事件 → 前端 QA Dashboard 刷新
```

### 15.2 Finding 数据流（跨角色汇总）

```text
Player Agent (run 1001):
  finding_report → finding 行 (run_id=1001, config_version="v001", severity="HIGH", category="INVARIANT_VIOLATION")

Exploit Hunter (run 1003):
  finding_report → finding 行 (run_id=1003, config_version="v001", severity="BLOCKER", category="CRASH")

QA Lead (run 1000):
  qa_collect_findings → 读取 finding WHERE run_id IN (1001,1002,1003,1004)
  → 汇总: Critical=1, High=1, Medium=0
  → Release Gate: FAIL (Critical > 0)
```

---

## 16. 依赖图与 Sprint Task Tree

### 16.1 Sprint 分组

| Sprint | 目标 | Task 数 | 说明 |
|--------|------|---------|------|
| QA-S1 | 基础设施 | 5 | AgentType + Schema + Prompt + 角色定义 + 白名单 |
| QA-S2 | Tools | 4 | QA 编排工具 + PlayerProfile + QA Dashboard API + 前端类型 |
| QA-S3 | QA Lead | 3 | qa_lead_p001 + QaOrchestratorService + QaReleaseGateService |
| QA-S4 | E2E 验收 | 3 | FakeLlmGateway 剧本 + 全链路 E2E + Challenge Matrix |
| QA-S5 | 前端+收尾 | 2 | QA Dashboard 页面 + 文档回写 |

### 16.2 QA-S1 任务树

| Task | 名称 | 目标 | 前置 | 关键文件 | 验收标准 |
|------|------|------|------|----------|----------|
| QA-S1-1 | AgentType 追加 | DESIGNER/TESTER/RESEARCH 后追加 4 个 QA AgentType | 无 | `AgentType.java` | 枚举编译通过；AgentTypeResolverTest 追加 4 个 QA 用例 |
| QA-S1-2 | DB Schema 追加 | schema.sql 追加 qa_role_assignment + qa_release_gate 两张表 | 无 | `schema.sql` | 空库启动后 16 张表；RepositoryTest 追加 CRUD |
| QA-S1-3 | Prompt 种子导入 | PromptService 追加 8 个 QA Prompt 种子导入 | QA-S1-1 | `PromptService.java` | 空库启动后 prompt_version 表有 11 行（3 原有 + 8 QA） |
| QA-S1-4 | AgentTypeResolver 路由扩展 | constraints_json.agent_type 显式指定优先；追加 QA 四种路由 | QA-S1-1 | `DefaultAgentTypeResolver.java` | QA Lead/Tester/Analyst/Researcher 各返回正确白名单；Designer/Tester/Research 不受影响 |
| QA-S1-5 | AgentEventType 追加 | 追加 QA_RELEASE_GATE_CREATED / QA_ROLE_COMPLETED / QA_SUBTASK_CREATED（**A9 裁决：MVP 零新事件，延后 QA-S5**） | QA-S1-1 | `AgentEventType.java` | MVP 不依赖 QA_* 事件；前端继续使用既有 26 事件。事件追加移到 QA-S5，届时 26→29 |

### 16.3 QA-S2 任务树

| Task | 名称 | 目标 | 前置 | 关键文件 | 验收标准 |
|------|------|------|------|----------|----------|
| QA-S2-1 | QA 编排工具 ×4 | qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings | QA-S1-2, QA-S1-4 | `qa/*.java` | 4 工具注册；单测通过；ToolRegistryIntegrationTest 断言总数 23+4=27 |
| QA-S2-2 | PlayerProfile 人格（Prompt 驱动） | 3 人格（NOVICE/AGGRESSIVE/BUILD_SPECIALIST）经 Prompt ROLE_FOCUS 区分，**不修改 FakeGameEnvironment**（冻结） | 无 | `prompts/qa_player_*_p001.md` | FakeGameEnvironment 零修改；人格差异体现在 Prompt 与游戏内 act 序列（A8/§10.2 口径） |
| QA-S2-3 | QA REST API | TesterController 追加 8 个 QA 端点 | QA-S1-2 | `api/QaController.java` | MockMvc 8 端点正常/异常分支 |
| QA-S2-4 | 前端类型扩展 | types.ts + api.ts 追加 QA 类型和 API 函数 | QA-S2-3 | `frontend/src/types.ts` | tsc --noEmit PASS |

### 16.4 QA-S3 任务树

| Task | 名称 | 目标 | 前置 | 关键文件 | 验收标准 |
|------|------|------|------|----------|----------|
| QA-S3-1 | QA Lead Prompt | qa_lead_p001.md 编写 + 验证 | QA-S1-3 | `prompts/qa_lead_p001.md` | Prompt 内容通过 AgentTypeResolverTest 断言 |
| QA-S3-2 | QaOrchestratorService | 子任务创建/状态查询/汇总逻辑 | QA-S1-2, QA-S2-1 | `qa/QaOrchestratorService.java` | 单测 6/6 PASS（创建/启动/查询/汇总/失败处理） |
| QA-S3-3 | QaReleaseGateService | Finding 汇总 + 阈值校验 + 判定一致性 | QA-S1-2, QA-S1-5 | `qa/QaReleaseGateService.java` | 单测 5/5 PASS（PASS/FAIL/CONDITIONAL/一致性校验） |

### 16.5 QA-S4 任务树

| Task | 名称 | 目标 | 前置 | 关键文件 | 验收标准 |
|------|------|------|------|----------|----------|
| QA-S4-1 | 角色 Prompt ×7 | 7 个非 Lead 角色的 System Prompt 编写 + 验证（3 player + combat + numerical + exploit + ux；Lead 属 QA-S3-1，合计 8） | QA-S1-3 | `prompts/qa_*_p001.md` | 每个 Prompt 通过 AgentTypeResolverTest 断言 |
| QA-S4-2 | FakeLlmGateway QA 剧本 | QA Lead 全链路 E2E 测试 | QA-S2-1, QA-S3-2 | `test/.../QaEndToEndTest.java` | 4 用例 PASS（Lead 编排 / Finding 汇总 / Release Gate / 子 Run 失败处理） |
| QA-S4-3 | Challenge Matrix | QA 挑战测试矩阵 | QA-S4-2 | `test/.../QaChallengeTest.java` | 6 用例 PASS（子 Run 超时 / 角色失败 / Release Gate FAIL / **编排超时兜底** / 空 Finding / 预算耗尽） |

### 16.6 QA-S5 任务树

| Task | 名称 | 目标 | 前置 | 关键文件 | 验收标准 |
|------|------|------|------|----------|----------|
| QA-S5-1 | QA Dashboard 页面 | React 前端 QA Dashboard | QA-S2-4 | `pages/QaDashboardPage.tsx` | tsc PASS / vitest PASS / vite build PASS |
| QA-S5-2 | 文档回写 | README/task-state/技术债更新 | QA-S4-3 | docs | 文档与实际代码一致 |

### 16.7 依赖图

```
QA-S1-1 (AgentType) ─────┬──→ QA-S1-4 (路由) ──┬──→ QA-S2-1 (QA Tools)
                         │                      │
QA-S1-2 (Schema) ────────┤──→ QA-S2-3 (REST) ──┤
                         │                      │
QA-S1-3 (Prompt) ────────┤                      ├──→ QA-S3-2 (Orchestrator)
                         │                      │
                         ├──→ QA-S3-1 (Lead Prompt) │
                         │                      │
QA-S1-5 (Events) ────────┘                      ├──→ QA-S3-3 (Release Gate)
                                                 │
QA-S2-2 (PlayerProfile) ─────────────────────────┤
                                                 │
                                                 ├──→ QA-S4-1 (角色 Prompt)
                                                 │
                                                 ├──→ QA-S4-2 (E2E)
                                                 │         │
                                                 │         ├──→ QA-S4-3 (Challenge)
                                                 │         │         │
                                                 │         │         ├──→ QA-S5-1 (Dashboard)
                                                 │         │         │
                                                 │         │         └──→ QA-S5-2 (文档)
                                                 │         │
QA-S2-4 (前端类型) ──────────────────────────────┘         │
                                                           │
QA-S2-1 + QA-S3-2 + QA-S3-3 ──────────────────────────────┘
```

---

## 17. E2E 验收剧本

### 17.1 剧本 1：QA Lead 全链路 Happy Path

```text
前置条件：configVersion=v001 已存在

1. 创建 QA Task (constraints_json={"agent_type":"QA_LEAD","configVersion":"v001"})
2. 启动 Run → AgentType=QA_LEAD
3. QA Lead 执行：
   a. get_game_config → 确认 v001 存在
   b. qa_create_subtask(PLAYER_AGENT, NOVICE) → SubTask 101
   c. qa_start_subrun(101, NOVICE) → SubRun 1001 (异步)
   d. qa_create_subtask(NUMERICAL_ANALYST) → SubTask 102
   e. qa_start_subrun(102) → SubRun 1002 (异步)
   f. qa_check_subrun_status(1001) → COMPLETED
   g. qa_check_subrun_status(1002) → COMPLETED
   h. qa_collect_findings([101,102]) → Finding 列表
   i. Release Gate 判定 → PASS
   j. create_design_document([RELEASE REPORT] ...)
4. 断言：
   - Lead Run status=COMPLETED
   - qa_role_assignment 行数=2，status=COMPLETED
   - finding 表有 Player Agent 产出的 Finding
   - qa_release_gate 行 decision=PASS
   - design_document 有 [RELEASE REPORT] 前缀
   - SSE 有 QA_RELEASE_GATE_CREATED 事件
```

### 17.2 剧本 2：Release Gate FAIL

```text
前置条件：注入 FakeGameEnvironment hpNegativeAtTick=100

1. 创建 QA Task (configVersion=v001)
2. 启动 Run → QA_LEAD
3. QA Lead 创建 Player Agent + Exploit Hunter 子任务
4. Player Agent 发现 BLOCKER Finding (INVARIANT_VIOLATED: HP < 0)
5. Exploit Hunter 发现 HIGH Finding (CRASH)
6. QA Lead 汇总 → Critical=1, High=1
7. Release Gate 判定 → FAIL (Critical > 0)
8. 断言：
   - qa_release_gate.decision=FAIL
   - qa_release_gate.critical_bugs=1
   - design_document 含 "FAIL" 标记
```

### 17.3 剧本 3：子 Run 失败处理

```text
前置条件：模拟 LLM 异常

1. 创建 QA Task → QA Lead Run
2. QA Lead 创建 3 个子任务
3. 子 Run 1: COMPLETED (0 findings)
4. 子 Run 2: FAILED (LLM 异常)
5. 子 Run 3: COMPLETED (1 finding)
6. QA Lead 汇总时：
   - qa_collect_findings 仅返回 Run 1 和 Run 3 的 Finding
   - Run 2 标记为 FAILED
7. Release Gate 判定基于可用 Finding（不因子 Run 失败而判定 FAIL，但报告中注明失败角色）
8. 断言：
   - qa_role_assignment: 1 COMPLETED, 1 FAILED, 1 COMPLETED
   - qa_release_gate.decision 基于可用数据
   - design_document 含 "子 Run [角色] 失败" 警告
```

### 17.4 剧本 4：多 Build 对比

```text
前置条件：v001 和 v002 均存在

1. 创建 QA Task (configVersion=v001)
2. 执行完整 QA 流程 → Gate 结果记录
3. 创建 QA Task (configVersion=v002)
4. 执行完整 QA 流程 → Gate 结果记录
5. 断言：
   - 两个 qa_release_gate 行可按 config_version 查询
   - Finding 可按 config_version 过滤
   - Regression 可对比两版本 Finding 差异
```

---

## 18. 技术债影响分析

### 18.1 现有技术债对 QA 的影响

| 编号 | 描述 | 对 QA 的影响 | 建议处理时机 |
|------|------|-------------|-------------|
| TD-001 | JDK 21 安装路径 | 无影响（QA 复用同一 JDK） | 不阻塞 |
| TD-002 | Maven 本地仓库 | 无影响 | 不阻塞 |
| TD-003 | ToolRegistry 测试隔离 | **中等影响**：QA 新增 4 个 Tool 后测试注册更复杂 | QA-S1 实施时评估 |
| TD-005 | P6-4 ConfigController 文档冲突 | 无影响 | 不阻塞 |
| TD-006 | acceptableBuildDpsVariance 代理 | **低影响**：QA Numerical Analyst 复用同一 EvaluationService | 不阻塞 |
| TD-T6-001 | FindingService 未验证 configVersion | **中等影响**：QA Finding 可能指向不存在的版本 | 建议在 QA-S1 清偿 |
| TD-T7-001 | Full-suite Spring Context 污染 | **高影响**：QA 测试新增后 full `mvn test` 的 context 污染可能加剧 | 建议在 QA-S1 排查 |
| TD-P8-1-001 | Tester v001 fixture 问题 | **高影响**：QA 复用同一 TestPlanService，可能继承同一 fixture 问题 | 建议在 QA-S1 清偿 |

### 18.2 QA 引入的新技术债（预计）

| 预计编号 | 描述 | 风险 | 缓解 |
|----------|------|------|------|
| TD-QA-001 | QA Lead 步数消耗大（编排开销） | 中 | maxSteps=200 配置；Lead Prompt 约束最小步数 |
| TD-QA-002 | 子 Run 并发度受单线程限制 | 低 | MVP 可接受；二期可改为多线程执行 |
| TD-QA-003 | PlayerProfile 人格差异仅靠 Prompt | 低 | 二期可增加 FakeGameEnvironment 人格感知 |

---

## 19. 非阻塞建议

### 19.1 Phase 2 Builder Agent（独立方向）

05_AI自动做游戏Agent.md 定义了 Autonomous Builder 的愿景。Builder Agent 与 QA Team 有天然的数据消费关系：

```text
QA Team 发现问题 (Finding)
    → Builder Agent 自动修复 (file.read / file.write / build.run / test.run)
    → QA Team 回归验证 (Finding FIXED)
```

**建议**：Builder Agent 作为 Phase 3 独立项评估，不在 QA Team Sprint 中实现。两者通过 Finding 表的 status 字段（OPEN → CONFIRMED → FIXED）解耦。

### 19.2 后续增强方向

| 方向 | 描述 | 依赖 |
|------|------|------|
| 真实游戏适配 | GameAdapter 实现真实 Steam/网页游戏 | WebGameAdapter / UnityAdapter |
| 多 ConfigVersion 并行测试 | 同时测试 v001 和 v002 | 并行子 Run 增强 |
| ML 驱动的 Player Agent | 使用 RL 训练玩家策略 | 独立 ML 基础设施 |
| 自动化 Release Pipeline | QA Gate PASS → 自动部署 | CI/CD 基础设施 |

---

## 20. 附录

### 20.1 Agent 扩展标准检查表（01_Agent_平台架构.md §11）

| # | 标准 | QA Lead | QA Tester | QA Analyst | QA Researcher |
|---|------|---------|-----------|------------|---------------|
| 1 | Agent ID | QA_LEAD | QA_TESTER | QA_ANALYST | QA_RESEARCHER |
| 2 | Description | QA 协调者 | 游戏操作/漏洞探测 | 数值/战斗分析 | 研究/UX 评估 |
| 3 | System Prompt | qa_lead_p001.md | qa_player_novice_p001.md / qa_player_aggressive_p001.md / qa_player_build_specialist_p001.md / qa_exploit_hunter_p001.md | qa_combat_analyst_p001.md / qa_numerical_analyst_p001.md | qa_ux_reviewer_p001.md |
| 4 | Tool List | 6 个 QA 工具 | 10 个 Tester 工具（8+web_search+fetch_page） | 7 个混合工具 | 10 个混合工具（Research 6+…） |
| 5 | Input Contract | configVersion | configVersion + playerProfile | configVersion | configVersion |
| 6 | Output Contract | Release Gate + Report | Finding | Analysis + Finding | Research + Finding |
| 7 | Project Context | taskId + runId | subTaskId + subRunId | subTaskId + subRunId | subTaskId + subRunId |
| 8 | Approval Policy | 无 | 无 | 无 | 无 |
| 9 | Error Policy | 子 Run 失败→记录继续 | 标准 ToolException | 标准 ToolException | 标准 ToolException |
| 10 | Test Strategy | E2E FakeLlmGateway | 复用 Tester Challenge Matrix | 单测 + 集成 | 单测 + 集成 |

### 20.2 与 03 文档 Release Gate 阈值对照

| 阈值 | 03 文档 | 本提案 | 说明 |
|------|---------|--------|------|
| Critical Bug > 0 | FAIL | FAIL | ✅ 一致 |
| Soft Lock > 1% | FAIL | FAIL | ✅ 一致 |
| Dominant Build > 50% | FAIL | FAIL | ✅ 一致 |
| Crash Rate > 0.5% | FAIL | FAIL | ✅ 一致 |
| CONDITIONAL_PASS 条件 | 未定义 | 无 Critical + High ≤ 3 + 建议修复项 | 本提案补充 |
| PASS 条件 | 未定义 | 无 Critical + High ≤ 1 + Medium ≤ 5 | 本提案补充 |

### 20.3 文件变更预测汇总

| 类别 | 新增文件 | 修改文件 |
|------|----------|----------|
| Java 生产代码 | ~16 个（qa/ 包 + 2 个 Repo） | 4 个（AgentType + DefaultAgentTypeResolver + PromptService + schema.sql 追加；**AgentLoop 零修改**【A1】） |
| Java 测试代码 | ~12 个（QaEndToEndTest 等） | 3 个（AgentTypeResolverTest + TesterRunWiringTest + EventPublishSseTest） |
| SQL | 0（追加到 schema.sql） | 1（schema.sql 追加 2 表） |
| 前端 | 3 个（QaDashboardPage + types 扩展 + api 扩展） | 2 个（App.tsx + main.tsx） |
| Prompt | 8 个（qa_lead + 3 player + combat + numerical + exploit + ux） | 0 |
| 文档 | 1 个（本文件） | 2 个（README + task-state） |

### 20.4 名词对照

| 术语 | 本提案含义 |
|------|-----------|
| QA Lead | QA 团队协调者 Agent，负责编排+汇总+Release Gate |
| Player Agent | 模拟真实玩家行为的 Agent，有多种人格 |
| Combat Analyst | 分析战斗系统平衡性的 Analyst |
| Numerical Analyst | 分析数值系统（DPS/TTK/收益曲线）的 Analyst |
| Exploit Hunter | 主动寻找漏洞的 Agent |
| UX Reviewer | 评估用户体验的 Agent（纯数据，无 CV） |
| Release Gate | QA 测试结果的发布判定（PASS/CONDITIONAL_PASS/FAIL） |
| SubTask | QA Lead 为角色创建的子 Task |
| SubRun | 角色执行测试的子 Run |
| Intra-Run Orchestration | QA Lead 在单个 Run 内通过 Tool 调用分发子任务 |

---

> **文档状态**：DRAFT — WAITING FOR HUMAN REVIEW
> **下一步**：人工审查本提案后，决定是否批准开始 QA-S1 Sprint。
> **STOP 条件**：本提案审查通过前，不得开始任何 QA 代码实施。
