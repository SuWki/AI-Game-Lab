# QA P1 Implementation Audit

> 生成：只读审计（未修改任何代码） | 日期：2026-09-09
> 范围：QA P0 实际完成情况验证 → 当前 QA Domain Model / Finding 生命周期 / Evidence / REST / Frontend / Tool / Isolation → P1 Task Tree
> 状态：**AUDIT-COMPLETE（待人工接受）**
> 特殊说明：Proposal（docs/QA_SYSTEM_ARCHITECTURE_PROPOSAL.md）在 P1 中仅作为 **Future Architecture Baseline**，本审计不实施 Multi-Agent Team / Release Gate。

## 〇、审计修正说明（2026-09-09 审计复核时添加）

> 本审计经对抗复核后发现两处需要显著标注的结论，现补充至文档头部：

### 修正 1：QA P0 实际未交付

§1 审计结论显示 QA P0 声明的 7 项交付（QA Agent 基础类型/路由、QA Prompt、QA Tool、Finding 创建、Finding 持久化、复用 Task/Run/ToolExecutor、QA 基础测试）在当前代码库中均未发现对应实现。关键证据链：
- `AgentType.java:14-18` 仅 3 个值（DESIGNER/TESTER/RESEARCH），无 QA
- `DefaultAgentTypeResolver.java:121-149` 穷尽 switch 无 QA 分支
- `TaskController.java:80-88` 白名单只收 3 个值
- `PromptService.java:24-43` 仅 seed 4 个版本，无 QA prompt
- `resources/prompts/` 下无 `qa_*.md` 文件
- `schema.sql` 14 张表，无 `qa_*` 表
- `ApplicationTests.java:62-67` EXPECTED_TABLES 精确 14 张
- `backend/src/main` 目录下无 `com.aigamelab.qa` 包（find 结果为空）
- git：仅 `main` 分支，无 stash，`backend/` 全部 untracked
- `docs/QA_S1_PLAN.md` 状态：**DEFINED（设计）— 不执行**
- 唯一真实存在的 QA 相关测试基础设施：`QaTestFixture.java` / `QaTestFixtureTest.java`（PF-002-A 交付物，仅测试隔离脚手架）

**结论**：QA P0 声明的最小闭环在当前代码中不存在。P1 实际需要从 P0 基础能力开始构建，而非建立在 P0 之上。在 P1 启动前，需人工确认：P0 是否在其他分支/工作区交付？若 P0 确实未交付，P1-1 应额外包含最小 QA AgentType 路由能力的实现。

### 修正 2：§11-§16 Plan 范围修正

审计初稿的 §11-§16 Plan 部分存在范围偏差：规划了 4 个 QA AgentType（QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER）、8 个 QA 人格 Prompt、qa_* 编排工具 4 件套、QaOrchestratorService + QaReleaseGateService、qa_role_assignment/qa_release_gate 两张新表——这些均属于 Multi-Agent Team / Release Gate / 子 Agent 编排范畴，与用户 §三/§十五 P1 约束直接冲突。

**用户 P1 实际范围（§十四执行顺序）**：
- P1-0 Implementation Audit
- P1-1 Finding Domain / Lifecycle
- P1-2 Finding Evidence
- P1-3 QA REST API
- P1-4 QA Frontend
- P1-5 Isolation / Regression
- P1-6 Acceptance

以下 §11-§16 已按此范围修正，删除所有 Multi-Agent 规划内容。

---

## 一、审查范围与证据来源

本审计基于以下实际代码/文档核对（只读）：QA P0 backend implementation audit (backend/src/main + backend/src/test)、backend/src/main REST API surface (Spring MVC controllers, envelope, error contract, QA/Tester endpoints)、frontend/src、Database schema layer (backend/src/main/resources/schema.sql) — AI Game Lab Phase 1 + Tester/R&D additions、Designer subsystem (P0-P8, FROZEN) — experiment/config-version/approval services, sim engine, tool layer, and the QA data-isolation line、docs/QA_SYSTEM_ARCHITECTURE_PROPOSAL.md — extraction of what QA P1 must NOT block for the future QA Analyst baseline、Backend Error Contract、workflow-gating + QA state survey (docs only, read-only)。
测试基线（参照 docs/reports/QA_PREFLIGHT_BASELINE.md）：**443 tests / 0 failures / 0 errors**（Research R1 clean build）。

# QA P1 实施审计：P0 实际完成情况 / Domain Model / Finding 生命周期 / Finding Evidence

> 审计范围：仅依据运行时与 schema 事实（backend/src/main、backend/src/test、schema.sql、docs），本次为只读起草，不含任何代码改动。

---

## §1 P0 实际完成情况

### 逐条结论

对 QA P0 声明的 7 个能力逐一核对，**全部 7 项均未由 QA P0 交付**（其中 3 项存在"可复用但非 QA 交付"的前置基础设施，判定为"部分实现"仅指该前置物存在，而非 QA P0 本身完成）。

| # | P0 声明 | 判定 | 证据 |
|---|---------|------|------|
| 1 | 基础类型/路由（QA AgentType + Resolver） | **未实现** | `AgentType.java:14-18` 仅有 `DESIGNER/TESTER/RESEARCH` 3 个值，无任何 `QA_*`（QA-S1 S1-1 规划的 QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER 共 7 个值未交付）。`DefaultAgentTypeResolver.java:121-133` systemPrompt() 与 `:143-149` toolWhitelist() 均为对 3 类型的穷尽 switch，无 QA 分支；`:176-181` resolveFromConstraints 用 `AgentType.valueOf`，错误消息只列 3 个类型。`TaskController.java:80-88` agentType 白名单同样只收 3 值，QA 值会被 `IllegalArgumentException` 拒绝。 |
| 2 | Prompt（QA 系统提示词种子） | **未实现** | `PromptService.java:24-43` 只 seed 4 个版本：p001(designer, active)、tester_p001、research_p001、designer_p002；无 `importQaSeedsIfAbsent`。`DefaultAgentTypeResolver.java:45-50` 只有 `TESTER_PROMPT_VERSION`/`RESEARCH_PROMPT_VERSION` 常量，无 QA prompt 常量。`resources/prompts/` 下只有 designer_p001/designer_p002/research_p001/tester_p001 四个 md，`qa_*.md` 为 0 个。 |
| 3 | Tool（QA 编排工具集） | **未实现** | main 中不存在任何 `qa_` 前缀工具（无 qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings）。`ToolRegistry.java:22-34` 只是 Spring 收集全部 GameTool bean——因 QA 工具不存在所以注册表里天然没有。`com.aigamelab.tool` 10 个 Designer 工具、`com.aigamelab.tester` 8 个、`com.aigamelab.research` 6 个，无 QA 工具。 |
| 4 | Finding 创建 | **部分实现（非 QA 交付）** | 唯一能创建 Finding 的 `FindingReportTool.java:29`（NAME=`finding_report`）位于 `com.aigamelab.tester`，只在 Tester 白名单可达（`DefaultAgentTypeResolver.java:56-58`）。`FindingService.java:38-41` report() 校验+插入——这是早于 QA 的通用 Tester 路径能力，不是 QA P0 产物。 |
| 5 | Finding 持久化 | **部分实现（仅 finding 表，无 QA 表）** | `schema.sql:164-184` finding 表已存在（T-DB1 批准），`FindingRepositoryImpl.java:50-82` JDBC 插入可用。但 QA-S1 S1-2 规划的 2 张 QA 表（qa_role_assignment / qa_release_gate，14→16）**未交付**；`ApplicationTests.java:62-67` EXPECTED_TABLES 仍为精确 14 张，坐实无 QA schema。 |
| 6 | 复用 Task/Run/ToolExecutor | **部分实现（可复用，但 QA 无法接入）** | `AgentLoop.java:239-250` 已通过 AgentTypeResolver 委托 prompt 选择与工具暴露，`ToolExecutor.java:83-99` 调度可用——扩展点是真实的。但因为没有 QA AgentType，QA 无法走通该链路；`Run.java:10-25` 无 agentType 字段，路由只能靠 `task.constraints_json`。 |
| 7 | 基础测试 | **部分实现（仅为测试基础设施）** | `src/test` 中 `qa/support/QaTestFixture.java:31-99`（tempDb 每类临时 SQLite + FK 安全 reset + v001 seed）与 `QaTestFixtureTest.java:33-157`（校验 fixture 自身）是测试隔离脚手架，**不是 QA agent 功能测试**。`AgentTypeResolverTest` 12+ 用例只覆盖 3 类型、零 QA 路由测试；`TesterRunWiringTest` 仅双轨（Tester/Designer），无 QA wiring 测试。 |

### 综合判定：QA P0 **未交付最小闭环**

**结论：否——QA P0 没有交付任何意义上的最小闭环。** 生产代码中不存在 `com.aigamelab.qa` 包（main 下 Glob 为 0 文件）；无 QA 控制器/端点、无 QA 前端页面、无 QA AgentType、无 QA prompt 种子、无 QA 工具、无 QA 表。代码证据链一致且互证：

- `AgentType.java:14-18`（3 值）+ `DefaultAgentTypeResolver.java:121-133/143-149`（穷尽 switch 无 QA 分支）+ `TaskController.java:80-88`（白名单 3 值）+ `PromptService.java:24-43`（4 版本无 QA seed）+ `resources/prompts/`（0 个 qa_*.md）+ `ApplicationTests.java:62-67`（14 表断言）= 后端全部路由/提示词/schema 面均无 QA 痕迹。
- 文档与代码互相印证：`docs/QA_ARCHITECTURE_DECISION_REVIEW.md` 将 QA 记为 **NOT STARTED**（草稿等人评审）；`docs/QA_S1_PLAN.md` 状态 **DEFINED（设计）**，明确未经"ARCHITECTURE FREEZE 人工批准 → PRE-FLIGHT CLEARANCE → QA-S1 独立授权"三级放行不得执行。`task-state.md` 中 QA 出现 0 次，无任何 QA Sprint 历史/验收报告。

唯一真实存在且可被 P1 直接复用的能力是**早于 QA 的通用 Tester 路径原语**：`finding_report → FindingService → FindingRepository → finding 表`（Finding.java:38-46 的 SEVERITIES/CATEGORIES/SOURCES/STATUSES 契约、:54-68 Evidence 锚点），以及两份测试隔离脚手架（QaTestFixture/QaTestFixtureTest）。P0 的 7 项声明对"实际 QA agent"而言全部 NOT FOUND。

---

## §2 当前 QA Domain Model

### Finding 作为领域对象（现状全字段）

依据 `map:finding-domain` + `map:schema`，Finding 已是一个完整领域对象，DDL 与 Java 双面定义：

- **Java 枚举契约**（`Finding.java:38-46`）：`SEVERITIES=[BLOCKER,HIGH,MEDIUM,LOW,INFO]`；`CATEGORIES=[CRASH,SOFT_LOCK,INVARIANT_VIOLATION,UNEXPECTED_STATE,BALANCE_ANOMALY,OTHER]`；`SOURCES=[INVARIANT,LLM,MANUAL]`（与 category 正交）；`STATUSES=[OPEN,CONFIRMED,DISMISSED,FIXED]`。
- **Evidence 记录**（`Finding.java:54-68`）：`sessionId + runId/toolCallIds` 锚点，是 JSON 契约而非 DB 表。
- **表结构**（`schema.sql:164-184`）：`id` PK；**三路关联** `run_id INT REFERENCES run(id) (null)` / `test_plan_id INT REFERENCES test_plan(id) (null)` / `task_id INT REFERENCES task(id) (null)`（均可空、无级联，append-only）；`scenario_id TEXT NOT NULL`；`config_version TEXT NOT NULL`（冗余存，回归查询路径）；`severity/category` NOT NULL；`source DEFAULT 'LLM'`；`title NOT NULL`；`expected/actual TEXT (null)`；`repro_steps TEXT NOT NULL`（Markdown：seed + scenarioId + toolCallId-range 复现路径）；`evidence_json DEFAULT '{}'`；`reproduction_rate REAL (null)`；`status DEFAULT 'OPEN'`；`created_at DEFAULT datetime('now','localtime')`。索引：`idx_finding_ver_sev(config_version, severity)`（:183）、`idx_finding_run(run_id)`（:184）；**无 status 单独索引、无 task_id 索引**。

### 一条 QA run/task 如何流到 Finding（现状路径）

当前唯一链路是 Tester 路径，QA 角色尚不可达：

1. 路由：`DefaultAgentTypeResolver.java:99-109` 读 `task.constraints_json.agent_type`（仅允许 DESIGNER/TESTER/RESEARCH），否则回退二进制逻辑（有 ACTIVE test_plan → TESTER，否则 DESIGNER）。`run` 表（`schema.sql:18-35`）与 `Run.java:10-25` 均无 agentType 字段。
2. 创建：`FindingReportTool.java:29`（仅 Tester 白名单）→ `FindingService.report()`（`FindingService.java:38-41` 校验+插入；`:68-102` validate 含 severity/category/source/status 白名单、PF-003 `config_version` 必须存在于 `game_config_version` 否则 40401、`evidence_json` 必须反序列化为 Evidence 且 sessionId 非空）→ `FindingRepositoryImpl.java:50-82` JDBC 插入 finding 表。
3. 读取：`TesterController.java:92-129` 暴露 GET `/api/findings`（configVersion/severity/runId 过滤）、GET `/api/findings/{id}`、POST `/api/findings/{id}/status`（手动确认/驳回）、GET `/api/findings/regression`（`FindingRegressionService.diff` 跨版本对比）；前端 `FindingsPage.tsx`（列表 + Drawer 详情 + 状态流转 + 过滤）已存在。

### 对 P1 而言：已存在 vs 缺失

**已存在（可直接复用，零 schema 改动）**：finding 表全部字段（含可空的 run_id/test_plan_id/task_id FK、config_version、repro_steps、evidence_json、reproduction_rate）；`finding_report → FindingService → FindingRepository` 创建链；Finding 四枚举；TesterController 的 4 个 Finding 查询/状态/回归端点 + ApiResp 包络 + BusinessException 错误契约；前端 FindingsPage（本身即"QA Finding 页"）；QaTestFixture 测试隔离。

**缺失（P1 需补齐）**：QA AgentType 4 值与 Resolver 穷尽 switch 扩展（`AgentType.java:14-18` / `DefaultAgentTypeResolver.java:121-133,143-149`）；QA prompt 种子（`PromptService` 现有 idempotent import 模式 `:94-119` 可直接复制，`importTesterSeedIfAbsent`/`importResearchSeedIfAbsent`）；QA 编排工具（qa_create_subtask 等 4 个）；`QaOrchestratorService` / `QaReleaseGateService`；2 张 QA 表（qa_role_assignment / qa_release_gate，需人工批准 migration，`ApplicationTests.EXPECTED_TABLES` 14→16 同步）；`/api/qa/*` 端点（现 28 端点零改动约束）；Finding 的 SQL 层 agent-type 过滤路径（task/run 无 agent_type 列，需 JOIN task + JSON1 取 constraints_json——P1 若需按 TESTER/DESIGNER 来源过滤需注意此 gap）；GET `/api/findings` 未暴露 testPlanId/taskId/status 过滤（repo 路径支持 testPlanId 但 `TesterController.java:97` 硬编码 null）。

---

## §3 Finding 生命周期

### 现状：无显式状态机，仅自由状态字符串 + 白名单

**结论：今天不存在显式生命周期状态机。** 状态只是 `finding.status` 上的一段受白名单约束的字符串：

- 合法取值：`Finding.STATUSES=[OPEN,CONFIRMED,DISMISSED,FIXED]`（`Finding.java:38-46`；`schema.sql:180` status 默认 'OPEN'）。
- 强制约束：仅 `FindingService.validate`（`FindingService.java:68-102`）做**集合成员校验**（status ∈ STATUSES），以及 `POST /api/findings/{id}/status`（`TesterController.java:107-115`）的"手动确认/驳回"入口。**没有任何迁移合法性矩阵**——代码中不存在 OPEN→CONFIRMED 合法而 OPEN→FIXED 非法的判定；任意状态→任意状态在 Java 层都是可写的。
- 无状态迁移/审计表：无 lifecycle-transition 表（map:schema 明示"transitions are pure Java"）；无迁移历史，无法审计 OPEN→CONFIRMED→FIXED 轨迹。
- 去重：**无任何去重机制**。finding 表无 UNIQUE 键，`(config_version, scenario_id, run_id)` 可重复插入；去重只能靠 LLM/人工通过 `evidence_json` 里的 runId/toolCallIds 锚点（`Finding.java:54-68`）与 `repro_steps` 的 seed+scenarioId+toolCallId-range 人工比对。

### P1 最小生命周期建议：**复用现有 4 状态，不新增状态、不新增表**

约束锚点：QA 提案明文规定不新增 Finding 状态（schema.sql:180 的 OPEN/CONFIRMED/DISMISSED/FIXED 已够用），提案唯一的 OPEN→CONFIRMED→FIXED 想法属 Phase 3 Builder、明确不在 QA Sprint 实现（docs/QA_SYSTEM_ARCHITECTURE_PROPOSAL §19.1:1144）；finding 表 DDL 属"must not alter"红线。因此 P1 不做 DDL，**只在 Java 层加一个静态迁移白名单校验器**即可获得最小状态机：

**合法迁移（LEGAL）**：`OPEN→CONFIRMED`、`OPEN→DISMISSED`、`CONFIRMED→FIXED`、`CONFIRMED→DISMISSED`（驳回已确认项）。
**非法迁移（ILLEGAL，应 40901 CONFLICT 拒绝）**：`OPEN→FIXED`（未确认不得直接修复）、`CONFIRMED→OPEN`（已确认不可回退）、`DISMISSED→*` 与 `FIXED→*`（终态不可再迁移）。

**实现方式（无新表）**：在 `FindingService` 内定义一个静态 `Map<FindingStatus, Set<FindingStatus>> LEGAL_TRANSITIONS`（纯 Java，零 DDL），在 `POST /api/findings/{id}/status` 的更新路径（`TesterController.java:107-115` → FindingService）先校验来源状态→目标状态是否在映射内，非法则抛 `BusinessException(40901)`（复用 `BusinessException.conflict()`，ErrorCodes 冻结 6 码内，符合错误契约）。映射表为单一事实源，可测试（复用 QaTestFixture 隔离）。若 P1 需要"驳回理由"，现有 `finding` 表无该列——**建议用现有字段承载**（如把人工驳回语义写进 `evidence_json` 扩展字段或 expected/actual 备注），不为此加列。迁移历史（审计）明确排除在 P1 之外，留待需要时以追加式新表（IF NOT EXISTS）另立 Task。

---

## §4 Finding Evidence 能力

### 现有 Evidence 字段清单

| 字段 | 位置 | 现状 |
|---|---|---|
| `evidence_json` | `schema.sql:164-184`（DEFAULT '{}'） | JSON 契约 = `Finding.Evidence`（`Finding.java:54-68`），锚定 `sessionId + runId/toolCallIds`；仅 Java 层校验反序列化且 sessionId 非空（`FindingService.java:68-102`），**DDL 不强制结构** |
| `repro_steps` | finding 表，NOT NULL | Markdown 复现路径：seed + scenarioId + toolCallId-range |
| `expected` / `actual` | finding 表，**可空（null）** | 预期/实际表现，创建时未强制非空 |
| `scenario_id` | finding 表，NOT NULL | 场景标识 |
| `config_version` | finding 表，NOT NULL | 被测版本（回归查询主键，`idx_finding_ver_sev`） |
| `source` | finding 表，DEFAULT 'LLM' | INVARIANT/LLM/MANUAL，正交于 category |
| `severity` / `category` / `title` / `reproduction_rate` | finding 表 | 严重度/类别/标题/复现率（rate 可空） |
| 关联 | `run_id` / `test_plan_id` / `task_id` | 三路可空 FK，append-only，无级联 |

### 评估：未来的 Tester/Analyst/Human 能否从今天的字段理解"为什么这是问题"

**部分可以，骨架已够、细节不足。** 已具备可读的"是什么/在哪发生/怎么复现"：`title` + `expected/actual` + `repro_steps`（含 seed/scenarioId/toolCallId-range 恢复路径）+ `evidence_json`（sessionId/runId/toolCallIds 指向可回放的 step/tool_call）+ `severity/category/source/config_version/reproduction_rate`。一个 Analyst 顺着 run_id/tool_callIds 可以回溯 `tool_call` 表的 arguments/result 与 `step` 的 llm_content 还原现场。

缺口（会导致"为什么这是问题"不完整）：
1. `expected/actual` 可空——创建时可不填，Human 看到的可能是"有问题但没说预期是什么"。
2. `evidence_json` 结构无 DDL 强制——内容随 LLM 发挥，无法保证一致性。
3. 无"影响/根因"显式字段、无状态变更历史、无操作者/时间戳（只有 created_at，无 updated_at/actor）。
4. 无与 experiment 定量结果（win_rate/dps）的直接链接字段。

### P1 最小 Evidence 建议：**结论为"无需新增字段"，改为收紧契约**

红线约束决定方向：finding 表 DDL 冻结（QA 提案明文"TestPlan/Finding DDL 不冲突 QA 复用同一表结构"、无 qa_finding 表计划、must-not-block #1 禁止改 finding DDL），故 **P1 不新增任何证据列**。最小动作是 Java 层收紧契约、把已有字段"喂满"：

1. **在 `FindingService.validate`（`FindingService.java:68-102`）强制 `expected` 与 `actual` 非空**（纯 Java 改动，零 DDL）——保证每条 Finding 都有"预期 vs 实际"，这是 Human/Analyst 理解"why"的第一道门槛。
2. **固定 `evidence_json` 写入契约**：以 `Finding.Evidence`（sessionId/runId/toolCallIds）为唯一必需锚点，`finding_report`（`FindingReportTool.java:29`）写入时强制补齐，保证可回放性一致。
3. **不新增影响/根因字段**：P1 阶段用现有字段承载（title 表意 + severity 定级 + category 定性已足够支撑 Tester/Lead 决策），根因分析属未来 QA Analyst 工作（QA_ARCHITECTURE_FREEZE 提案级别），届时按追加式迁移另立 Task。
4. `reproduction_rate` 保留可空语义，但建议 P1 在 `finding_report` 工具层尽量填充（有则填，无则空），不做硬约束。

综上：§4 结论为 **P1 无需新增证据字段**，只做 `FindingService.validate` 的 expected/actual 非空收紧 + evidence_json 契约固化，即可让 Tester/Analyst/Human 从今日字段读到"为什么这是问题"。

---

**关键文件索引**：`backend/src/main/java/com/aigamelab/runtime/AgentType.java`、`backend/src/main/java/com/aigamelab/runtime/DefaultAgentTypeResolver.java`、`backend/src/main/java/com/aigamelab/runtime/PromptService.java`、`backend/src/main/java/com/aigamelab/tester/FindingReportTool.java`、`backend/src/main/java/com/aigamelab/tester/FindingService.java`、`backend/src/main/java/com/aigamelab/tester/Finding.java`、`backend/src/main/java/com/aigamelab/repository/FindingRepositoryImpl.java`、`backend/src/main/java/com/aigamelab/api/TesterController.java`、`backend/src/main/resources/schema.sql`、`backend/src/test/java/.../qa/support/QaTestFixture.java`、`docs/QA_S1_PLAN.md`、`docs/QA_ARCHITECTURE_DECISION_REVIEW.md`。

---

## §5 当前 REST API

> 依据：map:rest-api + map:error-contract

### 5.1 Controller 清单与端点数量

后端在 `com.aigamelab.api` 包下共有 **9 个 Controller + 1 个 @RestControllerAdvice（GlobalExceptionHandler）**，合计暴露 **33 个 HTTP 端点**：30 个 REST JSON 端点（统一返回 `ApiResp<T>` 信封）+ 3 个 SSE 端点（其中 1 个仅 dev profile）。

| Controller | 基路径 | 端点数 | 端点 |
|---|---|---|---|
| ExperimentController | `/api/experiments` (ExperimentController.java:37) | 4 | GET list（过滤 configVersion/scenario）、GET {id}（解析 result_json）、POST run（异步 202）、POST compare（z 检验） |
| ConfigController | `/api/config` (ConfigController.java:44) | 4 | GET versions、GET proposals、POST proposals/{id}/approve、POST proposals/{id}/reject（纯委托 ApprovalService） |
| TaskController | `/api/tasks` (TaskController.java:43) | 3 | POST 创建（201）、GET list、POST {id}/runs 启动 Run（201） |
| RunController | `/api/runs` (RunController.java:36) | 5 | POST {id}/cancel、resume、retry、GET list（硬编码 LIMIT 200）、GET {id}（Run+Steps+ToolCalls） |
| TesterController | `/api` (TesterController.java:32) | 8 | POST tasks/{id}/test-plan（DRAFT）、GET/PUT test-plans/{id}、POST test-plans/{id}/activate、GET findings、GET findings/{id}、POST findings/{id}/status、GET findings/regression |
| ResearchController | `/api/research` (ResearchController.java:30) | 5 | GET sources、GET sources/{id}、GET claims、GET claims/{id}、GET reports |
| DashboardController | `/api/dashboard` (DashboardController.java:29) | 1 | GET stats |
| SseController | （无类级映射）(SseController.java:24) | 2 | GET /sse/runs/{id}（Last-Event-ID 回放，上限 1000）、GET /sse/global |
| DevEventController | @Profile("dev") 专属 (DevEventController.java:18) | 1 | POST /sse/dev/emit（仅 dev profile） |

### 5.2 标准信封与错误 JSON

- **信封**：`ApiResp<T>` record `{code:int, message:String, data:T}`（ApiResp.java:11-23）；成功 `ok()` = `{0,"ok",data}`，错误 `error()` 时 data=null，`@JsonInclude(ALWAYS)` 保证 null data 照常序列化。所有 REST 控制器强制使用该信封（ApiResp.java:8 注释「禁止手写 Map 包络」）。
- **错误码**：ErrorCodes 冻结 6 码（ErrorCodes.java:9-26）：`40001 INVALID_ARGUMENT / 40401 NOT_FOUND / 40901 CONFLICT / 42901 RATE_LIMITED / 50201 UPSTREAM_ERROR / 50000 INTERNAL_ERROR`。`BusinessException`（BusinessException.java:10-32）携带 code，仅 `notFound()`/`conflict()` 两个工厂（40001 需用裸构造器）。
- **统一捕获**：GlobalExceptionHandler（@RestControllerAdvice, GlobalExceptionHandler.java:32-96）把 BusinessException→自身 code、IllegalArgument/参数错误→40001、NoResourceFound→40401、DuplicateKey/DataIntegrity→40901、LlmException→50201（截断 300 字）、未知异常→50000。

### 5.3 HTTP 状态映射（code/100 整除推导）

| ErrorCodes | HTTP |
|---|---|
| 40001 INVALID_ARGUMENT | 400 |
| 40401 NOT_FOUND | 404 |
| 40901 CONFLICT | 409 |
| 42901 RATE_LIMITED | 429 |
| 50201 UPSTREAM_ERROR | 502 |
| 50000 INTERNAL_ERROR | 500 |

### 5.4 人类现在能否通过 REST 查询 Findings？

**明确结论：能 —— 但走的是 Tester 拥有的通用端点，而不是任何 QA 专属端点。** 已有的 4 个 Finding 端点全部真实可用：

- `GET /api/findings` — 列表，支持 configVersion/severity/runId 过滤（TesterController.java:92-98）
- `GET /api/findings/{id}` — 单条详情（TesterController.java:100-104）
- `POST /api/findings/{id}/status` — 手动 confirm/dismiss（TesterController.java:107-115）
- `GET /api/findings/regression` — 版本间回归 diff（TesterController.java:118-129）

因此「QA Finding 页面能查询 Findings」这一能力**已经存在**。**不存在**的是：`/api/qa/*` 命名空间、`testPlanId`/`taskId`/`status` 过滤参数（repository 层 `findByFilter` 已支持 testPlanId，但控制器硬编码为 null，TesterController.java:97）、list-all TestPlans 端点、以及任何分页（runs 硬编码 LIMIT 200、findings 无界 ORDER BY id）。

### 5.5 QA Finding 页面可链接的 tasks/runs 端点

- `POST /api/tasks`（TaskController.java:66-118）创建 Task → 201
- `GET /api/tasks`（TaskController.java:121-124）
- `POST /api/tasks/{id}/runs`（TaskController.java:130-135）启动 Run → 201
- `GET /api/runs`（RunController.java:78-98）
- `GET /api/runs/{id}`（RunController.java:101-117，含 Steps+ToolCalls）
- `POST /api/runs/{id}/cancel|resume|retry`（RunController.java:58-75）
- TestPlan 生命周期 4 端点（见 5.1 TesterController 行）

**约束/备注**：无 CORS、无 Spring Security（pom 仅 starter-web），跨域浏览器访问依赖 Vite 代理（frontend/vite.config.ts:13-14）；`42901 RATE_LIMITED` 已定义但零使用，P1 可安全复用；RunController.java:104 硬编码字面量 40401 而非 ErrorCodes.NOT_FOUND（既有小瑕疵，仅记录）。

## §6 当前 Frontend

> 依据：map:frontend

`frontend/src` 共 23 个文件。技术栈：React 18 + react-router-dom v6 + react-query v3 + Vite 5 SPA，**9 个页面、3 个共享组件（Badge/DefinitionList/Drawer）、全内联样式、SSE 驱动实时刷新**，vitest + jsdom + MockEventSource。

### 6.1 现在是否有 Finding/QA 页面？

**明确结论：有 —— QA Finding 页面已经存在，就是 FindingsPage.tsx。** `frontend/src/pages/FindingsPage.tsx:14-94` 已实现列表 + 过滤（configVersion/severity/runId）+ Drawer 详情 + 状态流转 + SSE 信号刷新；`FindingDetail`（FindingsPage.tsx:96-143）为页内内联组件（未导出、不可复用）。相关页面还有 `TestPlanPage.tsx`（TestPlan + ScenarioCard + AssociatedFindings 客户端过滤）与 `ConfigRegressionPage.tsx`（版本回归 diff）。**不存在独立的「QA Dashboard」** —— QA 表面已由这 3 个页面完整覆盖。

### 6.2 可复用组件/基础设施

- `Badge` + `severityTone()`（frontend/src/components/Badge.tsx:3）—— 任何 finding/severity 展示
- `DefinitionList`（frontend/src/components/DefinitionList.tsx:2）、`Drawer`（frontend/src/components/Drawer.tsx:4，560px 固定浮层）
- SSE 钩子族：`useGlobalSse`/`useRunSse`/`useSseDrivenRefresh`/`useTesterEventSignal`（frontend/src/hooks/useGlobalSse.ts:36-193）
- API：`api.listFindings(FindingFilter)` 与 `api.updateFindingStatus()`（frontend/src/api.ts:18-22、38-46）已类型化且有测试；`unwrap()` fetch 包装（api.ts:4-16）
- 类型：`Finding` 14 字段（frontend/src/types.ts:29-47）+ Severity/Category/FindingStatus/TestPlanStatus

### 6.3 最小 QA Finding 页面（route + page + api + components）= 已存在的组合

| 层 | 现状 |
|---|---|
| 路由 | `App.tsx:45-57` 已有 `/findings` 路由（含 Nav 链接，App.tsx:12-32） |
| 页面 | `FindingsPage.tsx` 已存在 |
| api | `api.ts` 的 `listFindings`/`updateFindingStatus` 已就绪 |
| 组件 | `Badge`/`Drawer`/`DefinitionList` + `useTesterEventSignal` 信号刷新 |

### 6.4 P1 明确不要构建的内容

- **QA Dashboard**（独立 QA 总览/指挥台）—— 不存在，也不要新建
- **Lead/Multi-agent 拓扑**（QA_LEAD 编排、子任务/子运行拓扑可视化）—— task/run 无 parent/child 列，`qa_role_assignment` 表不存在（属 QA 提案 §11.1 未来范围）
- **Release Gate dashboard** —— `qa_release_gate` 表不存在（QA-S1 S1-2 人工批准后的迁移范围）

**代码味道（只记录，P1 不修）**：WorkflowPage.tsx:277-307 本地 Badge 遮蔽共享组件；api.ts 有 2 个接口（ExperimentDetail/SimulationResultLike）应归 types.ts；Th/Td/inputStyle/btnStyle 在 5+ 页面重复。

## §7 QA Tool

> 依据：map:qa-p0 + map:tool-layer + map:tester

### 7.1 QA 工具现状：无专属工具，复用 finding_report

**明确结论：main 中不存在任何 QA 专属工具。** 无 `qa_create_subtask`/`qa_start_subrun`/`qa_check_subrun_status`/`qa_collect_findings`，无任何 `qa_*` 前缀工具名；`AgentType.java:14-18` 仅有 DESIGNER/TESTER/RESEARCH 三值；`DefaultAgentTypeResolver.java` 的 systemPrompt()/toolWhitelist() 穷举 switch 无 QA 分支；无 QA prompt 种子（PromptService 仅 seed 4 个版本，resources/prompts/ 无 qa_*.md）；schema.sql 14 表无 QA 表。

QA 当前唯一能落 Findings 的路径是**复用 Tester 通用原语**：

```
finding_report (FindingReportTool.java:29, com.aigamelab.tester)
  → FindingService.report() (FindingService.java:38-41)
  → FindingRepositoryImpl JDBC insert (FindingRepositoryImpl.java:50-82)
  → finding 表 (schema.sql:164-184)
```

该工具只挂在 Tester whitelist（DefaultAgentTypeResolver.java:56-58，8 工具），QA 角色无 whitelist 可挂。

### 7.2 finding_report 接受什么、产出什么（input → persisted Finding）

**输入**（经 `FindingService.validate` 校验，FindingService.java:68-102）：
- `configVersion`：必须存在于 `game_config_version`（PF-003 存在性校验，FindingService.java:93-96），否则抛 40401
- `severity` ∈ BLOCKER/HIGH/MEDIUM/LOW/INFO；`category` ∈ CRASH/SOFT_LOCK/INVARIANT_VIOLATION/UNEXPECTED_STATE/BALANCE_ANOMALY/OTHER（Finding.java:38-46）
- `source` 默认 'LLM'（INVARIANT/LLM/MANUAL，正交于 category）；`status` 默认 'OPEN'（OPEN/CONFIRMED/DISMISSED/FIXED）
- `scenarioId`（NOT NULL）、`repro_steps`（Markdown：seed + scenarioId + toolCallId 区间恢复路径）
- `evidence_json`：必须能反序列化为 `Finding.Evidence` 且 sessionId 非空（Evidence 锚定 sessionId + runId/toolCallIds，Finding.java:54-68）
- 可选 `expected`/`actual`/`reproductionRate`；`run_id`/`test_plan_id`/`task_id` 可空外键

**产出**：finding 表一行持久化记录。无生命周期/审计表 —— OPEN→CONFIRMED→FIXED 迁移纯 Java 校验（Finding.STATUSES），无 DDL 层约束。

### 7.3 P1 缺失什么

**明确结论：P1 缺的不是工具，而是 REST 查询面 —— 按架构，QA 的查询能力由 REST 提供，不是新工具。**

- 无 `/api/qa/*` 端点（提案 §12.1 为未来 QA 范围）
- `GET /api/findings` 不支持 `testPlanId`/`taskId`/`status` 过滤 —— repository 层 `findByFilter` 已支持（FindingRepositoryImpl.java:99-121），但 REST 把 testPlanId 硬编码为 null（TesterController.java:97）
- 无 list-all TestPlans 端点（`TestPlanRepository.findByTaskId` 已存在但未暴露）
- 无分页（runs 硬编码 LIMIT 200，findings 无界）
- 无按 agent_type 过滤的 SQL 路径（run/finding 无 agent_type 列，agent typing 仅存于 task.constraints_json）

因此 P1 的 REST 增量是**薄加法**：沿用 TesterController 委派 `FindingService.findByFilter` 的既有模式（TesterController.java:92-98），暴露 testPlanId/taskId/status 过滤参数，返回 `ApiResp.ok(...)`、错误抛 `BusinessException`（复用 6 冻结码）——即可支撑 QA Finding 页面。工具侧继续复用 `finding_report` 写 finding 表即可，**不需要任何新工具**。

---

**相关文件（绝对路径）**：
- `d:\AI-Game-Designer\backend\src\main\java\com\aigamelab\api\TesterController.java`
- `d:\AI-Game-Designer\backend\src\main\java\com\aigamelab\api\{TaskController,RunController,ExperimentController,ConfigController,ResearchController,DashboardController,SseController,DevEventController,GlobalExceptionHandler,ApiResp,ErrorCodes,BusinessException}.java`
- `d:\AI-Game-Designer\backend\src\main\java\com\aigamelab\tester\FindingReportTool.java`、`FindingService.java`
- `d:\AI-Game-Designer\backend\src\main\java\com\aigamelab\repository\FindingRepositoryImpl.java`
- `d:\AI-Game-Designer\backend\src\main\java\com\aigamelab\runtime\{AgentType,DefaultAgentTypeResolver}.java`
- `d:\AI-Game-Designer\backend\src\main\resources\schema.sql`
- `d:\AI-Game-Designer\frontend\src\pages\FindingsPage.tsx`、`d:\AI-Game-Designer\frontend\src\components\{Badge,DefinitionList,Drawer}.tsx`、`d:\AI-Game-Designer\frontend\src\{api.ts,types.ts,hooks\useGlobalSse.ts,App.tsx}`

---

Note: deepseek-v4-pro (the safety classifier) was unavailable (timed out) when reviewing this subagent's work. Please carefully verify the subagent's actions and output before acting on them.

## §8 工具隔离（Tool Isolation）

**8.1 今天 QA 能否调用 Designer/Tester/Research 工具？——不能，且严格地说"QA 角色"今天不存在。**

- `AgentType` 枚举只有 3 个值（`d:\AI-Game-Designer\backend\src\main\java\com\aigamelab\runtime\AgentType.java:14-18`：DESIGNER/TESTER/RESEARCH）。`DefaultAgentTypeResolver.resolveFromConstraints`（`...\runtime\DefaultAgentTypeResolver.java:176-181`）用 `AgentType.valueOf(agentTypeValue)` 解析 `task.constraints_json.agent_type`，任何 `QA_*` 字符串会抛 `IllegalArgumentException`（错误消息只列允许值 DESIGNER/TESTER/RESEARCH）。因此今天构造一个 QA Task 根本走不到工具层。
- 即便角色存在，`com.aigamelab.tool` / `com.aigamelab.tester` / `com.aigamelab.research` 下也没有任何 `qa_*` 工具（glob 零命中），QA 编排工具（qa_create_subtask/qa_start_subrun/qa_check_subrun_status/qa_collect_findings）不存在。

**8.2 今天的隔离机制到底是什么（精确陈述）。**

唯一强制点是 **广告层（advertisement）过滤**：`AgentLoop.selectToolSpecs`（`...\runtime\AgentLoop.java:248-263`）在构造 LLM tool specs 时按 `agentTypeResolver.toolWhitelist(agentType)` 过滤，未命中的工具不进入 spec。白名单来自 `DefaultAgentTypeResolver.toolWhitelist` 的穷举 switch（`:143-149`）：DESIGNER→10 工具、TESTER→8 工具、RESEARCH→6 工具，三集合互不相交（唯一跨子系统的是 Designer 的 R2-1 只读消费工具 get_research_context/get_research_claims，是 Designer 自己的工具而非 Research 工具）。

其余两处均无角色概念：

- `ToolRegistry`（`...\tool\ToolRegistry.java:27-34`）构造期由 Spring 收集**全部 24 个 GameTool Bean** 进同一个 `LinkedHashMap`，注册层不做任何按角色限制。
- `ToolExecutor`（`...\runtime\ToolExecutor.java:83-99`）按名 `registry.dispatch(...)`，**执行期不复查白名单**。任何代码直接调用 `registry.dispatch` 即可命中工具，绕过广告层过滤。

结论：三个角色今天互不可见，靠的是"枚举只有 3 值 + switch 穷举"这一**编译器强制的完备性**（新增枚举值不补分支即编译失败，杜绝静默漏配），而不是任何运行期强制。**隔离是 CONVENTION（约定）而非 ENFORCEMENT（强制）**：注册表共享、执行路径不校验、白名单只决定"LLM 能看见哪些工具"。QA 自身工具若加入，会与现有 24 个 Bean 进同一注册表，注册层同样零拦截。

**8.3 P1 在哪加检查（不碰冻结的 ToolExecutor/AgentLoop）。**

合法落地口已由冻结契约指定：AgentType + DefaultAgentTypeResolver（QA_ARCHITECTURE_FREEZE.md AF-001 明确其为 QA 唯一扩展点，AgentLoop 零修改——因为它本来就委托 AgentTypeResolver）。P1 加 QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER 4 个枚举值 + `systemPrompt()`/`toolWhitelist()` 各 4 个分支，即获得与现有 3 角色完全同构的隔离；编译器穷举属性自动继承。

最小附加选项（全部为**新文件/新 Bean**，不触碰冻结类）：

1. **每角色允许工具服务**（concrete minimal）：新增 `@Component RoleToolIsolationService`（新类，如 `com.aigamelab.qa` 包），构造后读取 `ToolRegistry.all()` 与 `agentTypeResolver.toolWhitelist(all AgentTypes)`，暴露 `assertRoleTool(agentType, toolName)`。QA 自己的 4 个编排工具在其 `execute()` 里调用它做前置断言——检查落在 QA 新代码边界，冻结的 dispatch 路径原样保留。
2. 可选加强（启动/CI 期 partition 校验）：断言独占工具仅属于一个角色（Designer 10 / Tester 8 / Research 6），跨角色冲突在启动时 fail-fast。

**诚实局限**：

- 该服务只能约束 QA 新工具的自我行为，不能回改冻结的 ToolExecutor→ToolRegistry 路径；既有 3 角色的 LLM 运行仍只有广告层过滤，一旦 spec 泄漏或代码直连 `registry.dispatch` 则无第二道拦截。
- 提案**有意**让 QA 复用跨角色工具：finding_report 同时进 TESTER/QA_ANALYST/QA_RESEARCHER 白名单，web_search 进 RESEARCH/QA_RESEARCHER，simulate_game 进 QA_ANALYST（`docs\QA_SYSTEM_ARCHITECTURE_PROPOSAL.md` §8.3:472-488）。因此 partition 断言必须只作用于独占工具、共享工具豁免——服务最终保证的是"约定一致性检查 + 审计单点"，不是密码学边界。
- 更根本：白名单只决定"LLM 能看见哪些工具"，不决定"系统会执行哪些工具"。若 QA_ANALYST 被批准拿到 simulate_game，隔离服务只能照单放行，真正的写边界要靠业务服务兜底（PF-001 存在性校验、approve-only 语义、experiment UNIQUE 幂等线）。

## §9 数据隔离（Data Isolation）

**9.1 QA 查询会否意外返回 Designer/Tester/Research 私有数据？——会，且没有任何机制阻止，因为全系统单租户、无鉴权、无所有权过滤。**

- REST 层无 Spring Security、无 CORS（浏览器访问依赖 Vite 代理 `frontend\vite.config.ts:13-14`），任何能访问 :8080 的调用方可打全部 GET 端点。
- **宽暴露查询端点（照单可查，无需任何 run/role 过滤）**：
  - `GET /api/experiments` + `/{id}`（`...\api\ExperimentController.java:54-90`）：Designer 仿真结果（result_json 含 win_rate/avg_dps/601 时间桶聚合）公开可列。
  - `GET /api/runs` + `/{id}`（`...\api\RunController.java:78-117`）：全量 run（LIMIT 200）+ step/tool_call 详情——`tool_call.arguments_json/result_json` 可能内嵌 modify_game_config 的 after_config_json、simulate_game 参数等 Designer 私有载荷。
  - `GET /api/config/versions` + `/proposals`（`...\api\ConfigController.java:66-94`）：game_config_version 全量快照（config_json）与 PENDING proposal（diff_json）公开可读。
  - `GET /api/research/sources` + `/claims`（`...\api\ResearchController.java:51-93`）：runId|taskId 是**参数必需而非所有权校验**——QA 传任意 runId 即可读该 run 的研究数据。
  - `GET /sse/global`（`...\api\SseController.java:50-59`）：全量 agent_event 事件流对任何订阅者广播。
- 结论：数据层不存在"Designer/Tester/Research 私有"的标记或过滤；所谓隔离今天只存在于**写边界**——工具白名单决定谁能写/执行（仅 DESIGNER 工具写 experiment/change_proposal/game_config_version 且 modify_game_config 走 approve-only 链；仅 finding_report 经 Tester 白名单写 finding）。REST 写端点不受角色保护（`POST /api/findings/{id}/status`、`POST /api/config/proposals/{id}/approve|reject` 任何客户端可调），但都经由 owner service 自身状态机校验。

**Finding 是否按设计共享？是。** `schema.sql:163` 注释明示 finding 是"Tester 的一等产出，供 Designer/Builder 消费"；可空 FK run_id/test_plan_id/task_id（`schema.sql:166-168`）使其成为跨角色交接产物。QA 提案复用同一张表、不建 qa_finding（`docs\QA_SYSTEM_ARCHITECTURE_PROPOSAL.md` §5.3:313、§11.3:672），未来 QA Lead 以 `finding WHERE run_id IN (...)` 聚合（§15.2:930）。Tester 写、QA 读 = 同一 entity，这是设计意图而非缺陷。

**9.2 QA Finding 应当暴露什么 / 绝不暴露什么。**

- 应当（即现有 17 列形状，零新字段）：链接层 task_id / run_id / test_plan_id（可空 FK）+ config_version（PF-003 必须真实存在于 game_config_version）+ scenario_id；证据层 repro_steps（Markdown：seed + scenarioId + toolCallId 区间恢复路径）、evidence_json（Evidence 锚点 sessionId + runId + toolCallIds，`...\domain\Finding.java:54-68`）、source、reproduction_rate；质量层 severity/category/status/title/expected/actual/created_at。
- 绝不：不得内嵌 Designer/Research 载荷——experiment.result_json 全文、change_proposal.diff_json/after_config_json、research source 的 content_summary/URL 全文、tool_call.arguments_json/result_json 原文拷贝、agent_event.payload_json 整体拷贝。证据应引用 toolCallId/sourceId 而非复制内容；evidence_json 只能容纳 Evidence 结构（sessionId 必填锚点，`FindingService.validateEvidenceJson` `...\tester\FindingService.java:108-124`）。由于 finding 表无对应列，违反的唯二途径是往 evidence_json/repro_steps 塞 JSON——P1 的硬约束是"QA 写 finding 必须经 `FindingService.report`（继承 validate）"。

**9.3 今天的数据访问边界 + P1 最小补强。**

现状边界 = **Read-wide / Write-narrow**：跨子系统读合法（先例：Tester 的 GameSessionService 读 ConfigVersionService、FindingService PF-003 读 game_config_version、Designer 经 R2-1 只读工具消费 research claims）；QA 读 finding/experiment/game_config_version/research 属同一合法边界；QA 绝不 insert experiment/change_proposal/game_config_version（Designer 独占，PF-001 存在性 + UNIQUE(config_version, seed, simulation_count, scenario) 幂等线）、绝不 dispatch simulate_game。

P1 最小补强（不触碰冻结契约）：

- **暴露 testPlanId 过滤**：repo 已支持（`...\repository\FindingRepositoryImpl.java:115-118`），仅控制器硬编码 null（`...\api\TesterController.java:97`）——纯控制器层加法。
- **status/taskId 过滤**：repo 契约冻结，按 ResearchController:79-84 的 in-memory status filter 先例在 controller/service 层过滤，避免动 FindingRepository 契约。
- **OPEN-findings 队列**：status 单列无索引（仅 idx_finding_ver_sev 复合索引，`schema.sql:183`），status-only 查询会全表扫；最小选择是复用 config_version+severity 过滤，或在下一次人工批准的 schema 编辑中按 IF NOT EXISTS 模式加 `idx_finding_status`。
- **QA 写路径强制经 FindingService.report**（继承白名单 + PF-003 + Evidence 校验）。

**诚实局限**：边界是"列形状 + 约定"而非机制——QA 代理经 service/repo 层可读任何行，REST GET 面完全开放；把 Designer/Research 数据塞进 finding 自由文本字段没有机制能拦，只能靠固定列 + 内容规则。另一已知缺口：run/finding 均无 agent_type 列，agent 类型只存在于 `task.constraints_json`，SQL 级按角色过滤 finding 需 JOIN task + JSON1 提取——但提案意图是 QA 聚合全部 run 的 findings，故非阻塞；若 P1 需要按 TESTER/DESIGNER 来源筛选，这是必须单独提的影响点。

---

## §10 Proposal 对照 (Future Architecture Baseline)

QA_SYSTEM_ARCHITECTURE_PROPOSAL 定义了完整的 QA 多智能体体系（QA_LEAD / QA_TESTER / QA_ANALYST / QA_RESEARCHER 四角色 + 编排工具 + qa_role_assignment / qa_release_gate 两张新表 + 5 个 Sprint QA-S1..QA-S5）。P1 作为 Phase 1 冻结基线实现，**必须为上述 Proposal 的每一项留出扩展空间，不得引入阻塞性设计决策**。以下逐项说明：

| Proposal 未来条目 | P1 为何不阻塞它 |
|---|---|
| **Finding 模型复用** — Proposal §1.3:69 明确"零新增 Finding 字段/状态"，§5.3:313 / §11.3:672 声明"TestPlan/Finding DDL 不冲突，QA 复用同一表结构"；qa_collect_findings 的聚合路径是 `finding WHERE run_id IN (...)` (§15.2:930) | P1 **不新增 qa_finding 表、不改 finding DDL、不加 QA 专属 status 值**。现有 OPEN/CONFIRMED/DISMISSED/FIXED 四态 (schema.sql:180) 已足够 Proposal 全部读取场景。status 过渡 (OPEN→CONFIRMED→FIXED) 是 Phase 3 Builder 建议 (§19.1:1144)，明确不属于 QA Sprint 范围 |
| **QA 角色枚举扩展** — Proposal §8.1:433-444 要求 AgentType 增加 QA_LEAD / QA_TESTER / QA_ANALYST / QA_RESEARCHER 四个独立类型，各有独立 prompt 和 tool whitelist (D2:57, §8.3:467-488) | P1 **不将 QA 合并为单一 agent type**，而是保持 AgentTypeResolver 的 explicit `constraints_json.agent_type`-first 路由 (DefaultAgentTypeResolver.java:99-104)，使四个 QA AgentType 可作为新 enum 值 + switch case 追加，不触碰现有 DESIGNER/TESTER/RESEARCH 分支 (§8.2:448-461) |
| **Task/Run 父子关系** — Proposal §7.2:414 冻结 task/run 表不加列；§11.1:625-637 通过新建 qa_role_assignment 表 (parent_run_id / sub_task_id / sub_run_id) 实现 Lead→Sub 链接；§10.1:562-567 定义 qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings 四个编排工具 | P1 **不在 task/run 表中增加 parent/child 列**，也不实现编排工具。P1 的 Task/Run 创建走现有 TaskController POST /api/tasks + POST /api/runs/{id}/runs 路径，子任务关联预留由 qa_role_assignment DDL 承接 |
| **QA REST 端点扩展** — Proposal §12.1:680-689 计划新增 /api/qa/* 端点族；§12.2:692-693 要求现有 28 端点零修改 | P1 仅在现有 TesterController 上做**纯追加式** Finding 查询增强 (暴露 testPlanId/status/taskId filter)，不新增 /api/qa/* 命名空间，不修改已有端点签名。ApiResp 信封 + ErrorCodes 6 码 + BusinessException 异常契约全部复用，为未来 /api/qa/* 端点提供一致的错误处理模板 |
| **QA Tool Whitelist** — Proposal §8.3:472-488 定义四个角色各自的工具白名单：QA_TESTER = Tester 8 + web_search/fetch_page；QA_ANALYST = Designer 实验工具 + finding_report；QA_RESEARCHER = Research 6 + finding_report + game tools；QA_LEAD = 6 个编排工具 | P1 **不注册任何 qa_* 工具**，也不修改 ToolRegistry/ToolExecutor。DefaultAgentTypeResolver 的 exhaustive switch 随 AgentType 新值自动编译报错 (AF-001 §0 约束)，确保后续 Sprint 添加工具白名单时不会遗漏 |
| **Research 数据复用** — Proposal §10.4:593-600 / §1.2:58 复用 research_source + research_claim 表 + Research 6 工具给 QA_RESEARCHER | P1 **保留 research_source/research_claim 表的现有 schema 和 Research 工具**不变更，QA_RESEARCHER 的未来白名单可直接引用 |
| **Finding 报告聚合** — Proposal §15.2:930 Lead 聚合路径 `finding WHERE run_id IN (...)` + 按 config_version 过滤；§17.4:1101-1102 config_version 为报告维度 | P1 保持 finding 表 config_version 列 + idx_finding_ver_sev (config_version, severity) 索引不变，finding_report 工具继续写入 config_version，确保 Lead 聚合路径的查询计划不受影响 |
| **QA-Tester 模拟人格** — Proposal §10.2:573-577 / §8.3:472-477 QA_TESTER 复用 Player Agent 行为但通过 prompt 驱动人格差异化 | P1 **不修改 TestPlanService.PLAYER_PROFILES** = ["SCRIPTED", "RANDOM"] (TestPlanService.java:34) 或 FakeGameEnvironment；QA 人格差异纯由 prompt 实现，QA subrun 路径不依赖 TestPlanService 骨架 |
| **Sprint 阶段化** — Proposal §16.1-16.6:939-989 定义 QA-S1 基础设施 / QA-S2 Tools / QA-S3 Lead / QA-S4 E2E / QA-S5 前端+收尾共 5 个 Sprint | P1 是冻结 Phase 1 基线 (Proposal §2:95-107)，提供 AgentTypeResolver 扩展点 + QaTestFixture + Finding 查询增强 + 基本端点，但 **不实现 QA-S1 的 qa_role_assignment DDL / QA 编排工具 / 角色 prompt**——这些留给后续 Sprint 且需人工授权 (AF-001 line 3 状态: ARCHITECTURE READY FOR FREEZE 候选) |

---

## §11 P1 Task Tree

以下为 P1 实施任务集，按严格顺序排列。每项标注审计结论：**已存在（可复用）** vs **需新建**。

---

### P1-0 Implementation Audit

**范围**：本文档——对 P0 后端 / 前端 / Schema / 工作流门控进行全量事实核查，产出基线审计报告。**正在执行，自身被引用（being-consulted）**。

| 维度 | 结论 |
|---|---|
| 已存在 | QA 后端零生产代码（backend/src/main 无 com.aigamelab.qa 包）；QA_S1_PLAN 状态 DEFINED（设计）；QA_ARCHITECTURE_FREEZE 状态 ARCHITECTURE READY FOR FREEZE 候选；443/0/0 后端基线 GREEN；前端 16 vitest 全绿 |
| 需新建 | 本审计文档本身 |
| 主要文件 | docs/QA_S1_PLAN.md, docs/QA_ARCHITECTURE_FREEZE.md, docs/QA_ARCHITECTURE_AUDIT_REPORT.md, docs/QA_PREFLIGHT_BASELINE.md |
| 等待前置 | 无（P1-0 是整个 P1 的入口） |

---

### P1-1 Finding Domain / Lifecycle — Hajime

**范围**：QA Agent 的 Finding 领域模型和生命周期。审计发现 **finding 表 DDL + Finding 领域模型 + FindingService + FindingRepository + FindingReportTool 已全部存在且功能完整**，但均为 Tester 路径服务（非 QA 专属）。P1 的工作是验证可复用性并确保 QA 路径可接入。

| 维度 | 结论 |
|---|---|
| 已存在（不重复实现） | ① finding 表 DDL 完整 (schema.sql:164-184, T-DB1 approved)，含 severity/category/source/status 全列 + evidence_json + repro_steps + reproduction_rate；② Finding.java:14-68 领域模型（SEVERITIES 5 值 / CATEGORIES 6 值 / SOURCES 3 值 / STATUSES 4 值 + Evidence record）；③ FindingService.report() (FindingService.java:38-102) 含 validate() 严格校验（PF-003 configVersion 存在性已激活于 :93-96）；④ FindingRepositoryImpl.java:50-82 JDBC insert + findByFilter:99-121；⑤ FindingReportTool.java:29 Tester 路径工具（name=finding_report）；⑥ FindingRegressionService.diff(oldVersion, newVersion) (FindingService.java:58-64)。**证据：FindingService 的 configVersion 存在性校验、severity/category/status 白名单、evidenceJson 反序列化均独立于 Tester，可直接被 QA Agent 调用** |
| 需新建 | ① QA Agent 的 AgentType enum 值 (至少先加一个 QA 占位值，或仅在 AgentTypeResolver 中增加 QA 分支而不改 enum，取决于 P1 决策)；② DefaultAgentTypeResolver 中 QA 分支的 exhaustive switch case（systemPrompt + toolWhitelist 两处）；③ QA prompt seed (qa_p001.md，inactive)；④ QaAgentService 或等价入口，接收 QA Task 并通过 AgentLoop 驱动 |
| 主要文件 | Finding.java, FindingService.java, FindingRepositoryImpl.java, FindingReportTool.java, FindingRegressionService.java, DefaultAgentTypeResolver.java (修改), AgentType.java (修改), PromptService.java (追加 importQaSeedIfAbsent), resources/prompts/qa_p001.md (新建) |
| 等待前置 | P1-0（本审计确认基线） |

---

### P1-2 Finding Evidence

**范围**：Finding 的 Evidence 锚定机制（sessionId + runId + toolCallIds）和回归比较。审计发现 **Evidence record 和回归比较已完整实现**。

| 维度 | 结论 |
|---|---|
| 已存在（不重复实现） | ① Finding.Evidence record (Finding.java:54-68) 含 sessionId + runId + toolCallIds，evidence_json 列存 JSON；② FindingRegressionService.diff() (FindingService.java:58-64) 执行 oldVersion→newVersion 跨版本比较；③ TesterController GET /api/findings/regression (TesterController.java:118-129) 暴露回归 diff 端点。**证据：QA Analyst 汇总 Finding 时所需的 evidence 锚定和回归能力已完整，无需新增代码** |
| 需新建 | ① QA 维度的 Finding 过滤增强——现有 FindingService.findByFilter 仅支持 configVersion / severity / runId / testPlanId 四维过滤 (FindingRepositoryImpl.java:99-121)；QA Lead 聚合需 `run_id IN (...)` 多值过滤 + status 过滤 + task_id 过滤（现有 repo 支持 task_id 但 Controller 未暴露）；② 如需 status-only 索引（idx_finding_status），须在 schema.sql 中追加 CREATE INDEX（仅当 P1 验证全表扫描性能不可接受时） |
| 主要文件 | FindingService.java (findByFilter 扩展), FindingRepositoryImpl.java (findByFilter 扩展), TesterController.java 或新建 QAController (暴露 filter), schema.sql (可选 status 索引) |
| 等待前置 | P1-1（Finding 领域基线确立后方可扩展 filter） |

---

### P1-3 QA REST API

**范围**：QA Agent 的 REST 端点层。审计发现 **零 QA 端点存在**——无 QA controller、无 /api/qa/ 路径、无 QA 专属 endpoint。

| 维度 | 结论 |
|---|---|
| 已存在（可复用模板） | ① TesterController (TesterController.java:32-129) 8 个端点 = 最佳模板，含 Finding CRUD + TestPlan 生命周期；② ApiResp.ok(...) 信封模式（所有 controller 统一）；③ BusinessException.notFound() / .conflict() 工厂 + ErrorCodes 6 码冻结表（RATE_LIMITED 42901 已定义但零使用，可直接复用）；④ GlobalExceptionHandler @RestControllerAdvice 全局异常转码 (GlobalExceptionHandler.java:32-96)。**证据：P1-3 是纯追加——遵循 TesterController 模式新建 QAController，零基础设施改动** |
| 需新建 | ① QaController.java (@RequestMapping("/api/qa")) 至少：POST /api/qa/tasks 创建 QA Task (agent_type=QA_LEAD/TESTER/ANALYST/RESEARCHER)；GET /api/qa/findings 暴露完整 filter（runId[], taskId, configVersion, severity, status）；POST /api/qa/findings/{id}/status 复用 FindingService.statusTransition；GET /api/qa/runs/{id}/findings 按 Run 聚合 Finding；② DTO 映射（可选，当前 controller 层直接返回 domain record，但 QA 复合响应可能需要） |
| 主要文件 | **新建** QaController.java; **修改** DefaultAgentTypeResolver.java (QA 分支 case); **修改** TaskController.java allowlist (放开 QA_* agent_type)；FindingService.java (filter 扩展 from P1-2) |
| 等待前置 | P1-2（Finding filter 扩展完成） |

---

### P1-4 QA Frontend

**范围**：QA 前端页面。审计发现 **QA Finding 页面已存在**——FindingsPage.tsx 是功能完整的 Finding 列表 + Drawer 详情 + status 操作 + filter。

| 维度 | 结论 |
|---|---|
| 已存在（不重复实现） | ① FindingsPage.tsx:14-94 完整 Finding 列表页（含 filter by configVersion/severity + Drawer 详情 + status 操作）；② FindingDetail inline 组件 (FindingsPage.tsx:96-143)；③ TestPlanPage.tsx:12-141 完整 TestPlan 查看 + ScenarioCard + AssociatedFindings；④ ConfigRegressionPage.tsx:34-144 跨版本回归对比页；⑤ Badge + severityTone (Badge.tsx) 已支持 QA severity 显示；⑥ Drawer 组件 + DefinitionList 可直接复用；⑦ useTesterEventSignal SSE 驱动实时刷新 (useGlobalSse.ts:181-193)；⑧ api.ts 已有 listFindings / updateFindingStatus 类型化方法。**证据：FindingsPage 本身就是 QA Finding 页面，无需新建** |
| 需新建 | ① QA Task 创建页/面板（从现有 WorkflowPage 或新建 QA 组件，选择 agent_type + 配置 Task 参数）；② QA Run 聚合视图（按 Task 维度聚合多个 Finding），对应 P1-3 的 GET /api/qa/tasks/{id}/summary 端点；③ 可选：提取 Th/Td/inputStyle/btnStyle 到 shared helpers（5+ 页面重复）；修复 WorkflowPage 本地 Badge 遮蔽共享组件 (WorkflowPage.tsx:277-307) |
| 主要文件 | **修改** FindingsPage.tsx (增强 filter 支持 taskId/status)；**新建** QaTaskCreatePanel 或扩展 WorkflowPage；api.ts (追加 QA API 方法)；types.ts (追加 QA Task 类型) |
| 等待前置 | P1-3（REST API 端点就绪） |

---

### P1-5 Isolation / Regression

**范围**：QA P1 的测试隔离和回归保障。审计发现 **QA 测试隔离基础设施已完整**。

| 维度 | 结论 |
|---|---|
| 已存在（不重复实现） | ① QaTestFixture.java:31-99（per-class temp SQLite + datasourceProps + resetDbToCleanState FK-safe + v001 seed）= QA P1 的隔离基础；② QaTestFixtureTest.java:33-157 验证 fixture 正确性；③ ApplicationTests.java:62-81 14-table 集合断言；④ 所有已有测试类的 @DynamicPropertySource + temp SQLite PRAGMA 模式 (journal_mode=WAL, foreign_keys=ON, max pool=1)；⑤ AgentTypeResolverTest 12+ 测试覆盖三路路由；⑥ TesterRunWiringTest dual-track wiring 模式（FakeLlmGateway + StubTool）。**证据：QaTestFixture 是 QaTestFixtureTest 已验证的正确隔离工具，P1 QA 测试必须继承 QaTestFixture 模式而非 AbstractRepositoryTest（AF-001 §0/§18 硬约束）** |
| 需新建 | ① QA AgentType 路由测试（仿 AgentTypeResolverTest，验证 QA_LEAD/TESTER/ANALYST/RESEARCHER 路由正确性）；② QA Run-wiring 测试（仿 TesterRunWiringTest，验证 QA AgentLoop 选择正确 prompt + tool whitelist）；③ QaTestFixture 集成（所有 QA 测试类继承或组合 QaTestFixture，不共享 static DB）；④ 回归基线比对：P1 每个 Task 完成后执行完整 443-suite + 前端 vitest，确保零破坏（基线 443/0/0, frontend 16/0 in QA_PREFLIGHT_BASELINE.md） |
| 主要文件 | **新建** QaAgentTypeResolverTest.java, QaRunWiringTest.java; **引用** QaTestFixture.java (不修改)；**引用** ApplicationTests.java (expected_tables 确认仍为 14，直到后续 DDL 迁移) |
| 等待前置 | P1-1（AgentType 扩展完成方可编写路由测试） |

---

### P1-6 Acceptance

**范围**：P1 整体验收。审计发现 **QA 无 Sprint 历史，无 P0 checkpoint**——P1 从 Preflight 基线开始。

| 维度 | 结论 |
|---|---|
| 已存在 | ① 工作流 §11 AUTO_ACCEPT 8 项检查清单可直接作为 Task 级验收模板；② §12 Git/Scope 安全闸门；③ QA_PREFLIGHT_BASELINE.md 记录 443/0/0 后端 + 16 前端基线；④ AF-001 §0 冻结边界 + Contract Matrix (11 条不触碰对象)；⑤ 工作流 §9 Sprint Checkpoint 闸门格式 |
| 需新建 | ① P1 Acceptance Report（按 §9 Sprint Checkpoint 格式）——需包含：编译通过、443-suite 零回归、16 vitest 零回归、P1 专属 QA 测试通过数、scope check（仅 QA 新增，未触碰 Designer/Tester/Research 冻结文件）、Git diff summary、技术债扫描（TD-003/TD-QA-R-001 状态确认）；② task-state.md 更新（P1-N 标记 DONE）；③ 产出可交付物清单（QaController + QaTestFixture 验证 + QA route tests + 增强 FindingsPage filter） |
| 主要文件 | **新建** docs/reports/QA_P1_ACCEPTANCE.md; **修改** docs/state/task-state.md |
| 等待前置 | P1-5（回归基线验证完成） |

---

# QA P1 实施审计（只读） — 第 12、13 节

> 前置声明：P1 计划当前处于 **DEFINED（设计）** 状态，**不得执行**。执行须经三级放行：① ARCHITECTURE FREEZE 人工批准 → ② PRE-FLIGHT CLEARANCE（PF-001~PF-004 全过）→ ③ P1 独立授权（对齐 `docs/QA_S1_PLAN.md` 与 `docs/QA_ARCHITECTURE_FREEZE.md`）。本节文件计划为"获批后方可落地的目标指针"，全部遵循复用优先（Task/Run/Finding/ToolExecutor/Prompt Registry/Repository/REST 包络/SSE/Event/前端组件零复制）与最小 add‑on（新文件 > 改动；本计划不改 `schema.sql`）。

## §12 文件修改计划

### 全局冻结清单（P1-1..P1-6 一律禁止触碰）
`AgentLoop.java`、`RunStateMachine.java`、`BudgetGuard.java`、`LoopDetector.java`、`ToolExecutor.java`、`ToolResult.java`、`ErrorCodes.java`、`ApiResp.java`、`BusinessException.java`、`GlobalExceptionHandler.java`、`AgentEventType`（26 值时序）、SSE 协议、`schema.sql` 既有 14 表、Designer P0-P8（10 工具 + 服务）、Tester T1-T7（8 工具 + TesterController 8 端点 + FindingService/FindingRegressionService/TestPlanService 契约）、Research R1（6 工具 + 2 表）。**P1 中仅允许修改 3 个生产文件**，均为 AF-001 明确授权的着陆点：`AgentType.java`、`DefaultAgentTypeResolver.java`、`PromptService.java`；其余一律新增。

---

### P1-1 · AgentType 路由扩展（QA 唯一着陆点的第 1 半）

- **目标**：`AgentType` 3→7 值；`DefaultAgentTypeResolver` 的穷举 switch 增加 QA 分支与 QA 白名单集合，使 7 个 AgentType 均可路由到合法 Prompt + 合法工具集，且 DESINGER/TESTER/RESEARCH 既有分支与回退逻辑零改动。
- **NEW 文件**：无（纯改动，先在既有文件中落枚举与 switch）。
- **MODIFIED 文件**：
  - `backend/src/main/java/com/aigamelab/runtime/AgentType.java` — 追加 4 个枚举值 `QA_LEAD / QA_TESTER / QA_ANALYST / QA_RESEARCHER`（附注释语义：编排/门控、Player/Exploit、Combat/Numerical、UX），顺序整理为 `DESIGNER/TESTER/RESEARCH/QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER`。理由：枚举是编译器强制覆盖点——新增值令 `DefaultAgentTypeResolver` 两个穷举 switch 编译失败，保证 QA 分支不可能被静默遗漏。
  - `backend/src/main/java/com/aigamelab/runtime/DefaultAgentTypeResolver.java` — 新增 QA Prompt 版本常量（`QA_LEAD_PROMPT_VERSION="qa_lead_p001"` 等 8 个）；新增 4 个 QA 白名单 Set（QA_LEAD=6、QA_TESTER=10、QA_ANALYST=7、QA_RESEARCHER=10，按 QA_ARCHITECTURE_FREEZE §6/B 提案值，获批前不落地）；`systemPrompt()`（L121-133）与 `toolWhitelist()`（L143-149）各补 4 个分支；`resolveFromConstraints`（L176-181）仅改错误消息文案列出全部 7 值（**禁止**改动解析/回退逻辑）。
- **冻结文件（禁止触碰）**：`AgentLoop.java`（L239-250 委托不动）、`AgentTypeResolver` 接口、`resolveFromConstraints` 语义、DESIGNER/TESTER/RESEARCH 既有分支与 Set。
- **BLOCKER → §15**：任何 QL 白名单实际授予 Designer 工具（`simulate_game/analyze_experiment/compare_experiments/get_game_config`）与修改 AgentLoop 的行为均为 BLOCKER，引用 §15 决裁——QA_ANALYST 的 7 工具组合仍为**提案态**，未获人工批准前不得使任何 QA 类型可调用 Designer 工具。

### P1-2 · QA Prompt 种子导入

- **目标**：8 个 `qa_*.md` 角色 Prompt 以 inactive 幂等导入，`prompt_version` 总数 3→11，唯一 active 保持 `designer_p001`。
- **NEW 文件**（8 个，均在 `backend/src/main/resources/prompts/`）：
  - `qa_lead_p001.md`、`qa_player_novice_p001.md`、`qa_player_aggressive_p001.md`、`qa_player_build_specialist_p001.md`、`qa_combat_analyst_p001.md`、`qa_numerical_analyst_p001.md`、`qa_exploit_hunter_p001.md`、`qa_ux_reviewer_p001.md`
- **MODIFIED 文件**：
  - `backend/src/main/java/com/aigamelab/llm/PromptService.java` — 新增 QA 种子常量（version/resource/description，8 组）与 `importQaSeedsIfAbsent()`（完全复制 `importTesterSeedIfAbsent` L94-103 的幂等模式：按版本存在性跳过、inactive 插入、绝不触碰 active 标记），并在构造器 `PromptService(...)`（L47-53）追加 8 次调用。理由：复用既有导入契约与幂等保证，唯一改动点是新增方法与调用行。
- **冻结文件（禁止触碰）**：`tester_p001.md / designer_p001.md / designer_p002.md / research_p001.md` 内容与 active 状态、`firstByVersion` 契约、`PromptVersionRepository`、`schema.sql` 的 `prompt_version` 表。
- **BLOCKER → §15**：把任一 `qa_*` 种子置为 active、修改既有种子内容、或新增 SSE 事件/AgentEventType 均为 BLOCKER → §15。

### P1-3 · Finding 领域查询面 + QA REST 端点

- **目标**：以 QA 视角暴露 Finding 查询/聚合视图（runId/taskId/severity/status），且既有 REST 28 端点零修改、仓库契约零修改、`schema.sql` 零修改。
- **NEW 文件**：
  - `backend/src/main/java/com/aigamelab/qa/QaFindingQueryService.java` — 一组组合查询服务：`findByRunId/…findByTaskId/…findBySeverityStatus/…`，内部**仅**调用既有 `findingService.findByFilter(configVersion, severity, runId, null)` 的结果集做内存级 taskId/status 过滤，并复用 `FindingService.require`/`updateStatus` 白名单行为；不触碰 `FindingRepository`。
  - `backend/src/main/java/com/aigamelab/api/QaController.java` — 新 `@RestController @RequestMapping("/api/qa")`，仅做 HTTP 适配委托给 `QaFindingQueryService`/`FindingService`，返回 `ApiResp.ok(...)` 包络、错误抛 `BusinessException`（notFound→40401 / INVALID_ARGUMENT→40001）（对齐 ConfigController.java:33-41 / RunController.java:32-33 红线：Controller 不写业务逻辑）。
- **MODIFIED 文件**：无。
- **冻结文件（禁止触碰）**：`TesterController.java`（8 端点含 GET /api/findings 不动）、`FindingService.java` 契约、`FindingRepository.java`/`FindingRepositoryImpl.java` 契约、`schema.sql` finding 表与索引、错误码注册表。
- **BLOCKER → §15**：任何把 taskId/status 下沉到 SQL 的改动（改 `FindingRepositoryImpl.findByFilter` 签名/SQL 或新增 `idx_finding_status` 索引）即触碰冻结的 Repository 契约与 schema.sql → BLOCKER → §15 另立迁移桥（顺带说明：P1 内存过滤在测试规模下可接受；生产级 SQL 过滤需人工批准的 repo/schema 迁移，并须与 `ApplicationTests.EXPECTED_TABLES` 锁步，见 §15）。

### P1-4 · QA 编排工具 4 件套

- **目标**：`qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings` 四个工具，实现 `GameTool` SPI，自动注册（Spring Bean 收集），复用既有服务读接口。
- **NEW 文件**：
  - `backend/src/main/java/com/aigamelab/qa/tool/QaCreateSubtaskTool.java`
  - `backend/src/main/java/com/aigamelab/qa/tool/QaStartSubrunTool.java`
  - `backend/src/main/java/com/aigamelab/qa/tool/QaCheckSubrunStatusTool.java`
  - `backend/src/main/java/com/aigamelab/qa/tool/QaCollectFindingsTool.java`
  - `backend/src/main/java/com/aigamelab/qa/tool/QaToolErrorTranslator.java` — 共享的参数解析/BusinessException→非关键 ToolException 翻译小工具（`[code] message` 格式，复用 AbstractTesterTool 模式语义，**不创建 AbstractQaTool 基类**、不继承 AbstractTesterTool，遵守 AF-001 §6）
  - 复用（不改动）：`RunRepository` 查询、`RunControlService`（读接口）、`FindingService`/`ExperientService` 读接口、`AgentEventPublisher` 只读消费。
- **MODIFIED 文件**：无（`ToolRegistry`/`ToolExecutor`/`AgentLoop` 零改动——`@Component` Bean 被 Spring 自动收集注册，无需改注册表）。
- **冻结文件（禁止触碰）**：`ToolRegistry.java`、`ToolExecutor.java`、`AbstractTesterTool.java`、既有 24 个工具、`AgentLoop`。
- **BLOCKER → §15**：任何"工具经 `ToolRegistry.register()` 手动注册进生产路径"、"继承 AbstractTesterTool / 新建 AbstractQaTool 基类"、"修改 ToolExecutor 异常语义"均为 BLOCKER → §15（保持 `ToolRegistryIntegrationTest` 现有断言随 4 Bean 注册自动变绿的 add‑on 语义，总数 23→27）。

### P1-5 · QaOrchestratorService + QaReleaseGateService

- **目标**：编排运行时（固定顺序 Lead→Player1/2/3→Combat→Numerical→Exploit→UX、deadline、`poll_seq` 防 LoopDetector、6 态回写以 run 行为准）与门控服务（PASS/CONDITIONAL_PASS/FAIL/INCONCLUSIVE，绝不以子 Run 超时/失败自动 PASS）。
- **NEW 文件**：
  - `backend/src/main/java/com/aigamelab/qa/QaOrchestratorService.java`（`@Service`）
  - `backend/src/main/java/com/aigamelab/qa/QaOrchestratorConfig.java`（wallClockBudget 默认 300s 可覆盖）
  - `backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java`
  - 编排状态采用 **内存/JVM 驻留**（assignment 状态、子 Run 终态、result_summary 均在不新增表的前提下于服务内存维护，`qa_check_subrun_status` 轮询参数携带 `poll_seq`）。
- **MODIFIED 文件**：无。
- **冻结文件（禁止触碰）**：`RunControlService`（不加 join/通知）、`AgentLoop`（不加 timeout）、`LoopDetector`、`FindingService`/`FindingRegressionService` 契约、run 状态机、Hikari 连接池、`schema.sql`。
- **BLOCKER → §15**：任何将 `qa_role_assignment` / `qa_release_gate` 落库的诉求（依赖 schema.sql 新增 2 表 + `ApplicationTests.EXPECTED_TABLES` 14→16）→ **BLOCKER → §15**，P1 阶段明确禁止改 `schema.sql` 与表数断言；新表属 S1-2 人工闸门迁移，P1 用内存态覆盖编排语义，为批准后的持久化迁移预留 `QaAssignmentStore` 接口即可（接口新增不改既有任何文件）。

### P1-6 · Contract Matrix 一致性 + 隔离收口

- **目标**：将 AF-001 冻结矩阵固化为测试断言，并做完整回归收口（纯测试 Task，零生产代码）。
- **NEW 文件**：
  - `backend/src/test/java/com/aigamelab/qa/ContractMatrixTest.java`
  - `backend/src/test/java/com/aigamelab/qa/QaToolIsolationTest.java`
- **MODIFIED 文件**：无。
- **冻结文件（禁止触碰）**：全部生产代码（本 Task 纯断言）；既有测试夹具（`QaTestFixture` 只复用不修改，遵守 PF-002：**不继承 AbstractRepositoryTest**）。
- **BLOCKER → §15**：为"让测试过"而放宽任一红线逻辑、或改动矩阵对应文档后不同步改代码（工作流 §41）。

---

## §13 测试计划

> 测试基建复用（全部按 map:test-infra）：`QaTestFixture`（tempDb 按类独立临时 SQLite + datasourceProps + FK 安全 reset + v001 seed，**不继承 AbstractRepositoryTest**）、`FakeLlmGateway`、`StubTool` 注册模式（TesterRunWiringTest）、`TesterPromptImportTest` 导入断言模式、`AgentTypeResolverTest` 路由测试模式、`ToolRegistryIntegrationTest`（23→27）。**不变式：后端全量套件 `443+ / 0 / 0`（failures=0 / errors=0 绝对不变）**；前端不动（vitest 16/16 保持，跨层基线）。

| P1 Task | 新增/扩展测试文件 | 覆盖要点 | 新增用例估计 |
|---|---|---|---|
| P1-1 路由 | 扩展 `backend/src/test/java/com/aigamelab/runtime/AgentTypeResolverTest.java` | 7 分支穷举：每类型 Prompt 非空 + 工具集非空 + 名称集合精确匹配；`resolve("agent_type=QA_LEAD")` 成功；非法值 40001 消息列全 7 值；**隔离**：4 个 QA 白名单必须与冻结矩阵逐一相等且互不包含 DESIGNER/TESTER/RESEARCH 工具名 | 14~18 |
| P1-2 Prompt | 新 `backend/src/test/java/com/aigamelab/llm/QaPromptImportTest.java` | 空库启动后 `prompt_version` 行 = 3 原 + 8 QA = 11；8 个 `qa_*` 全部 inactive；唯一 active=designer_p001；进程重启幂等不重复导入；每条 Prompt 含角色行为铁律断言存在 | 8~12 |
| P1-3 领域+REST | 新 `backend/src/test/java/com/aigamelab/api/QaControllerTest.java`、新 `backend/src/test/java/com/aigamelab/qa/QaFindingQueryServiceTest.java` | **domain**：Finding 创建落库回读（复用 `finding_report`→`FindingService`）；按 runId/taskId/severity/status 组合查询；status 白名单（非法状态 40001）；**非法状态流转拒绝**；**重复 Finding 指纹**（复用 FindingFingerprint 语义）识别；**REST**：`GET /api/qa/findings?runId=&taskId=&severity=&status=` 各过滤组合、`GET /api/qa/findings/{id}`、404（不存在的 id/资源→40401）、非法参数（空值/非法 severity→40001）、包络为 `ApiResp` 且 HTTP status=code/100（用 `@DynamicPropertySource` temp-DB 对齐 `TaskControllerTest` 模式） | 18~24 |
| P1-4 工具 | 新 `backend/src/test/java/com/aigamelab/qa/tool/QaOrchestrationToolsTest.java`、扩展 `backend/src/test/java/com/aigamelab/tool/ToolRegistryIntegrationTest.java`（23→27） | 每工具 nameAndSchema/成功路径/参数非法/领域错误；`qa_start_subrun` 注入角色 Prompt+Tools 后正确启动子 Run、`sub_run_id` 回填；`qa_collect_findings` 正确聚合 Finding+Experiment+Claim、部分失败角色不抛错；**工具隔离**：QA 角色调用 Designer 工具（simulate_game/get_game_config/get_game_design）与 Tester/Research 工具名 → 白名单拒绝；未知名 → 40401；Finding 经 collect 复现即证明判定数据落库 | 14~18 |
| P1-5 编排+门控 | 新 `backend/src/test/java/com/aigamelab/qa/QaOrchestratorServiceTest.java`、新 `backend/src/test/java/com/aigamelab/qa/QaReleaseGateServiceTest.java` | **domain/REST 之外的隔离与红线**：编排顺序固定可枚举；失败角色不中断（部分结果语义）；deadline 触发→assignment=TIMEOUT 且子 Run 行不改；`poll_seq`：连续 3 次轮询同 subRunId 不触发 LoopDetector（父 Run 不被误杀）；gate 阈值判定（FAIL on critical/soft_lock/失衡；PASS on 全过；CONDITIONAL_PASS on 仅 medium 以下）；关键角色 TIMEOUT/FAILED→INCONCLUSIVE（**绝无 PASS**）；manual_override 字段写入与审计 | 16~22 |
| P1-6 Contract+回归 | 新 `backend/src/test/java/com/aigamelab/qa/ContractMatrixTest.java`、新 `backend/src/test/java/com/aigamelab/qa/QaToolIsolationTest.java` | 8 角色 → AgentType/Prompt 文件/白名单三线一一匹配（硬编码矩阵 == Resolver 输出）；7 AgentType / 8 Prompt / 4 新工具计数断言；隔离红线：任何既有类型也索引不到 `qa_*` 工具；**回归**：顺序 full-suite + 随机 ordering 复跑，443+ 全绿、0 failures / 0 errors（sunfire `-Djunit...random` 与 `-Dsurefire.runOrder=random` 双跑，对齐 QA_PREFLIGHT_BASELINE §2） | 10~14 |

**新增用例总量区间：约 80~108 个**，后端套件总量从 443 升至 **约 523~551**；每条 P1 Task 完成后必须满足不变式 **`443+ / 0 / 0`**（failures=0 / errors=0），否则 STOP 进入 §7 修复循环；前端 vitest 保持 16/16 不动作为跨层回归判据。

**P1 断言矩阵注意点**：
- `ToolRegistryIntegrationTest` 的 23→27 断言依赖 Spring Bean 自动收集（P1-4），不得改为显式 `register()`。
- 所有新测试类使用 `QaTestFixture` 的独立临时 DB（per-class），杜绝共享 DB 的 `sqlite_sequence` 传染（对齐 PF-002 选型 A，规避 TD-QA-R-001）。
- `ApplicationTests` 的 `EXPECTED_TABLES` P1 阶段**必须保持 14 不表**——任何 14→16 改动即 BLOCKER → §15。
- P1-3 的 REST 用例必须断言 `4xxxx/5xxxx` 数值码与 `ApiResp` 包络（`code/100` HTTP 映射），并显式覆盖非法参数组合（40001 并不存在 404）；`RATE_LIMITED(42901)` P1 不启用（零使用保持，属 §15 后续）。

---

## §14 冻结边界（Frozen Boundary）

QA P1 的冻结集与既有基线一致（基线证据：`docs/reports/QA_PREFLIGHT_BASELINE.md` 记录的 443/0/0 为 git HEAD `33b6b15`，`docs/state/task-state.md:21-28` 记录 PROJECT STATUS=RELEASE CANDIDATE、P0-P8 COMPLETE、T1-T7 FROZEN）。每一项 P1 只走"非修改扩展路径"，绝不触碰冻结文件本体。

| # | 冻结对象 | Map 证据 | P1 用到的非修改扩展路径 |
|---|---|---|---|
| 1 | **AgentLoop** | `AgentLoop.java:239-250` 已把 prompt 选择与工具暴露委托给 AgentTypeResolver；AF-001 §0 "AgentLoop 零修改"；工作流 §15 P1 Agent Runtime 已 FROZEN | **零修改**。仅通过 `DefaultAgentTypeResolver` 的穷尽 switch 扩展（map1 constraint a：QA 唯一落地扩展点；`AgentLoop.selectToolSpecs:248-263` 已强制按白名单过滤）。 |
| 2 | **RunStateMachine** | run 状态机 PENDING/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED/TIMEOUT（schema.sql:18-35）；map2 红线"不修改……状态机"；工作流 §5.2 "Agent Runtime 语义"冻结 | **零修改**。P1 不新增/改动 run 状态；未来 QA Lead 编排只经新表 `qa_role_assignment` 关联（提案 §11.1:625-637），而非改 run 表状态语义。 |
| 3 | **BudgetGuard** | run 表 `token_budget`/`tokens_used` 承载预算语义（schema.sql）；Agent Runtime(P1-0/1/2) 已 FROZEN（task-state.md:23-25） | **零修改**。预算语义冻结；QA Task 仅按现有 `TaskController` 创建 Run 时正常给 `token_budget`，不引入新预算逻辑/Flags。 |
| 4 | **LoopDetector** | AgentLoop 循环检测属 Agent Runtime 冻结域（工作流 §5.2/§15）；map6 冻结边界清单将 AgentLoop/run/step/tool_call 模型整体冻结 | **零修改**。QA 复用既有循环检测，不加新标志位。 |
| 5 | **ToolExecutor** | `ToolExecutor.java:83-99` dispatch + requiresApproval→WAITING_APPROVAL；`:100-115` ToolException critical→Run FAILED / non-critical→observation 语义冻结（map: Error Contract）；map6 冻结 "22 existing GameTools" | **零修改**。新增 QA 工具一律为 `@Component` GameTool Bean，由 `ToolRegistry.java:22-34` Spring 自动收集（工具名 `qa_` 前缀），白名单仅在 `DefaultAgentTypeResolver` 新 QA 分支声明。 |
| 6 | **ToolResult** | 工具结果契约冻结（LLM 面，map: Error Contract）；重试/approval 语义在 ToolExecutor/修改工具侧 | **零修改**。QA 工具遵循既有 ToolResult 返回契约。 |
| 7 | **ErrorCodes** | 6 码冻结注册表（ErrorCodes.java:9-26）；HTTP=code/100（GlobalExceptionHandler）；ApiResp 包络强制（ApiResp.java "禁止手写 Map 包络"） | **不新增码**。P1 复用 40001/40401/40901（`BusinessException.notFound()/conflict()` 工厂 + raw constructor 表达 40001）；42901 RATE_LIMITED 当前零使用（map2 gap d）——未来限量场景可直接复用。 |
| 8 | **Designer P0-P8** | P0-P8 COMPLETE/DONE（map4、task-state）；`game_config_version` append-only 无 active/rollback（ConfigVersionService.java:26-27）；experiment `UNIQUE(config_version,seed,simulation_count,scenario)` 幂等线（schema.sql:83）；10 个 Designer 工具冻结（含 R2-1 只读工具） | 只读 `game_config_version`（findByVersion/findLatest 已有 Tester 共用先例：GameSessionService.java:44-47、FindingService PF-003 existence check :93-96）；**绝不调用** simulate_game/analyze_experiment/compare_experiments（无 QA 白名单，QA_ANALYST 授权仍是提案态）。 |
| 9 | **Tester T 系列** | T1-T7 FROZEN（task-state.md；T4/T5 已 FROZEN/ACCEPTED）；8 个 tester 工具 + finding_report 仅走 Tester 白名单（DefaultAgentTypeResolver.java:56-58）；finding 表 T-DB1 已批准 DDL（schema.sql:164-184） | **复用不修改**：finding_report→FindingService→FindingRepository→`finding` 表即 P1 的 finding 创建契约；Finding.SEVERITIES/CATEGORIES/SOURCES/STATUSES（Finding.java:38-46）+ Evidence 锚点（:54-68）。Tester 白名单不增不减。 |
| 10 | **Research R1（已验收）** | R1 accepted（基线记录）；6 个 research 工具（DefaultAgentTypeResolver.java:76-78）；research_source/research_claim 表（schema.sql:191-219） | 只读复用 research 数据作 QA 上下文（map4 reusability 3）；不改 research 白名单、不改表（proposal must-not-block (6) 要求 QA_RESEARCHER 未来直接复用这 6 个工具）。 |

配套冻结物（P1 同样不得触碰）：schema.sql 现有 **14 张表**（ApplicationTests.java:62-67 EXPECTED_TABLES 保持 14，QA 两张新表是 QA-S1-2 人工放行的 migration，非 P1）；prompt 现有 4 条种子（designer p001 为唯一 active）；AgentEventType 26 枚举值；已有 REST 端点 28→30 个零修改（map2）。

**P1 的文档级扩展点（均无需改冻结文件）**：`DefaultAgentTypeResolver`（唯一 AgentType/白名单落地，编译器穷尽 switch 强制无漏）；`PromptService` 幂等导入模式（importTesterSeedIfAbsent/importResearchSeedIfAbsent，PromptService.java:94-119）——qa_* 种子仿此**以 inactive 导入**；`QaTestFixture`（tempDb + datasourceProps + resetDbToCleanState）作为 QA 测试隔离地基（PF-002 强制，所有权在 src/test）。

**Repo-git 现实**：`git ls-files` 仅含 README.md（1 个文件）；backend/、docs/、frontend/、scripts/ 全部 untracked——"冻结"靠文档约定（AF-001、工作流 §5.2/§12、task-state.md）而非 git 强制（map:docs-state）。因此 P1 的冻结保障不能依赖 git diff，而应落地为**冻结文件内容哈希清单（manifest）+ §12 Scope/冻结设计 gate + §16 无冻结 diff 验收**三项机制。

---

## §15 风险

| # | 风险 | 评级 | 缓解（一行，指向实际扩展点） |
|---|---|---|---|
| 1 | **QA Finding 模型漂移**：P1 新增字段/状态与 Proposal 未来基线或 Tester 现存 finding 契约冲突 | **HIGH** | 以冻结契约为准——Finding.SEVERITIES/CATEGORIES/SOURCES/STATUSES（Finding.java:38-46）+ Evidence（:54-68）只读不改；发现未来基线冲突即记入 QA_ARCHITECTURE_DECISION_REVIEW 影响清单并停手报人工，不自行改契约。 |
| 2 | **Tool 隔离缺口**：共享 ToolRegistry（TD-003），today 无 unregister/reset 机制，新增 qa_* 工具可能泄漏给其他 Agent | **HIGH** | 不改 ToolExecutor/ToolRegistry 机制，隔离只在扩展点 `DefaultAgentTypeResolver` 新 QA 分支白名单 + `AgentLoop.selectToolSpecs:248-263` whitelist 过滤完成；AgentTypeResolverTest 对每个角色做**精确集合断言**（equalTo 白名单，多一个少一个都红）；测试侧用 QaTestFixture 每类独立临时 DB（PF-002）。 |
| 3 | **数据隔离泄漏**：宽泛 REST 查询（findings 目前无 testPlanId/status 过滤、runs 硬编码 LIMIT 200）把 QA/Designer/Tester 数据互相暴露 | **MED** | 新端点严格走 TesterController 委托模式：仅控制器层加过滤参数（testPlanId 已存在于 repo 路径但 TesterController.java:97 硬编码 null，status 过滤仿 ResearchController.listClaims:79-84 的 in-memory 先例），绝不改 repository 契约/schema（ConfigController.java:33-41 红线）。 |
| 4 | **Finding 生命周期"hack"**：无真状态机、无迁移历史表，非法迁移（如 FIXED→OPEN）若不在 service 层校验将污染 QA 核心工件 | **MED** | P1-4 在 FindingService 加纯 Java 迁移校验（合法边仅 OPEN→CONFIRMED/DISMISSED、CONFIRMED→FIXED），不新增生命周期表（schema 冻结），越权迁移抛 40001 并断言于测试。 |
| 5 | **重复 Finding**：finding 表无唯一约束（schema.sql:164-184 无 UNIQUE），QA Lead 聚合时重复行会虚增计数/污染回归 diff | **MED** | P1-4 在 FindingService.report() 加服务层幂等/去重校验（按 configVersion+scenario_id+title 已存在即拒/复用），不改 DDL；无法约束的场景在报告登记为技术债（TD 登记不修复，工作流 §40）。 |
| 6 | **回归 443 基线**：共享 static DB + sqlite_sequence 不重置导致随机序偶发 flaky（TD-QA-R-001：`eventReplayAfterIdAndRecent` Expected 3 but was 5） | **HIGH** | 每 Task 前后各跑一次全量（顺序 + 一次随机序，复现 QA_PREFLIGHT_BASELINE.md §2 方法）维持 >=443/0/0；QA 测试一律 QaTestFixture 每类独立临时 DB（PF-002 强制，禁止 AbstractRepositoryTest）；TD-QA-R-001 保持豁免记录、不由 P1 借机修共享 static DB。 |
| 7 | **QA 路由回归**：P1 改路由破坏 Designer/Tester/Research 现有路由（AgentType.valueOf 对非法值抛 IllegalArgumentException） | **HIGH** | 只**追加** enum values + `DefaultAgentTypeResolver` switch cases；显式 constraints_json.agent_type 优先 + 现有 fallback（ACTIVE TestPlan→TESTER else DESIGNER，:98-111）不动；以 AgentTypeResolverTest 既有 12+ 用例 + TesterRunWiringTest 全绿为硬闸。 |
| 8 | **前端范围蔓延**：QA Dashboard / Lead 编排页 / Gate 页被拖入 P1 | **MED** | P1 声明为后端任务；FindingsPage.tsx 已是现成的 QA Finding 页（list+Drawer+filters+status），仅复用已 typed+tested 的 api.listFindings/updateFindingStatus；Dashboard/Lead/Gate 属 QA-S3/S5，验收加 tsc + vitest 16 零新增零破坏。 |
| 9 | **冻结文件诱惑**：P1 修复发现需改 AgentLoop 等冻结文件即自行实现 | **HIGH** | 按工作流 §12 Scope/冻结设计 gate 触发 BLOCKED：触碰 AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult/ErrorCodes/schema 14 表/AgentEventType 任一即 STOP，输出影响分析报告交人工验收（与 §14 冻结集逐一对照），绝不绕过。 |

---

## §16 每个 Task 的验收标准

任务边界以地图缺口/扩展点推导（map1 constraint e、map5 must-not-block、map6 extension points），全部为**可自动执行**的判据（运行测试/脚本即得 PASS/FAIL，无人工判断）。

**P1-1｜QA AgentType 路由扩展**（只追加，编译器穷尽强制）
- ACC-1：新增 `AgentTypeResolverTest` 用例：`constraints_json.agent_type` 为 QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER 的四种 Task 均解析成功且返回与提案 §8.3 一致的精确工具白名单（集合 equalTo 断言）；既有 DESIGNER/TESTER/RESEARCH 12+ 用例全绿（`mvn test` 含新用例且全量零回归）。
- ACC-2：非法 agent_type 值（如 `"QA_X"`、`"qa_tester"`）仍经 `AgentType.valueOf` 抛 IllegalArgumentException，且错误文本枚举现有全部 7 个 AgentType 名；AgentType 新增后所有穷尽 switch 编译通过（编译期证明无漏分支）。

**P1-2｜QA prompt 种子（inactive 导入）**
- ACC-1：应用启动（integration test）后 `prompt_version` 表恰好新增 4 条 QA 种子（qa_lead_p001/qa_tester_p001/qa_analyst_p001/qa_researcher_p001）且 **is_active=0**；重复启动两次 seed 行数不变（`importQaSeedsIfAbsent` 幂等，仿 PromptService.java:94-119）。
- ACC-2：断言 designer p001 仍为唯一 is_active=1 的版本，且 tester_p001/research_p001/designer_p002 内容哈希与基线一致（种子未被动过）。

**P1-3｜QA Finding 只读查询 REST（thin controller）**
- ACC-1：集成测试对新增端点（如 GET /api/qa/findings?configVersion=&severity=&testPlanId=&status=）断言返回 ApiResp{code:0,data:[...]} 且过滤正确（status=OPEN 只返回 OPEN；testPlanId 过滤生效——该参数现硬编码 null 于 TesterController.java:97）。
- ACC-2：查询不存在的 finding id 返回 40401/HTTP 404 且 data=null（仍走 `BusinessException.notFound()` + GlobalExceptionHandler，包络与六码契约不变）；控制器仅委托 `FindingService.findByFilter`，项目内以测试断言无新增 repository/schema/状态机改动（ConfigController.java:33-41 红线）。

**P1-4｜Finding 生命周期迁移校验 + 去重（service 层，无 DDL）**
- ACC-1：FindingService 迁移校验测试：合法边 OPEN→CONFIRMED、OPEN→DISMISSED、CONFIRMED→FIXED 成功；非法边（OPEN→FIXED、CONFIRMED→OPEN、FIXED→任何）抛 BusinessException 40001（或 40901）；不新增迁移表，SQL 层行数与 14 表断言不变。
- ACC-2：去重测试：同一 (configVersion, scenario_id, title) 重复 report 第二次被拒或复用（40901 或 reused=true），`finding` 表行数断言只增 1；现有 POST /api/findings/{id}/status 手工路径同样受校验约束（回归 TesterController status 用例全绿）。

**P1-5｜QA 测试隔离地基（PF-002 合规）**
- ACC-1：QaTestFixture 相关用例通过：任意 QA 测试类 setUp 自建临时 SQLite（tempDb）后断言 PRAGMA journal_mode=wal 且 foreign_keys=1（同 ApplicationTests.java:84-88 模式），且 resetDbToCleanState 后表集合恰为 14 张（EXPECTED_TABLES containsExactlyInAnyOrder）。
- ACC-2：脚本/grep 断言 `src/test` 下 QA 相关测试类**无任何 extends AbstractRepositoryTest**（PF-002 强制），退出码 0；QA 测试彼此互不共享 static DB（类级隔离拓扑由 QaTestFixtureTest 既有断言守护）。

**P1-6｜QA 隔离与越权防护（白名单边界）**
- ACC-1：用 FakeLlmGateway + StubTool（TesterRunWiringTest:67-71 模式）跑一个 QA_LEAD Task，断言 `selectToolSpecs` 后暴露的工具**精确等于** QA_LEAD 白名单（6 个，集合断言），**不含** simulate_game/analyze_experiment/compare_experiments 等任何 Designer/Tester 工具；对白名单外工具名无白名单可达（ToolRegistry 对未注册/无权工具抛 40401，ToolRegistry.java:62-68）。
- ACC-2：QA 生效路径对 finding 的写入仍只能经 Tester 白名单的 finding_report（QA 白名单内无第二写入口）；集成测试断言 QA_LEAD 直接解析后拿到的白名单与提案 §8.3 QA_LEAD 列表逐字一致。

**全局门（Sprint 级，独立于各 Task 重跑）**
- GATE-1｜全量回归：full-suite 至少 **443 tests / 0 failures / 0 errors / BUILD SUCCESS**，且按 QA_PREFLIGHT_BASELINE.md §2 方法**顺序 + 一次随机 ordering 两次复跑全绿**（新增 QA 测试计入总数，基线只升不降）。
- GATE-2｜冻结零 diff：以 P1 启动时记录的**冻结文件内容哈希 manifest**（覆盖 §14 全部冻结对象 + schema.sql + 现有 30 端点涉及文件 + 4 条 prompt 种子）为基准，验收时 `scripts/check_frozen.sh` 重新计算全部一致、退出码 0（git 仅跟踪 README.md，故冻结保障以清单比对而非 git diff 实现；清单本身随验收报告归档）。

---

## 附录 A — 对抗复核（Critic）

### Critic 1

- **CORRECTION** QA agent type/role exists for routing: No QA agent type exists. The routing enum, resolver, and TaskController all treat DESIGNER/TESTER/RESEARCH as the closed set. A task with agent_type=QA is impossible to create (TaskController rejects) and would fail resolution. QA P0: NO (backend/src/main/java/com/aigamelab/runtime/AgentType.java:14-18 defines only DESIGNER, TESTER, RESEARCH; DefaultAgentTypeResolver.java:176 resolves via AgentType.valueOf (an agent_type="QA" would throw IllegalArgumentException, line 179-181); TaskController.java:62,80,88 rejects any agentType outside DESIGNER/TESTER/RESEARCH; grep for qa|QA|qa_ across backend/src/main returns ZERO matches)
- **CORRECTION** QA prompt seeded in prompt_version: No QA prompt is seeded. prompt_version is seeded with p001 + tester_p001 + research_p001 + designer_p002 and only those four rows exist per test assertions. There is no qa_p001 (or any qa_*) prompt resource or seed. QA P0: NO (backend/src/main/java/com/aigamelab/llm/PromptService.java:24-36 seeds only p001(designer_p001.md), tester_p001, research_p001 (plus designer_p002 per R2-1); backend/src/main/resources/prompts/ contains exactly designer_p001.md, designer_p002.md, research_p001.md, tester_p001.md (no qa_p001.md or any qa prompt); tests assert exactly 4 seeded rows (backend/src/test/java/com/aigamelab/llm/PromptServiceTest.java:66,78; TesterPromptImportTest.java:94))
- **CORRECTION** a QA tool exists (qa_* or reused finding_report): finding_report is Tester-owned, not QA-reusable: there is no QA agent type, no QA tool whitelist (DefaultAgentTypeResolver toolWhitelist switch is exhaustive over DESIGNER/TESTER/RESEARCH, lines 143-149), so no QA context can ever reference or invoke it. No qa_* tool of any kind exists. QA P0: NO (finding_report DOES exist as a real tool: FindingReportTool.java:27-29 (name 'finding_report'), but it is a TESTER tool — extends AbstractTesterTool, listed in TESTER_TOOL_NAMES (DefaultAgentTypeResolver.java:58), created for T3-3 of the AI Game Tester; no qa_* tool exists anywhere (grep qa_|Qa|QA under src/main = 0 matches))
- **CORRECTION** a QA flow can create a Finding: The only flow that creates a Finding runs inside the AI Game Tester agent loop (test_plan -> finding_report tool -> FindingService). No QA flow exists in code: no QA agent, no QA prompt, no QA run path. QA P0: NO (A Finding CAN be created, but only through the TESTER flow: FindingReportTool.doExecute -> findingService.report(...) -> findingRepo.insert (FindingReportTool.java:78, FindingService.java:38-41); only agent entrypoints are TaskController/RunController/TesterController/ResearchController — there is no QaController or any QA-orchestrated path)
- **CONFIRMED** Finding persists: Finding persistence genuinely works — but it is the Tester (T3) delivery, not QA. Nothing QA-defined creates, owns, or consumes it. If this claim was meant as 'QA P0 shipped finding persistence', that is a misattribution; as a code fact the persistence layer exists and is exercised. (finding table defined in backend/src/main/resources/schema.sql:164-184 (run_id/test_plan_id/task_id FKs, severity/category/source/status, repro_steps, evidence_json); persisted via FindingService.report -> FindingRepositoryImpl.insert; QaTestFixtureTest.java:85-88 inserts a full finding row and asserts counts (line 109), proving the table is real and readable)
- **CORRECTION** QA reuses Task/Run/ToolExecutor (no new runtime): The shared Task/Run/ToolExecutor runtime exists and is reused by TESTER and RESEARCH — but by NO QA code, because there is no QA agent or QA flow in the codebase at all. 'QA reuses X' cannot be true when QA has zero presence in src/main. QA P0: NO (The shared runtime is real: AgentLoop.java:57 uses AgentTypeResolver, ToolExecutor.java:45 takes ToolRegistry/RunRepository, task/run tables in schema.sql:6,18; RESEARCH and TESTER agents both reuse it)
- **CONFIRMED** QA basic tests exist under com.aigamelab.qa.* (QaTestFixtureTest etc): Tests DO exist, but only as QA test-infrastructure: they validate the per-class SQLite temp-DB isolation fixture, FK-safe cleanup topology, seeding idempotency — i.e. 'how QA tests will run' (PF-002), not any QA agent feature, finding flow, or prompt. 'et cetera' is just these two files; there is no QA agent functionality anywhere for tests to exercise. This package exists only because the QA P0 work built its fixture before any product code. (backend/src/test/java/com/aigamelab/qa/support/QaTestFixture.java and QaTestFixtureTest.java (package com.aigamelab.qa.support). QaTestFixtureTest.java:33-35 is a @SpringBootTest with 4 @Test methods (resetClearsFullFkChainLeavingOnlyV001Seed:103, seedingIsIdempotentAcrossResets:130, tempDbReturnsDistinctFilePerClass:140, usesDedicatedQaDbFileNotSharedRepositoryFile:148); these are the ONLY 2 files under src/test/com/aigamelab/qa (verified by find))

### Critic 2

- **CONFIRMED** (a) Finding is a Java record exposing exactly 17 components: id, runId, testPlanId, taskId, scenarioId, configVersion, severity, category, source, title, expected, actual, reproSteps, evidenceJson, reproductionRate, status, createdAt: Record header lists all 17 fields in this exact order. DB column mapping in FindingRepositoryImpl.java:25-42 (id, run_id, test_plan_id, task_id, scenario_id, config_version, severity, category, source, title, expected, actual, repro_steps, evidence_json, reproduction_rate, status, created_at) matches 1:1. Note: the class javadoc at Finding.java:8-9 ('表未批准前...禁止落库') is stale — the finding table now exists in schema.sql:164 and FindingRepositoryImpl javadoc says 'T-DB1 已批准 DDL'. (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/domain/Finding.java:19-36)
- **CONFIRMED** (b) Finding.SEVERITIES = [BLOCKER, HIGH, MEDIUM, LOW, INFO]: Exact list order BLOCKER,HIGH,MEDIUM,LOW,INFO. Used by FindingService.validate() severity whitelist (FindingService.java:69-72) and by severityRank in FindingRegressionService.java:156-163 (BLOCKER index 0 = highest). (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/domain/Finding.java:38-39 (also field doc comment line 11))
- **CONFIRMED** (b) Finding.SOURCES = [INVARIANT, LLM, MANUAL]: Detection-source enum. validate() only enforces this if source is non-null (FindingService.java:77-80); source defaults to 'LLM' at insert when null (FindingRepositoryImpl.java:67). FindingReportTool hardcodes source 'LLM' for Agent reports (FindingReportTool.java:87). (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/domain/Finding.java:43-44 (also line 13))
- **CONFIRMED** (b) Finding.STATUSES = [OPEN, CONFIRMED, DISMISSED, FIXED]: Exact list OPEN,CONFIRMED,DISMISSED,FIXED. DDL default and comment match (schema.sql:176 'status TEXT NOT NULL DEFAULT ''OPEN'' -- OPEN/CONFIRMED/DISMISSED/FIXED'). IMPORTANT distinction: the regression statuses NEW/FIXED/PERSISTED/CHANGED are a SEPARATE enum on FindingRegressionItem (FindingRegressionItem.java:26-29), not Finding.status values — an audit conflating the two sets would be wrong. Also note 'OPEN' status is deliberately NOT among regression item statuses. (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/domain/Finding.java:45-46 (also line 14))
- **CONFIRMED** (b) Finding.CATEGORIES = [CRASH, SOFT_LOCK, INVARIANT_VIOLATION, UNEXPECTED_STATE, BALANCE_ANOMALY, OTHER] (separate constant, required by validate()): validate() enforces category non-null + membership (FindingService.java:73-76); DDL comment matches (schema.sql:169-170). (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/domain/Finding.java:40-41 (also line 12))
- **CONFIRMED** (c) Finding.Evidence nested record has exactly 7 fields: sessionId (String, required main anchor), runId (Long, nullable), toolCallIds (List<Long>), observationSnapshot (Map<String,Object>), tick (Long, auxiliary display only), seed (Long), overrides (Map<String,Object>): Evidence is a nested record inside Finding (lines 54-67). No scenarioId/configVersion/expected/actual fields on Evidence — those live on the parent Finding. Evidence assembly in FindingReportTool.buildEvidence writes exactly these keys (sessionId, runId, toolCallIds, observationSnapshot, tick, seed, overrides) at FindingReportTool.java:118-148. (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/domain/Finding.java:54-67)
- **CONFIRMED** (d) FindingService.validate() rule set: severity non-null+memeber, category non-null+member, source (if non-null) member, title non-blank, scenarioId non-blank, configVersion non-blank AND really existing in game_config_version, reproSteps non-blank, evidenceJson (if non-empty) must deserialize to Evidence with non-blank sessionId: Order: severity 69-72, category 73-76, source 77-80, title 81-84, scenarioId 85-88, configVersion non-blank 89-92, configVersion existence 93-96, reproSteps 97-100, evidenceJson 101->validateEvidenceJson 108-124. status is NOT validated inside report()/validate() — insert defaults null status to 'OPEN' (FindingRepositoryImpl.java:78). (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/tester/FindingService.java:68-102 (validate) and 108-124 (validateEvidenceJson))
- **CORRECTION** (d) report() rejects configVersion that does not really exist in game_config_version (PF-003 / TD-T6-001 cleared). Any audit claim that validate() only checks non-empty and skips existence is OUTDATED: The current code DOES enforce existence: configService.findByVersion(f.configVersion()).orElseThrow(BusinessException.notFound(...)) at FindingService.java:95-96, documented as PF-003/TD-T6-001 clearance (FindingService.java:20-22 javadoc). Chain verified: ConfigVersionService.findByVersion delegates to repo; ConfigVersionRepositoryImpl.findByVersion runs 'SELECT * FROM game_config_version WHERE version=?' against the real table (schema.sql:88 CREATE TABLE game_config_version). This directly contradicts the stale statement in docs/QA_ARCHITECTURE_AUDIT_REPORT.md:66 and :154 that v%s is unvalidated/'仍未清偿' — the debt has since been cleared in code. (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/tester/FindingService.java:93-96 -> ConfigVersionService.java:59-60 -> ConfigVersionRepositoryImpl.java:41-44 -> schema.sql:88)
- **CONFIRMED** (e) No status-transition/state-machine validation exists: updateStatus() only whitelist-checks the target status against Finding.STATUSES, then performs an unconditional UPDATE with no source-state or transition-matrix enforcement: updateStatus checks only Finding.STATUSES.contains(status) (line 56-59) then calls repo.updateStatus which is a bare 'UPDATE finding SET status=? WHERE id=?' (FindingRepositoryImpl.java:86). The 'OPEN → CONFIRMED/DISMISSED/FIXED' comment at FindingService.java:54 is doc-only — e.g. DISMISSED→FIXED or FIXED→OPEN are all accepted. Additionally report() does not validate status at all (validate() has no status branch; see FindingService.java:68-102). (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/tester/FindingService.java:54-62; d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/repository/FindingRepositoryImpl.java:85-87)
- **CONFIRMED** (f) No duplicate-prevention exists on the write path: report() = validate + unconditional insert; the finding DDL has NO unique constraint; dedup happens only in-memory at regression-diff time via the FindingIdentity fingerprint: report() is just validate+insert (FindingService.java:39-41). schema.sql finding table (164-180) has only PK id and two non-unique indexes idx_finding_ver_sev/idx_finding_run (183-184) — no UNIQUE(version,scenario,fingerprint...). Documents explicitly allow duplicates: FindingRegressionService.java:33 and domain/FindingIdentity.java:12 (Finding 允许重复上报). Dedup is only pickHighestSeverityByFingerprint() during diff() (FindingRegressionService.java:64-65,143-154). (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/tester/FindingService.java:38-41; d:/AI-Game-Designer/backend/src/main/resources/schema.sql:164-180; d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/tester/FindingRegressionService.java:33,64-65,143-154)
- **CONFIRMED** (g) findByFilter signature is findByFilter(String configVersion, String severity, Long runId, Long testPlanId) returning List<Finding>, with null parameters meaning 'ignore condition': All three layers share the 4-arg signature (String configVersion, String severity, Long runId, Long testPlanId) -> List<Finding>. Impl builds 'SELECT * FROM finding WHERE 1=1' + per-null predicates (config_version, severity, run_id, test_plan_id) + ORDER BY id (FindingRepositoryImpl.java:101-120). RegressionService calls it as findByFilter(ver, null, null, null) (FindingRegressionService.java:61-62) and TesterController as findByFilter(configVersion, severity, runId, null) (TesterController.java:97). (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/tester/FindingService.java:49-52; d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/repository/FindingRepository.java:27-28; d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/repository/FindingRepositoryImpl.java:99-121)
- **CORRECTION** Draft audit references tester/Fingerprint.java and path prefix backend/src/main/com/... — no Fingerprint class exists; the fingerprint logic lives in tester/FindingFingerprintService.java and the normalized identity value object in domain/FindingIdentity.java; the actual source root is backend/src/main/java/com/: Files found: tester/FindingFingerprintService.java (@Service, fingerprint(Finding)->FindingIdentity at :37-47, static normalizeTitle = trim.toLowerCase.replaceAll("\\s+"," ") at :60-67, null/blank throws IllegalArgumentException) and domain/FindingIdentity.java (record scenarioId/category/normalizedTitle, PLACEHOLDER sentinel at :25-26). No class named Fingerprint exists anywhere in the repo. Correct base path is backend/src/main/java/com/aigamelab/, not backend/src/main/com/. (d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/tester/FindingFingerprintService.java:28-75; d:/AI-Game-Designer/backend/src/main/java/com/aigamelab/domain/FindingIdentity.java:19-26)

### Critic 3

- **CORRECTION** (a) A per-role tool whitelist exists in the runtime today: A per-role tool whitelist DOES exist and IS applied in the runtime loop: AgentLoop.selectToolSpecs resolves the AgentType via agentTypeResolver.resolve(task), calls agentTypeResolver.toolWhitelist(agentType), and filters the LlmToolSpec list (skip tools not in the set, AgentLoop.java:256-258). DefaultAgentTypeResolver.toolWhitelist returns distinct non-null sets per role (DESIGNER=10 tools L65-70, TESTER=8 L56-58, RESEARCH=6 L76-78). So the claim that 'no per-role tool whitelist exists' is false. Caveat: enforcement is only at the prompt/spec-advertisement layer (which tools the LLM is told exist); the dispatch path below has no role check (see next check). (AgentLoop.java:248-263; DefaultAgentTypeResolver.java:143-149)
- **CONFIRMED** (a) Isolation absent at dispatch: the loop passes any toolName to ToolExecutor without a role check: AgentLoop iterates resp.toolCalls() and calls toolExecutor.execute(fresh, step, tc) with no role/agent-type argument (L154-155). ToolExecutor.execute fetches the tool by name (registry.get(dto.name()) L63) and dispatches it (registry.dispatch(dto.name(), args, ToolContext.of(run.id(), step.id())) L83-84); ToolContext carries only runId/stepId, never the role. ToolRegistry.dispatch (L62-68) executes any registered name with no permission/role check. If the LLM emits a toolName that is registered but outside its whitelist (e.g. a TESTER run emitting simulate_game, or a DESIGNER run emitting game_start), it executes. This exactly matches the parenthetical evidence criterion: dispatch-time isolation is absent. (AgentLoop.java:154-155; ToolExecutor.java:63,83-84; ToolRegistry.java:62-68)
- **CONFIRMED** (b) A QA finding query can leak Tester/Designer/Research data; run/task-queryable APIs expose simulate/tester-session/research data broadly: The QA finding query (FindingsPage -> GET /api/findings and /api/findings/{id}, TesterController.java:92-104) returns complete finding rows with NO ownership or role scoping: FindingRepositoryImpl.findByFilter (L99-121) filters only on optional configVersion/severity/runId, and GET /api/findings has no run/task requirement at all (global list). Each finding's evidence_json embeds full Tester session data - sessionId, runId, toolCallIds, observationSnapshot (full game-state snapshot), seed, overrides (built in FindingReportTool.buildEvidence L118-149; shape defined in Finding.java:54-68). Additionally, broad per-run/task exposure of all three roles' data exists: GET /api/runs/{id} (RunController.java:101-117) returns Run + all Steps + all tool_calls including arguments_json and result_json (stored by ToolExecutor.completeToolCall L134-139) for any run id - spanning Designer simulate_game sim results, Tester game_start/game_observe/game_act session data, and Research web_search/fetch_page content. GET /api/experiments + /{id} (ExperimentController.java:54-90) exposes the simulate (experiment) table with no run/task scoping. GET /api/research/sources|claims|reports (ResearchController.java:51-161) is queryable by runId OR taskId. No auth/authorization layer exists anywhere in the codebase (only LlmMessage.role chat roles). FindingsPage also links findings to the full run trace (FindingsPage.tsx:79,128-131), making the leak path direct. (TesterController.java:92-104; FindingRepositoryImpl.java:99-121; FindingReportTool.java:118-149; RunController.java:101-117; ExperimentController.java:54-90; ResearchController.java:51-161)
- **CONFIRMED** (c) Finding is shared across Tester and QA in a single table with no separate qa table: Only ONE `finding` table exists (schema.sql L164-184); the schema comment at L149 explicitly declines separate tester tables ('明确不新增 tester_run/tester_step/tester_event/tester_project 等其他 Tester 表'). A grep of every CREATE TABLE statement in schema.sql finds zero qa_* tables. FindingRepository (L15-29) is a single contract over the finding table; FindingRepositoryImpl inserts/queries only `finding`. The Tester writes via finding_report -> FindingService -> same table (FindingReportTool.java:76-95), and QA reads/flips status via /api/findings (TesterController.java:92-115) on the same table. The QA architecture docs confirm the proposed qa_role_assignment/qa_release_gate tables are NOT implemented and '源码 grep qa_ 零命中' (QA_ARCHITECTURE_DECISION_REVIEW.md:44-46,175). Frontend has no QA page/table - FindingsPage.tsx is the shared consumption surface. Finding.SOURCES (INVARIANT/LLM/MANUAL, Finding.java:43-44) and STATUSES (OPEN/CONFIRMED/DISMISSED/FIXED) carry no QA marker. (schema.sql:164-184,149; FindingRepository.java:15-29; FindingRepositoryImpl.java:51-121; docs/QA_ARCHITECTURE_DECISION_REVIEW.md:44-46,175)

### Critic 4

- **CONFIRMED** All 7 frozen files exist at their known paths under backend/src/main/java/com/aigamelab/: AgentLoop.java, RunStateMachine.java, BudgetGuard.java, LoopDetector.java, ToolExecutor.java (all in runtime/), ToolResult.java (tool/), ErrorCodes.java (common/) — all located and readable. (Glob results: backend\src\main\java\com\aigamelab\runtime\{AgentLoop,RunStateMachine,BudgetGuard,LoopDetector,ToolExecutor}.java, backend\src\main\java\com\aigamelab\tool\ToolResult.java, backend\src\main\java\com\aigamelab\common\ErrorCodes.java)
- **CONFIRMED** None of the 7 frozen files contain QA-specific logic (qa_, QA, etc.): Grep for patterns qa_|QA|qa[A-Z]|QA[A-Z] returned zero matches in all 7 files. No frozen file has been contaminated with QA support logic. (Grep across all 7 files — 7/7 returned 'No matches found')
- **CONFIRMED** Baseline records 443 tests / 0 failures / 0 errors: docs/reports/QA_PREFLIGHT_BASELINE.md explicitly states: '443 tests / 0 failures / 0 errors — BUILD SUCCESS' across sequential, JUnit random (seed=7), and surefire random orderings. Baseline date: 2026-09-04, git HEAD 33b6b15. (docs/reports/QA_PREFLIGHT_BASELINE.md line 21: '| 顺序 full-suite mvn -o test | 443 tests / 0 failures / 0 errors — BUILD SUCCESS |')
- **CONFIRMED** No qa_ tables exist in schema.sql: Grep for qa_ (case-insensitive) in backend/src/main/resources/schema.sql returned zero matches. QA tables have not been added to the production schema. (Grep 'qa_' over schema.sql — 'No matches found')
- **CONFIRMED** backend/ is untracked in git — 'frozen' is convention-only, not git-enforced: git status --short shows '?? backend/' — the double-question-mark prefix means the entire backend/ directory is untracked. There is no git history to diff against, so 'frozen files' cannot be protected by git diff hooks or branch protection; the freeze is purely a team convention. (git status --short output: '?? backend/' (also '?? docs/' '?? frontend/' '?? scripts/' '?? .gitignore', and 'M README.md'))
---

# QA P1 AUDIT COMPLETE — WAITING FOR HUMAN ACCEPTANCE

> 在人工确认本 Audit 前，不开始任何大规模编码。
> P1 启动条件：①本 Audit 已接受；②P1-1..P1-6 按序推进；③任何触及冻结文件的发现必须先 STOP 并按 §15 上报等待人工批准。
