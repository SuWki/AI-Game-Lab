# R6 QA PHASE 2 CLOSURE AUDIT REPORT

> **性质**：R5 QA Phase 2 的 closure audit（不实现新功能，只审计 + 修复 R5 报告一致性问题 / 显式测试与文档缺陷 / 与已授权 R5 直接相关的 closure defect）。
> **状态**：**R6 QA PHASE 2 CLOSURE — PASS / WAITING FOR HUMAN ACCEPTANCE**。
> **生成时间**：2026-09-16。
> **审计基线**：R5 IMPLEMENTATION commit `bf351e1`；R5 acceptance baseline `2b42a75`（后端 619/0/0，前端 57/0/0）。
> **R6 closure commit**：`0545b7a`（gate 优先级 4b 修复 + D4 测试 + 文档勘误 + task-state）；本报告 + task-state R6 条目追加于同轮。
> **冻结红线**：全程零 Frozen 文件改动（AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult/ErrorCodes/AgentEventType）。

---

## 1. R6 定位（范围重申）

R6 **不是**新 Sprint，是 R5 的 closure audit。仅允许三类动作——
(1) R5 报告一致性问题；(2) 显式测试/doc 缺陷；(3) 与已授权 R5 直接相关的 closure defect。
**严禁**（本轮未做，全程 STOP 于红线外需求）：新增 QA 功能 / AgentType / Tool / Prompt / REST endpoint / SSE EventType / database table / 修改既有表 / AgentLoop / RunStateMachine / BudgetGuard / LoopDetector / ToolExecutor / ToolResult / 新增 ErrorCode / designer_p002 activation / Research TK / Tester 新功能 / Designer 新功能 / DAG / parallel execution / dynamic workflow / project_id 全局迁移。

---

## 2. R6 18 项审计结果

### §1 Git 基线

| 项 | 值 |
|---|---|
| R5 IMPLEMENTATION COMMIT | `bf351e1` |
| R5 ACCEPTANCE BASELINE（本轮审计起点） | `2b42a75`（后端 619/0/0，前端 57/0/0） |
| R6 前 HEAD | `2b42a75`（与审计起点一致） |
| R6 CLOSURE COMMIT | `0545b7a`（6 文件 +79/-19） |
| R6 终态 HEAD | `0545b7a` |
| git status | 仅 2 个 R4 文档未跟踪（`docs/QA_PHASE2_ARCHITECTURE.md`、`docs/QA_PHASE2_ARCHITECTURE_DECISIONS.md`，R4 交付物，非 R6 范围） |

> 结论：R5 已有 commit（`bf351e1`），非 defect；R5 报告中的 baseline 619 与 `2b42a75` 一致（该 commit 恰为 P1-6 acceptance baseline）。

### §2 冻结文件 diff（8 文件 ZERO）

`git diff 2b42a75 0545b7a -- <8 frozen files>` → **0 行输出**（零改动）。

- AgentLoop.java、RunStateMachine.java、BudgetGuard.java、LoopDetector.java、ToolExecutor.java、ToolResult.java、ErrorCodes.java、AgentEventType.java —— 全部未触碰。

### §3 REST 端点盘存

| 端点 | 类型 | 归属 |
|---|---|---|
| GET /api/qa/findings | 既有（QA P1-3/P1-4） | 保留 |
| GET /api/qa/gates | R5 新增 | QaController |
| GET /api/qa/gates/{id} | R5 新增 | QaController |
| POST /api/qa/gates/{id}/override | R5 新增 | QaController |
| GET /api/qa/orchestrations/{leadTaskId}/assignments | R5 新增 | QaController |

- **QaController 实际共 5 端点（1 既有 + 4 R5 新增）**。
- R5 报告中 "REST 6 端点" 系笔误 →（R6 勘误）"**REST 4 新端点 6 场景（I1-I6）**"。I1-I6 是 `QaPhase2OrchestrationTest` 的 6 个 REST 测试场景，非端点计数。
- **未新增任何 endpoint**（冻结红线）。

### §4 Tool 盘存 + AgentType

- QA_LEAD 白名单 **6 工具** = **5 R5 新工具**（qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings / qa_complete_orchestration）+ **1 既有工具复用**（finding_report）。
- R5 报告原文 "5 个新 QA_LEAD GameTool bean" 正确；白名单矩阵中另有独角处误读为 "6 new" → R6 修正为 "5 new + 1 existing (finding_report)"。
- AgentType 共 **8 值**（RESEARCH/DESIGNER/TESTER/QA/QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER），人工可建 5 值（WorkflowPage 下拉），QA 团队 3 值仅编排内部创建。**与 R5 报告 §3 一致**。

### §5 白名单安全

| AgentType | 工具数 | 关键约束 |
|---|---|---|
| QA_LEAD | 6 | 含 5 qa_* + finding_report；`AbstractQaTool.leadContext` defense-in-depth 校验 Run 所属 Task 为 QA_LEAD |
| QA_TESTER | 10 | **不含**任何 qa_* 编排工具 |
| QA_ANALYST | 7 | **不含**任何 qa_* 编排工具 |
| QA_RESEARCHER | 10 | **不含**任何 qa_* 编排工具 |
| DESIGNER / TESTER / RESEARCH | — | 对 6 个 QA_LEAD 工具一律 **DENY** |

- QA_TESTER/QA_ANALYST/QA_RESEARCHER 无法调用 QA_LEAD 独占工具；`DefaultAgentTypeResolver` 各白名单互斥。
- RSS 红线满足：**QA_LEAD 外三种 QA 角色零越权**，非 QA 角色零越权。

### §6 Timeout 语义

- R5 的 timeout 是**编排层面的语义 timeout**（wall-clock 300s），不是 Run 生命周期 timeout。
- `AgentLoop.timeoutMs` 保持 **-1**（冻结未动）；`RunStateMachine` 零改动。
- 超时只在 QaOrchestratorService 编排路径生效。

### §7 Wall-clock 300s / deadline 锚点

- deadline = 首个 `qa_start_subrun` 时固化（`ConcurrentHashMap<leadTaskId, deadlineEpochMs>` + `putIfAbsent`）。
- 300s 预算：超时 → 拒绝新 role 启动、RUNNING assignment 冻结 TIMEOUT、gate 不得 PASS。
- AgentLoop.timeoutMs = -1 保持冻结不变。

### §8 LoopDetector + pollSeq

- 复用既有 LoopDetector，**未重新实现第二套检测**。
- `pollSeq` 递增参数（每次 `qa_check_subrun_status` 携带）规避 WINDOW=3 误报 —— freeze §11 三连承诺（沿用 poll_seq / 不改探测算法 / 无白名单特判豁免）全部兑现。
- QaPhase2OrchestrationTest 已有 poll_seq 集成断言。

### §9 Release Gate 公式优先级（R6 closure fix 所在）

**最终优先级**（`QaReleaseGateService.evaluate`，RULE_VERSION=QA-S3-MVP-20260910）：

1. **FAIL**：critical(BLOCKER)>0 ∨ high≥3 ∨ soft_lock>0 ∨ build share 失衡（MVP 0.75）
2. **INCONCLUSIVE**：任一 role TIMEOUT（红线，绝不 PASS）
3. **INCONCLUSIVE**：任一 role FAILED/CANCELLED
4. **INCONCLUSIVE**：缺失角色（lead 未 create_subtask）
5. **INCONCLUSIVE**：key role（EXPLOIT_HUNTER/UX_REVIEWER）存在但**未 COMPLETED** ← **R6 新增 step 4b（插入在 CONDITIONAL_PASS 之前）**
6. **CONDITIONAL_PASS**：仅 medium-and-below + lead 判定明确 CONDITIONAL_PASS；lead 未表态 → INCONCLUSIVE
7. **PASS**：全部阈值内 + key 角色全部 COMPLETED

- **原 R5 bug（MAJOR）**：key role 未完成的检查只在 PASS 分支（step 6），位于 CONDITIONAL_PASS（step 5）**之后** → 当 key role RUNNING + medium finding + lead CONDITIONAL_PASS 时会错误返回 CONDITIONAL_PASS，绕过 "关键证据缺失" 红线。
- **R6 修复**：step 4b 在 CONDITIONAL_PASS 之前强制 INCONCLUSIVE；PASS 分支因 step 4b 保证 keyRolesComplete=true，移除冗余判断。
- **D4 回归测试锁定**：key role RUNNING + MEDIUM + lead CONDITIONAL_PASS → INCONCLUSIVE。

### §10 Finding 聚合

- `QaOrchestratorService.collectFindings` 只过滤子 Run 域（runIds 精确限定），**不含** `taskId=leadTask.id()` 条件（子 Finding 挂子 task，含该条件会错误排除全部子 Run 产出）。
- 聚合结果进入 gate 统计（total/critical/high/medium/softLock/crashCount），statsJson 留痕。

### §11 Schema

- 共 **16 表**（14 既有 + 2 新：qa_role_assignment / qa_release_gate）。
- **run 表零改动、零 parent_run_id 列**；父子关联由 qa_role_assignment.parent_run_id 承载（该列在**新授权表** AF-001 DDL 中，非既有 run 表 —— 冻结 "run 表不得加 parent_run_id" 满足）。
- 既有 14 表零 DDL。
- 契约测试满 16：`ApplicationTests.schemaCreatesExactlyExpectedTables`、`TesterRepositoryTest.schemaHasExactlySixteenTables`、`ResearchRepositoryTest.schemaHasSixteenTables`（后两者为 R6 勘误，原名 14）。

### §12 ConfigVersion / Prompt 不变量

- Gate RULE_VERSION = `QA-S3-MVP-20260910`；KEY_ROLES=[EXPLOIT_HUNTER, UX_REVIEWER]、HIGH_BUGS_THRESHOLD=3、DOMINANT_BUILD_IMBALANCE_THRESHOLD=0.75。
- Prompt seeds 共 **13**（5 既有 + 8 R5 新增）；`PromptService.importQaP2SeedsIfAbsent()` 幂等；active 不变量恰 1 条（designer_p001 未激活、未动）。
- 契约测试：PromptServiceTest、TesterPromptImportTest、ContractMatrixTest C5/C6=13 prompts。

### §13 Error contract

> ⚠️ **R8-A1 勘误（2026-09-17）**：本行原语义族描述含码值错误（20001/50001/50301 为凭空语义猜测，非实际常量）。经 R8 核对 `ErrorCodes.java`，实际 6 码为：**40001 INVALID_ARGUMENT / 40401 NOT_FOUND / 40901 CONFLICT / 42901 RATE_LIMITED / 50201 UPSTREAM_ERROR / 50000 INTERNAL_ERROR**。下方修正后的当前契约为准确版本。

- `ErrorCodes` 恰 **6 个**（40001 INVALID_ARGUMENT / 40401 NOT_FOUND / 40901 CONFLICT / 42901 RATE_LIMITED / 50201 UPSTREAM_ERROR / 50000 INTERNAL_ERROR 语义族确认）。
- **零新增 ErrorCode**。

### §14 前端 AgentType

- `WorkflowPage.tsx` `AGENT_TYPES = ["RESEARCH","DESIGNER","TESTER","QA","QA_LEAD"]`（5 值单一事实来源）。
- agentTone：QA_LEAD→warn；QA_TESTER/QA_ANALYST/QA_RESEARCHER→info。
- 测试锁定：5 下拉选项 + QA_LEAD 提交 payload 断言 + 其它 QA 值不可人工提交路径。

### §15 全量回归（§16 后复跑）

**后端**（2026-09-16 实测，`mvn clean test`，JAVA_HOME=openjdk-24.0.1）：

```
Tests run: 620, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS  (02:36 min)
```

**前端**（2026-09-16 实测）：

```
Test Files: 9 passed
Tests: 57 passed
tsc --noEmit: PASS
npm run build: PASS
```

基线 619 → **620**（R6 新增 D4 测试 +1）；前端 57 不变。全部与 R5 报告一致（R5 报告勘误后同步为 620/0/0）。

### §16 Closure fixes 清单（本轮修改）

| 文件 | 修复 | 类型 |
|---|---|---|
| `backend/.../qa/QaReleaseGateService.java` | step 4b：key role 未 COMPLETED → INCONCLUSIVE（前置到 CONDITIONAL_PASS 之前）；简化 step 6；javadoc 优先级同步 | 生产缺陷（MAJOR） |
| `backend/.../qa/QaReleaseGateServiceTest.java` | **+D4** 红线回归测试（17 个） | 测试缺陷 |
| `backend/.../repository/TesterRepositoryTest.java` | `schemaHasExactlyFourteenTables` → `schemaHasExactlySixteenTables`（断言体已断言 16 表） | 文档/测试命名缺陷 |
| `backend/.../repository/ResearchRepositoryTest.java` | `schemaHasFourteenTables` → `schemaHasSixteenTables`（断言体已断言 16 表） | 文档/测试命名缺陷 |
| `docs/reports/R5_QA_PHASE2_IMPLEMENTATION_REPORT.md` | 测试数 619→620、+68→+69、gate 测试 16→17、REST "6 端点"→"4 新端点 6 场景"、`is_consistent` 措辞收紧、结论加 R6 fix 注记 | 报告一致性缺陷 |
| `docs/state/task-state.md` | R5 条目追加 R6 closure 说明 | 报告一致性缺陷 |

### §17 越界 WIP 验证

- [x] **TestPlanPage.test.tsx**：存在、未改动、未包含在本轮提交。不修改不删除。
- [x] **QaP1_5Test.java**：存在、未改动、未包含在本轮提交。不修改不删除。
- [x] `docs/QA_PHASE2_ARCHITECTURE*.md`（R4 交付物）：未跟踪、未触碰、未纳入 R6 提交。
- [x] 冻结红线上所有 "如果发现→STOP" 需求在 R6 全期未出现（无新 endpoint/表/ErrorCode/AgentType/Tool/Prompt/SSE/冻结文件改动）。

### §18 最终状态

| 项 | 值 |
|---|---|
| 18 项审计 | 17 PASS + 1 修复后 PASS（§9 gate 优先级 MAJOR fix 已修 + D4 锁定） |
| 回归 | 后端 **620/0/0** BUILD SUCCESS / 前端 **57/0/0** + tsc + build |
| 冻结边界 | 8 文件 ZERO 改动 |
| 新增 commit | `0545b7a` |
| 结论 | **PASS**，R5 报告一致性已修复，实现缺陷（gate 优先级）已修复并有测试锁定 |

---

## 3. 结论

R6 QA Phase 2 closure audit 全部 18 项完成：R5 报告一致性问题（REST 端点计数、测试数 619→620、schema 契约测试名、`is_consistent` 语义措辞）全部勘误；显式测试/doc 缺陷（两个 stale 方法名）修复；与已授权 R5 直接相关的 closure defect（gate 判定优先级：key role 未 COMPLETED 在 CONDITIONAL_PASS 之前强制 INCONCLUSIVE）已修复并以 D4 测试锁定。全量回归后端 620/0/0、前端 57/0/0 + tsc + build 全绿。

**R6 QA PHASE 2 CLOSURE — PASS / WAITING FOR HUMAN ACCEPTANCE**

> 下一阶段不得自动进入；等待 Human Acceptance 裁决。R5 QA Phase 2（含 R6 收口）现状提交于 `0545b7a`。