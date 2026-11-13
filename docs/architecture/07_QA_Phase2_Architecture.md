# QA Phase 2 Architecture

> 2026-11-12 范围与状态说明：这是早期 R4 候选设计，含已过期的“无编排/无回放”基线，保留为历史。当前 QA 入口见 [模块设计](../projects/04_AI_Game_Agent_Team/README.md)，当前运行契约见 [Runtime](../platform/AGENT_RUNTIME_RECOVERY.md)，实际状态见 [task-state](../state/task-state.md)。

> **状态：DRAFT — WAITING FOR HUMAN ARCHITECTURE ACCEPTANCE**
> **权威基线：** `docs/QA_ARCHITECTURE_PROPOSAL.md`(DRAFT) → `docs/QA_ARCHITECTURE_AUDIT_REPORT.md`(CONDITIONALLY_PASS) → `docs/QA_ARCHITECTURE_DECISION_REVIEW.md`(A1–A10 APPROVE) → `docs/QA_ARCHITECTURE_FREEZE.md`(authoritative) → 本文档(consolidated, R4)。
> **性质：** 本文档仅为 **ARCHITECTURE DESIGN**。任何实现均需先获得 Human Architecture Acceptance + Schema Authorization + 各项实现准入条件（见 §24）。本文件不含可执行的 DDL / 迁移 / 代码。
> **日期：** 2026-09-15 ｜ **R4 基线 commit：** `57433a0`（backend 551/0/0，frontend vitest 53/0/0）

---

## 1. Executive Summary

QA Phase 2 的目标是把当前"单 QA Agent 仅能上报 Finding"（QA P1）扩展为 **Multi-Agent QA 团队（QA_LEAD 编排 + 角色子 Agent 执行）**，并落地 **Release Gate（发布门禁）**，让 QA 从"问题发现器"升级为"发布决策输入"。

本次 Architecture Discovery（对真实代码 + 既有架构冻结链路的事实核对）确认：

1. **不存在 SubRun**：`TaskController.java:39-41` 明文规定"不引入 parent_run_id/child_run_id/workflow/DAG 等编排结构"，run 表无 parent 列（schema.sql:18-34），14 张表均无父链语义。**SubRun 在本架构中为 proposed（新增）概念，落地依赖 §17 两张新建表。**
2. **QA P1 是单 Agent 最小闭环**：`AgentType.QA` + `qa_p001` prompt + 1 个共享工具 `finding_report`；`/api/qa/*` 仅一个端点 `GET /api/qa/findings`；无编排、无 Gate、无角色端点（`QaController.java:23-24` 明确记录 Phase 2 不在 R3 范围）。
3. **运行时扩展点已就位且冻结边界清晰**：新 AgentType / 新工具只需改 `DefaultAgentTypeResolver`（NOT frozen）+ 新增 `GameTool` Bean（Spring 自动发现，`ToolRegistry` 无需改），**不需要修改任何冻结文件**（AgentLoop / RunStateMachine / BudgetGuard / LoopDetector / ToolExecutor / ToolResult / ErrorCodes 均保持 byte-identical）。
4. **编排无处可复用于超时与并行**：`AgentLoop.timeoutMs=-1`（时间预算禁用）、单 DB 连接（Hikari pool=1）、LoopDetector WINDOW=3 → 编排必须是 **顺序 + 编排层 deadline**，不得依赖 AgentLoop 层超时；poll 工具必须携带递增 poll_seq 防误判死循环。

**推荐架构：Option A — Service-layer orchestration（服务层确定性编排）**，其核心是不可冻结文件的零修改扩展：

- 新 **`QaOrchestratorService`**（编排层，非冻结文件的新 Spring Bean）：负责 QA 任务生命周期（subtask 创建 / subrun 启动 / 状态轮询 / Finding 聚合 / Gate 计算 / 预算与超时执行）。
- 新 **4 个 AgentType**（`QA_LEAD` / `QA_TESTER` / `QA_ANALYST` / `QA_RESEARCHER`）— 只改 `AgentType.java` 枚举 + `DefaultAgentTypeResolver`。
- 新 **4 个 QA_LEAD-only 编排工具**（`qa_create_subtask` / `qa_start_subrun` / `qa_check_subrun_status` / `qa_collect_findings`），QA_LEAD LLM 通过它们表达编排意图，确定性执行落在 Orchestrator。
- 新 **8 张 Prompt**（qa_lead + 3 player personas + combat + numerical + exploit_hunter + ux_reviewer；版本化 inactive 种子，沿用 designer_p002 先例）。
- 新 **2 张表** `qa_role_assignment`（6 态）+ `qa_release_gate`（冻结字段集 + config index）— **Schema authorization  required before implementation（ZERO DDL in this doc）**。
- **Error Contract 不变**（6 码）；**Frozen Boundary 变更 = 0 项**。
- **Finding 共享域复用**（Golden Rule："一等待捕获，回归对比路径不变"）：不作平行 QA-Finding，Gate 输入 = 既有 finding 表统计。

**Implementation admission conditions 未满足前，QA Phase 2 IMPLEMENTATION — NOT AUTHORIZED。**

---

## 2. Current System Constraints

以下均为 Architecture Discovery 对真实代码逐行核实的事实（R4 基线 `57433a0`）：

### 2.1 Runtime（冻结文件，byte-identical 要求）
| 组件 | 事实 |
|---|---|
| `AgentLoop.java` | 无状态 @Component；`run(runId)` 同步 while(true) 处理单个 Run 直到终止/挂起；`timeoutMs=-1`（时间预算禁用）；`cancelChecker` 单槽 LongPredicate，仅 `RunControlService.cancel()` 注入 |
| `RunStateMachine.java` | 纯静态校验；7 状态；PENDING→RUNNING→{WAITING_APPROVAL,COMPLETED,FAILED,CANCELLED,TIMEOUT}；WAITING_APPROVAL→{RUNNING,CANCELLED}；终态不可流出；same-state 拒绝(40901) |
| `BudgetGuard.java` | 读 Run 记录三围：step（currentStep≥maxSteps→COMPLETED）、token（tokensUsed≥tokenBudget→COMPLETED）、time（timeoutMs>0 才生效，当前 -1 禁用） |
| `LoopDetector.java` | WINDOW=3：同一 run 内连续 3 次 (tool_name, arguments_json) 完全相同 → AgentLoop 强制 COMPLETED("检测到无效循环") |
| `ToolExecutor.java` | 无状态：INSERT tool_call STARTED → registry null check → JSON Schema 校验 → dispatch → 审批挂起/完成；**不校验白名单**（白名单仅是 AgentLoop.selectToolSpecs 对 LLM 的可见性过滤，非执行授权） |
| `ToolResult.java` | 冻结 record(status, payloadJson, summaryText)；success()="COMPLETED", failure()="FAILED" |
| `ErrorCodes.java` | 6 码：40001 / 40401 / 40901 / 42901 / 50201 / 50000 |

### 2.2 Agent 身份（NOT frozen，可扩展）
- `AgentType` 枚举 4 值：DESIGNER / TESTER / RESEARCH / QA（`AgentType.java:16-21`，无字段无方法）。
- 每类型行为全部沉淀在 `DefaultAgentTypeResolver`（按枚举键）：`resolve()`（constraints_json.agent_type 显式优先，否则有 ACTIVE TestPlan→TESTER、无→DESIGNER；QA 永不回退命中）、`systemPrompt()`、`toolWhitelist()`。
- 工具白名单（`DefaultAgentTypeResolver.java:64-95,166-173`）：DESIGNER=10、TESTER=8、RESEARCH=6、**QA=1（仅 finding_report）**。
- `PromptService`：`getByVersion(string)` 字符串等值过滤；`qa_p001` 与 `designer_p002` 均为 **inactive 种子**；`designer_p002` 提供了"版本化 inactive Prompt 规范、运行期不采用"的既有先例。

### 2.3 数据模型（schema.sql，14 张表，冻结）
- **task**：无 agent_type 列、无 parent 列；agent_type / researchSourceTaskId 存在于 `constraints_json`。
- **run**：7 态状态字符串；无 parent_run_id、无 project_id、无 config_version（config 在 test_plan/finding）；task_id 非 UNIQUE → **一 task 多 run 合法**；无 run 数上限；`started_at` 在 PENDING→RUNNING 时 COALESCE 回填。
- **step / tool_call**：每个 run 独立 seq 空间；tool_call 含 approval_status；tool_call 无 token 列。
- **finding**：共享域单表；`source` 三值（INVARIANT/LLM/MANUAL）；status 四态 OPEN→{CONFIRMED,DISMISSED}→{FIXED,DISMISSED}（终态不可变，非法迁移 40901）。
- **agent_event**：`run_id`/`task_id` 两列均为 TEXT（兼容外键缺失）；26 类事件值域在 `AgentEventType`。

### 2.4 编排现状（关键约束）
- 无 SubRun / parent_run_id / workflow / DAG（`TaskController.java:39-41` 明文禁止）。
- 跨 Agent 先例只有 **Task 级 `researchSourceTaskId`**（DESIGNER-only，指向 RESEARCH task，存 constraints_json，运行时由 AbstractDesignerResearchTool 经 run→task→constraintsJson 读取）。
- Run 全部在 daemon 无界缓存线程池 `agent-loop-worker` 执行（`Executors.newCachedThreadPool`）；无 @Async / @Scheduled。
- 取消是协作式（cancelFlags + CANCELLED 落库，AgentLoop 每轮检查）。
- `RunRecoveryService`（ApplicationRunner）启动时自动续跑所有 RUNNING 的 run → 编排必须容忍子 run 崩溃重启后独立恢复。
- QaController 仅 1 端点；`QaP1_1Test` 精确守卫 QA 白名单 = `{"finding_report"}`（改白名单必触发测试红 → 需同步改测试 + ADR 授权）。

---

## 3. Goals

1. **Multi-Agent QA 编排**：QA_LEAD 调度一组角色子 Agent（Player×3 / Combat / Numerical / Exploit / UX），产出一份完整 QA 结论而非单人发现。
2. **确定性排序、预算与超时**：顺序编排（非并行），编排层 deadline（wallClockBudget，默认 300s）+ 每子 run step/token 预算；拒绝无限递归、无界扇出、预算绕过、事件风暴。
3. **Release Gate 作为一等实体**：确定性子服务器阈值 + LLM 辅助 lead_decision，二者一致性判定，人工可 override；**子 Agent 超时绝不上传为 PASS**。
4. **零冻结破坏**：不改 AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult/ErrorCodes；AgentType 扩展仅落在未冻结文件。
5. **Error Contract 稳定**：继续 6 码体系，不新增。
6. **Finding 共享域复用**：QA 多 Agent 的产出继续写 finding 单表，Gate 读同一张表统计；回归对比路径不变。
7. **可观测**：每个子 run 独立 step/tool_call 全链路；QA_LEAD 决策通过 existing 事件全量可追溯；MVP 不新增 SSE 事件类型（26==26）。

---

## 4. Non-Goals

- **不实现** Phase 2 runtime（QA_LEAD / SubRun / Release Gate / Multi-Agent）— 本文档只做设计。
- **不执行任何 schema 迁移 / 建表**（ZERO DDL；§17 仅 Proposed Schema）。
- **不新增 ErrorCode**（ErrorCodes.java 保持 6 码）。
- **不修改任何冻结文件**（§八清单）— 若设计需要，仅标注 "FROZEN CONTRACT CHANGE REQUIRED" 且不可实现。
- **无并行编排执行**：不引入 join/notify/barrier，不做跨 run 并行（单 DB 连接、LoopDetector 干扰、无界线程池事实约束）。
- **无 QA Dashboard / 新前端页面**（MVP，与 freeze §16 一致）。
- **无 QA-S5 之前的 SSE 事件扩展**（26→29 仅在 QA-S5 落地）。
- **无 Researcher 网络工具**（web_search/fetch_page 仅作为 QA_RESearch 子 Agent 白名单成员，不新造 Research 能力）。
- **无 designer_p002 启用**、无新 Designer/Tester/Research 功能微调。
- **无 project_id 隔离改造**（§16 论证 — 当前系统全表无 project_id，不属于本阶段授权范围）。

---

## 5. Existing QA P1 Architecture

（全部为 P1-1..P1-6 HUMAN ACCEPTED 后的现状，R4 基线核实）

| 维度 | P1 现状 |
|---|---|
| Agent 类型 | 新增 `AgentType.QA`；`qa_p001` prompt（12 行：ANALYZE_TASK→ASSESS_EVIDENCE→IDENTIFY_GAP→REPORT + 6 铁律） |
| 工具 | QA 白名单 = `{finding_report}`（与 TESTER 共享，最小闭环，不建 QA-only 工具） |
| Finding 路径 | `FindingReportTool`：source 硬编码 "LLM"；testPlanId/taskId 在工具层为 null（Service/API 层 T4-2 回填）；Evidence 以 sessionId 为主锚点；`FINDING_CREATED` 事件仅 runId 非 null 时发布 |
| FindingService | `report()` = validate-then-insert（**无去重**，指纹服务仅在回归比对用）；`updateStatus()` 单一封闭路径 + 4 态迁移矩阵 |
| REST | `GET /api/qa/findings`（8 参：configVersion/severity/category/source/status/runIds/testPlanId/taskId）；信封 `ApiResp{code,message,data}` |
| 前端 | FindingsPage 8 维过滤 + 状态抽屉；useGlobalSse 固定 26 事件名 + REPLAY_TRUNCATED |
| 文档 | P1-1..P1-6 全部 "COMPLETE — WAITING FOR HUMAN ACCEPTANCE"（QA_P1_ACCEPTANCE.md:25-30）；Phase 2 明确记为 "未来：QA Team 多 Agent 编排 / Release Gate / 子任务系统"（QA_P1_1_IMPLEMENTATION_REPORT.md:154） |

**关键判读**：P1 把 QA 做成一个"有凭证的报告者"，没有脚手架可复用 — subrun/subtask/gate 表与服务全部要新建（freeze §14 确指两张新表，DDL 延后到 QA-S1-2 人工批准）。

---

## 6. Proposed QA Phase 2 Architecture

### 6.1 总体形态（Sequential Orchestration MVP）

```
Human / Frontend (R3-B2 QA 入口，复用 POST /api/tasks)
        │  agent_type=QA_LEAD
        ▼
  Task(Lead) ──new──► QaOrchestratorService.startQA(leadTaskId, gateTarget)
        │  (NEW service, NOT frozen)
        ▼
  Run(Lead)  [AgentLoop 原样执行，白名单=qa_lead tools]
        │  qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings
        ▼
  qa_role_assignment (6 态) ── 顺序驱动 8 个子 Task/子 Run
        │   Lead→Player×3→Combat→Numerical→Exploit→UX
        ▼
  SubRun_i (task 复用 run 表，run.task_id=subTaskId)
        │  role prompt + role whitelist（默认含 finding_report）
        ▼
  finding 表（共享域）◄── 各子 Agent 上报
        ▼
  QaOrchestratorService.aggregate() → qa_release_gate (PENDING→PASS/CONDITIONAL_PASS/FAIL)
        │  子超时绝不 auto-PASS
        ▼
  Human 在 UI/端点查看 Gate；人工可 manual_override
```

### 6.2 核心设计杠杆
- **确定性执行在服务层，意图表达在 LLM 层**：QA_LEAD 通过编排工具表达"我要创建 sub task / 启动 sub run / 查状态 / 收 Finding"，工具内部将确定性动作（建 Task/建 Run/更新 assignment/聚合统计）委托给 `QaOrchestratorService`。LLM 不直接写 DB。
- **父子关联在 `qa_role_assignment`，不在 run 表**（freeze §8）：run 表零改动。
- **角色 = AgentType + 独立白名单 + 独立 Prompt**（freeze §4）：不共用 `AgentType.QA`，避免 P1 QA 语义被 Phase 2 污染。
- **Gate 正交于 Run 状态**（freeze §10）：Gate 是配置版本的评价结论，不属于任何单个 run 的状态字段。

---

## 7. QA_LEAD

### 7.1 身份
- 新增 **4 个 AgentType**：`QA_LEAD`、`QA_TESTER`、`QA_ANALYST`、`QA_RESEARCHER`（+ 既有 4 值 = 8 值枚举）。改动面：`AgentType.java` + `DefaultAgentTypeResolver`（systemPrompt/toolWhitelist 穷举 switch，均 NOT frozen）+ 前端 `AGENT_TYPES` 镜像（WorkflowPage）+ `allowedAgentTypes()` 自动扩展。
- **QA_LEAD 的 Prompt**：`qa_lead_p001`（新增，inactive 种子，遵循 designer_p002 先例；运行期经由 resolver case 命中）— 8 个 Prompt 角色映射见 §8.2。

### 7.2 QA_LEAD 白名单（6 工具）
| 工具 | 用途 |
|---|---|
| `qa_create_subtask` | 声明要创建的角色子任务（参数：role、goal、配置摘要）→ Orchestrator 建 Task + qa_role_assignment(PENDING) |
| `qa_start_subrun` | 声明启动某 assignment 的 subrun → Orchestrator 建 Run(PENDING) + 提交 agentLoop.run |
| `qa_check_subrun_status` | 轮询某 assignment / subrun 状态 → 返回 6 态 + 简要统计；**必须携带递增 poll_seq** 防 LoopDetector 误判 |
| `qa_collect_findings` | 按 configVersion/severity/RoleTask 拉取 finding 聚合 → 供 Lead 汇总 |
| `finding_report` | 既有共享工具，Lead 本人也可上报（Optional 保留） |
| `qa_complete_orchestration` | Lead 确认编排完成、请求生成 Gate 决策输入（aggregate 触发） |

> 注：freeze §6 原始划定 4 个编排工具；此处为满足"Lead 结束编排"与"Lead 可直接上报"两个闭环延伸至 6 个，**属对冻结基线的修订建议，需人工确认**（见 §25.H6）。

### 7.3 QA_LEAD 职责边界
- **只拍板，不脏手**：Lead 决定"测什么角色、按什么顺序、收集什么口径、形成什么 Gate 建议"；子 Agent 决定"具体怎么查"。Lead 白名单**不含** game_* / test_plan_* / web_* 执行工具（避免 Lead 抢子 Agent 活计并放大预算）。

---

## 8. Multi-Agent Orchestration

### 8.1 编排模型（Sequential / Non-Parallel，设计理由）
| 项目 | 决策 | 依据（real code） |
|---|---|---|
| 并行度 | **顺序执行**，一次一个子 assignment | Hikari pool=1 单连接；无 join/notify；LoopDetector 在跨 run 顺序下无并发干扰（Lead 轮询单独跑） |
| 执行线程 | 子 run 复用 `agent-loop-worker` cached pool（每 run 一个 AgentLoop 调用） | RunControlService 既有 executor；QaOrchestratorService 通过 `runRepo.insert` + `executor.submit(agentLoop.run)` 自建（绕过 start() 以自定义 maxSteps/tokenBudget） |
| 进度追踪 | `qa_role_assignment` 六态 + 顺序索引 | 新建表（§17） |
| 超时 | 编排层 deadline（wallClockBudget 默认 300s）+ 每子 run 步/额预算 | `AgentLoop.timeoutMs=-1` → 时间预算必须在编排层 |
| 停止条件 | 8 角色全终态 或 wallClockBudget 耗尽 或 Lead 主动停止 | 确定性 |
| 重启容忍 | 子 run RUNNING 被 RunRecoveryService 重启时可独立完成；Orchestrator 以 assignment 状态为准做幂等推进 | RunRecoveryService 既有行为 |

### 8.2 角色矩阵（8 Role / 8 Prompt，freeze §3）
| # | Role | AgentType | Prompt | 白名单要点 |
|---|---|---|---|---|
| 1 | Lead | QA_LEAD | qa_lead_p001 | 6 编排工具（§7.2） |
| 2-4 | Player(3 personas: 休闲/核心/付费) | QA_TESTER | three persona prompts | 测试类 + finding_report（≈Tester 8 + 复用） |
| 5 | Combat | QA_TESTER | combat prompt | 测试类 + finding_report |
| 6 | Numerical | QA_ANALYST | numerical prompt | 分析类 + finding_report |
| 7 | Exploit Hunter | QA_TESTER | exploit_hunter prompt | Tester 8 + web_search/fetch_page → **10**（freeze A8） |
| 8 | UX Reviewer | QA_RESEARCHER | ux_reviewer prompt | Research 6 + fetch_page 等 → **10**（freeze A8） |

> freez 决定的精确白名单数字（QA_LEAD 6 / QA_TESTER 10 / QA_ANALYST 7 / QA_RESEARCHER 10）见 `QA_ARCHITECTURE_FREEZE.md` §3。本文遵循。

### 8.3 编排时序（MVP 顺序）
1. Human 创建 QA_LEAD Task（POST /api/tasks, agent_type=QA_LEAD）。
2. Human/系统触发 Lead run（POST /api/tasks/{id}/runs）或已有端点。
3. Lead 依 prompt 依次 `qa_create_subtask`（8 个角色 assignment 全部 PENDING）→ Orchestrator 建 8 个 subTask。
4. Lead `qa_start_subrun`（依次启动）；Orchestrator 建 Run(PENDING) 并 submit，assignment→RUNNING。
5. Lead `qa_check_subrun_status`（poll_seq 递增）；子 run 终态时 assignment→完成态。
6. 子 Agent via `finding_report` 写 shared finding 表。
7. 全部完成后 Lead `qa_complete_orchestration` → Orchestrator `aggregateAndCreateGate`。
8. Gate 落 `qa_release_gate`，状态 PENDING → 确定性计算 automatic_result → LLM 辅助 lead_decision → is_consistent → PASS/CONDITIONAL_PASS/FAIL。

---

## 9. SubRun Model

### 9.1 现状判读（Discovery 结论）
- **SubRun 在当前代码库中不存在**（proposed 概念）。
- run 表无 parent 列（schema.sql:18-34）；TaskController.java:39-41 明文不引入 parent_run_id/child_run_id/workflow/DAG；无任意 run 关联子 run 的机制。
- 跨 Agent 链路先例仅为 Task 级 `researchSourceTaskId`（constraints_json 字段）。

### 9.2 Proposed 模型（不落 run 表新列）
- **SubTask** = 普通 task 行（agent_type=QA_TESTER/QA_ANALYST/QA_RESEARCHER），其 constraints_json 记录 `parentTaskId`（QA_LEAD task id）+ `qaRole`（Exploit/UX/...）— 复用 `researchSourceTaskId` 的 JSON-key 先例，**不新增 task 列**。
- **SubRun** = 普通 run 行（run.task_id = SubTask.id），由 Orchestrator 经 `runRepo.insert` 创建，携带 per-role `maxSteps`/`tokenBudget`。
- **父子关联权威 = `qa_role_assignment`**（assignment.lead_task_id / assignment.sub_run_id / assignment.role / status / order_no）。run 表保持零改动。

### 9.3 为什么不用 run 表 parent_run_id
| 方案 | 结论 |
|---|---|
| run.parent_run_id 新列 | 改 schema（冻结 14 表）+ 违反 TaskController 禁令 → 需 FROZEN CONTRACT CHANGE，非必要 |
| qa_role_assignment 承载父子 | **采纳**：assignment 已含 role/status/order，一个实体同时回答"谁、什么角色、什么状态、什么顺序" |

---

## 10. Finding / Evidence Integration

### 10.1 共享域（Golden Rule 保持）
- 子 Agent 一律经 `finding_report` 写 **finding 单表**（与 P1/Tester 同一张表）。不建平行 QA-Finding 表/域。
- 回归对比路径不变："一等待捕获，回归对比路径不变"。

### 10.2 归因
- `FindingReportTool` 硬编码 `source="LLM"`、testPlanId/taskId 工具层 null。Phase 2 归因采用 **`finding.run_id → run.task_id → task.constraints_json.qaRole`** 解析（freeze §11），不改 tool、不改 Finding.source 枚举。
- `testPlanId/taskId` 回填仍走 Service/API 层 T4-2 既有接线（P1 未改）。

### 10.3 去重层（design note）
- `FindingService.report()` 无去重；多子 Agent 可能对同一缺陷重复上报。禁用 fingerprint（专用回归比对）**不接入 report 路径**（freeze 建议 `FindingRegressionService` 复用），Phase 2 在聚合阶段以 Evidence.sessionId+scenarioId+title 相似度做提示/标记，不自动合并、不删改共享数据（record-and-surface 原则）。

### 10.4 聚合查询
- `qa_collect_findings` 读 `findByQaFilter`（8 维，含 runIds IN / configVersion）既有实现；Gate 统计（critical_bugs / high_bugs / soft_lock_count / crash_rate / dominant_build_share）由 Orchestrator 在共享表上 `GROUP BY config_version, severity, category` 计算。

---

## 11. Release Gate

### 11.1 实体（qa_release_gate，Proposed，§17）
- 生命周期：**PENDING → {PASS / CONDITIONAL_PASS / FAIL}** + derived `final_result`；`manual_override()` 人工覆盖；`lead_decision` LLM 辅助建议；`is_consistent`（auto vs lead 一致性）；config_version 索引。
- **正交于 Run 状态**（freeze §10）：Gate 是配置版本级别的评价结论，不写进任何 run.status（Run 状态机冻结）。

### 11.2 确定性与 LLM 分离
| 层 | 职责 | 确定性 |
|---|---|---|
| `automatic_result` | 纯 server 端阈值公式（见 freeze §13.3） | ✅ 确定性、可测试、可审计 |
| `lead_decision` | LLM 依据聚合统计 + 子 Agent 摘要给出建议（PASS/CONDITIONAL_PASS/FAIL） | ❌ 非确定性，仅作输入 |
| `final_result` | auto 与 lead 的组合判定 + `is_consistent` 标记 | ✅ 确定性组合规则 |
| `manual_override()` | 人类覆盖 final_result | ✅ 人工 |

### 11.3 红线
- **子 run TIMEOUT 绝不自动上传为 PASS**：超时的 assignment 会计入 Gate 的 `inconclusive_count`；若关键角色缺失证据 → automatic_result 直接 FAIL 或 INCONCLUSIVE（视公式），绝不因超时就放行。
- Gate 创建前，Orchestrator 必须确认全部 8 assignment 处于终态（或 dead-letter 结局已记录），缺失产出在 gate 上显式列出。

---

## 12. Event / SSE Model

### 12.1 MVP：零新增事件（26==26）
- 命中既有 26 值域内的：`TASK_CREATED`（subTask 创建）、`RUN_STARTED`/`RUN_COMPLETED`/`RUN_FAILED`（subrun）、`FINDING_CREATED`（子 Agent 上报）。
- 前端沿用 `useGlobalSse` 固定 26 名列表；新编排视图（若未来加）用本地 `useQaEventSignal` 风格 hook 过滤既有 RUN_*/FINDING_CREATED 刷新，**不扩展前端订阅列表**。

### 12.2 QA-S5（未来 Sprint，非本阶段实现）
- 3 个新事件：`QA_SUBTASK_CREATED` / `QA_ROLE_COMPLETED` / `QA_RELEASE_GATE_CREATED`（26→29）。需要改 `AgentEventType`（**冻结文件 → FROZEN CONTRACT CHANGE REQUIRED**，前置人工授权），因此**推迟到 QA-S5** 而非 MVP。

---

## 13. Budget / Timeout / Retry

### 13.1 预算来源
- **每子 run** 走 run 表既有 `max_steps` / `token_budget`（Orchestrator 经 `runRepo.insert` 自定义；BudgetGuard 原样生效：step/token 耗尽→COMPLETED）。时间预算由 `AgentLoop.timeoutMs` 禁用 → **编排层负责 wall-clock**。
- **编排层 deadline**：wallClockBudget（默认 300s）。到期未完成的子 run：Orchestrator 调 `RunControlService.cancel()` 发协作式取消 → assignment→TIMEOUT；该角色产出按缺失处理（§11.3）。
- **父 route 预算**：整棵 QA 树（Lead + 8 角色）的累计 token 上限由 Orchestrator 持有；超限停止后续启动，已启动的按各自 run 预算自限。

### 13.2 防无限递归 / 防无界扇出（硬约束）
| 风险 | 设计护栏 |
|---|---|
| 无限递归 | Lead 工具 `qa_create_subtask` 只允许创建**一层**子任务（Orchestrator 校验 assignment.lead_task_id 的 goal 类型固定为 QA 角色集；子 Agent 无编排工具，无可递归入口） |
| 无界扇出 | 每 QA 树 **固定 8 角色**（枚举白名单），Orchestrator 拒绝超集；run 数受 assignment 约束 |
| 预算绕过 | 子 Agent 无 `qa_start_subrun` 等工具；预算写入由 Orchestrator 生成 run 行时固化 |
| 事件风暴 | MVP 零新事件；子 Agent 每步仍是一次 FINDING_CREATED（既有语义）；无数据风暴面新增 |

### 13.3 Retry
- 子 run FAILED：**不自动重试**（确定性优先）；assignment→FAILED，Orchestrator 继续后续角色（失败隔离），最终 Gate 计入该角色缺口；重试仅人工触发（复用 `POST /api/runs/{id}/retry` 既有端点，不改契约）。

---

## 14. Failure / Cancellation

| 场景 | 行为 |
|---|---|
| 子 run FAILED（工具 critical / RuntimeException） | assignment→FAILED；**不影响其他子 run**；Lead 在 `qa_check_subrun_status` 可见 FAILED，可决定补测或收口；Gate 记录 FAILED 角色 |
| 子 run TIMEOUT（编排层 deadline 取消） | assignment→TIMEOUT；Gate INCONCLUSIVE 处理，绝不上传 PASS |
| Lead run 失败 / 取消 | Orchestrator 收到 Lead run 终态（FAILED/CANCELLED）→ 后续未启动子 run 不启动；已运行子 run 保持自带完成（不级联强杀，避免跨 run 竞态）；Gate 若已建则状态 FAIL 或保持 PENDING 待人裁 |
| 进程重启 | RunRecoveryService 自动续跑 RUNNING 子 run；Orchestrator 以 assignment 状态幂等推进（不重复建 run）；WAITING_APPROVAL 子 run 静置等人工 resume（既有契约） |
| 部分成功 | Gate 输出包含成功角色清单 + 失败/TIMEOUT 缺口清单（audit 记录），人类裁决 |

> **Cancellation 唯一路径**：统一走 `RunControlService.cancel()`（cancelChecker 单槽，不能由 Orchestrator 直接装自己的 checker）。

---

## 15. Permission / Whitelist

- **Whitelist 语义 = LLM 可见性过滤**（ToolExecutor 不占执行授权）：改白名单只需新增 `GameTool` Bean（Spring 自动发现，`ToolRegistry` 零改）+ `DefaultAgentTypeResolver` 白名单 Set 扩容。
- 新 4 个 AgentType 的 whitelist 独立定义；子 Agent（QA_TESTER/QA_ANALYST/QA_RESEARCHER）**不含编排工具**（防递归/防越权）。
- QA_RESearch subset 复用 web_search/fetch_page（既有 Research 工具 Bean，无新工具实现）。
- `QaP1_1Test` 精确守卫 QA={finding_report} 将变红 → Phase 2 需同步更新该守卫测试并给出新 ADR 式授权记录（QaP1_1 决定"无 QA-only 工具"将因 Phase 2 编排工具而修订）。

---

## 16. Project Isolation

### 16.1 事实
- **全系统无 project_id**（Discovery Grep：backend/src/main/java 无任何 project/projectId 引用；schema.sql 14 表无 project 列）。
- 现有隔离边界 = **task 归属**：findings/run/step/tool_call/design_document/research_* 均以 task_id（或经 run→task 可达）隔离；04_Project_项目空间规范.md 作为文档存在，但无运行时强制。

### 16.2 设计立场
- **本阶段不引入 project_id**（属 Non-Goals §4）：新增编排实体（qa_role_assignment/qa_release_gate）以 `lead_task_id` 为根，继承现有 task 级隔离语义，不放大隔离半径。
- 任何未来 Project 级隔离改造应独立立项（对全 14+2 表加列属 FROZEN CONTRACT CHANGE，需单独 ADR + 迁移），不属于 QA Phase 2。若 Human 认为 Phase 2 必须同时落地 project 隔离，请在 §25 决策单中显式授权（将触发全表 schema 变更与独立迁移设计）。

---

## 17. Schema Proposal（ZERO DDL）

> **Schema authorization is required before implementation.**
> 本节仅提供 Proposed Schema（表名/用途/列/FK/索引/生命周期/迁移策略）。不产生任何可执行 DDL，审批通过前不得在任何环境建表。

### 17.1 表一：`qa_role_assignment`
| 项 | Proposed |
|---|---|
| 用途 | QA 树的角色分配与父子 run 关联权威；承载 6 态编排进度；顺序号支持确定性编排 |
| 列 | `id` PK；`lead_run_id` INTEGER FK→run(id)；`lead_task_id` INTEGER FK→task(id)；`role` TEXT（QA_LEAD/PLAYER_CASUAL/PLAYER_CORE/PLAYER_PAYING/COMBAT/NUMERICAL/EXPLOIT_HUNTER/UX_REVIEWER）；`sub_task_id` INTEGER FK→task(id) NULL；`sub_run_id` INTEGER FK→run(id) NULL；`status` TEXT（PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED）；`order_no` INTEGER；`sub_run_steps_used` INTEGER NULL；`sub_run_tokens_used` INTEGER NULL；`detail_json` TEXT DEFAULT '{}'；`created_at`/`updated_at` TEXT |
| FK | run.id / task.id 外键 |
| 索引 | `idx_qa_assign_lead(lead_run_id, order_no)`；`idx_qa_assign_subquest(sub_run_id)`；`UNIQUE(lead_task_id, role)`（一棵 QA 树一个角色只一次） |
| 生命周期 | 随 QA 树创建；保留（审计），不物理删除 |
| 迁移策略 | 新表，IF NOT EXISTS 语义；无数据迁移 |

### 17.2 表二：`qa_release_gate`
| 项 | Proposed |
|---|---|
| 用途 | 配置版本的一次发布门禁结论（1 棵树 / 1 target config 一条） |
| 列 | `id` PK；`lead_task_id` INTEGER FK→task(id)；`config_version` TEXT（冗余，指向 game_config_version 既有主键）；`status` TEXT（PENDING/PASS/CONDITIONAL_PASS/FAIL）；`automatic_result` TEXT（PASS/CONDITIONAL_PASS/FAIL/INCONCLUSIVE）；`rule_version` TEXT；`lead_decision` TEXT（建议；LLM 辅助）；`lead_decision_evidence_json` TEXT DEFAULT '{}'；`final_result` TEXT（组合结论）；`is_consistent` INTEGER；`inconclusive_count` INTEGER DEFAULT 0；`manual_override` INTEGER/TEXT（未覆盖/NULL）；`stats_json` TEXT DEFAULT '{}'（critical_bugs/high_bugs/soft_lock_count/crash_rate/dominant_build_share）；`created_at`/`decided_at` TEXT |
| FK | task.id / game_config_version.version（逻辑） |
| 索引 | `idx_qa_gate_cfg(config_version)`；`idx_qa_gate_status(status)` |
| 生命周期 | 创建即 PENDING；decided_at 落定后终态；保留审计 |
| 迁移策略 | 新表，IF NOT EXISTS；无数据迁移 |

### 17.3 迁移时序（未来实施，非本文件）
1. Human 批准 §17.1/§17.2 Schema authorization。
2. 在 QA-S1-2 作为独立 DDL Review 任务（参照 T-DB1_DDL_Review 先例）落地 `schema.sql`（破坏性变更零容忍：仅 ADD TABLE IF NOT EXISTS）。
3. 回归验证：既有 551 backend 测试全绿后才允许继续。

---

## 18. Error Contract Impact

**当前 6 码（40001/40401/40901/42901/50201/50000）不变，零新增。** 仅以下映射建议（属设计意图，非实现）：

| 场景 | 建议映射 | 说明 |
|---|---|---|
| 编排工具参数非法（未知 role / 非法 status 目标） | 40001 INVALID_ARGUMENT | 复用 GlobalExceptionHandler |
| 编排目标不存在（lead_task/assignment/sub_run 缺失） | 40401 NOT_FOUND | 复用 |
| 状态机违规（assignment 非法迁移，如 COMPLETED→RUNNING；Gate 决赛后再次 PASS） | 40901 CONFLICT | 复用 RunStateMachine 同款语义 |
| 编排预算/速率（墙钟超限、树已满 8 角色） | 42901 RATE_LIMITED | 复用"限流"语义作"产能上限" |
| 子 run 依赖上游（LLM 网关失败冒泡） | 50201 UPSTREAM_ERROR | 既有冒泡，不截断 |
| 兜底 | 50000 INTERNAL_ERROR | 既有 |

---

## 19. Frozen Boundary Impact

### 19.1 变更清单（0 项必需变更）
经 Discovery 逐一验证，本架构 **不需要修改任何冻结文件**。需要改动/新增的文件均在非冻结侧：

| 类别 | 文件 | 冻结？ |
|---|---|---|
| AgentType 枚举扩展 | `AgentType.java` | ❌ NOT frozen |
| Prompt/Tool 路由 | `DefaultAgentTypeResolver.java` | ❌ NOT frozen |
| 新工具 Bean | 新 `qa/*.java` GameTool | 新增 |
| 新编排服务 | `QaOrchestratorService` | 新增 |
| 新 Prompt 种子 | `PromptService.java` + resources/prompts/*.md | ❌ NOT frozen（FYI：PromptService 不在冻结清单） |
| Schema 新增表 | `schema.sql` 追加 2 张新表 | ⚠️ **schema.sql 在冻结集** → 需 Schema authorization（§17），且仅 ADD，不改既有 14 表 |
| 前端角色镜像 | WorkflowPage AGENT_TYPES / api.ts | 前端非冻结 |
| 后端 QA 端点 | `QaController` / 新 `QaOrchestrationController` | 新增/扩展（QaController 非冻结） |

### 19.2 需要 FROZEN CONTRACT CHANGE 的候选（当前架构已避开）
1. **`AgentEventType` 扩展 3 事件**（QA-S5 才做）：需人授权 + `AgentEventType.java` 修改 → **FROZEN CONTRACT CHANGE REQUIRED**。为什么：事件值域在冻结集。确切变更：追加 `QA_SUBTASK_CREATED/QA_ROLE_COMPLETED/QA_RELEASE_GATE_CREATED`（不改旧值）。替代：MVP 保持 26。风险：低（仅追加）。迁移：前端 useGlobalSse 名表随之 +1 vs +2（前端非冻结）。**必须人授权（§25.H3）**。
2. **任何 run/task/schema 现有表加列**（如将来 Project 隔离）：需独立 ADR + 迁移 → FROZEN CONTRACT CHANGE REQUIRED。
3. **RunStateMachine 扩态 / AgentLoop 改超时**：本架构明确不用（编排层 deadline 替代）→ 无需变更。

---

## 20. Alternatives Considered

对 **3 个真实候选** 按 9 个判据评估：

| 判据 | Option A: Service-layer orchestration（推荐） | Option B: Agent-driven QA_LEAD（纯 Agent 编排） | Option C: Dedicated SubRun persistence（run 表父链） |
|---|---|---|---|
| 架构契合度 | ★★★★ 确定性执行在服务层、意图在 LLM 层，与既有 agent-loop-worker + task 链语义最贴合 | ★★☆ 编排全权交给 LLM 循环内工具序列，缺确定性停/超时保证 | ★★☆ 违反 TaskController 显式禁令（parent_run_id/DAG） |
| 复杂度 | ★★★★ 新 1 Service + 6 工具 + 2 表，可控 | ★★★☆ 工具更少，但状态机/超时散落工具内，更难测 | ★★★ 表简单但全链路引入父链语义回写 |
| Schema 影响 | ★★★★ 仅 2 张新表（ADD-only） | ★★★★ 同左（表不变） | ★★☆ run 表加 parent_run_id → schema 冻结破坏 |
| 冻结边界影响 | ★★★★ 零冻结文件变更 | ★★★★ 零 | ★ low（breaker） |
| 可观测性 | ★★★★ assignment 表 + Gate 表全链路审计 | ★★★☆ 信息在 Lead run step 文本里，难结构化查询 | ★★★★ 父链在 run 表，但需额外 join assignment 才得角色语义 |
| 失败隔离 | ★★★★ 子 run 失败独立终态，Orchestrator 继续 | ★★★☆ Lead run 失败即全树中断 | ★★★☆ 同 A，但依赖 parent 完整性 |
| 预算控制 | ★★★★ run 行级预算 + 编排层 wall-clock 双保险 | ★★☆ 只有 run 行级，编排层墙钟缺失风险高 | ★★★☆ run 行级 + 父链可溯 |
| 可扩展性 | ★★★★ 新角色=新 whitelist+prompt，无需改编排器 | ★★★ 扩展靠 prompt 演进，工具面窄 | ★★☆ 扩展受 run 表结构约束 |
| 迁移风险 | ★★★★ 无数据迁移，纯增量 | ★★★★ 同左 | ★★☆ 存量 run 无 parent、索引重建 |

**决策：Option A。** 理由：唯一同时满足"零冻结修改 + 确定性 + 失败隔离 + 全审计 + 无迁移"的选项；Option B 的墙钟/停机责任缺失是 P0 风险；Option C 直接触碰 `TaskController.java:39-41` 的显式架构禁令与 run 表冻结。

---

## 21. Recommended Architecture

组合采纳（与 freeze A1–A10 一致，零冲突）：

1. **Service-layer orchestration**（Option A）：新 `QaOrchestratorService` 为确定性唯一编排者；`qa_role_assignment` 表为父子关联权威。
2. **4 个新 AgentType + 8 Prompt + 6 QA_LEAD 工具 + 角色白名单**（§7-§8）：全部落在非冻结文件；QA 树深度固定 1（Lead→角色）。
3. **顺序执行 + 编排层 deadline**（§13）：不依赖 AgentLoop 时间预算；LoopDetector 由 poll_seq 规避。
4. **SubRun = 普通 run 行 + assignment 关联**（§9）：run 表零改动。
5. **Gate = qa_release_gate 实体 + 确定性公式 + LLM 建议 + 人工 override**（§11）。
6. **MVP 零新事件**（26==26）；QA-S5 视授权再扩 3 事件（29）。
7. **2 张新表**：Schema authorization 先行（§17），纯 ADD，不改既有 14 表。
8. **Error 6 码不变**；映射建议仅设计意图（§18）。

---

## 22. Migration / Implementation Phases

> 以下阶段仅为**实现路线建议**。QA-S1..S5 的任何 Sprint 都需要 Human 逐项授权；本文档（架构接受）≠ 实现授权。

| Sprint | 内容（建议，源自 QA_ARCHITECTURE_PROPOSAL 5-Sprint 树） | 前置授权 |
|---|---|---|
| 预任务（可选） | C4 ExperimentService 静默回退修复；TD-T7-001/TD-T6-001 fixture 污染清理（freeze 列为独立预任务） | 独立 Task |
| QA-S1 | 角色/工具/白名单/编排器骨架 + 2 表落地（DDL Review） | **Schema authorization（§17）** + 架构接受 |
| QA-S2 | sub run 生命周期 + Finding 共享域归因 + 去重标记 | — |
| QA-S3 | 8 Prompt 全量 + 子 Agent tool 面盘点 + 前端最小入口 | — |
| QA-S4 | Release Gate（确定性公式 + LLM 辅助 + override + 展示端点） | Gate 规则批准（§25.H5） |
| QA-S5 | （可选）新 3 SSE 事件 + 前端编排视图 | **AgentEventType 冻结变更授权（§25.H3）** |

每 Sprint 验收标准：既有回归（backend ≥551 / frontend ≥53）全绿；任务束式增量；不做平行实现。

---

## 23. Risks

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | **Child timeout→PASS 传导**（Gate 因超时放行） | P0 红线 | Gate 公式将 TIMEOUT 角色计入 INCONCLUSIVE；缺失关键角色证据→FAIL/INCONCLUSIVE（§11.3） |
| R2 | LoopDetector 误判：poll 连续同参 | P1 | qa_check_subrun_status 强制 poll_seq 参数（每轮不同 argsJson） |
| R3 | 子 run 崩溃重启后编排器重复推进 | P1 | Orchestrator 幂等：以 assignment 状态为准，run 已存在则不重建 |
| R4 | 白名单守卫测试红 + ADR 授权缺口 | P1 | 实施前同步 QaP1_1Test 更新 + 新授权记录（§15） |
| R5 | LLM 依赖（Gate lead_decision 非确定性） | P1 | 确定性 auto_result 独立裁决；lead 建议仅作输入 + is_consistent 标记 |
| R6 | 单 DB 连接 + cached pool 并发子 run 竞态 | P2 | 顺序执行（MVP）+ 幂等推进（阶段内不并行） |
| R7 | 前端 26 名事件表随 QA-S5 扩展漂移 | P2 | 前端非冻结，MVP 不扩，QA-S5 同步更新 api/useGlobalSse 测试 |
| R8 | 预算绕过（子 Agent 伪造工具自跑） | P1 | 子 Agent 白名单无编排工具；run 行由 Orchestrator 固化 |

---

## 24. Admission Conditions for Implementation

下列条件**全部满足前**，QA Phase 2 **IMPLEMENTATION — NOT AUTHORIZED**（架构接受 ≠ 实现授权）：

1. **Human Architecture Acceptance**：Human 对本文档（含 Option A 推荐、SubRun proposed 模型、§17 表结构）给出显式接受。
2. **Schema Authorization**：对 `qa_role_assignment` + `qa_release_gate` 两张新表（Proposed at §17）给出显式批准（含 DDL Review 独立评审）。
3. **Frozen Contract Change Authorization**：若任何 Sprint 触碰冻结文件（尤其 QA-S5 的 AgentEventType 扩展），需逐项单独授权 — 当前架构零必需冻结变更。
4. **Error Contract Decision**：维持 6 码、不新增（Human 认可 §18 映射建议不落地为实现）。
5. **Budget Model Approval**：编排层 wallClockBudget 默认 300s + run 级 step/token 预算的模型获 Human 批准。
6. **Release Gate Rule Approval**：Gate 公式 / 子超时不上传 PASS 红线获 Human 批准。
7. **Multi-Agent Scope Approval**：8 Role / 顺序编排 / MVP 零新事件的边界获 Human 批准（超出该范围的并行编排、Separation of Projects、QA-S5 事件扩展需另行决策）。

---

## 25. Human Decisions Required

| ID | 决策项 | 建议 | 后果若拒绝 |
|---|---|---|---|
| H1 | 接受 Option A（Service-layer orchestration）作为推荐架构？ | 接受 | 需重选 B/C 并重评风险 |
| H2 | **Schema authorization**：追加 `qa_role_assignment` + `qa_release_gate` 两张新表（ADD-only）？ | 批准（在独立 DDL Review 中细化） | 无 gate/subrun 关联，Phase 2 无法因果成立 |
| H3 | 是否现在就授权 QA-S5 的 3 个新 SSE 事件（AgentEventType 冻结变更）？ | 延期到 QA-S5 | MVP 保持 26==26，零冻结变更 |
| H4 | 确认 Error Contract 维持 6 码、不加新码？ | 确认 | 新增 ErrorCode 需另立 FROZEN CONTRACT CHANGE |
| H5 | 批准 Gate 公式与"子超时不上传 PASS"红线？ | 批准 | Gate 决策可信度下降 |
| H6 | 编排工具定为 6 个（含 `qa_complete_orchestration` 与 Lead 可 `finding_report`）？或维持 freeze 的 4 个？ | 6 个（推荐） | 缩到 4 个：Lead 收口由 Orchestrator 在最后子 run 终态时自动触发 |
| H7 | 8 Role 顺序编排边界获准（不做并行 MVP）？ | 批准 | 并行需独立架构评估（单连接/线程池约束） |
| H8 | QA 编排工具与白名单改变是否接受连带修订 `QaP1_1Test` 守卫断言？ | 接受（记入授权页） | 测试红挡在每次 CI |

---

## 附：P1-5 未跟踪产物声明

**Existing untracked P1-5 artifacts remain pending separate human disposition.** 以下两个文件在本仓库工作区存在但未被 git 跟踪，**本阶段 DO NOT DELETE / DO NOT COMMIT / DO NOT MODIFY**，仅记录在案，等待 Human 另行裁决：

- `frontend/src/pages/TestPlanPage.test.tsx`（P1-5 Tester 前端隔离回归测试，含 /api/findings 与 /api/qa/findings 隔离断言）
- `backend/src/test/java/com/aigamelab/qa/QaP1_5Test.java`（P1-5 Isolation & Regression Deepening）

---

*End of QA Phase 2 Architecture design. Schema authorization required before any DDL. Implementation admission conditions per §24. This is an architecture design document only — no runtime, no schema, no code was changed.*