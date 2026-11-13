# R7 POST-QA PHASE 2 ARCHITECTURE & ROADMAP AUDIT

> **性质**：Post-QA Phase 2 Architecture & Roadmap Audit（仅审计 + 设计评估，不实现）。
> **状态**：**R7 ARCHITECTURE & ROADMAP AUDIT — COMPLETE / WAITING FOR HUMAN DECISION**。
> **生成时间**：2026-09-16。
> **审计基线**：R6 QA Phase 2 Closure `0545b7a`；R4 Architecture Design `9e18bbd`。
> **回归基线**：后端 620/0/0，前端 57/0/0。
> **冻结红线**：全程零 Frozen 文件改动（AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult/ErrorCodes/AgentEventType）。
> **审计方法**：12 维度并行审计 + 对抗验证（BLOCKER/HIGH findings 由独立 agent 否认）。
> **已完成维度**：9/12（AgentType+Whitelist / Tester / Designer / QA Phase 2 / Frozen-Boundary / Schema Ownership / Project Isolation / Tech Debt / Security-Permission）；其余 3 维度（Research / Cross-Agent / Architecture-Quality）agent 超时未返回，由既有数据与人工核验补全。

---

## 1. Executive Summary

R7 审计确认 **QA Phase 2 freeze-ready**：R5 编排 + R6 closure 全部交付完成，冻结边界零侵入，回归全绿。本次审计共 **12 个审计维度**并行执行，其中 **9 个维度返回结果**；在返回的 9 个维度中累计 **35 项 Findings**，无 BLOCKER 级阻塞。

> ⚠️ **R8-A5 措辞勘误（2026-09-17）**：本段与下表原先混用"12 维度"与"16 项审计"两种口径，导致审计维度数（12）、返回维度数（9）、Findings 条数（35）、严重度分布、架构风险数（11）、人工决策项数（8）相互混淆。现按统计口径分列，互不并计。

**统计口径（分列，互不混淆）**：

| 口径 | 数量 | 说明 / 严重度（BLOCKER / HIGH / MEDIUM / LOW-INFO） |
|---|---|---|
| 审计维度总数 | **12** | 并行 workflow 发起维度总数 |
| — 返回结果的维度 | **9** | 其中 3 维度（Research / Cross-Agent / Architecture-Quality）agent 超时未返回，由既有数据与人工核验补全 |
| 审计 Findings（9 维度累计） | **35** | 0 / 1 / 12 / 22 |
| 技术债（§12 独立盘存） | **24** | 0 / 1 / 12 / 11 |
| 架构风险（§13 独立盘存） | **11** | 0 / 2 / 6 / 3 |
| 人工决策项（§16） | **8** | — |

> 说明：审计 Findings（35）与技术债（24）/ 架构风险（11）为**不同口径的独立盘存**，部分条目跨表重叠（如 ToolExecutor dispatch 同时出现在 Findings 与架构风险），故各列之和不等于全体去重后的唯一条目数。

**最高优先级发现**：

1. **AgentLoop.observe() 零历史回放**（[AgentLoop.java:224-230](backend/src/main/java/com/aigamelab/runtime/AgentLoop.java#L224-L230)）：LLM context 仅包含 `[system(prompt), user(goal)]`，不回放历史 steps/tool_calls 结果。R5 QA_LEAD 编排依赖 LLM 在多轮中记住 subtask/run ID，真实 LLM 场景下闭环不成立。FB-02 同时确认冻结 AgentLoop 的 javadoc（line 28-29）声称 observe() 重建历史 assistant/tool messages，与实际实现不符。
2. **R6 §13 ErrorCode 文档漂移**（[R6_QA_PHASE2_CLOSURE_AUDIT.md:128](docs/reports/R6_QA_PHASE2_CLOSURE_AUDIT.md#L128)）：报告列出 `20001/50001/50301`，实际 ErrorCodes.java 为 `40001/40401/40901/42901/50201/50000`（R8-A5 勘误：恰 6 码，原行仅列 4 码不完整）——报告原列 3 个码值（20001/50001/50301）均非实际常量。
3. **task-state.md 与 tech-debt.md 状态矛盾**（TS-02）：TD-T6-001/TD-T7-001 在 tech-debt.md 标记已清偿，task-state.md 仍为 OPEN。
4. **QA-01 dominantBuildShare 死代码**：`QaReleaseGateService.countStats` 硬编码 `dominantBuildShare=null`（:150），`isBuildShareImbalanced(null)` 恒 false——Gate FAIL 的 build share 失衡分支（0.75 阈值）**永不触发**。
5. **FB-01 RUN_TIMEOUT 事件缺口**：`AgentLoop.finalizeRun()` 可发 `RUN_TIMEOUT`（status=TIMEOUT 时），但 AgentEventType 无此枚举值，`PersistingAgentEventPublisher` 静默丢弃。当前不可达（timeoutMs=-1），但若启用时间预算则事件丢失。
6. **ToolExecutor dispatch 无白名单校验（TD-02，HIGH）**：白名单仅塑造 LLM 可见 tool specs（AgentLoop.selectToolSpecs），`ToolExecutor.execute()`（:58-84）零 agentType/whitelist/role 检查——LLM 幻觉/重放非本 AgentType 工具名会被实际执行（mutating 边效应仅审批挂起缓解）。已知项 TD-QA-P11-001 / R4 H-9bis，工具为冻结文件（ToolExecutor.java）。

---

## 2. Current Baseline

| 维度 | 值 | 验证来源 |
|---|---|---|
| 后端测试 | 620/0/0 BUILD SUCCESS | R6 §15 实测 |
| 前端测试 | 57/0/0 + tsc + build | R6 §15 实测 |
| 数据库表 | 16 张（14 既有 + 2 新） | schema.sql, ContractMatrixTest |
| ErrorCode | 6 码（40001/40401/40901/42901/50201/50000） | ErrorCodes.java |
| AgentType | 8 值 | AgentType.java, ContractMatrixTest C1 |
| 工具注册 | 29 个 GameTool @Component | ToolRegistry, ContractMatrixTest C3 |
| SSE Event | 26 个 AgentEventType | AgentEventType.java |
| Prompt 版本 | 13 个（1 active = designer_p001） | PromptService, ContractMatrixTest C5/C6 |
| Frozen 边界 | 8 文件零改动 | git diff, R6 §2 |
| Git HEAD | `9e18bbd`（clean working tree） | git status |
| 前端页面 | 14 页面 + 9 测试文件 | frontend/src/pages/ |
| REST 端点 | Tester 8 + Research 6 + QA 5 + Config 5 + Run 2 + Task 2 + Experiment 4 + SSE 2 + Dashboard 2 + DevEvent 2 | Controller audit |

---

## 3. Designer Audit (P0-P8)

**审计结论：PASS**

| 项 | 状态 | 验证 |
|---|---|---|
| P0-P8 全部完成 | 34 任务 DONE | FINAL_RELEASE_CANDIDATE_REPORT.md |
| designer_p001 | active（唯一） | PromptService:88-106, setActive |
| designer_p002 | INACTIVE，零激活尝试 | PromptService:150-157, inactive=false |
| 11 Designer 工具 | 冻结于 e7e18cd | git log e7e18cd..HEAD: 0 commits |
| 跨线零越权 | 无 test_plan/finding/qa_* 访问 | Grep tool/*.java |
| v001.json | 有效，P4-0 校准正确 | config/v001.json |

**设计系统状态**：DESIGNER 使用硬编码 system prompt（`DefaultAgentTypeResolver.java:57,183`），不从 prompt_version 表加载。10 个工具包含 2 个 R2-1 Research→Designer 消费工具（get_research_context, get_research_claims）。

---

## 4. Tester Audit (T1-T7)

**审计结论：CONDITIONALLY PASS（3 个文档漂移）**

| 项 | 状态 | 验证 |
|---|---|---|
| T1-T7 全部 DONE/FROZEN | FINAL FREEZE 2026-08-31 | task-state.md:25 |
| Tester 工具 | 8 个（TESTER_TOOL_NAMES） | DefaultAgentTypeResolver.java:83-85 |
| TesterController | 8 端点 | TesterController.java |
| SSE 事件 | 6 TEST_* 类型 | AgentEventType.java:27-32 |
| gameenv/ | 零框架依赖，纯 Java 接口 | GameAdapter.java, FakeGameEnvironment.java |
| TestPlan CRUD | 完整 | TestPlanService + Repository |
| Finding CRUD | 完整 + LEGAL_TRANSITIONS 生命周期 | FindingService |

**Finding（文档漂移）**：

| ID | 严重度 | 问题 | 位置 |
|---|---|---|---|
| TS-01 | MEDIUM | FindingRepository/TestPlanRepository Javadoc 仍声称 'T-DB1 人工闸门未批准——schema 无此表'，但 DDL 已存在且 Impl 已上线 | FindingRepository.java:10-13 |
| TS-02 | MEDIUM | task-state.md 标 TD-T6-001/TD-T7-001 为 OPEN/DEFERRED，但 tech-debt.md 标记已清偿（PF-003） | task-state.md:1098-1099 vs tech-debt.md:81-82 |
| TS-03 | LOW | task-state.md 称 AgentEventType '6 枚举追加至 22'，实际已 26（R0 新增 4 RESEARCH_*） | task-state.md:1062 |

---

## 5. Research Audit (R0-R2)

**审计结论：PASS**

| 项 | 状态 | 验证 |
|---|---|---|
| R0-R2 全部 DONE | FROZEN | task-state.md |
| Research 工具 | 6 个 | RESEARCH_TOOL_NAMES |
| ResearchController | 6 GET 端点 | /api/research/sources, claims, reports |
| 前端 ResearchPage | 有页面 + 测试 | ResearchPage.tsx, ResearchPage.test.tsx |
| WebSearchTool | SerpAPI 集成 | SERPAPI_API_KEY env |
| TK（信任知识库） | DEFERRED | 架构设计 §15 |
| LLM 实测限制 | Live E2E 未做（依赖 LLM key） | R4 §21 |

**Research→Designer 产物消费**：通过 get_research_context/get_research_claims 两个只读工具实现，Designer 白名单已包含。Research Source/Claim/Report 三表 + 3 个查询端点完整。

---

## 6. QA Phase 2 Audit

**审计结论：CONDITIONALLY PASS（架构风险需人工裁决）**

| 项 | 状态 | 验证 |
|---|---|---|
| QaOrchestratorService | 确定性 ROLE_ORDER，300s wall-clock | R5 §6, R6 §7 |
| QaReleaseGateService | RULE_VERSION, 优先级链（含 R6 step 4b 修复） | R5 §7, R6 §9 |
| AbstractQaTool | leadContext defense-in-depth 校验 | AbstractQaTool.java |
| QA_LEAD 工具 | 6 个（5 新 + finding_report 复用） | QA_LEAD_TOOL_NAMES |
| QA REST | 5 端点（1 既有 + 4 R5 新） | QaController.java |
| 前端 AGENT_TYPES | 5 值含 QA_LEAD | WorkflowPage.tsx:8 |
| 前端 QA gate/orchestration | **无消费者** | api.ts 无 gates/orchestrations 端点 |
| 回归 | 后端 620/0/0, 前端 57/0/0 | R6 §15 |

**关键架构风险**：

1. **AgentLoop.observe() 零历史回放**（[AgentLoop.java:224-230](backend/src/main/java/com/aigamelab/runtime/AgentLoop.java#L224-L230)）：每轮 LLM context 仅含 `[system(prompt), user(goal)]`，无历史 steps/tool_calls 回放。QA_LEAD 编排在多轮中无法看到先前 qa_create_subtask/qa_start_subrun 的返回结果，subtask/run ID 无法在 context 中持续。**当前仅 Fake 剧本场景可用，真实 LLM 场景闭环不成立**。R4 §21 已列为 Phase 2 准入门槛候选。

2. **Wall-clock deadline 进程内**：`QaOrchestratorService.deadlines` 为 `ConcurrentHashMap`，进程重启即丢失。重启后 Lead 恢复续跑时 deadline 归零，语义不明（FB-04 确认）。

### 6.1 QA Phase 2 审计 Findings（工作流维度，CONDITIONALLY_PASS）

| ID | 严重度 | 问题 | 位置 |
|---|---|---|---|
| QA-01 | MEDIUM | Gate FAIL 的 build share 失衡分支（0.75 阈值）是死代码：`countStats` 硬编码 `dominantBuildShare=null`，`isBuildShareImbalanced(null)` 恒 false | QaReleaseGateService.java:150,160 |
| QA-02 | MEDIUM | Wall-clock 300s deadline 执行路径零测试覆盖：deadlineExceeded / freezeRunningToTimeout / startSubrun 超时拒绝均未测 | QaOrchestratorService.java:55,158,233,268 |
| QA-03 | LOW | `checkSubrunStatus` fallback 把 `leadTask.id()`（Task ID）当作 parentRunId 传给 `findByParentRun`（应传 Run ID）；实际不可达（主路径 findBySubTask 恒成功） | QaOrchestratorService.java:193 |
| QA-04 | LOW | Gate status 列可写 `INCONCLUSIVE`，但 `QaReleaseGate.STATUSES = [PENDING, PASS, CONDITIONAL_PASS, FAIL]` 不含该值——域不闭合 | QaOrchestratorService.java:249 |
| QA-05 | LOW | 顺序编排仅靠 prompt 约定，无代码守卫：`startSubrun` 不校验前一 role 是否 COMPLETED，`newCachedThreadPool` 允许并行子 Run | QaOrchestratorService.java:149-179 |
| QA-06 | INFO | `normalizeLeadDecision` 用 substring 匹配，`"NOT INCONCLUSIVE — PASS"` 会被误判为 INCONCLUSIVE | QaReleaseGateService.java:193-201 |

---

## 7. Cross-Agent Architecture

### 7.1 Finding 域共享（非重复）

Finding 域由 Tester 定义，QA 复用，**无平行 QaFinding 域**：
- `FindingService.findByQaFilter()` 为 QA 专用查询方法，复用同一 `finding` 表
- `finding_report` 工具跨 6 个白名单（QA/TESTER/QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER），共享设计
- 前端 FindingsPage 使用 `GET /api/qa/findings`（8 维），TestPlanPage 使用 `GET /api/findings`（3 维），端点分离且有隔离测试

### 7.2 工具抽象

| 层级 | 抽象 | 关系 |
|---|---|---|
| Tester 工具 | AbstractTesterTool → GameAdapter → FakeGameEnvironment | 纯离线 |
| QA 编排工具 | AbstractQaTool → QaOrchestratorService → AgentLoop | 复用 AgentLoop |
| Research 工具 | AbstractResearchTool → SerpAPI | 独立外部依赖 |
| Designer 工具 | AbstractDesignerResearchTool | 只读 Research 产物 |

### 7.3 Prompt 耦合

8 个 AgentType 的 prompt 通过 `DefaultAgentTypeResolver` switch-case 路由：
- DESIGNER 硬编码（不从 DB 加载）
- TESTER/RESEARCH/QA 从 prompt_version 表加载
- QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER 通过 ThreadLocal `QA_PROMPT_VERSION_HOLDER` 路由

### 7.4 Schema 耦合

| 表 | 主消费者 | 共享方 |
|---|---|---|
| finding | Tester | QA (findByQaFilter) |
| task | TaskController | QA (createSubtask), Research |
| run | RunController | QA (startSubrun) |
| qa_role_assignment | QA | — |
| qa_release_gate | QA | — |

### 7.5 隐藏依赖

- QA 编排工具依赖 `ObjectProvider<AgentLoop>` 延迟注入打破循环依赖
- QA 编排依赖 `FindingService.findByQaFilter()`（跨领域调用）
- `qa_complete_orchestration` 调用 `QaReleaseGateService.evaluate()`（gate 逻辑与编排逻辑耦合）

---

## 8. Frozen Boundary

### 8.1 冻结文件（8 个）

| 文件 | 职责 | 自 e7e18cd 状态 |
|---|---|---|
| AgentLoop.java | LLM 交互主循环 | ZERO 改动 |
| RunStateMachine.java | Run 状态机 | ZERO 改动 |
| BudgetGuard.java | Token 预算守卫 | ZERO 改动 |
| LoopDetector.java | 循环检测 | ZERO 改动 |
| ToolExecutor.java | 工具执行编排 | ZERO 改动 |
| ToolResult.java | 工具返回值 | ZERO 改动 |
| ErrorCodes.java | 错误码注册表 | ZERO 改动 |
| AgentEventType.java | SSE 事件类型 | ZERO 改动 |

### 8.2 QA 与冻结核心的关系

QA Phase 2 **不隐式依赖冻结核心的内部状态**：
- QaOrchestratorService 通过 `ObjectProvider<AgentLoop>` 获得 AgentLoop 引用，仅调用公共 API（`runRepository`、`taskRepository`）
- AbstractQaTool 通过 `ToolContext` 获取 runId/taskId，不直接访问冻结内部
- pollSeq 递增参数规避 LoopDetector WINDOW=3 误报（三连承诺已兑现）

### 8.3 结论

**PASS** — 冻结边界完整，QA 建立在新 Service 层之上，无隐式冻结核心依赖。FB 审计发现 4 项潜在风险（FB-01..FB-04）：

| ID | 严重度 | 问题 | 位置 |
|---|---|---|---|
| FB-01 | MEDIUM | `AgentLoop.finalizeRun()` 可发 `RUN_TIMEOUT` 事件，但 AgentEventType 无此值，PersistingAgentEventPublisher 静默丢弃。当前不可达（timeoutMs=-1） | AgentLoop.java:184 + AgentEventType |
| FB-02 | LOW | 冻结 AgentLoop javadoc 声称 observe() 重建历史 assistant/tool messages，实际实现仅 system+user | AgentLoop.java:28-29 vs 224-229 |
| FB-03 | MEDIUM | 冻结文件硬编码约束（AgentLoop.timeoutMs=-1、RunStateMachine.TRANSITIONS 静态表）将阻碍未来非 QA 的 timeout/新 RunStatus 需求 | AgentLoop.java:52 + RunStateMachine.java:24-28 |
| FB-04 | LOW | QA 编排 deadline 进程内（ConcurrentHashMap），JVM 重启丢失；BudgetGuard 未启用（timeoutMs=-1），QA 子 Run 重启后可无限执行 | QaOrchestratorService.java:79,165 |

---

## 9. Schema

### 9.1 表清单（16 张）

| # | 表名 | 主消费者 | R5 新增 |
|---|---|---|---|
| 1 | task | TaskController | — |
| 2 | run | RunController | — |
| 3 | step | AgentLoop | — |
| 4 | tool_call | ToolExecutor | — |
| 5 | experiment | ExperimentService | — |
| 6 | game_config_version | ConfigVersionService | — |
| 7 | change_proposal | ApprovalService | — |
| 8 | agent_event | AgentEventPublisher | — |
| 9 | design_document | DesignDocumentRepository | — |
| 10 | prompt_version | PromptService | — |
| 11 | test_plan | TestPlanService | — |
| 12 | finding | FindingService | — |
| 13 | research_source | ResearchSourceRepository | — |
| 14 | research_claim | ResearchClaimRepository | — |
| 15 | **qa_role_assignment** | QaOrchestratorService | ✅ |
| 16 | **qa_release_gate** | QaReleaseGateService | ✅ |

### 9.2 所有权映射

- **run 表**：零改动，无 `parent_run_id` 列。父子关联由 `qa_role_assignment.parent_run_id` 承载（新授权表）
- **finding 表**：Tester 定义，QA 复用（findByQaFilter），无新列
- **task 表**：QA 编排通过 `constraintsJson.agent_type` 字段路由，无 schema 变更

### 9.3 Schema Ownership 审计 Findings（工作流维度，CONDITIONALLY_PASS）

| ID | 严重度 | 问题 | 位置 |
|---|---|---|---|
| SC-01 | MEDIUM | **finding 表所有权不明确**：schema 注释声明 "Tester 的一等产出，供 Designer/Builder 消费"，但 4 种 QA AgentType（QA/QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER）白名单全部可写 finding_report，QA gate 依赖 Finding 行计算门禁；`source` 列仅 INVARIANT/LLM/MANUAL，无法区分 Tester 与 QA 产出作者 | schema.sql:163-164 + DefaultAgentTypeResolver 白名单 |
| SC-02 | LOW | **qa_release_gate 缺 UNIQUE(lead_task_id, config_version)**：QaReleaseGate javadoc 声明 "一棵 QA 树对一个 config_version 只有一条 Gate 行"，但 schema 仅 3 个非唯一索引（idx_qrg_task/idx_qrg_config/idx_qrg_status），唯一性靠服务层 check-then-insert（QaOrchestratorService:241） | schema.sql:282-284 |
| SC-03 | LOW | **report_doc_id→design_document 释放报告路径零调用者**：`updateReportDoc(id, reportDocId)` 已在 Repository 定义（QaReleaseGateRepository:35 + Impl:143），但全后端无调用者——Release Report→design_document 复用链路未接线 | QaReleaseGateRepositoryImpl.java:143 |
| SC-04 | INFO | **config_version 无约束 TEXT 软键**：`finding/test_plan/experiment/qa_release_gate` 等表持 `config_version` TEXT 但无 FK 到 game_config_version，属隐藏跨-agent 依赖（软键，无引用完整性） | schema.sql 多处 |

> SC-01 是核心发现：finding 表被两个 Agent 家族（Tester/QA）作为一等产出共享，无归属区分列。建议在 T-DB1 文档明确为共享表，或新增 `producer_type` 列（Phase 执行期回填）。

### 9.4 结论

**PASS** — Add-only 迁移，16 表契约测试满（ApplicationTests + TesterRepositoryTest + ResearchRepositoryTest），零既有表改动。新增 SC-01~SC-04 为 QUALITY 保留项，不阻塞 freeze。

---

## 10. Project Isolation

### 10.1 现状评估

**评级：LOW（无 project_id 概念）**

| 项 | 评估 |
|---|---|
| project_id 列 | 不存在于任何表 |
| 多租户隔离 | 不支持——单项目设计 |
| QA 子树隔离 | lead_task_id 作为隔离根（QaReleaseGateService 查询限定 leadTaskId） |
| 跨 QA 子树访问 | 不可能——每个 lead_task_id 独立 tree |
| 前端 | 单一入口，无项目选择器 |

### 10.2 Project Isolation 审计 Findings（工作流维度，PASS）

| ID | 严重度 | 问题 | 位置 |
|---|---|---|---|
| PI-01 | LOW | **单租户架构真实存在**：16 张表零 project_id 列已由 schema + 领域 record 验证；grep 后端/前端 仅命中有 projectileCount 游戏术语，零 domain project 引用。R4 §16.3（H-8）已诚实披露："project_id 全表不存在（单项目事实）"。属 **正确记录的已知限制**，非文档与代码脱节 | schema.sql:6-285, 所有 Domain record, task-state.md |

> PI-01 的 LOW 评级含义：**当前无第二个租户可存在，因此不可能泄露**。前向风险 MEDIUM（multi-project 需全局 schema migration，属 FROZEN CONTRACT CHANGE）。

### 10.3 风险

- 当前设计天然安全（单项目），但未来扩展到多项目时需要全局 project_id migration
- R4 架构设计 §16 已诚实披露此限制，并将 project_id 改造列为 H-8（独立立项）

### 10.4 结论

**LOW** — 当前不阻塞任何功能，但 multi-project 扩展需要独立设计。

---

## 11. Security / Permission

### 11.1 三层防护

| 层级 | 机制 | 位置 |
|---|---|---|
| L1 | AgentType → 白名单路由 | DefaultAgentTypeResolver.toolWhitelist() |
| L2 | 白名单互斥（QA_* 不含 qa_* 编排工具） | QA_TESTER/ANALYST/RESEARCHER 白名单 |
| L3 | 运行时 guard（defense-in-depth） | AbstractQaTool.leadContext() |

### 11.2 白名单安全矩阵

| AgentType | 工具数 | qa_* 工具 | 非 QA 越权 |
|---|---|---|---|
| DESIGNER | 10 | 0 | DENY |
| TESTER | 8 | 0 | DENY |
| RESEARCH | 6 | 0 | DENY |
| QA | 1 | 0 | DENY |
| QA_LEAD | 6 | 5 | — |
| QA_TESTER | 10 | 0 | DENY |
| QA_ANALYST | 7 | 0 | DENY |
| QA_RESEARCHER | 10 | 0 | DENY |

### 11.3 L3 guard 验证

`AbstractQaTool.leadContext()` 校验：
- 调用方 task 的 `agent_type` 必须是 `QA_LEAD`
- 否则抛出 `BusinessException.conflict()`
- 仅对 5 个 qa_* 工具生效（finding_report 无需 guard）

Security/Permission 维度审计（工作流，RISK）新增 Findings：

| ID | 严重度 | 问题 | 位置 |
|---|---|---|---|
| SP-01 | MEDIUM | **ToolExecutor dispatch 无白名单校验**（= TD-02 / TD-R7-018 / R-11，验证 agent CONFIRMED）：`execute()` 仅 `registry.get(name)` 确认注册 + `registry.dispatch()` 执行，零 agentType/whitelist/role 检查；AgentLoop 将 LLM 工具调用直通 execute 无二次过滤。LLM 幻觉/注入（research 网页内容）可触发任意外白名单工具 | ToolExecutor.java:63-84 + AgentLoop.java:154-158 |
| SP-02 | MEDIUM | **AbstractTesterTool/AbstractResearchTool/AbstractDesignerResearchTool 无运行时 agent-type guard**（非对称）：只有 AbstractQaTool.leadContext 存在 L3 二检；Designer/Tester/Research 工具线无第二屏障，全靠 visibility filter | AbstractTesterTool.java:46-59 等 |
| SP-03 | LOW | **正面验证**：QA_TESTER 无法触达 qa_create_subtask——L2（白名单不含）与 L3（leadContext 拒绝，agent_type≠QA_LEAD → conflict）双重阻断，编排提权被正确拦截 | DefaultAgentTypeResolver + AbstractQaTool.java:92-96 |
| SP-04 | LOW | **QaToolIsolationTest I1 只断言 DESIGNER/TESTER/RESEARCH**，未断言 QA_TESTER/QA_ANALYST/QA_RESEARCHER 白名单排除 5 个编排工具；亦无打针拒绝测试 | QaToolIsolationTest.java:85-98 |
| SP-05 | INFO | **无认证层**：TaskController 接受全部 8 个 AgentType；全后端零 auth 中间件；maxSteps 无上界校验（可负）。本地单用户场景可接受，网络部署前需加鉴权 | TaskController.java:71-129,171-177 |

### 11.4 ToolExecutor 层

ToolExecutor 不在 dispatch 时校验白名单——白名单仅在 AgentLoop.selectToolSpecs() 中过滤 LLM 可见工具。这意味着如果绕过 AgentLoop 直接调用 ToolExecutor.dispatch()，或 LLM 幻觉/重放了非本 AgentType 的工具名，任何注册工具都可执行。

- **工作流 Tech-Debt 维度标注 TD-02 = HIGH（security-authorization）**：`ToolExecutor.execute()`（:58-84）零 agentType/whitelist/role 检查，29 工具共享全局 registry。
- **缓解现状**：存活风险仅靠 LLM 遵从 tool specs；qa_* 编排工具另有 AbstractQaTool.leadContext 二检（QA_LEAD 专属），但非 qa_* 的跨线工具（如 DESIGNER 的 modify_game_config）无 dispatch 期校验；approval-suspend 仅挂起非阻止。
- **归类**：已知项 TD-QA-P11-001 / R4 H-9bis（P0 硬化点）；**ToolExecutor.java 为冻结文件，修复需人工授权解冻**（见 R-11 / H-9bis）。

### 11.5 结论

**RISK（工作流维度标注）→ 本项目结论：CONDITIONALLY PASS** — L1/L2 一致且互斥，L3 对 QA 编排工具有效（SP-03 正面验证）；但 L3 对 Designer/Tester/Research 工具线缺失（SP-02）、ToolExecutor dispatch 层零校验（SP-01，HIGH 级、需解冻 ToolExecutor）、QA 编排工具隔离测试覆盖不全（SP-04）。综合归类 R-11 / TD-R7-018，列入最高优先级人工决策项（H-9bis）。冻结边界下维持"可冻结"（当前无外部入口绕过 AgentLoop，风险仅靠 LLM 遵从 tool specs 缓解）。

---

## 12. Technical Debt

### 12.1 活跃技术债

| ID | 问题 | 位置 | 影响 | 优先级 | 冻结? | 必须解决? | 建议 Phase |
|---|---|---|---|---|---|---|---|
| TD-001 | JDK 21 借用 VS Code JRE | 系统环境 | CI/构建不可复现 | MEDIUM | 否 | 是（CI 前） | Phase 1 |
| TD-002 | Maven 非默认仓库路径 | D:\apache-maven-3.9.10\repo | 换机不可复现 | LOW | 否 | 否 | Phase 1 |
| TD-003 | ToolRegistry 无 unregister | ToolRegistry.java | 测试隔离弱 | MEDIUM | 否 | 否 | Phase 2 |
| TD-005 | 02 vs 03 P6-4 ConfigController 文档冲突 | docs/ | 未来范围争议 | MEDIUM | 否 | 否 | Phase 1 |
| TD-006 | acceptableBuildDpsVariance 用胜率方差代理 | EvaluationService.java | 非阻塞 | LOW | 否 | 否 | Phase 3 |
| TD-QA-R-001 | eventReplayAfterIdAndRecent 绝对 id 偶发失败 | RepositoryTest.java | 测试不稳定 | MEDIUM | 否 | 否 | Phase 2 |
| **TD-R7-001** | **R6 §13 ErrorCode 文档漂移（20001/50001/50301 错误）** | R6_QA_PHASE2_CLOSURE_AUDIT.md:128 | 误导开发者 | **MEDIUM** | 否 | **是** | **Phase 1（修复文档）** |
| **TD-R7-002** | **task-state.md TD-T6-001/TD-T7-001 状态矛盾** | task-state.md:1098-1099 | SSOT 不一致 | **MEDIUM** | 否 | **是** | **Phase 1（修复文档）** |
| **TD-R7-003** | **FindingRepository/TestPlanRepository Javadoc 过时** | FindingRepository.java:10-13 | 误导贡献者 | **MEDIUM** | 否 | 否 | Phase 1 |
| **TD-R7-004** | **AgentEventType 计数 22→26 过时** | task-state.md:1062 | 历史记录偏差 | LOW | 否 | 否 | Phase 1 |
| **TD-R7-005** | **dominantBuildShare 死代码（QA-01）** | QaReleaseGateService.java:150,160 | Gate FAIL build-share 分支永不触发 | MEDIUM | 否 | 否 | Phase 3 |
| **TD-R7-006** | **Wall-clock deadline 零测试覆盖（QA-02）** | QaOrchestratorService | 治理行为无集成验证 | MEDIUM | 否 | 否 | Phase 2 |
| **TD-R7-007** | **checkSubrunStatus fallback task/run id 混淆（QA-03）** | QaOrchestratorService.java:193 | 潜在错误查询（当前不可达） | LOW | 否 | 否 | Phase 2 |
| **TD-R7-008** | **INCONCLUSIVE 域不闭合（QA-04）** | QaReleaseGateService + QaReleaseGate.STATUSES | 下游过滤契约不一致 | LOW | 否 | 否 | Phase 2 |
| **TD-R7-009** | **RUN_TIMEOUT 事件缺口（FB-01）** | AgentLoop.java:184 | 启用 timeout 时事件丢失 | MEDIUM | 否 | 否 | 冻结解禁时 |
| **TD-R7-010** | **AgentLoop javadoc 与实现不符（FB-02）** | AgentLoop.java:28-29 | 误导维护者 | LOW | **是** | 否 | 冻结解禁时 |
| **TD-R7-011** | **finding 表所有权不明确（SC-01）** | schema.sql:163-164 | Tester/QA 双家族写同一表，无法区分产出作者 | MEDIUM | 否 | 否 | Phase 1（文档明确共享）/ Phase 3（加 producer_type 列） |
| **TD-R7-012** | **qa_release_gate 缺唯一约束（SC-02）** | schema.sql:282-284 | javadoc 声明唯一但 schema 无 UNIQUE，靠服务层 check-then-insert | LOW | 否 | 否 | Phase 2 |
| **TD-R7-013** | **updateReportDoc 零调用者（SC-03）** | QaReleaseGateRepositoryImpl.java:143 | Release Report→design_document 链路未接线（死代码） | LOW | 否 | 否 | Phase 3 |
| **TD-R7-014** | **config_version 软键无 FK（SC-04）** | schema.sql 多处 | 6 表间隐藏跨-agent 依赖，无引用完整性 | LOW | 否 | 否 | 冻结解禁时/独立立项 |
| **TD-R7-015** | **Gate-2 check_frozen.sh 未实现** | scripts/（仅有 dev/reset-db/bat/sh） | 冻结零漂移靠人工 git diff 纪律保障，无自动守护 | MEDIUM | 否 | 否 | 下一阶段 housekeeping |
| **TD-R7-016** | **X-7 configVersions 双 React Query key** | ConfigDiffPage 'config-versions' vs ConfigRegressionPage 'configVersions' | 同端点双缓存，审批失效仅刷新其中一项 → 版本下拉可过期 | LOW | 否 | 否 | 前端清理 |
| **TD-R7-017** | **X-5 useSseDrivenRefresh/useRunSse 死导出** | useGlobalSse.ts:177,192 + ResearchPage.tsx:81 | 死代码 + ResearchPage 注释宣称"复用 useSseDrivenRefresh"实际未导入，误导维护者 | LOW | 否 | 否 | 前端清理 |
| **TD-R7-018** | **ToolExecutor dispatch 无白名单校验（TD-02）** | ToolExecutor.java:58-84 | LLM 幻觉/重放非本 AgentType 工具名会被实际执行（边效应仅审批挂起）；目前仅靠 LLM 遵从 tool specs 缓解 | **HIGH**（工作流标注 security-authorization） | **是** | 否（已知 TD-QA-P11-001 / R4 H-9bis） | 需解冻 ToolExecutor 的独立授权任务 |

### 12.2 已清偿（保留记录）

| ID | 问题 | 清偿 Task | 日期 |
|---|---|---|---|
| TD-T6-001 | FindingService 未校验 configVersion | PF-003 | 2026-09-04 |
| TD-P8-2-001 | advanceStepIfRunning 必须在 ToolExecutor 之前 | P8-2 修复 | 2026-08-31 |
| TD-QA-PROP-001 | QA 提案影响分析 | 仅分析，不修复 | 2026-09-01 |

---

## 13. Architecture Risks

| ID | 风险 | 位置 | 影响 | 优先级 | 阻塞? |
|---|---|---|---|---|---|
| R-01 | AgentLoop.observe() 零历史回放（FB-02 佐证 javadoc 不符） | AgentLoop.java:224-230 | 真实 LLM 多轮编排闭环不成立 | **HIGH** | 是（Phase 3+） |
| R-02 | Wall-clock deadline 进程内（FB-04 确认） | QaOrchestratorService.deadlines | 重启丢失，QA 子 Run 可无限执行 | MEDIUM | 否 |
| R-03 | dominantBuildShare 恒 null（QA-01 确认为死代码） | QaReleaseGateService.java:150,160 | 0.75 阈值永不触发 FAIL | MEDIUM | 否（Gate 其他分支仍有效） |
| R-04 | INCONCLUSIVE 不在 STATUSES 常量域（QA-04） | QaReleaseGateService:249 + QaReleaseGate.STATUSES | 域不闭合 | LOW | 否 |
| R-05 | 无 @Transactional | 多服务 | 孤儿行风险 | MEDIUM | 否 |
| R-06 | ToolExecutor 无白名单 dispatch 校验 | ToolExecutor.java | 信任 AgentLoop 过滤 | LOW | 否 |
| R-07 | RUN_TIMEOUT 事件缺口（FB-01） | AgentLoop.java:184 + AgentEventType | 启用时间预算时事件丢失 | MEDIUM | 否（当前不可达） |
| R-08 | 冻结文件硬编码约束（FB-03） | AgentLoop.java:52 + RunStateMachine | 未来非 QA timeout 需冻结修改 | MEDIUM | 否 |
| R-09 | Wall-clock deadline 零测试覆盖（QA-02） | QaOrchestratorServiceTest | 治理行为（拒绝/冻结/阻断）无集成验证 | MEDIUM | 否 |
| R-10 | 顺序编排仅 prompt 约定（QA-05） | QaOrchestratorService.java:149-179 | 并发子 Run 可得但 Gate 仍正确 | LOW | 否 |
| R-11 | ToolExecutor dispatch 无白名单校验（TD-02 / TD-R7-018 / R4 H-9bis） | ToolExecutor.java:58-84 | LLM 幻觉或重放非本 AgentType 工具名会被实际执行；现仅靠 LLM 遵从 tool specs 缓解 | **HIGH**（工作流标注 security-authorization） | 否（冻结 + 已知 HOLD） |

---

## 14. Candidate Next Phases

### Phase 1: 文档清理与基础设施（建议立即执行）

| 属性 | 值 |
|---|---|
| Phase ID | R7-P1 |
| 目标 | 修复所有文档漂移、技术债状态矛盾、JDK 21 独立安装 |
| Why Now | 阻塞后续所有阶段的 CI/构建可复现性；文档矛盾误导贡献者 |
| Dependencies | 无 |
| Risks | 低——仅文档 + 环境配置 |
| 冻结边界 | 否 |
| Schema | 否 |
| ErrorCode | 否 |
| 新 AgentType/Tool/Event | 否 |
| 建议立即执行 | **是** |

### Phase 2: AgentLoop 历史回放（核心架构修复）

| 属性 | 值 |
|---|---|
| Phase ID | R7-P2 |
| 目标 | 实现 observe() 从 DB 回放历史 steps/tool_calls，支持真实 LLM 多轮编排 |
| Why Now | QA_LEAD 编排在真实 LLM 场景下闭环不成立（R-01），阻塞 Phase 3+ |
| Dependencies | 无（可在冻结文件上修改，需人工授权） |
| Risks | 中——涉及冻结 AgentLoop 修改，需严格回归 |
| 冻结边界 | **是**（AgentLoop.java 修改，需人工授权解冻） |
| Schema | 否 |
| ErrorCode | 否 |
| 新 AgentType/Tool/Event | 否 |
| 建议立即执行 | 否——需人工裁决是否解冻 AgentLoop |

### Phase 3: QA Dashboard 前端（用户体验）

| 属性 | 值 |
|---|---|
| Phase ID | R7-P3 |
| 目标 | 前端接入 /api/qa/gates 和 /api/qa/orchestrations 端点，提供 Release Gate 状态可视化 |
| Why Now | QA Phase 2 后端已交付，但无前端消费者（api.ts 未映射 gates/orchestrations） |
| Dependencies | 无——后端端点已存在 |
| Risks | 低——仅前端消费既有 API |
| 冻结边界 | 否 |
| Schema | 否 |
| ErrorCode | 否 |
| 新 AgentType/Tool/Event | 否 |
| 建议立即执行 | 否——需人工裁决优先级 |

### Phase 4: Project Isolation（多项目支持）

| 属性 | 值 |
|---|---|
| Phase ID | R7-P4 |
| 目标 | 全局 project_id migration，支持多项目隔离 |
| Why Now | 当前单项目设计，未来扩展需此基础 |
| Dependencies | 无——但影响面广（16 表均需加 project_id） |
| Risks | 高——全局 schema migration，影响所有 Repository/Service |
| 冻结边界 | 否（但涉及 schema.sql 修改） |
| Schema | **是**（16 表加 project_id 列） |
| ErrorCode | 否 |
| 新 AgentType/Tool/Event | 否 |
| 建议立即执行 | 否——需独立需求评估 |

### Phase 5: 持久化 Wall-Clock Deadline（可靠性）

| 属性 | 值 |
|---|---|
| Phase ID | R7-P5 |
| 目标 | 将 QA 编排 deadline 从进程内 ConcurrentHashMap 迁移到 DB 持久化 |
| Why Now | 进程重启丢失 deadline（R-02），影响生产可靠性 |
| Dependencies | 无——但需新增 deadline 字段到 qa_role_assignment 或新建表 |
| Risks | 中——涉及 schema 变更 + QaOrchestratorService 重构 |
| 冻结边界 | 否 |
| Schema | 是（新增字段或表） |
| ErrorCode | 否 |
| 新 AgentType/Tool/Event | 否 |
| 建议立即执行 | 否——需人工裁决优先级 |

---

## 15. Recommended Sequencing

> ⚠️ **R8-A6 措辞勘误（2026-09-17）**：原图以串行箭头把 Phase 2 → Phase 3/5 画成关键路径，暗示 P3/P5 依赖 P2 完成。实际 P2 / P3 / P5 为**三条相互独立的轨道**：P3（QA Dashboard 前端）依赖的 QA Phase 2 后端 API 已在树，可独立于 P2 立即进行；P5（持久化 Deadline）为独立可靠性项，与 P2/P3 均无代码依赖；P4 独立且可延迟。本修正仅澄清依赖措辞，不改动候选 Phase 内容，不自动授权任何 Phase。

```
Phase 1（文档清理 + JDK 21）— 无依赖，可立即执行
  │
  ├── Phase 2（AgentLoop 历史回放）— 独立轨；需人工裁决解冻 AgentLoop（H-1）
  │
  ├── Phase 3（QA Dashboard 前端）— 独立轨；QA Phase 2 后端 API 已存在，可立即执行
  │
  ├── Phase 5（持久化 Deadline）— 独立轨；可靠性项，可独立排期
  │
  └── Phase 4（Project Isolation）— 独立项，可延迟
```

**依赖关系**：Phase 1 与 Phase 2/3/4/5 无依赖，可立即执行。**Phase 2/3/5 互为独立轨道，互不阻塞**；Phase 3 因 QA Phase 2 后端 API 已在树而可独立于 Phase 2 进行。Phase 2 需人工裁决是否解冻 AgentLoop——这是整个 roadmap 的**唯一**关键决策点（不影响 Phase 3/5 排期）。

---

## 16. Human Decisions Required

### H-1: AgentLoop 历史回放解冻

- **问题**：AgentLoop.java 是冻结文件，修改需人工授权。是否允许解冻以实现 observe() 历史回放？
- **影响**：阻塞 Phase 2 本身（核心架构修复）。**不影响 Phase 3/5**——二者为独立轨道，P3 的 QA Phase 2 后端 API 已在树，可独立进行（R8-A6 勘误修正原"阻塞后续 Phase 3+"的依赖误述）
- **选项**：(A) 解冻 AgentLoop 实现历史回放 (B) 保持冻结，QA 仅限 Fake 剧本场景 (C) 新建 QaAgentLoop 子类绕过冻结

### H-2: 文档清理优先级

- **问题**：TD-R7-001/R7-002/R7-003/R7-004 + TS-01/TS-02/TS-03 共 7 项文档漂移，是否立即修复？
- **建议**：是——低风险高价值，可立即执行

### H-3: QA Dashboard 前端优先级

- **问题**：QA Phase 2 后端已交付但无前端消费者，是否优先开发 QA Dashboard？
- **选项**：(A) 立即开发 (B) 延迟到 Phase 2 历史回放后 (C) 暂不开发

### H-4: Project Isolation 立项

- **问题**：是否将 project_id migration 作为独立立项评估？
- **建议**：延迟——当前单项目设计不阻塞任何功能

### H-5: dominantBuildShare 接线

- **问题**：Gate 的 0.75 阈值因 dominantBuildShare 恒 null 而短路，是否接线？
- **选项**：(A) 接线 EvaluationService (B) 保持当前行为 (C) 暂不处理

### H-6: INCONCLUSIVE 域闭合

- **问题**：INCONCLUSIVE 写入 gate.status 但不在 STATUSES 常量域，是否补入？
- **建议**：是——低风险，域完整性

### H-7: Research TK（信任知识库）

- **问题**：R0-R2 已完成但 TK 仍 DEFERRED，是否启动？
- **选项**：(A) 启动 TK (B) 保持 DEFERRED (C) 取消 TK

### H-8: 持久化 Deadline 优先级

- **问题**：Wall-clock deadline 进程内重启丢失，是否优先迁移到 DB？
- **建议**：与 Phase 3 并行评估

---

## Final Status

**R7 ARCHITECTURE & ROADMAP AUDIT — COMPLETE / WAITING FOR HUMAN DECISION**

> 下一阶段不得自动进入；等待 Human Decision 裁决。审计报告：`docs/reports/R7_POST_QA_PHASE2_ARCHITECTURE_ROADMAP_AUDIT.md`。
