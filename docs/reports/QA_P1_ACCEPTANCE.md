# QA P1 整体 Closure / Acceptance Report（含 P1-6 Contract Matrix）

> 归属：QA P1 Sprint，Task P1-6（Acceptance / Contract Matrix）。本文档前身为 R3-B3 QA P1 Closure 文档收口。
> 状态：**QA P1 COMPLETE — WAITING FOR HUMAN ACCEPTANCE**。
> 生成时间：2026-09-16（P1-6 收口更新，覆盖 P1-5 / P1-6 实际交付与 R5 Phase 2 后续状态）。
> 前置：P1-1 ✅ / P1-2 ✅ / P1-3 ✅ / P1-4 ✅ / P1-5 ✅ / P1-6 ✅。

> **R5 交互说明**：本文档验收的是 **QA P1（P1-1..P1-6）全周期**。在 P1-6 收口窗口内，独立授权的 R5「QA Phase 2」（编排 + Release Gate，commit bf351e1，`docs/reports/R5_QA_PHASE2_IMPLEMENTATION_REPORT.md`）已在同一棵树合入，将 P1-6 的两个契约测试类扩展为断言 **R5 后契约（8 AgentType / 13 Prompt / 29 Tool）**。本文档基线采用 **最终树状态（HEAD bf351e1）**：后端 619/0/0、前端 57/0/0，即 R5 合入后的全量回归。P1-5/P1-6 两个 Task 自身的纯 P1 结果（561 / 56）也在 §5 单列。

## 1. P1 目标回顾

QA P1 的目标（`docs/QA_P1_IMPLEMENTATION_AUDIT.md` §十四 实际范围）：让 QA Agent 从"最小闭环"成长为"可被人类使用的查询/入口完整闭环"——P1 本身不引入 Phase 2 Multi-Agent / Release Gate（该部分由后续独立授权的 R5 交付）。

| Task | Audit 定义 | 交付物 |
|---|---|---|
| P1-1 | 最小闭环：AgentType → Route → Prompt → Tool → Finding | QA AgentType + qa_p001 + finding_report 复用 + AgentLoop E2E |
| P1-2 | Finding Evidence 数据质量 + Lifecycle | expected/actual 必填校验 + 最小迁移矩阵 |
| P1-3 | QA REST API | Finding 多维过滤 + GET /api/qa/findings |
| P1-4 | QA Frontend | WorkflowPage QA 入口（后端支持已就绪，补齐 UI 触达） |
| P1-5 | Isolation / Regression 深化 | 隔离回归测试补强（见 `docs/QA_P1_5_IMPLEMENTATION_REPORT.md`） |
| P1-6 | Acceptance / Contract Matrix | **本文档** + ContractMatrixTest + QaToolIsolationTest |

## 2. 各 Task 最终状态（Individually）

| Task | 实施报告 | 测试 | 状态 |
|---|---|---|---|
| **P1-1** | `docs/QA_P1_1_IMPLEMENTATION_REPORT.md` | QaP1_1Test 17/17 | COMPLETE — WAITING FOR HUMAN ACCEPTANCE |
| **P1-2** | `docs/QA_P1_2_IMPLEMENTATION_REPORT.md` | QaP1_2Test 22/22 | COMPLETE — WAITING FOR HUMAN ACCEPTANCE |
| **P1-3** | `docs/QA_P1_3_IMPLEMENTATION_REPORT.md` | QaP1_3Test 26 + QaControllerTest 13 = 39/39 | COMPLETE — WAITING FOR HUMAN ACCEPTANCE |
| **P1-4** | `docs/QA_P1_4_IMPLEMENTATION_REPORT.md` | WorkflowPage.test.tsx 6/6 | COMPLETE — WAITING FOR HUMAN ACCEPTANCE |
| **P1-5** | `docs/QA_P1_5_IMPLEMENTATION_REPORT.md` | QaP1_5Test 10 + TestPlanPage.test.tsx 3 = 13/13 | COMPLETE（§20：不单独发验收） |
| **P1-6** | **本文档** + ContractMatrixTest/QaToolIsolationTest | ContractMatrixTest 19 + QaToolIsolationTest 8 = 27/27 | COMPLETE — WAITING FOR HUMAN ACCEPTANCE |

QA 专属新增测试合计（P1-1..P1-6）：
- **后端 115 项**：QaP1_1Test 17 + QaP1_2Test 22 + QaP1_3Test 26 + QaControllerTest 13 + QaP1_5Test 10 + ContractMatrixTest 19 + QaToolIsolationTest 8
- **前端 9 项**：WorkflowPage.test.tsx 6 + TestPlanPage.test.tsx 3

> ContractMatrixTest 与 QaToolIsolationTest 由 P1-6 编写；R5 将前者扩展至 R5 后契约（C2e-h 四个 QA 团队白名单、C3=29、C5/C6=13），隔离红线断言（I 组）保持原 P1-6 内容。

## 3. P1 关键行为变化（Behavior Changes）

P1 没有只"加新东西"，还按 Audit 授权对既有 Finding 基础设施做了两处**对 Tester 冻结契约内的应用层收紧**（均有文档 + 测试守卫）：

1. **Evidence 数据质量**（P1-2）：Finding 携带 Evidence 时 expected/actual 必须非 null 非 blank → 40001。触发条件精确（`hasEvidence()`），无 Evidence 的 MANUAL 登记路径不受影响。
2. **Finding 最小 Lifecycle**（P1-2）：`updateStatus` 升级为"合法边校验"——OPEN→CONFIRMED/DISMISSED、CONFIRMED→FIXED/DISMISSED、DISMISSED/FIXED 为终态；越权迁移 40901。无新表。

**对既有调用方的影响**：8 组 Tester 测试 fixture 补齐 expected/actual（适配收紧），生产冻结文件零改动。

## 4. P1-1 stale prompt-count 断言核查（B3 专项）

核查结论：**无遗留 stale 断言**（P1-1 当时已将 PromptServiceTest / TesterPromptImportTest 更新为 5 行）。后续 R5 将 prompt 契约测试扩展至 13（新增 8 个 QA 角色 seed），same 模式持续保持——**不引入新 stale 断言**。

## 5. 测试结果（真实数字，2026-09-16 实测）

**最终树状态（HEAD bf351e1，含 R5 合入）：**

| 层 | 运行方式 | tests | failures | errors | 结论 |
|---|---|---|---|---|---|
| 后端 | `mvn -o clean test`（JDK 24） | **619** | **0** | **0** | BUILD SUCCESS |
| 后端 | JUnit 方法级随机 seed=7（`-Djunit.jupiter.execution.order.random.seed=7`） | **619** | **0** | **0** | BUILD SUCCESS |
| 后端 | 类级随机（`-Dsurefire.runOrder=random`） | **619** | **1** | **0** | BUILD FAILURE* |
| 前端 | `npx vitest run` | **57** | **0** | **0** | 9 test files passed |
| 前端 | `npx tsc --noEmit` | 0 errors | — | — | PASS |
| 前端 | `npm run build` | OK | 0 errors | — | PASS（R5 报告） |

*类级随机失败项：`RepositoryTest.eventReplayAfterIdAndRecent:315`（Expected size 3 but was 5）——**TD-QA-R-001** 已知 flaky（共享 static DB + sqlite_sequence，类级随机序下复现），已在 P1-5 基线（561/1/0）与本基线（619/1/0）**两次一致复现**。§15 明确规定不修，持续豁免登记。

**P1 纯交付基线（不含 R5）**：后端 561/0/0（P1-5 后实测）、前端 56/0/0（P1-5 后实测）。

| 里程碑 | 后端 | 前端 |
|---|---|---|
| QA Preflight | 443 | 22 |
| P1-1 后 | 490 | 22 |
| P1-2 后 | 512 | 22 |
| P1-3 后 | 551 | 22 |
| P1-4 后 | 551 | 28 |
| P1-5 后（P1 纯交付） | 561 | 56 |
| P1-6 + R5 合入后（最终树） | **619** | **57** |

| 已知豁免 | 说明 |
|---|---|
| TD-QA-R-001 `RepositoryTest.eventReplayAfterIdAndRecent` | 类级随机下偶发（共享 static DB + sqlite_sequence 不重置），顺序 full-suite 与 seed=7 双跑全绿；P1 保持豁免，已两次复现、原因一致（`Expected size 3 but was 5`） |

## 6. Scope Compliance（P1 全周期）

- ✅ **未触碰 Frozen Boundary**：AgentLoop / RunStateMachine / BudgetGuard / LoopDetector / ToolExecutor / ToolResult / ErrorCodes 零改动（P1-5 逐文件审计，P1-6 契约测试固化 Assertion）
- ✅ **schema.sql 在 P1 期间零改动**（R5 Phase 2 独立授权新增 2 表：qa_role_assignment / qa_release_gate，14→16，非 P1 行为）
- ✅ **无新增 ErrorCode**（C7 断言 40001/40401/40901/42901/50201/50000 六码冻结）
- ✅ **无平行 QA Domain**：AgentType.QA 加入既有枚举；finding_report 复用；Finding/Evidence/Repository/Service 全复用
- ✅ **P1 本身无 Phase 2**：无 QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER、无 SubRun、无 Release Gate、无 QA Dashboard（P1 范围内。Phase 2 由 R5 独立授权交付，见 §Header 说明）
- ✅ **不修改 Research / Designer / Tester 生产代码**（P1-2 仅适配 Tester 测试 fixture）
- ✅ **前端零新页面**：FindingsPage 已是 QA Finding 视图；仅 WorkflowPage 入口补全
- ✅ **TD 保持**：TD-QA-R-001（豁免，两次复现）、TD-QA-P11-001（ToolExecutor dispatch 无角色校验，Frozen Boundary 内）

## 7. 交付物清单（Deliverables）

| 类别 | 交付 |
|---|---|
| 后端主代码 | `AgentType.QA`、`DefaultAgentTypeResolver` QA 分支、`PromptService` qa_p001 种子、`FindingFilter`、`FindingRepository.findByQaFilter`、`FindingService.findByQaFilter`+`validateFilter`、`QaController` |
| 资源 | `prompts/qa_p001.md` |
| 前端 | WorkflowPage AGENT_TYPES 含 QA + agentTone QA 分支 |
| 后端测试 | `QaP1_1Test` 17 / `QaP1_2Test` 22 / `QaP1_3Test` 26 / `QaControllerTest` 13 / `QaP1_5Test` 10 / **`ContractMatrixTest` 19 / `QaToolIsolationTest` 8** |
| 前端测试 | `WorkflowPage.test.tsx` 6 / `TestPlanPage.test.tsx` 3 |
| 文档 | P1-1/2/3/4/5 实施报告 ×5 + `docs/QA_P1_5_IMPLEMENTATION_REPORT.md` + **本文档** |
| 状态 | `docs/state/task-state.md` QA 线状态落档（§8） |

## 8. task-state.md 更新

- QA 线：P1-1..P1-6 全部 COMPLETE — WAITING FOR HUMAN ACCEPTANCE。
- 全量基线更新：后端 **619/0/0**、前端 **57/0/0**（最终树，含 R5）。
- P1-5 专项：报告指向 `docs/QA_P1_5_IMPLEMENTATION_REPORT.md`，测试 QaP1_5Test 10 + TestPlanPage.test.tsx 3。
- P1-6 专项：ContractMatrixTest 19 + QaToolIsolationTest 8（R5 扩展后契约）。
- R5 线：`R5_QA_PHASE2_IMPLEMENTATION_REPORT.md` 已单列（bf351e1）。
- NEXT ACTION：R3 P1 Gate 人工验收（QA P1-1..P1-6）→ 通过后 R3 主线关闭完成。

## 9. 附录：验收 Checklist（人工验收用）

- [x] QA P1-1：QA AgentType/Route/Prompt/Tool/Finding 闭环（测试 17/17）
- [x] QA P1-2：Evidence 质量 + Lifecycle 矩阵（22/22；无迁移表；40901 越权迁移）
- [x] QA P1-3：多维过滤 8 维 + QaController（39/39）
- [x] QA P1-4：WorkflowPage QA 入口（6/6；后端零改动）
- [x] QA P1-5：隔离回归深化（QaP1_5Test 10 + TestPlanPage 3；A1-A3/B1/C1-C2/D1/E1-E2/F1）
- [x] QA P1-6：Contract Matrix 冻结断言（ContractMatrixTest 19：C1 AgentType 枚举 / C2 白名单矩阵 / C3 Tool 注册 / C4 finding_report 隔离 / C5-C6 Prompt 13 / C7 ErrorCodes / C8 子集互斥 / C9 Prompt 三线）
- [x] QA P1-6：隔离红线（QaToolIsolationTest 8：qa_* 泄漏守卫 / 注册守卫 / fallback 永不 QA / prompt 路由隔离 / finding_report 共享边界 / 显式路由）
- [x] P1-1 stale prompt-count 断言无遗留
- [x] 全量回归（最终树）：后端 619/0/0（顺序）+ 619/0/0（seed=7）；类级随机 619/1/0 = TD-QA-R-001 豁免；前端 57/0/0；tsc 0 errors
- [x] Frozen Boundary 零改动；P1 内 schema 零改动；无新增 ErrorCode
- [x] P1 范围内无 Phase 2 功能泄漏（Phase 2 = 独立 R5 授权交付）
- [x] 各 Task 独立实施报告齐备
- [x] task-state.md 状态落档