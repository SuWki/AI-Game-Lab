# QA-S1 Task Tree（输出 D）

> 2026-11-12 范围与状态说明：本文件保留对应阶段的设计/决策与准入条件，不能以其中历史表数、无编排、无回放或预算成功描述判断当前实现。当前模块入口见 [QA 设计](projects/04_AI_Game_Agent_Team/README.md)，共享 Runtime 的已授权变更见 [ADR](decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)。新生产评估以模块演进设计为准，旧 Gate 指标不因此重解释。

> 状态：**DEFINED（设计）— 不执行**。执行须待：① ARCHITECTURE FREEZE 人工批准 → ② PRE-FLIGHT CLEARANCE（PF-001~PF-004 全过）→ ③ QA-S1 独立人工授权。
> 任务数量按**真实架构依赖**推导（S1-1~S1-10，10 个实现单元），非凑数；每 Task 含九字段模板。
> 依据：`docs/QA_ARCHITECTURE_FREEZE.md`（AF-001）A/B/E/F/G 全部节。
> 边界重申：本轮**只设计不执行**；QA AgentType 扩展仅 `DefaultAgentTypeResolver`；AgentLoop/Designer/Tester/Research/Repository/Error Contract 零修改。

---

## 0. QA-S1 范围与非目标

**范围内（S1-1 ~ S1-10）**：AgentType 枚举 + Resolver、8 Prompt 导入、2 张表 DDL（人工闸门）、4 编排工具、QaOrchestratorService（sequential）、QaReleaseGateService、Contract Matrix 断言、编排 E2E、红线负例。

**非目标（明确排除）**：
- PlayerProfile 枚举扩展（3 人格纯 Prompt 驱动，V2 不属本里程碑）
- 新 SSE 事件（QA_* → QA-S5；MVP 复用现有 26 事件）
- 前端 QA 页（→ QA-S5）
- Run 级 timeout / 修改 `AgentLoop.timeoutMs`（编排层 deadline 兜底）
- Release Manager 独立 Agent（并入 QA Lead）
- Builder（DESIGN ONLY）
- 不扩展 `finding.source` / ErrorCodes / AgentEventType

**依赖 DAG 一览**：
```
S1-1 AgentType ─┬─► S1-4 Resolver ──► S1-5 编排工具 ─┬─► S1-6 Orchestrator ──► S1-9 Sequential E2E ──► S1-10 红线负例
S1-2 DB DDL ────┘      │                            └─► S1-7 ReleaseGate ──┘
S1-3 Prompt ───────────┴─► S1-8 Contract 断言
```

---

## S1-1 · AgentType 枚举扩展（QA_* 4 值）

- **Task ID**：`S1-1`
- **Purpose**：`AgentType` 增加 `QA_LEAD / QA_TESTER / QA_ANALYST / QA_RESEARCHER`（总数 3→7），作为 Resolver 扩展的枚举基础。
- **Dependencies**：无。
- **Files/modules**：`backend/src/main/java/com/aigamelab/runtime/AgentType.java`。
- **Allowed changes**：枚举追加 4 值（附注释：QA_LEAD=编排/门控；QA_TESTER=Player/Exploit；QA_ANALYST=Combat/Numerical；QA_RESEARCHER=UX）。
- **Forbidden changes**：`DefaultAgentTypeResolver`（本 Task 不动）、AgentLoop、`resolveFromConstraints` 行为。
- **Acceptance Criteria**：`AgentType.values()` 长度为 7；顺序为 DESIGNER/TESTER/RESEARCH/QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER（值语义注释正确）。
- **Tests**：`runtime/AgentTypeResolverTest` 新增枚举存在断言（后续 S1-4 扩展同一测试类）。
- **Risk**：低。穷举 switch 尚无 QA 分支前，`valueOf("QA_LEAD")` 可通过但 Resolver 未支持 → S1-4 前不得暴露给任何运行路径。

---

## S1-2 · 两张新表 DDL（人工闸门）

- **Task ID**：`S1-2`
- **Purpose**：落地 `qa_role_assignment`（6 态）+ `qa_release_gate`（字段定稿见 AF-001 §14）及索引；这是**唯一 schema migration Task**，须先提请人工批准。
- **Dependencies**：无（但实现落在 QA-S1 内，前置为冻结批准）。
- **Files/modules**：`backend/src/main/resources/schema.sql`（**追加** QA 分节，既有 14 表零修改）；`backend/src/test/java/com/aigamelab/ApplicationTests.java`（`EXPECTED_TABLES` 14→16 唯一改动）。
- **Allowed changes**：追加两段 `CREATE TABLE IF NOT EXISTS` + 4 索引（idx_qra_parent / idx_qra_subtask / idx_qrg_task / idx_qrg_config）；ApplicationTests 表数断言 14→16（对齐 T-DB1 先例）。
- **Forbidden changes**：**禁止**修改既有 14 表任何列/约束；**禁止**执行 DDL（本轮只计划，实施在 QA-S1 授权后）。
- **Acceptance Criteria**：schema 初始化后 16 表；新表外键/默认值/索引与 AF-001 §14 DDL 逐字段一致；`EXECUTE` 状态迁移合法（assignment 6 态、gate status PENDING 起始）。
- **Tests**：`repository/QaRepositoryTest`（新）：两表 CRUD、FK 指向 run/task 正确、gate 默认 PENDING、索引存在（对齐 T-DB1 裁决 §七 九项要求）。
- **Risk**：中（schema 改动须人工授权）。缓解：完全对齐审计 §6.2 + AF-001 定稿，且是 QA-S1 唯一 DB Task。

---

## S1-3 · 8 个 QA Prompt 种子导入

- **Task ID**：`S1-3`
- **Purpose**：`backend/src/main/resources/prompts/` 新增 8 个 `qa_*.md` 种子 + `PromptService` 幂等导入（inactive），供 Resolver/编排运行时按角色加载。
- **Dependencies**：无（可并行 S1-1/S1-2）。
- **Files/modules**：`resources/prompts/qa_lead_p001.md`、`qa_player_novice_p001.md`、`qa_player_aggressive_p001.md`、`qa_player_build_specialist_p001.md`、`qa_combat_analyst_p001.md`、`qa_numerical_analyst_p001.md`、`qa_exploit_hunter_p001.md`、`qa_ux_reviewer_p001.md`；`llm/PromptService.java`（新增 `QA_SEED_VERSIONS` + `importQaSeedsIfAbsent()`，仿 `importTesterSeedIfAbsent` 幂等）。
- **Allowed changes**：Prompt 文件新建；`PromptService` 追加导入方法（构造器调用点追加，保持唯一 active=designer_p001）。
- **Forbidden changes**：tester_p001 / designer_p001 内容与 active 状态；`findByVersion` 契约；qa_* 设为 active。
- **Acceptance Criteria**：空库启动后 prompt_version 行 = 3 原 + 8 QA = 11；qa_* 全部 inactive；唯一 active 仍为 designer_p001；进程重启幂等不重复导入。
- **Tests**：`llm/QaPromptImportTest`（新，仿 TesterPromptImportTest）：导入 8 / 均 inactive / active 唯一 / 重启幂等。
- **Risk**：低。注意 Prompt 文本是"角色行为"关键件 —— 评审将作为独立验收点（每条铁律可断言存在）。

---

## S1-4 · DefaultAgentTypeResolver 扩展（QA 唯一着陆点）

- **Task ID**：`S1-4`
- **Purpose**：Resolver 穷举 switch 增加 QA 分支：`systemPrompt(type)`（L120-131）→ 返回 qa_* 版本名；`toolWhitelist(type)`（L141-147）→ 返回 §6 白名单（6/10/7/10）；`resolveFromConstraints` L179 允许值消息含 4 个 QA 值。
- **Dependencies**：S1-1（枚举值）、S1-3（prompt 版本常量可引用）。
- **Files/modules**：`runtime/DefaultAgentTypeResolver.java`。
- **Allowed changes**：仅 Resolver 内部 switch + 错误消息；QA_TESTER/QA_RESEARCHER 白名单 Set 定义（QA_LEAD=6、QA_TESTER=10、QA_ANALYST=7、QA_RESEARCHER=10）。
- **Forbidden changes**：AgentLoop（L242-250 委托不动）；`resolveFromConstraints` 逻辑（仅消息文案）；DESIGNER/TESTER/RESEARCH 既有分支与 Set。
- **Acceptance Criteria**：7 个 AgentType 各返回非空 prompt + 合法工具集；QA 白名单与 Contract Matrix 完全一致（6/10/7/10）；`resolveFromConstraints("agent_type":"QA_LEAD")` 成功、非法值错误消息列全 7 值。
- **Tests**：`runtime/AgentTypeResolverTest` 扩展：7 分支穷举（每个 type 断言 prompt 非空 + tool 非空 + 名称集合）；非法值 40001 消息断言。
- **Risk**：低-中。穷举 switch 编译保证；唯风险是白名单遗漏工具名（编译期不查）→ 由 S1-8 Contract Matrix 断言兜底。

---

## S1-5 · 4 个 QA 编排工具实现

- **Task ID**：`S1-5`
- **Purpose**：实现 `qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings` 四工具（SPI 复用既有 GameTool；错误翻译仿 `AbstractTesterTool` 模式，但不继承之 —— AF-001 §6）。
- **Dependencies**：S1-2（表存在）、S1-4（白名单含 4 工具名）。
- **Files/modules**：新增 `tool/qa/QaCreateSubtaskTool.java`、`QaStartSubrunTool.java`、`QaCheckSubrunStatusTool.java`、`QaCollectFindingsTool.java`（或 `qa/` 包）；共用参数解析/异常翻译小工具（**不新建 AbstractQaTool 基类**，AF-001 裁决）。
- **Allowed changes**：新增 4 工具 + 注册；复用 `RunControlService`/`RunRepository`/`FindingService`/`ExperimentService` 既有服务读接口。
- **Forbidden changes**：`ToolRegistry`/`ToolExecutor`/`AgentLoop`；`AbstractTesterTool` 及其异常语义；任何既有工具。
- **Acceptance Criteria**：
  - create_subtask：非法 role / 重复子任务 / 缺 configVersion → BusinessException；成功 → subtask id + assignment(PENDING)。
  - start_subrun：subTaskId 不存在/已启动 → 抛错；成功 → 子 Run（注入角色 Prompt+Tools）+ assignment.sub_run_id 回填 + PENDING→RUNNING。
  - check_subrun_status：subRunId 不存在 → 抛错；子 Run FAILED 正常返回 status；tokensUsed 正确。
  - collect_findings：聚合 subTaskIds 指向子 Run 的 Finding + Experiment + Claim；失败角色缺 finding 不抛错。
- **Tests**：`tool/qa/QaOrchestrationToolsTest`（新）：每工具 nameAndSchema / 成功 / 参数非法 / 领域错误路径；`ToolRegistryIntegrationTest` 追加 4 注册断言（总数 23→27）。
- **Risk**：中。异常翻译若照搬 AbstractTesterTool 会制造 Tester 域耦合 → 用独立小工具实现相同模式（非继承），避免 TD-QA-PROP-001 的"平行契约"指控。

---

## S1-6 · QaOrchestratorService（Sequential 编排运行时）

- **Task ID**：`S1-6`
- **Purpose**：编排运行时：按冻结顺序（Lead→Player1/2/3→Combat→Numerical→Exploit→UX）驱动子任务创建/启动/轮询/收口；实现**编排层 deadline**（谁设置、谁检测、TIMEOUT 落 assignment）、`poll_seq` 防 LoopDetector、6 态回写（以 run 行为准）。
- **Dependencies**：S1-5（工具）、S1-2（表）。
- **Files/modules**：新增 `qa/QaOrchestratorService.java`（`@Service`）；新增 `qa/QaOrchestratorConfig.java`（wallClockBudget 默认 300s 可覆盖）。
- **Allowed changes**：新增编排服务（复用 `RunControlService.start`、`AgentEventPublisher` 只读消费、`RunRepository` 查询）；`qa_role_assignment` 写回。
- **Forbidden changes**：`RunControlService`（不加 join/通知）、`AgentLoop`（不加超时）、LoopDetector、Hikari 连接池。
- **Acceptance Criteria**：
  - 顺序固定且可枚举断言；失败角色不中断编排（部分结果语义）；
  - deadline 触发 → assignment=TIMEOUT，子 Run 行不改，编排继续；
  - `qa_check_subrun_status` 轮询参数带 poll_seq（同 subRunId 多轮 poll 不触发 LoopDetector）；
  - 终态回写 COMPLETED/FAILED/TIMEOUT/CANCELLED + result_summary。
- **Tests**：`qa/QaOrchestratorServiceTest`（新）：顺序 / 失败继续 / timeout 检测 / poll_seq 规避 LoopDetector（连续 3 次 poll 不判死父 Run）/ assignment 同步语义。
- **Risk**：高（核心逻辑）。缓解：deadline 可配置、部分结果语义明确、poll_seq 防误杀为强制验收点。

---

## S1-7 · QaReleaseGateService（自动判定 + INCONCLUSIVE）

- **Task ID**：`S1-7`
- **Purpose**：服务端自动判定（`automatic_result`），读 findings + 子 Run 终态，按 AF-001 §13.3 阈值产出 PASS/CONDITIONAL_PASS/FAIL/INCONCLUSIVE；**绝不**因子 Run 超时/失败自动 PASS（红线）。
- **Dependencies**：S1-6（终态）、S1-2（gate 表）。
- **Files/modules**：新增 `qa/QaReleaseGateService.java`；复用 `FindingRegressionService`（可选 baseline 回归）。
- **Allowed changes**：新增门控服务；写 `qa_release_gate`（status/automatic_result/lead_decision/is_consistent/final_result/manual_override* 字段）。
- **Forbidden changes**：`FindingService`/`FindingRegressionService` 契约；run 状态机；Lead Run 状态写（Gate 与 Run 正交）。
- **Acceptance Criteria**：
  - 阈值判定正确（FAIL on critical/soft_lock/失衡；PASS on 全过；CONDITIONAL_PASS on 仅 medium 以下）；
  - 关键角色 TIMEOUT/FAILED → INCONCLUSIVE（非 PASS）；
  - `is_consistent` 正确记录 auto vs lead；`final_result` 初始 PENDING 直至人工审阅。
- **Tests**：`qa/QaReleaseGateServiceTest`（新）：四态判定矩阵、超时角色→INCONCLUSIVE、manual_override 字段写入与审计。
- **Risk**：中。阈值草案 QA-S3 校准；QA-S1 用已知失衡/健康版本对抗验证（S1-9/10 覆盖）。

---

## S1-8 · Contract Matrix 五处一致性断言

- **Task ID**：`S1-8`
- **Purpose**：将 AF-001 B 节 Contract Matrix 固化为测试断言，五处（§14/§20.1/Prompt/Resolver/Task Tree）一致性由 CI 守护，防"文档对、代码错"漂移。
- **Dependencies**：S1-3（prompt 文件）、S1-4（resolver）。
- **Files/modules**：新增 `qa/ContractMatrixTest.java`（或并入 AgentTypeResolverTest）。
- **Allowed changes**：仅测试文件。
- **Forbidden changes**：任何生产代码（本 Task 纯断言）。
- **Acceptance Criteria**：8 角色 → AgentType / prompt 文件 / 白名单集合三线一一匹配（硬编码矩阵 == Resolver 输出）；6/10/7/10 白名单总量；7 AgentType / 8 Prompt / 4 新工具计数断言。
- **Tests**：`qa/ContractMatrixTest`（新）全绿；与 S1-4 ResolverTest 互补。
- **Risk**：低。一旦 matrix 变化测试红 → 强制先改文档再改码（工作流 §41）。

---

## S1-9 · Sequential 编排集成测试（QA-S1 主验收）

- **Task ID**：`S1-9`
- **Purpose**：真实链路 E2E：Lead（QA_LEAD）经真实 `RunControlService`/`AgentLoop`（未改）驱动 7 子任务 → collect → gate 判定 → report 落 design_document。
- **Dependencies**：S1-6、S1-7、S1-4、S1-3。
- **Files/modules**：新增 `qa/QaSequentialEndToEndTest.java`。
- **Allowed changes**：仅测试；使用 QA fixture（PF-002 产出，**不继承 AbstractRepositoryTest**）。
- **Forbidden changes**：生产代码；既有测试夹具。
- **Acceptance Criteria**：健康/失衡两配置各自跑通顺序编排；finding 可经 `qa_collect_findings` 聚合；gate 输出与阈值一致；report 落库；编排完成后 Lead Run 终态与 gate 判定解耦成立。
- **Tests**：`QaSequentialEndToEndTest`（2 场景 × 断言矩阵）。
- **Risk**：中-高（跨层）。缓解：逐 Task 依赖已固，E2E 前各单元全绿；使用 FakeGameEnvironment 确定性。

---

## S1-10 · 红线负例（timeout ≠ PASS / LoopDetector / 部分结果）

- **Task ID**：`S1-10`
- **Purpose**：显式负例守护冻结红线：**Child run timeout → Parent PASS 绝不发生**；poll_seq 规避假阳性；部分结果正确标记。
- **Dependencies**：S1-9。
- **Files/modules**：新增 `qa/QaRedlineNegativeTest.java`。
- **Allowed changes**：仅测试。
- **Forbidden changes**：为"让测试过"而放宽任一红线逻辑（红线本身就是验收，不得弱化）。
- **Acceptance Criteria**：
  1. 注入子 Run TIMEOUT（编排 deadline 强制触发）→ assignment=TIMEOUT、gate automatic_result ∈ {FAIL, INCONCLUSIVE}，**绝无 PASS**；
  2. 连续 3 次同 subRunId poll 不触发 LoopDetector（poll_seq 生效）；
  3. 部分角色失败 → collect 不抛错、Lead 报告含失败角色清单、gate 非 PASS。
- **Tests**：`QaRedlineNegativeTest`（≥3 用例，每条对应一条红线）。
- **Risk**：低（测试）；意义是锁死最关键的 QA 正确性边界。

---

## 验收关卡与 Checkpoint

- **执行方式**：连续 Sprint（S1-1→S1-2→S1-3→S1-4→S1-5→S1-6→S1-7→S1-8→S1-9→S1-10），按 AGL 工作流 v2 Checkpoint 单收口；S1-2 前置**人工 DDL 闸门**。
- **AUTO_ACCEPT 8 项 checks**：编译 PASS / 专项全绿 / full-suite 回归（基线=PF-004）零新增失败 / 无越界（红线清单）/ 无未登记技术债 / 文档回写 / git diff scope audit / 报告齐备。
- **不满足即停**：QA-S1 任何 Task 失败 → 修复后重跑；触碰冻结边界 → 立即 STOP 并回报人工。

---

**STOP：QA-S1 Task Tree 设计完毕。不得自动进入实现 —— 等待「ARCHITECTURE FREEZE 人工批准」→「PRE-FLIGHT CLEARANCE」→「QA-S1 独立授权」三级放行。**