# R5 QA PHASE 2 IMPLEMENTATION REPORT

> **归属**：R5 — QA Phase 2（编排 + Release Gate）。
> **状态**：**R5 QA PHASE 2 IMPLEMENTATION — COMPLETE / WAITING FOR HUMAN ACCEPTANCE**。
> **生成时间**：2026-09-16。
> **前置**：R4 Architecture Design + QA Architecture Freeze 验收通过（AGL-T2/T4/T5 FROZEN 边界内），人类授权 R5 进入。
> **基线**：后端 551/0/0，前端 48/0/0（R3 P2 验收基线）。

---

## 1. Summary

R5 QA Phase 2 按 24 章节规定性指令完成：

| 交付物 | 状态 | 章节 |
|---|---|---|
| 2 张新 DB 表（qa_role_assignment / qa_release_gate） | ✅ | §2 |
| 4 个新 AgentType（QA_LEAD/TESTER/ANALYST/RESEARCHER） | ✅ | §3 |
| 5 个新 QA_LEAD GameTool bean | ✅ | §4 |
| 8 个新 prompt seed 文件 | ✅ | §5 |
| QaOrchestratorService（确定性顺序编排） | ✅ | §6 |
| QaReleaseGateService（gate 公式） | ✅ | §7 |
| QA 编排 REST 端点 + TaskController 扩展 | ✅ | §8 |
| 前端镜像新 AgentType | ✅ | §9 |
| 测试覆盖（单元 + 集成 + 回归） | ✅ | §10 |
| 冻结边界验证 + Git 基线 | ✅ | §11 |

**Scope 红线全部守住**：零 Frozen 文件改动（AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult/ErrorCodes/AgentEventType），零既有 14 表改动。

---

## 2. Schema（§3 硬约束：只增不改）

`schema.sql` 新增 2 表，既有 14 表零改动：

**qa_role_assignment**（父子关联锚点，run 表零改动）：
- 6 态 status：PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED
- 唯一索引 `idx_qra_lead_role(lead_task_id, role)`（同 lead + 同角色只允许一条）

**qa_release_gate**（一棵 QA 树对一个 config_version 一条）：
- status：PENDING/PASS/CONDITIONAL_PASS/FAIL；automatic_result 额外含 INCONCLUSIVE
- 索引 `idx_qrg_task/config/status`

Schema 契约测试同步更新：`ApplicationTests.schemaCreatesExactlyExpectedTables`、`TesterRepositoryTest.schemaHasExactlySixteenTables`、`ResearchRepositoryTest.schemaHasSixteenTables` 从 14 → 16 表（均为 R5 规定性增量，非漂移）。

---

## 3. AgentType（4 个新值，共 8 值）

`AgentType` 枚举新增 QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER。人工可创建 5 值（WorkflowPage 下拉：RESEARCH/DESIGNER/TESTER/QA/QA_LEAD）；其余 3 值由 QA_LEAD 编排内部创建（`QaOrchestratorService.createSubtask`），不暴露给人类入口。

---

## 4. QA_LEAD GameTool 白名单（6 工具）

`DefaultAgentTypeResolver.QA_LEAD_TOOL_NAMES`：

| 工具 | 参数 | 职责 |
|---|---|---|
| qa_create_subtask | role, configVersion | 为角色建子任务 + PENDING assignment |
| qa_start_subrun | subTaskId | 启动子 Run（wall-clock deadline 拒绝超时后启动） |
| qa_check_subrun_status | subRunId, pollSeq | 轮询子 Run 终态并回写 assignment |
| qa_collect_findings | （无） | 聚合跨角色 Finding |
| qa_complete_orchestration | configVersion, leadDecision, leadDecisionEvidence? | 触发 Gate 计算并落库 |
| finding_report | （既有 Tester 工具） | 子 Run 上报 Finding |

防御性权限复核：`AbstractQaTool.leadContext(ctx)` 校验调用 Run 所属 Task 的 agent_type=QA_LEAD，否则拒绝（freeze §17）。

**白名单矩阵**（R5 §6）：

| AgentType | 工具数 |
|---|---|
| QA_LEAD | 6 |
| QA_TESTER | 10 |
| QA_ANALYST | 7 |
| QA_RESEARCHER | 10 |

工具总数：24 → **29**（`ToolRegistryIntegrationTest.totalToolCount` 同步更新）。

---

## 5. Prompt Seeds（8 个新文件，共 13 个版本）

`resources/prompts/` 新增：qa_lead_p001、qa_player_novice_p001、qa_player_aggressive_p001、qa_player_build_specialist_p001、qa_combat_analyst_p001、qa_numerical_analyst_p001、qa_exploit_hunter_p001、qa_ux_reviewer_p001。

`PromptService` 新增 `importQaP2SeedsIfAbsent()` 幂等导入。Prompt 版本总数 5 → **13**（契约测试同步更新：PromptServiceTest、TesterPromptImportTest）。active 不变量保持恰 1 条（designer_p001 不动）。

---

## 6. QaOrchestratorService（确定性顺序编排）

- **顺序**（freeze §7 唯一路径）：Lead → Player1(NOVICE) → Player2(AGGRESSIVE) → Player3(BUILD_SPECIALIST) → Combat → Numerical → Exploit → UX → Aggregate → Gate
- **LLM 只表达意图，本服务确定性执行**：Task/Run 创建、状态机、timeout、budget、role assignment、final gate result 全部由服务决定
- **子 Run = 普通 run 行**（freeze §8），父子关联由 qa_role_assignment 承载
- **Wall-clock 预算 300s**（R5 §9）：deadline 由 `ConcurrentHashMap<leadTaskId, deadlineEpochMs>` 管理，首次 start_subrun 固化；超时 → 拒绝新 role 启动、RUNNING assignment 冻结 TIMEOUT、gate 不得 PASS。AgentLoop.timeoutMs 保持 -1（冻结不动）
- **poll_seq 机制**（R5 §11）：每次 qa_check_subrun_status 携带递增 pollSeq，避免 LoopDetector WINDOW=3 误报

**循环依赖修复**：QaOrchestratorService ↔ AgentLoop ↔ ToolExecutor ↔ ToolRegistry（工具自动注册）构成构造期环。改为 `ObjectProvider<AgentLoop>` 延迟注入，`startSubrun` 内 `getObject()` 使用——不触碰任何冻结文件。

**collectFindings 修复**：过滤不再携带 `taskId=leadTask.id()`（子 Finding 挂子 task 而非 lead task，该条件会错误排除全部子 Run 产出），runIds 已精确限定子 Run 域。

---

## 7. QaReleaseGateService（§10 / freeze §13.3 公式）

`RULE_VERSION = "QA-S3-MVP-20260910"`，HIGH_BUGS_THRESHOLD=3，DOMINANT_BUILD_IMBALANCE_THRESHOLD=0.75，KEY_ROLES_FOR_PASS=[EXPLOIT_HUNTER, UX_REVIEWER]。

**判定优先级**（红线：child timeout ≠ PASS）：
1. hard FAIL：critical(BLOCKER)>0 ∨ high≥3 ∨ soft_lock>0 ∨ build share 失衡
2. INCONCLUSIVE：任一 role TIMEOUT（红线，绝不 PASS）
3. INCONCLUSIVE：任一 role FAILED/CANCELLED
4. INCONCLUSIVE：缺失角色 / key role 未 COMPLETED
5. CONDITIONAL_PASS：仅 medium finding + lead 明确 CONDITIONAL_PASS；lead 未表态 → INCONCLUSIVE
6. PASS：全部阈值内 + key 角色 COMPLETED

`is_consistent` 标记 **pass-class 结果（PASS/CONDITIONAL_PASS）与 lead 判定的一致**（audit 语义；FAIL/INCONCLUSIVE 时恒为 0）；statsJson 记录 total/critical/high/medium/softLock/crashCount 统计留痕。

Gate 与 Lead Run 状态**正交**——Lead Run COMPLETED 不等价于过 Gate（防假等价）。

---

## 8. REST 端点

`QaController`（保留既有 /api/qa/findings）新增：

| 端点 | 说明 |
|---|---|
| GET /api/qa/gates | 列表（可选 leadTaskId/configVersion/status 过滤） |
| GET /api/qa/gates/{id} | 单条（不存在 40401） |
| POST /api/qa/gates/{id}/override | 人工覆盖（finalResult 白名单校验，非法 40001） |
| GET /api/qa/orchestrations/{leadTaskId}/assignments | 角色 assignment 列表 |

`TaskController` 更新错误消息允许 8 个 AgentType（DESIGNER/TESTER/RESEARCH/QA/QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER）；researchSourceTaskId 仍仅 DESIGNER 可携带。

`QaReleaseGateRepositoryImpl.overrideDecision` 修复 SQL 参数计数 bug（5 占位符原只传 4 参数 → status/final_result 均置 finalResult）。

---

## 9. 前端

`WorkflowPage.tsx`：`AGENT_TYPES = ["RESEARCH","DESIGNER","TESTER","QA","QA_LEAD"]`（人工可建 5 值单一事实来源）；agentTone 新增 QA_LEAD→warn、QA_TESTER/QA_ANALYST/QA_RESEARCHER→info。测试同步：5 下拉选项断言 + QA_LEAD 提交 payload 断言。

---

## 10. 测试结果

### 后端（回归基线）

```
Tests run: 620, Failures: 0, Errors: 0, Skipped: 0
```

新增测试（基线 551 → 620，+69）：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| QaReleaseGateServiceTest | 17 | 纯函数 gate 公式 A-G 全矩阵（PASS/FAIL/INCONCLUSIVE/CONDITIONAL_PASS/consistent/stats）+ D4 key role 未完成红线 |
| QaPhase2OrchestrationTest | 15 | 全链路集成：白名单注入、createSubtask、40901 冲突、startSubrun、checkSubrunStatus 回写、gate PASS/FAIL、poll_seq LoopDetector、REST 4 新端点 6 场景（I1-I6） |
| ContractMatrixTest | （扩展） | C2e-h 四 AgentType 白名单、C3=29 工具、C4 finding_report 隔离、C5/C6=13 prompts |
| QaToolIsolationTest | （扩展） | QA 工具隔离验证 |

### 前端

```
Test Files: 9 passed
Tests: 57 passed
tsc --noEmit: PASS
npm run build: PASS
```

### 冻结边界（§11 验证）

```
git diff --stat 8 个冻结文件 → 零改动 ✅
既有 14 张表：零 DDL 改动 ✅（16 = 14 + 2 新增）
```

---

## 11. 已知限制（延后项）

| 限制 | 说明 | 处置 |
|---|---|---|
| Wall-clock deadline 进程内 | 重启后丢失（MVP 单 JVM 语义） | 已知，架构 freeze 接受 |
| DAG / parallel / dynamic workflow | MVP 只实现顺序编排 | 架构 freeze §7 明确延后 |
| QA 编排无人工审批门 | Lead Run 通过 AgentLoop 既有 WAITING_APPROVAL 机制 | 复用既有，不新增 |
| 子 Run token/step 预算 | 复用子 Task maxSteps=50 + tokenBudget=100000 | 与既有 Task 语义一致 |

---

## 12. 交付物清单

**新文件（后端）**：QaRoleAssignment/QaReleaseGate domain、QaRoleAssignmentRepository(+Impl)、QaReleaseGateRepository(+Impl)、qa/ 包（QaOrchestratorService、QaReleaseGateService、AbstractQaTool、5 个工具）、8 个 prompt seed。

**修改文件（后端）**：schema.sql（+2 表）、AgentType、DefaultAgentTypeResolver、PromptService、TaskController、QaController、QaReleaseGateRepositoryImpl、6 个契约测试（16 表/13 prompt/29 工具）。

**修改文件（前端）**：WorkflowPage.tsx、WorkflowPage.test.tsx。

---

## 13. 结论

R5 QA Phase 2 全部规定性交付物完成，回归全绿（后端 620/0/0，前端 57/0/0），冻结边界零违规，实现过程中的两处真实缺陷（构造期循环依赖、collectFindings taskId 过滤）均已修复并有测试锁定；R6 closure audit 追加修复 gate 判定优先级（key role 未完成在 CONDITIONAL_PASS 前强制 INCONCLUSIVE），测试锁 D4。

**R5 QA PHASE 2 IMPLEMENTATION — COMPLETE / WAITING FOR HUMAN ACCEPTANCE**
