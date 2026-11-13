# QA Phase 2 Architecture Design

> 2026-11-12 范围与状态说明：本文件保留对应阶段的设计/决策与准入条件，不能以其中历史表数、无编排、无回放或预算成功描述判断当前实现。当前模块入口见 [QA 设计](projects/04_AI_Game_Agent_Team/README.md)，共享 Runtime 的已授权变更见 [ADR](decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)。新生产评估以模块演进设计为准，旧 Gate 指标不因此重解释。

> **文档性质：ARCHITECTURE DESIGN ONLY（仅设计，不实现）。**
> 本文件为 R4「QA Phase 2 Architecture Design」的交付物。QA Phase 2 的全部 runtime / Multi-Agent 编排 / SubRun / Release Gate / 新 Tool / 新 SSE / 新 DB schema / 新 API / 前端均 **未获实现授权**。本文档只提出方案、接口、数据结构、风险与实施边界。
> 任何实现的先决条件见 §20（Implementation Admission Criteria）。**架构接受 ≠ 实现授权。**
> **状态：DRAFT — WAITING FOR HUMAN ARCHITECTURE ACCEPTANCE.**
> **日期：** 2026-09-16 ｜ **审计基线 commit：** `2b42a75`（HEAD；后端 619/0/0，前端 57/0/0）。
> **回归验证（§20，2026-09-16 实测）：** 后端 `mvn -o clean test` **620/0/0 BUILD SUCCESS**（较 2b42a75 基线 +1 = R6 收口 commit `0545b7a` 中门禁优先级修复新增的 D4 测试，审计 620 数字由此而来）；前端 vitest **57/0/0**、tsc 0 errors。R4 审计基于 0545b7a（R5 收口）之后的树状态，gate 优先级修复 / 表数断言 14→16 / R5 报告勘误均为 R6 提交的工程事实。
> **R5 处理立场：** 当前 R5（commit `bf351e1`）已合入 HEAD，本轮将 R5 当作 **现有工程事实** 进行审计——R5 的交付物是"已经存在的架构"，不是"Phase 2 已授权实现的假设"。Phase 2 的实施方案在本文档 §18 中以 Architecture A / B 两个候选呈现，由 Human Acceptance 裁决，本文档不替 Human 选定。

---

## 目录

1. Current Baseline（现状基线与 R5 工程事实）
2. Goals（目标）
3. Non-Goals（非目标）
4. QA_LEAD（Architecture 主张与职责边界）
5. Multi-Agent Orchestration（多 Agent 编排）
6. SubRun（子运行建模）
7. Budget（预算）
8. Failure（失败语义）
9. Loop Detection（循环检测）
10. Evidence（证据）
11. Finding（共享域）
12. Release Gate（发布门禁）
13. Event / SSE（事件模型）
14. API（接口设计边界）
15. Schema（数据模型方案）
16. Isolation（隔离与安全）
17. Frozen Boundary（冻结边界分析）
18. Alternatives（候选架构对比：Architecture A vs B）
19. Risks（风险清单）
20. Implementation Admission Criteria（实施准入条件）
21. Open Questions（未决问题）
22. Human Decisions Required（人类决策单）

---

## 1. Current Baseline（现状基线与 R5 工程事实）

本节全部为 2026-09-16 对真实代码（HEAD `2b42a75`）的逐文件审计事实。

### 1.1 总体形态

| 维度 | 现状（工程事实） | 来源 |
|---|---|---|
| 后端 | Spring Boot 3.3 / JDK 24 / SQLite（WAL）单库 `agl.db` | backend/ |
| 构建 | `mvn -o clean test`（Maven 3.9.10，无 wrapper） | 实测 |
| 测试基线 | 后端 **619/0/0**（顺序）、619/0/0（seed=7 随机）；类级随机 619/1/0=TD-QA-R-001 豁免；前端 **57/0/0**、tsc 0 errors | QA_P1_ACCEPTANCE.md |
| frozen 边界 | 8 个冻结源文件零改动（§17） | ContractMatrixTest C1-C9 / QaToolIsolationTest I1-I6 |
| AgentType | 8 值：DESIGNER / TESTER / RESEARCH / QA / **QA_LEAD / QA_TESTER / QA_ANALYST / QA_RESEARCHER** | AgentType.java |
| Prompt | 13 版本（1 active = designer_p001，12 inactive；其中 8 个 QA 角色 seed） | PromptService + prompts/*.md |
| Tool | 29 注册工具（24 P1 既有 + 5 QA_LEAD 编排工具） | ToolRegistry + ContractMatrixTest C3 |
| 表 | **16 张**（14 既有 + `qa_role_assignment` + `qa_release_gate`） | schema.sql |
| SSE 事件 | 26 值域（`AgentEventType` 冻结；R5 零新增 QA 事件） | AgentEventType.java + eventTypeEnumMatchesBlueprint16Types |
| ErrorCode | 6 码（40001/40401/40901/42901/50201/50000）冻结 | ErrorCodes.java |
| 前端 | WorkflowPage AGENT_TYPES 5 值含 QA_LEAD；FindingsPage 8 维过滤 | frontend/src/ |

### 1.2 R5 交付物作为工程事实（非"Phase 2 已授权"）

R5（`bf351e1`）是独立授权的编制任务，其交付物**已经在树中运行**。当前 Phase 2 架构设计必须把它当现有事实审计，而不是重新设计一套"理想"：

| R5 交付 | 工程事实（逐文件核实） | 关键代码位置 |
|---|---|---|
| `qa_role_assignment` 表 | 6 态（PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED）；`UNIQUE(lead_task_id, role)`；`parent_run_id` FK run / `lead_task_id` FK task / `sub_task_id` / `sub_run_id`；player_profile 3 值 | schema.sql L228-284 |
| `qa_release_gate` 表 | 4 状态 `status`（PENDING/PASS/CONDITIONAL_PASS/FAIL）+ `automatic_result` 4 值（+INCONCLUSIVE）；`is_consistent` / `inconclusive_count` / `manual_override` / 审计四件套；`stats_json` | schema.sql L286-340 |
| 4 个新 AgentType | `QA_LEAD` / `QA_TESTER` / `QA_ANALYST` / `QA_RESEARCHER`；QA 家族永不 fallback 命中，仅显式路由 | AgentType.java + DefaultAgentTypeResolver |
| 5 个 QA_LEAD 编排工具 | `qa_create_subtask` / `qa_start_subrun` / `qa_check_subrun_status`（poll_seq）/ `qa_collect_findings` / `qa_complete_orchestration` | backend/.../qa/Qa*.java |
| QaOrchestratorService | 顺序编排（ROLE_ORDER 7 角色）；wall-clock `WALL_CLOCK_BUDGET_MS=300_000` 进程内 ConcurrentHashMap（重启丢失）；`ObjectProvider<AgentLoop>` 打破循环依赖 | qa/QaOrchestratorService.java |
| QaReleaseGateService | `RULE_VERSION="QA-S3-MVP-20260910"`；优先级链 FAIL > TIMEOUT-INCONCLUSIVE > FAILED/CANCELLED-INCONCLUSIVE > missing-role-INCONCLUSIVE > CONDITIONAL_PASS（lead 显式声明）> PASS（key roles COMPLETED）> 兜底 INCONCLUSIVE | qa/QaReleaseGateService.java |
| REST | `GET /api/qa/findings`（8 维）；`GET /api/qa/gates` / `GET /api/qa/gates/{id}` / `POST /api/qa/gates/{id}/override`；`GET /api/qa/orchestrations/{leadTaskId}/assignments` | api/QaController.java |
| 前端 | WorkflowPage AGENT_TYPES（RESEARCH/DESIGNER/TESTER/QA/QA_LEAD）；agentTone 分支；gates/orchestrations 端点**尚未映射到 api.ts** | frontend/src/ |

### 1.3 R5 现状的关键局限（审计发现，架构设计的输入）

以下是审计发现的 R5 存在事实。Phase 2 设计必须对它们作出判断（接受、修复、或归档为技术债），但**本阶段不改任何代码**：

1. **AgentLoop.observe() 不回放工具结果**（核心运行时最大缺口）。`AgentLoop.java:224-230`：`observe()` 每轮只组装 `system(selectSystemPrompt(task))` + `user(task.goal())`，注释明文 `历史 steps/tool_calls 由后续迭代从 DB 回放（当前 Fake 剧本场景无需）`——**回放从未实现**。含义：真实 LLM 多轮编排中，QA_LEAD 看不到自己上一轮 `qa_create_subtask`/`qa_start_subrun`/`qa_check_subrun_status` 的返回结果，无法在 context 中持有 subtaskId/subRunId。**R5 编排的 LLM 侧闭环在 Fake 场景外不成立**（§17.2 深入分析；§21、§22 需人类裁决是否构成 Phase 2 准入门槛）。
2. **wall-clock deadline 进程内**：`QaOrchestratorService.deadlines` 为 ConcurrentHashMap，进程重启即丢失。重启后 Lead 恢复续跑时 deadline 归零（需重新 putIfAbsent 或按已用时间折算）——现状语义不明。
3. **`dominantBuildShare` 恒为 null**：`QaReleaseGateService.countStats()` 硬编码 `dominantBuildShare` 不参与统计（build-share 数据管道未接线），`isBuildShareImbalanced` 短路永假。0.75 阈值形同虚设。
4. **`INCONCLUSIVE` 与 gate.status 域不闭合**：`automatic_result` 可出现 INCONCLUSIVE，但 `QaReleaseGate.STATUSES` 只含 4 值；`decide()` 将 automaticResult 直接写入 status（含 INCONCLUSIVE），导致领域常量与 DB 值域不一致（审计 agent 记录为软不一致）。
5. **无 `@Transactional`**：QaOrchestratorService/createSubtask 等多人语句操作（插入 Task + 插入 Assignment）非原子，中途失败会留下孤儿行。
6. **步骤配额**：子 run 预算硬编码 `maxSteps=50 / tokenBudget=100000`（与 RunControlService 同款），不读 task.maxSteps。
7. **子 run 取消不级联**：取消 Lead run 不取消已派生子 run。
8. **TD-QA-R-001**（RepositoryTest 类级随机 flaky）继续豁免；**TD-QA-P11-001**（ToolExecutor dispatch 无角色校验）在 Frozen Boundary 内保留。

> 这些"已存在的局限"不是 R5 的 defect 判决（R5 已 ACCEPTED 语义），而是 Phase 2 架构设计的**起点约束**。

---

## 2. Goals（目标）

Phase 2 架构设计的**意图目标**（设计要回答"如何让 QA 从单 Agent 上报者升级为多 Agent 协作团队 + 发布门禁"，但实现与否由 §20 准入）：

1. **QA_LEAD 编排闭环**：QA_LEAD 作为编排者调度角色 Agent 执行、汇总 Finding、生成发布建议；编排意图由 LLM 表达，确定性执行沉淀在服务层。
2. **确定性、可审计的 Release Gate**：以 Finding/run 事实为输入、确定性规则为裁决、LLM 为辅助输入、人类可 override、全程审计留痕。
3. **零平行域**：复用既有 Finding 域，不设计 QaFinding/QaEvidence/QaLifecycle；复用 AgentLoop/LoopDetector/BudgetGuard，不重造第二套。
4. **Frozen Boundary 零破坏（若无人类授权则必须零破坏）**：不对冻结文件做任何依赖假设；若架构确需触及冻结边界，明确标注 "FROZEN CONTRACT CHANGE REQUIRED" 并上升人类决策，**不得默认允许**。
5. **设计空间完整披露**：对"编排实体、SubRun 建模、Gate 状态机、Isolation 半径"给出两个候选方案（Architecture A / B）并对比，不替人类拍板。
6. **诚实面对 project_id 现实**：当前系统全表无 project_id、单项目架构；对 §16 的隔离要求给出真实结论而非纸面承诺。

---

## 3. Non-Goals（非目标与明确排除）

本期（R4）明确**不做**，仅在 §4-§17 内设计接口/数据结构/风险/实施边界：

- **不实现** QA_LEAD / Multi-Agent 编排 / SubRun / Release Gate / 新 QA Tool / 新 SSE / 新 DB schema / 新 API / Phase 2 前端（§0 排除；R5 已交付的运行时可作为基线存在，但 Phase 2 的完整形态不视为已授权）。
- **不新增 ErrorCode**（保持 6 码；Release Gate 是 QA 编排的决策结果，不是 HTTP ErrorCode）。
- **不引入第二个 LoopDetector** / 不重写 AgentLoop 的时间/循环逻辑。
- **不修改 Frozen Event infrastructure**（AgentEventType、PersistingAgentEventPublisher、SSE 订阅端点；26 事件值域不变）。
- **不无限扩张 `/api/qa/*`**：保持 `/api/qa/findings`；新增端点仅按 §14 讨论，不实际创建。
- **不实际修改 schema.sql**：§15 仅 Proposal（ZERO DDL）。
- **不引入 project_id 列**（除非人类决定单独立项；见 §16）。
- **不做并行编排设计**（单 DB 连接 Hikari pool=1 + LoopDetector 窗口隔离 + 无界 cached pool 事实；MVP 顺序编排）。
- **不做 Release Manager 独立 Agent**（合并到 QA_LEAD 语义）。
- **不做 QA-S5 之前的 SSE 事件扩展**（26→28/29 延后）。
- **不验证工厂**：为"验证架构"写 production code 被禁止（§18 红线）。

---

## 4. QA_LEAD（编排者：Agent / Module，不是服务集群）

### 4.1 身份与职责

QA_LEAD 是 **一个 Agent（AgentType 枚举值）+ 一组编排工具 + 一个编排服务（QaOrchestratorService）** 的组合语义：

| 层 | 归属 | 职责 |
|---|---|---|
| AgentType `QA_LEAD` | NOT frozen | 3→8 枚举扩展；resolver 路由；6 工具白名单 |
| Prompt `qa_lead_p001` | NOT frozen | LLM 意图表达（inactive 种子，运行期经 resolver case 命中） |
| 6 编排工具 | 新增 GameTool Bean | 意图层表达（create/start/check/collect/complete/finding_report） |
| `QaOrchestratorService` | 新增服务（既有） | 确定性执行层：subtask/subrun/assignment/gate 全部落库语义 |
| `QaReleaseGateService` | 新增服务（既有） | 确定性门禁公式（纯函数） |

**架构立场：QA_LEAD = 编排模块而非服务集群。** 它不引入新 JVM、不引入新 DB 实例、不引入 per-agent 进程——全部复用 `agent-loop-worker` cached pool 线程、共享 `agl.db` 单库、同一 AgentLoop 运行时。§5 详述（设计约束：`「agents are modules，不是 service；无 per-agent SQLite；全数据在统一 agl.db」`——此要求与 R5 现有实现天然一致，架构无需引入新运行时拓扑）。

### 4.2 白名单（6 工具，QA_LEAD only）

| 工具 | 语义 | 备注 |
|---|---|---|
| `qa_create_subtask` | 声明创建角色子任务 → Orchestrator 建 Task + assignment(PENDING) | 参数：role / configVersion |
| `qa_start_subrun` | 启动角色 subrun → 建 Run(PENDING)，submit 到 cached pool，assignment→RUNNING | 参数：subTaskId；deadline putIfAbsent |
| `qa_check_subrun_status` | 轮询 subrun → 返回 6 态 + 统计；终态写回 assignment | **必须携带递增 poll_seq**（LoopDetector 规避） |
| `qa_collect_findings` | 聚合已完成子 run 的 Finding | 仅 COMPLETED 子 run 贡献 |
| `qa_complete_orchestration` | 收口：deadline 冻结 + Gate 评估 + 落 qa_release_gate | 参数：configVersion / leadDecision / evidence |
| `finding_report` | 既有共享工具，Lead 本人可直接上报 | 8 个 role 亦可用 |

### 4.3 职责边界（设计主张）

- **Lead 拍板、不脏手**：Lead 决定测什么角色、什么顺序、什么口径、Gate 建议；角色 Agent 决定具体怎么查。Lead 白名单不含 `game_*`/`test_plan_*`/`web_*` 执行工具（防预算放大与越权抢活）。
- **LLM 意图 / 服务确定性分离**：LLM 不直接写 DB；五项编排工具的确定性动作全部委托 Orchestrator。
- **Assignment 是编排状态权威**（R5 事实）：QA 树父子关联在 `qa_role_assignment`，run 表零改动。

---

## 5. Multi-Agent Orchestration（多 Agent 编排）

### 5.1 拓扑（现状即设计基线，作为 Architecture A 候选骨架）

```
Human / Frontend（WorkflowPage，POST /api/tasks，agent_type=QA_LEAD）
        │
        ▼
  Task(Lead) ── QaOrchestratorService.startQA / createSubtask
        │
        ▼
  Run(Lead) [AgentLoop 原样执行，白名单=6 编排工具]
        │  qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings / qa_complete_orchestration
        ▼
  qa_role_assignment（6 态）── 顺序驱动 7 个子 Task/子 Run
        │   Lead→PLAYER_NOVICE→PLAYER_AGGRESSIVE→PLAYER_BUILD_SPECIALIST→COMBAT_ANALYST→NUMERICAL_ANALYST→EXPLOIT_HUNTER→UX_REVIEWER
        ▼
  子 Run_i（run.task_id = subTaskId；maxSteps=50 / tokenBudget=100000）
        │  role prompt + role whitelist（默认含 finding_report）
        ▼
  finding 表（共享域）◄──── 各角色 Agent 经 finding_report 上报
        ▼
  QaOrchestratorService.aggregate() → qa_release_gate（PENDING→PASS/CONDITIONAL_PASS/FAIL；automatic_result 4 值）
        ▼
  Human 查看/override（GET /api/qa/gates…，POST …/override）
```

### 5.2 角色矩阵（8 Role / 8 Prompt，R5 工程事实）

| # | Role | AgentType | Prompt | 白名单要点 |
|---|---|---|---|---|
| 1 | Lead | QA_LEAD | qa_lead_p001 | 6 编排工具 |
| 2 | Player_NOVICE | QA_TESTER | qa_player_novice_p001 | Tester 8 + web_search + fetch_page = 10 |
| 3 | Player_AGGRESSIVE | QA_TESTER | qa_player_aggressive_p001 | 10 |
| 4 | Player_BUILD_SPECIALIST | QA_TESTER | qa_player_build_specialist_p001 | 10 |
| 5 | Combat_Analyst | QA_ANALYST | qa_combat_analyst_p001 | 7（simulate/analyze/compare/metrics/events/finding_report/create_design_document）|
| 6 | Numerical_Analyst | QA_ANALYST | qa_numerical_analyst_p001 | 7 |
| 7 | Exploit_Hunter | QA_TESTER | qa_exploit_hunter_p001 | 10 |
| 8 | UX_Reviewer | QA_RESEARCHER | qa_ux_reviewer_p001 | 10（Research 6 + finding_report + game_observe + game_collect_events + analyze_experiment）|

### 5.3 顺序编排事实与约束（为什么并行是 Non-Goal）

- **Hikari pool=1**：单 DB 物理连接，并发子 run 的查询/写入都串行化——并行收益被连接池吸收，反而放大竞态（BudGuard step/token 竞写 run 行）。
- **LoopDetector 隔离是 per-run**（`WHERE run_id=?`，WINDOW=3）：子 run 互不污染；并行会引入 run 间调度不确定性，使编排验收非确定性。
- **agent-loop-worker = cached pool（无界）**：无并行上限护栏，擅自并行需要新线程管理语义。
- **结论**：MVP 顺序编排。并行作为 Phase 2 远期候选列入 §21 未决项（必须伴随独立连接池/屏障设计，超出本阶段）。

### 5.4 幂等与恢复（设计主张）

- 子 run 崩溃后 `RunRecoveryService` 自动续跑（RUNNING 可恢复）；Orchestrator 以 assignment 状态为准做幂等推进（`startSubrun` 只接受 PENDING；`checkSubrunStatus` 终态写回）。
- **孤儿防御**：createSubtask 非事务（§1.3.5）；Phase 2 若采纳 Architecture B 需补事务或补偿清理（§15.4）。

---

## 6. SubRun（子运行建模）

### 6.1 现状（R5 工程事实）

- **SubRun 不是独立表**：子运行 = 普通 `run` 行（`run.task_id = subTaskId`）。
- **父子关联权威 = `qa_role_assignment`**：`parent_run_id` / `lead_task_id` / `sub_task_id` / `sub_run_id` 四链 + `order_no` 排序。
- **run 表零改动**（freeze §8 与 TaskController.java:39-41 禁令一致）。
- 跨 Agent 先例仅为 Task 级 `researchSourceTaskId`（constraints_json 字段）——R5 的子任务链路（`lead_task_id`/`qa_role`/`config_version`/`player_profile` 同样存于 constraints_json）与之同款。

### 6.2 设计问题：SubRun 是否应为"新领域实体"？

| 方案 | 建模 | 优 | 劣 |
|---|---|---|---|
| S1（现状 / Architecture A 候选） | SubRun = run 行 + assignment 关联 | 零 schema 变更、run 表冻结保持、复用 AgentLoop/BudgetGuard/恢复语义全免费 | 检索子树需 join；无"子运行树"一阶视图；run 表无 parent 指针（语义藏在 assignment） |
| S2（Architecture B 候选） | 新增 `qa_subrun` 物化实体（或视图）| 一阶树查询、可加 orchestration-level 预算/统计列、语义自愈 | 新表/新迁移（add-only）；与 run 表双写一致性成本 |

### 6.3 设计答案（本文件的主张）

- **Phase 2 MVP 采纳 S1（SubRun = ordinary run 映射）。** 理由：R5 已按 S1 落地并 619/0/0 全绿；S1 满足"父子关联 rank + 6 态 + 顺序索引"，一切编排语义由 assignment 承载；run 表冻结零破坏。新增领域实体不带来新能力，只带来双写/迁移风险。
- **S2 保留为 Architecture B 的一部分**（§18），触发条件为：人类裁决需要"一阶子树查询、或 orchestration-level 预算持久化、或 run 表父子指针"时再评估；作为 add-only 迁移设计（§15.4）而非改造既有表。
- **任何 SubRun 实体均不建"第二套字段"**：不复制 run 的状态机（`RunStateMachine` 冻结 7 态），subrun 状态 = run.status 权威 + assignment 视角冗余。

---

## 7. Budget（预算）

### 7.1 三重防线（R5 既有，勿重造）

| 层 | 机制 | 状态 |
|---|---|---|
| 步数 | `BudgetGuard`：run.currentStep ≥ run.maxSteps → COMPLETED（按当前进度总结）| 既有冻结 |
| Token | `BudgetGuard`：run.tokensUsed ≥ run.tokenBudget → COMPLETED | 既有冻结 |
| 墙钟 | AgentLoop.timeoutMs=-1 **禁用**；编排层 `WALL_CLOCK_BUDGET_MS=300_000`（deadlines ConcurrentHashMap）| R5 新增，进程内 |

### 7.2 设计主张

- **不修改 AgentLoop.timeoutMs**（冻结 -1）；墙钟预算保持在编排层。
- **子 run 预算**：现状硬编码 maxSteps=50/tokenBudget=100000。Phase 2 主张：**允许按 role 定制**（如 Exploit/UX 给更高步数），但经 Orchestrator 生成 run 行时固化，不入 LLM 可控路径。
- **父树累计预算**：整棵 QA 树（Lead + 7 角色）的累计 token 上限由 Orchestrator 持有；超限停止后续启动，已启动按各自 run 预算自限。
- **重启语义**：deadline 进程内丢失是已知限制。Phase 2 若收口，可选：`qa_role_assignment.updated_at` + 子 run 已用时间估算折算 deadline（Architecture B 持久化选项之一），或接受 MVP 单 JVM 语义并在 §21 记录。

---

## 8. Failure（失败语义）

| 场景 | R5 事实 / 设计主张 |
|---|---|
| 子 run FAILED（ToolException critical / RuntimeException）| assignment→FAILED；**不吃掉其他子 run**；Lead 可在 check 中看到并决定补测或收口；Gate 记录 FAILED 角色 → 优先级链 FAILED/CANCELLED→INCONCLUSIVE |
| 子 run TIMEOUT（编排层 deadline 取消 / freezeRunningToTimeout）| assignment→TIMEOUT；**Gate 永不因此 PASS**（优先级链 TIMEOUT→INCONCLUSIVE，红线）|
| Lead run FAILED / CANCELLED | Orchestrator 于 Lead 终态收到信号 → 后续子 run 不启动；已运行子 run 保持自带完成（不级联强杀，避免跨 run 竞态）；Gate 若已建保持 PENDING 待人裁 |
| 进程重启 | RunRecoveryService 续跑 RUNNING 子 run；Orchestrator 幂等推进；WAITING_APPROVAL 子 run 静置等人工 resume |
| 部分成功 | Gate 输出成功角色清单 + 缺口清单（inconclusive_count / 缺失 role）；人工裁决 |
| 重试 | 子 run FAILED **不自动重试**（确定性优先）；重试仅人工触发（复用既有 `POST /api/runs/{id}/retry`，不改契约）|

**取消唯一路径**：`RunControlService.cancel()`（cancelChecker 单槽）。Phase 2 若需要"Lead 取消级联子 run"，设计为 Orchestrator 遍历 assignment 调 cancel（不改 Frozen cancel 语义），列为 Architecture B 项（§18）。

---

## 9. Loop Detection（循环检测）

### 9.1 现状（冻结事实）

- `LoopDetector`：`WINDOW=3`，`detect(runId, lastToolName, lastArgsJson)` 用 SQL 查该 run 最近 3 条 tool_call 是否完全一致；命中 → AgentLoop 强制 COMPLETED("检测到无效循环")。
- 隔离 per-run（`WHERE run_id=?`）：子 run 互不污染。
- **QA_LEAD 轮询误判风险**：若 LLM 连续 3 次 `qa_check_subrun_status` 传入相同 args，触发 WINDOW=3 → 编排被打断。
- **R5 已装护栏**：`poll_seq` 必填且递增（1,2,3,…），使每次 args_json 不同；服务端只校验 `>0` 不存储。

### 9.2 设计主张（不重造第二套 LoopDetector）

1. **沿用 poll_seq 机制**（R5 已实现，够用）。Phase 2 补充设计：poll_seq 持久化到 `qa_role_assignment.detail_json`（可选），供复现/审计；不改变探测算法。
2. **不对齐工具白名单做探测豁免**（不改 LoopDetector 冻结代码；不引入 "qa_* 工具不算循环" 特判）。
3. **Phase 2 新增的具身工具**（若有）必须同样携带防重参机制；在 §14 工具设计规范中写入"任何轮询类工具必须带单调递增序列参数"。

---

## 10. Evidence（证据）

### 10.1 现状（共享域事实）

- Evidence **不是独立表/独立域**：是 `finding.evidence_json` 内嵌 JSON（sessionId 主锚 + runId + toolCallIds + observationSnapshot + tick 辅助 + seed + overrides）。
- `FindingReportTool` source 硬编码 "LLM"；testPlanId/taskId 工具层 null，Service/API 层 T4-2 回填。
- 无 QaEvidence 平行域（禁止新建）。

### 10.2 Phase 2 证据链设计

- **角色归因**：`finding.run_id → run.task_id → task.constraints_json.qaRole`（freeze §11 的解析链，R5 已按此实现 collectFindings 过滤：**不含 taskId=leadTaskId**，按 runIds 聚合子 run 域）。
- **Gate 证据 = Finding 统计 + run 状态**，无第二套证据存储。
- **可选增强（设计、非实现）**：aggregation 阶段对重复上报做 record-and-surface 标记（Evidence.sessionId+scenarioId+title 相似度提示，不自动合并、不删改共享数据），保持"回归对比路径不变" Golden Rule。

---

## 11. Finding（共享域）

- **禁止** 新建 QaFinding / QaEvidence / QaLifecycle 平行域。
- 角色 Agent 一律经 `finding_report` 写 **finding 单表**（与 P1 / Tester 同一张表）。
- Gate 输入 = 既有 finding 表统计（severity/category 聚合）。
- 合法性边界由 P1-2 既有矩阵守护（OPEN→CONFIRMED/DISMISSED、CONFIRMED→FIXED/DISMISSED；越权 40901）——Phase 2 不扩枚举、不改迁移矩阵。
- `finding.source` 保持 3 值（INVARIANT/LLM/MANUAL），不扩展。

---

## 12. Release Gate（发布门禁）

### 12.1 事实：Gate 是决策结果，不是 HTTP ErrorCode

- **设计红线**：`qa_release_gate.status` 是 QA 编排对某 config_version 的**评价结论**，属于应用域数据，不属于错误码体系；**不新增 ErrorCode**（ErrorCodes 保持 6 码）。
- 系统约定：Gate 不存在于 API 错误信封的 code 中；`POST /api/qa/gates/{id}/override` 以 200 + ApiResp 返回 Gate 实体，决策值在 `body.data.status`。

### 12.2 状态机（现状 + 设计张量）

**R5 现状**：`status` ∈ {PENDING → PASS / CONDITIONAL_PASS / FAIL / (INCONCLUSIVE 写库但不在常量域)}；`automatic_result` ∈ {PASS/CONDITIONAL_PASS/FAIL/INCONCLUSIVE}；`final_result` 人审兜底。

**Phase 2 架构要求（用户 §12.9）**："PASS/FAIL/BLOCKED，确定性规则 + 人工 override + 审计 trail + unknown/insufficient evidence 状态"。映射设计：

| 用户要求 | 映射到现状/设计 |
|---|---|
| PASS | `PASS`（key roles COMPLETED + 阈值全过 + lead 一致或无条件）|
| FAIL | `FAIL`（critical>0 ∨ high≥3 ∨ softLock>0 ∨ build share>0.75）|
| BLOCKED | 设计为 **INCONCLUSIVE 显式化**：TIMEOUT/FAILED/CANCELLED/missing-role/evidence 不足 → Gate **不落 PASS/FAIL**，保持 `INCONCLUSIVE`（状态更名建议见下）|
| 确定性规则 | QaReleaseGateService 优先级链（§1.2），纯函数、rule_version 可追溯 |
| 人工 override | `manual_override` + reason/by/at 审计四件套 + final_result 覆盖 |
| audit trail | override 不可逆写死（manual_override=1）；decided_at COALESCE 首决时间；stats_json 快照 |
| unknown/insufficient evidence | `INCONCLUSIVE` 承载；`inconclusive_count` 量化缺口 |

**设计主张**（上升人类决策 H-7）：
- **状态族更名建议**：将"INCONCLUSIVE（自动）/ PENDING（人工等待）"显式化为一等状态 `BLOCKED`（即 4 值 + BLOCKED = "证据不足不可裁决"），或维持现状用 INCONCLUSIVE 表达。**不擅自新增 ErrorCode，但状态字面量与领域常量须闭合**（现状 INCONCLUSIVE 不在 STATUSES 域是不闭合——§1.3.4）——任一方案都需要人类裁决后才可实施。
- **红线保持**：子 run TIMEOUT 绝不自动 PASS；关键角色（Exploit/UX）COMPLETED 缺失 → 不放行。

### 12.3 现状缺陷（审计确认，设计需回应）

1. `dominantBuildShare` 恒 null → build-share 判 FAIL 永远短路（§1.3.3）。Phase 2 若要启用该判据，需定义 build-share 数据管道（该判据来自 freeze 公式 §13.3，当前是"定义了但未接线"的工程事实）。
2. `decide()` 把 INCONCLUSIVE 写入 status（域不闭合）。
3. `is_consistent` 语义：仅 CONDITIONAL_PASS / PASS 两分支在 lead 文本含关键词时置 true，其余全 false；需在 Phase 2 定义"consistency"的精确定义（含 lead 缺席、lead 与 auto 冲突）。

---

## 13. Event / SSE（事件模型）

### 13.1 冻结事实

- `AgentEventType` 26 值域冻结；`PersistingAgentEventPublisher.isValid` 拒绝未知类型（eventTypeEnumMatchesBlueprint16Types 测试守护）。
- Persist-then-push 链：publisher → DomainEventEnvelope → AgentEventListener(INSERT agent_event)→ SseEmitterRegistry → SSE push。`id`=autoincrement 全局序，Last-Event-ID replay ≤1000。
- 前端 `useGlobalSse` 26 名固定订阅 + REPLAY_TRUNCATED。

### 13.2 Phase 2 事件设计

- **MVP 零新增事件（26==26）**：子 run 的生命周期全部命中既有 RUN_STARTED/RUN_COMPLETED/RUN_FAILED/STEP_STARTED/TOOL_CALLED/TOOL_RETURNED/FINDING_CREATED/TASK_CREATED。编排进度本身经 `qa_role_assignment` 落库（GET /api/qa/orchestrations/…）可轮询获取。
- **QA_* 3 事件（QA_SUBTASK_CREATED / QA_ROLE_COMPLETED / QA_RELEASE_GATE_CREATED）列入 Architecture B**（§18）并标注 **FROZEN CONTRACT CHANGE REQUIRED**（AgentEventType 属冻结集）。Human 若批准 → 追加不改旧值 + 前端 useGlobalSse 名表同步（前端非冻结）+ 测试同步。**默认不批准。**
- 已发现且必须记录的缺口：`finalizeRun` 若 publish `RUN_TIMEOUT` 会被 `isValid` 静默丢弃（AgentEventType 无 RUN_TIMEOUT）；Lead 编排的 TIMEOUT 语义目前由 assignment 承载，SSE 侧无事件。Phase 2 设计选择：或接纳"TIMEOUT 只走 Gate 状态、不新增 SSE 事件"，或在 Architecture B 下连同 QA_* 一起追加（需授权）。**不改 frozen publisher 的 isValid 行为。**

---

## 14. API（接口设计边界）

> **本节仅讨论设计意图，不实际创建任何 endpoint**（用户红线 §11）。保持 `/api/qa/findings` 8 维过滤契约不变。

### 14.1 现状 surface（R5 既有，事实）

| 端点 | 语义 |
|---|---|
| GET /api/qa/findings | 8 维 AND 过滤（P1-3 契约，保持）|
| GET /api/qa/gates | 过滤：leadTaskId / configVersion / status |
| GET /api/qa/gates/{id} | 单 Gate；404 |
| POST /api/qa/gates/{id}/override | 人工覆盖：finalResult ∈ STATUSES + reason/by；不可逆审计 |
| GET /api/qa/orchestrations/{leadTaskId}/assignments | 按 order_no ASC；编排进度视图 |

### 14.2 Phase 2 需要讨论、不必新增的端点面

| 设计问题 | 建议（讨论）|
|---|---|
| Lead run 起点 | 复用 `POST /api/tasks`（agent_type=QA_LEAD）+ `POST /api/tasks/{id}/runs`。**不新增** `POST /api/qa/orchestrations` 启动端点（除非 Human 更偏好显式启动；列为 H-10）|
| Gate 列表前端触达 | gates/orchestrations 尚在 api.ts 缺失（工程事实）。Phase 2 前端接入时在 api.ts 补 4 行映射函数即够，**不扩后端契约** |
| Gate 重算/再评估 | 设计为 idempotent `decide()`（decided_at COALESCE）。**不新增** "recalculate" 端点（H-11 待裁决：是否需要 `POST /api/qa/gates/{id}/re-evaluate`，依赖 Architecture B 的 durable orchestration 语义）|
| 「不无限扩张 `/api/qa/*`」执行规则 | 任何新端点必须满足：单一职责 + 复用 envelope（ApiResp{code,message,data}）+ 复用 6 错误码映射；否则走 H-12 人工裁决 |

### 14.3 Error Mapping（设计意图，非实现）

| 场景 | 映射（复用既有）|
|---|---|
| 编排工具参数非法（未知 role / 非法 status）| 40001 INVALID_ARGUMENT |
| lead_task / assignment / subrun 缺失 | 40401 NOT_FOUND |
| 状态机违规（PENDING 重复 start、树已满、override 决赛后再改）| 40901 CONFLICT |
| 墙钟超时 / 树产能上限 | 42901 RATE_LIMITED（"限流/产能"语义）|
| LLM 上游失败 | 50201 UPSTREAM_ERROR 冒泡 |
| 兜底 | 50000 INTERNAL_ERROR |

---

## 15. Schema（数据模型方案）

> **ZERO DDL。不实际修改 schema.sql。** 本节给出 Proposal 供人类裁决（§15.4 + H-2）。

### 15.1 现状（16 表，R5 事实）

- `qa_role_assignment`：id / parent_run_id FK run / lead_task_id FK task / role（8 值）/ player_profile(3) / status(6 态) / order_no / sub_task_id FK task / sub_run_id FK run / sub_run_steps_used / sub_run_tokens_used / result_summary / error_message / detail_json / created_at / updated_at；索引 idx_qra_parent/lead/subrun/subtask + UNIQUE idx_qra_lead_role(lead_task_id, role)。
- `qa_release_gate`：id / lead_task_id FK task / run_id FK run / config_version / status(4) / automatic_result(4) / rule_version / lead_decision / lead_decision_evidence_json / final_result / is_consistent / inconclusive_count / manual_override / override_reason/by/at / critical_bugs / high_bugs / medium_bugs / soft_lock_count / crash_rate / dominant_build_share / stats_json / report_doc_id FK design_document / created_at / decided_at；索引 idx_qrg_task/config/status。

### 15.2 设计问题：Option A（minimal）vs Option B（complete）

| 候选 | 内容 | 迁移面 | 适用 |
|---|---|---|---|
| **Option A — Minimal** | **零新增表**。沿用 R5 两张表：assignment 承载父子+进程态；gate 承载结论。补充仅有的列级增强（若有）必须是纯 ADD NOT NULL DEFAULT。| 无（或 2 无数据迁移）| Architecture A（§18）配套；把 R5 现状固化为设计 |
| **Option B — Complete** | 新增 `qa_subrun`（或物化视图 / run 树）**+** `qa_orchestration_run` 级预算/统计列 **+** gate 状态域闭合（BLOCKED 或 INCONCLUSIVE 进 STATUSES）**+** `qa_role_assignment.poll_seq` 持久化。全部 **ADD-only**（不 alter 既有 14 表/既有列）| 3-4 张新表（IF NOT EXISTS，无数据迁移）| Architecture B（§18）配套 |

### 15.3 状态闭合问题（两方案都要回答）

现状 `INCONCLUSIVE` 可写入 `status` 但不在 `QaReleaseGate.STATUSES` 常量域——这是**领域与 DB 的不闭合**。任一架构都必须把 Gate 的**完整状态族**定义清楚：
- 建议方案 i：**BLOCKED 并列**（status ∈ {PENDING, PASS, CONDITIONAL_PASS, FAIL, BLOCKED}）；automatic_result ∈ {PASS, CONDITIONAL_PASS, FAIL, INCONCLUSIVE}；`INCONCLUSIVE` 只出现在 automatic_result，人工可将 BLOCKED→PASS/FAIL（override），自动将 INCONCLUSIVE→BLOCKED。
- 建议方案 ii：保持 4 值，INCONCLUSIVE 作为唯一的"未决结果"落到 `final_result=INCONCLUSIVE` + status 保持 PENDING，直到人工 finalize。
- **由 Human 裁决（H-7）。** 两者就适配用户"unknown/insufficient evidence 状态"要求而言等价，区别在语义命名与查询便利。

### 15.4 迁移策略（未来实施的建议形状，非本文执行）

1. 仅 `CREATE TABLE IF NOT EXISTS` 新增表（零破坏既有 14 表 DDL）。
2. 不改既有列/约束；枚举扩展只追加。
3. 迁移前必须通过既有回归（619 backend / 57 frontend 全绿）→ 独立 DDL Review（QA-S1-2 先例）。

---

## 16. Isolation（隔离与安全）

### 16.1 现实核查（审计事实）

- **全系统无 project_id**：`backend/src/main/java` 全树 Grep 无 project/projectId 引用；schema.sql 16 表无 project 列。**单项目架构是现状**。
- 现有隔离边界 = **task 归属**：findings / runs / steps / tool_calls / design_document / research_* 全以 task_id（或经 run→task 可达）隔离。
- 用户 §4/§13 要求"使用 project_id isolation""不让 Parent Run A 读到 Project B 的 SubRun"——**与单项目现实冲突**。本架构若承诺"逐 projection 隔离"即是虚构，必须诚实处理（见 §16.2-16.3 与本文件 §22 H-8）。

### 16.2 现状隔离语义（可信的部分）

- QA 树在 `qa_role_assignment.lead_task_id` 之下自带树级隔离：siblings 无法通过 assignment 表读取别的树的子 run 关联（query 均以 lead_task_id 为根）。
- RunRecoveryService 全局续跑是单项目内行为；多项目不存在（无 project 概念）。
- 前端路由无鉴权层（本系统单用户/单项目语义）——不在 Phase 2 范围。

### 16.3 设计主张

- **Phase 2 默认以 lead_task_id 为隔离根**，继承现有 task 级隔离语义，隔离半径不放大。此方案下"Parent Run A 不会读到 Project B 的 SubRun"在无 project 概念的现状下简化为"Parent Run A 只能读到自己的子树"——这是**可验证**的保证（assignment.lead_task_id 过滤），也是 Architecture A 的诚实表述。
- **project_id 改造 = 独立立项**：对全 16 表加 project 列属 **FROZEN CONTRACT CHANGE**（alter 既有表违反 add-only 原则 + 触碰 schema 冻结），需要独立 ADR + 完整迁移 + 全表回归。**不属于 Phase 2 范围**，本文件不设计其实现，仅记录为 H-8 由人类裁决是否另立项目。
- 若 Human 坚持 Phase 2 必须落地 project 隔离，本架构给出约束（不展开实现 DDL）：所有新表从第一天带 `project_id`；既有表逐表迁移；前端所有查询带 project 过滤；回归基线全量跑。触发独立工作流。

---

## 17. Frozen Boundary（冻结边界分析）

### 17.1 冻结对象清单与 Phase 2 关系

| # | 冻结对象 | Phase 2 需要改动？ | 设计立场 |
|---|---|---|---|
| 1 | AgentLoop.java | **是——候选缺口**（见 §17.2）| 不默认改动；改动须授权（H-9）|
| 2 | RunStateMachine | 否 | 不扩 run 状态；assignment/gate 状态是独立域 |
| 3 | BudgetGuard | 否 | 预算语义不动 |
| 4 | LoopDetector | 否 | 不新增 flags；poll_seq 规避已够 |
| 5 | ToolExecutor | 否 | 白名单是可见性过滤非执行授权（TD-QA-P11-001 在该边界内）——**但这是 Phase 2 最重要的安全硬化点**（见 §17.2bis）|
| 6 | ToolResult | 否 | success/failure 契约冻结 |
| 7 | ErrorCodes | 否 | 6 码不动 |
| 8 | Designer P0-P8 | 否 | Phase 2 不涉及 |
| 9 | Tester T1-T7 | 否 | Phase 2 不设及 |
| 10 | Research R1 | 否 | Phase 2 不涉及 |
| — | AgentEventType | 否（MVP）| QA_* 3 事件延后到 Architecture B（若授权）|
| — | schema.sql（16 表）| 否（MVP）| 零 DDL；§15 仅 Proposal |

### 17.2 AgentLoop.observe() 缺口——本轮最重要的设计裁决点

**事实**：`AgentLoop.observe()`（冻结文件）每轮只发 `system+goal`，不回放历史 steps/tool_calls（`AgentLoop.java:228` 注释）。R5 测试全部走 FakeLlmGateway（剧本式），未暴露此缺口；真实 DeepSeek 多轮编排中 QA_LEAD 无法在 context 里携带上一轮工具返回。

**对 Phase 2 的影响（三条设计路径，均不擅自落地）**：

| 路径 | 描述 | 冻结影响 | 架构归属 |
|---|---|---|---|
| P1 接受现状 | 编排意图设计为"自包含式"：QA_LEAD 每轮只依据 system+goal 决策，子任务 ID 等状态由 Orchestrator 幂等推断（tools 参数尽量少依赖前一轮返回值）。多轮联动的表达力受限，但零冻结改动。 | 零 | Architecture A 默认 |
| P2 冻结变更申请 | 在 `observe()` 增加历史 tool_call/step 回放（`SELECT ... FROM tool_call WHERE run_id=? ORDER BY id LIMIT N` 组装 assistant/tool 消息）→ **FROZEN CONTRACT CHANGE REQUIRED（AgentLoop.java）** → 需人授权（H-9）。此改动同时惠及 Designer/Tester/Research 多轮路径。 | 1 个冻结文件 | Architecture B 核心项 |
| P3 编排层补偿 | 不动 AgentLoop；Orchestrator 为 Lead run 提供"记忆注入"：每次 LLM 调用前把 assignment/run 状态摘要并入 task.goal 或 sponsor 一个包装 LlmGateway——需新非冻结组件，且与 AgentLoop 的 messages 构造契约耦合。 | 零（新组件）| 二者可选 |

**本文件不替 Human 选择 P1/P2/P3**（§21 未决 + H-9 决策）。但明确：**P1 是"现状即架构"的诚实表述，P2 是唯一让真实 LLM 多轮编排成立的完整闭环。**

### 17.2bis 执行期白名单缺口——安全硬化点（工具系统审计新发现）

**事实（tool-system 审计逐文件核实）**：`ToolExecutor.execute()` **不校验白名单**——它按名字从 `ToolRegistry` 取任意已注册工具并执行；白名单过滤**唯一**发生在 `AgentLoop.selectToolSpecs()`（对 LLM 的可见性过滤，非执行授权）。唯二的运行时防御：`AbstractQaTool.leadContext()` 二次校验 `agent_type==QA_LEAD`（**仅限 5 个 qa_* 工具**）。含义：DESIGNER/TESTER/RESEARCH run 的 LLM 若输出工具名被注入/混入/重放（如 `modify_game_config` / `simulate_game` / `game_act` / 任一 `qa_*`），`ToolExecutor` 会直接执行。

**对 Phase 2 的设计主张**（不实现，收入架构边界）：
- **推荐**：在 executor 侧或 resolver 侧增加"白名单二检"——按 Run 解析出的 AgentType 重校验 `tool_call.tool_name ∈ toolWhitelist(agentType)`，不命中 → 40901 拒绝 + `tool_call` FAILED + 事件留痕。若走 executor 侧则属 **FROZEN CONTRACT CHANGE（ToolExecutor.java）**，需 H-9bis 授权；若走 resolver 侧（非冻结）可作为 Architecture A 的低风险加固。
- 风控分级：该缺口现为 **P0（执行原子性上没有授权墙）**，但被"白名单暴露面 + 单项目 + 单用户"环境稀释为实际低危；Phase 2 引入多 Agent 编排后威胁面扩大，建议在 σ 中明确列为 Phase 2 安全基线项。

### 17.3 "不要为了架构完整性假设 Frozen 可解除"——本文件立场

- 除 §17.2 明确标注的 P2 候选（AgentLoop.observe 回放）外，**本架构所有推荐方案都规避冻结边界**；凡触碰均显式标注 "FROZEN CONTRACT CHANGE REQUIRED" 并挂人类决策，**默认不解除**。
- AgentEventType 扩展（Code/B ）、project_id 改造（§16.3）同规则。

---

## 18. Alternatives（候选架构：Architecture A vs B）

> **本文件不选定最终方案。** 按用户 §16，把两个架构作为并列候选交付，由 Human Acceptance 决定。

### 18.1 一句话概括

- **Architecture A — Minimal（最小侵入编排层）**：在 R5 现有形态上把「Service-layer orchestration + ordinary-run SubRun + assignment 关联 + 顺序编排 + Gate」固化为正式架构；所有增强仅落在非冻结文件；接受 observe() 不回放、deadline 进程内、build-share 未接线为已记录限制。
- **Architecture B — Complete（独立持久化编排）**：升级为「SubRun 一阶建模/视图 + orchestration 持久状态（预算、poll_seq、子树统计）+ gate 状态族闭合（BLOCKED）+ observe() 历史回放（需授权）+ 可选 QA_* 事件（需授权）+ 韧性（事务/级联取消/幂等重试）+ build-share 管道」。

### 18.2 对比矩阵

| 判据 | Architecture A（Minimal） | Architecture B（Complete） |
|---|---|---|
| 本质 | 固化 R5 事实为架构；零新表；零冻结变更 | 补全编排观察面与韧性；2-4 新表(add-only)；≤2 冻结变更（均需授权）|
| SubRun 建模 | ordinary run 行 + assignment 关联 | + `qa_subrun` 物化/视图 + run 树一阶查询 |
| orchestration 状态 | assignment 6 态 + 进程内 deadline | + durable 预算/poll_seq/子树统计列 |
| Gate 状态族 | 现状 4 值（INCONCLUSIVE 写库但不闭合）| 状态族闭合（BLOCKED 或 INCONCLUSIVE∈STATUSES）|
| LLM 多轮闭环 | ✗（observe 不回放；意图自包含化）| ✓（observe 回放——冻结变更需授权）|
| 观察/审计 | Gate + assignment 落库，够用但子树要 join | 一阶子树 + 全持久化的编排统计 |
| 失败韧性 | 无事务；重启丢 deadline | @Transactional 补偿 / durable 预算 / 级联取消 |
| Schema 迁移 | 零（或 0 数据迁移）| add-only 2-4 表（IF NOT EXISTS，零数据迁移）|
| Frozen 影响 | 零 | AgentLoop.observe + AgentEventType（候选，需 H-9/H-3 授权）|
| 回归风险 | 最低（不动树）| 中（动 observe 会波及其它 Agent path → 需要全量回归证实无行为漂移）|
| implementation cost | 低——R5 已把骨架落地 | 高——新表 + 冻结变更 + 迁移 + 全量回归 |
| 与 R5 关系 | R5 现状 = architecture | R5 现状 = 中间态，Phase 2 继续演化 |

### 18.3 推荐倾向（供 Human 参考，非决定）

本架构流程的诚实评估：
- 若优先 **低风险 + 快速可用 + 零冻结授权消耗**，选 **A**（并在 §21 把 B 的能力列作 backlog）。
- 若优先 **真实 LLM 多轮闭环 + 完整可审计编排 + 状态闭合**，且人类愿意授权 observe()/事件冻结变更，选 **B**。
- 混合建议：**A 为基线，B 中不触碰冻结的部分（状态域闭合、durable 预算、事务、build-share 管道）可分批并入**——这是本文件最倾向的演进路线，但**仍交由 Human 决定**。

---

## 19. Risks（风险清单）

> ≥16 项，含概率/严重度/缓解/阻塞状态。P0=红线风险但有既有规避；阻塞=会挡 gate 或需先裁决。

| # | 风险 | 概率 | 严重度 | 缓解 | 阻塞？ |
|---|---|---|---|---|---|
| R1 | **observe() 不回放 → 真实 LLM 多轮编排不成立**（QA_LEAD 无法持有前一轮工具结果）| 高（生产必然暴露）| P0 | 路径 P1（自包含意图）/ P2（冻结变更授权）/ P3（编排层记忆注入）；H-9 裁决前不实现编排的 LLM 闭环依赖 | **是（H-9 前不得把编排闭环当作可用）** |
| R2 | Child timeout → PASS 传导（红线被绕过）| 低 | P0 | Gate 优先级链：TIMEOUT→INCONCLUSIVE 绝不 PASS；KEY_ROLES_FOR_PASS 守卫 | 否 |
| R3 | LoopDetector 误判杀 Lead（poll 连续同参）| 中 | P1 | poll_seq 必填递增（R5 已装）；Phase 2 新增轮询工具强制同规 | 否 |
| R4 | wall-clock deadline 进程内 → 重启后编排语义漂移 | 高 | P1 | 接受单 JVM 语义并文档化（A）；或 durable 预算（B）| 否 |
| R5 | `dominantBuildShare=null` → build-share FAIL 判据永远不会触发（0.75 阈值虚设）| 必然（现状）| P1 | 定义 build-share 管道（Phase 2 是否启用需裁决）；不启用则从公式注释标 dead | 否 |
| R6 | Gate 状态域不闭合（INCONCLUSIVE 写 status 但不在常量域）| 必然（现状）| P1 | 状态族闭合设计（§15.3）；H-7 裁决 BLOCKED vs 4 值 | 否 |
| R7 | 无 @Transactional → 半途失败孤儿行（Task 建了 Assignment 没建）| 中 | P1 | A：部署期启动清理/幂等重试；B：事务 + 补偿 | 否 |
| R8 | 取消不级联 → Lead 取消后子 run 空转消耗预算 | 中 | P1 | B：Orchestrator 遍历 assignment cancel（不动 frozen cancel 语义）| 否 |
| R9 | project_id 要求 vs 单项目现实的期待落差 | 高 | P1 | §16 诚实披露 + H-8 裁决；默认 lead_task_id 隔离半径 | **是（H-8 未裁决前不得承诺 project 级隔离）** |
| R10 | 前端 26 事件名表与 Architecture B 事件扩展漂移 | 低（MVP）| P2 | MVP 不扩；QA-S5 同步 api/useGlobalSse + 前端测试 | 否 |
| R11 | **执行期白名单缺口**（ToolExecutor 不校验白名单，注入/重放工具名即执行；唯 QA_LEAD 二次校验）| 中（多 Agent 后升高）| P0 | 白名单暴露面过滤 + AbstractQaTool.leadContext 双防线现状；Phase 2 增加 executor/resolver 侧白名单二检（§17.2bis；H-9bis 若涉冻结）| 否 |
| R12 | 预算绕过（角色 Agent 伪造编排工具自跑）| 低 | P1 | 角色白名单不含 qa_* 编排工具；run 行预固化预算 | 否 |
| R13 | RunRecoveryService 启动续跑拾取孤儿 RUNNING 子 run → 编排双推进 | 中 | P1 | Orchestrator 幂等（assignment 状态为准）；startSubrun 只接受 PENDING | 否 |
| R14 | 顺序编排下 LLM 一次工具批包含多个 qa_* 调用 → 服务层状态被 LLM 猜错 | 中 | P2 | prompt 约束一次一个编排动作；失败 40901 幂等重放 | 否 |
| R15 | Gate 决策值经 override 被滥用（无权限模型）| 低 | P1 | override 不可逆 + reason/by 审计；final_result 覆盖留痕 | 否 |
| R16 | Architecture B 的 observe() 变更波及其它 Agent 行为（Designer/Tester/Research 多轮路径）| 高 | P1 | 全量回归（619+）+ 行为漂移比对；作为独立冻结变更 Task | 是（若走 B）|
| R17 | TD-QA-R-001 类级随机 flaky 在 Phase 2 增表后复发面扩散 | 中 | P2 | §15 豁免继续；新表不共享 static DB fixture；QA 测试沿用 QaTestFixture per-class temp DB | 否 |
| R18 | 28→ 前端 re-trigger 时 Gate 无"重算/再评估"语义 → 人工重复 override | 低 | P2 | decide() idempotent；H-11 裁决 re-evaluate 端点 | 否 |
| R19 | 编排 wall-clock=300s 对 8 角色 + 真实 LLM 调用欠预算（一次真实调用即数千 token）| 中 | P1 | run 级 step/token 预算打底；300s 为编排层上限可调；预算模型 H-4 裁决 | 否 |
| R20 | 无独立 Release Manager / 无 Gate 展示页 → 人类触达 Gate 全靠 override API + FindingsPage | 中 | P2 | MVP 前端最小化：api.ts 补 gates 映射 + FindingsPage 增 Gate 卡片（Phase 2 前端如需入口则并入）| 否 |

---

## 20. Implementation Admission Criteria（实施准入条件）

下列条件**全部满足前**，QA Phase 2 **IMPLEMENTATION — NOT AUTHORIZED**（架构接受 ≠ 实现授权，与 §21/§22 联动）：

1. **Human Architecture Acceptance**：Human 对本文档（含 Architecture A/B 对比、§15 Schema Proposal、状态族建议 §15.3）给出显式接受，并**选定 A / B / 混合演进路线**。
2. **Schema Authorization（若涉及新表/新列）**：对 Option A（零新表）或 Option B（add-only 2-4 表）给出显式批准 + 独立 DDL Review。
3. **Frozen Contract Change Authorization（若涉及）**：AgentLoop.observe 回放（H-9）、QA_* 事件（H-3）必须逐项单独授权——只有 Human 明确批准才解除；**本文档不含"默许解除"**。
4. **Error Contract Decision**：维持 6 码，不新增（Gate 状态不是 ErrorCode）。
5. **Budget Model Approval**：wallClockBudget 默认 300s + run 级预算模型获准；重启语义（进程内 vs durable）定为决策。
6. **Release Gate Rule Approval**：Gate 优先级链、TIMEOUT 绝不上传 PASS 红线、状态族（BLOCKED 或 INCONCLUSIVE 化）获准。
7. **Multi-Agent Scope Approval**：8 Role 顺序编排、MVP 零新事件的边界获准（并行、project 隔离、QA-S5 事件扩展均需另行决策）。
8. **observe() 缺口裁决**：H-9 明确走 P1（自包含意图）/ P2（冻结变更）/ P3（编排层补偿）；**裁决前不得宣称编排的 LLM 闭环可用**。
9. **回归门槛**：每次实施 Sprint 收口后 `mvn -o test` 619/0/0（或增量新基线）全绿 + 前端 57/0/0 + tsc 0 errors；Frozen Boundary git diff 零改动（除非对应授权）。
10. **测试纪律**：QA 新测试沿用 QaTestFixture per-class 临时 DB（不继承 AbstractRepositoryTest，PF-002）；不制造为文档服务的假测试。

---

## 21. Open Questions（未决问题）

1. **Q1 — observe() 回放**：Phase 2 接受 P1（现状，编排意图自包含化）还是申请 P2（冻结变更回放）或 P3（编排层记忆注入）？（→ H-9）
2. **Q2 — Gate 状态族**：BLOCKED 显式化 vs INCONCLUSIVE 化（→ H-7）。
3. **Q3 — build-share 管道**：0.75 阈值是否启用？启用需要定义 build 分布数据来源与统计口径（目前恒 null）。
4. **Q4 — deadline 韧性**：重启后 300s 预算语义（归零重计 vs 按 elapsed 折算 vs durable 持久化）？（→ H-5）
5. **Q5 — project_id**：是否单独立项做全表的 project 隔离？Phase 2 默认 lead_task_id 隔离根是否被接受？（→ H-8）
6. **Q6 — 子任务预算定制**：角色 run 预算保持统一 50/100000 还是 per-role 定制？
7. **Q7 — 前端触达**：Phase 2 是否需要最小 Gate/编排展示（FindingsPage 增卡片 or 独立页）？MVP 不含页面的边界是否保持？
8. **Q8 — 事件扩展时机**：QA-* 3 事件走 Architecture B 还是继续延后 QA-S5？（→ H-3）
9. **Q9 — run 表 parent 指针**：S2 建模是否终将被需求驱动（如"删除 QA 树"或"跨树审计"出现）而必须物化 SubRun 说？
10. **Q10 — 取消级联**：Lead 取消是否应当级联子 run（默认不级联，B 项）。
11. **Q11 — 一致性定义**：`is_consistent` 的精确定义（lead 缺席 / lead 冲突 / 静默默认）需在设计基线上定稿。
12. **Q12 — 容量**：一棵 QA 树 8 run × 50 步 × 4k maxTokens 的产能模型是否被产品接受（300s 墙钟 + 单连接）？

---

## 22. Human Decisions Required（人类决策单）

> 全部决策待 Human 裁决。本文档不代选。

| ID | 决策项 | 默认建议 | 后果若拒绝 |
|---|---|---|---|
| H-1 | 接受 Architecture A / B / 混合演进的哪个作为 Phase 2 架构基线？ | 混合（A 基线 + B 非冻结项并入）| 不选定则 Phase 2 无法推进实施规划 |
| H-2 | Schema authorization：Option A（零新表）还是 Option B（add-only 2-4 表）？ | MVP 零新表（A）；B 的表列后置 | 若零新表被拒，需立即评审 B 的迁移 |
| H-3 | QA-* 3 个 SSE 事件（QA_SUBTASK_CREATED / QA_ROLE_COMPLETED / QA_RELEASE_GATE_CREATED）现在授权还是延后 QA-S5？ | 延后（MVP 26==26）| 若现在授权 → FROZEN CONTRACT CHANGE（AgentEventType）+ 全链测试同步 |
| H-4 | Error Contract 保持 6 码、Gate 状态不入错误域？ | 确认 | 需另立 FROZEN CONTRACT CHANGE |
| H-5 | 编排墙钟模型：300s 默认 + 重启语义（进程内 vs durable）？ | 300s + 进程内（MVP 单 JVM），durable 入 B | 若要求 durable → 需持久化预算列（B）|
| H-6 | Gate 公式与 TIMEOUT 绝不上传 PASS 红线批准？ | 批准（R5 既有公式）| Gate 可信度下降 |
| H-7 | Gate 状态族：BLOCKED 显式化 vs INCONCLUSIVE 化？ | INCONCLUSIVE 化（改动最小）| 若选 BLOCKED → 需求变 + 域/DB 同步 |
| H-8 | project_id：Phase 2 遵循 lead_task_id 隔离根（不引入 project_id），还是单独立项做全表 project 隔离？ | 遵循 lead_task_id 根；project 单独立项 | 若坚持 Phase 2 内做 → 全 16 表 FROZEN 变更 + 独立迁移，重大工期 |
| H-9 | observe() 缺口裁决：P1（现状自包含化）/ P2（冻结变更回放）/ P3（编排层补偿）？ | P1 现值（零风险）；P2 需单独立项授权 | 若不裁决 → 编排 LLM 闭环不可宣称可用 |
| H-9bis | 执行期白名单二检：resolver 侧（非冻结，低风险，Architecture A 可含）还是 executor 侧（FROZEN CONTRACT CHANGE）？ | resolver 侧（现状风险可接受时缓做 executor）| 若都不做 → 維持现状单项目低危暴露（多 Agent 后威胁面扩大）|
| H-10 | Lead run 启动是否需显式 `POST /api/qa/orchestrations` 启动端点（vs 复用 /api/tasks）？ | 复用既有 tasks/runs 路径 | 若要新端点 → 进入 §14.2 评审 |
| H-11 | 是否需要 Gate 重新评估端点？ | 暂不需要（decide 幂等）| 若要 → 新端点设计 |
| H-12 | 是否接受「不无限扩张 /api/qa/*」执行规则（新端点单一职责 + envelope + 6 码映射）？ | 接受 | 若拒绝 → API 设计治理重新商定 |

---

## 附：审计溯源与未跟踪产物声明

> **审计溯源与基线**：审计基线为 HEAD `0545b7a`（R6 收口 commit，gate 优先级修复 + 表数断言，随其后入库；R4 基于 0545b7a 树状态审计，实际 commit 顺序为 R6=0545b7a → R4=a6844cc）。6 路并行审计（domain-model / core-runtime / tool-system / R5-implementation / infrastructure / contracts-and-state）+ 对 AgentLoop.observe() 的直接核实 + 对冻结契约测试（ContractMatrixTest/QaToolIsolationTest）的复核。
- **重要交叉验证**：R5 报告（`docs/reports/R5_QA_PHASE2_IMPLEMENTATION_REPORT.md`）、AF-001（`docs/QA_ARCHITECTURE_FREEZE.md`）、P1/Acceptance（`docs/reports/QA_P1_ACCEPTANCE.md`）、先期 Phase 2 提案（`docs/architecture/07_QA_Phase2_Architecture.md`）。
- **未跟踪产物声明**：本文件不涉及 git 未跟踪产物的创建/删除/提交；P1-5 未跟踪产物（TestPlanPage.test.tsx / QaP1_5Test.java）维持原状待人类另行裁决。

---

*End of QA Phase 2 Architecture Design. This is an architecture design document only — runtime/schema/code unchanged by R4（回归实测 2026-09-16：后端 620/0/0、前端 57/0/0、tsc 0 errors）。Implementation admission criteria per §20. Architecture acceptance does not equal implementation authorization.*