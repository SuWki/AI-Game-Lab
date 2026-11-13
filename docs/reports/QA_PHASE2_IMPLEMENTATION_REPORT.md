# QA PHASE 2 IMPLEMENTATION REPORT

> **归属**：QA Phase 2 — 基于人类架构验收（Architecture A 主线 + B 非冻结部分）+ R7 审计的收敛硬化实现轮。
> **状态**：**QA PHASE 2 IMPLEMENTATION — COMPLETE / WAITING FOR HUMAN ACCEPTANCE**。
> **生成时间**：2026-09-17。
> **前置**：R6 Closure `0545b7a`（ACCEPTED）；R7 Architecture & Roadmap Audit `f61e0a8`（docs-only，WAITING FOR HUMAN DECISION → 已授权实施本阶段收敛项）。
> **回归基线**：后端 620/0/0，前端 57/0/0 + tsc 0（R7 审计基线）。
> **实现后终态**：后端 **628/0/0**（+8 新测试），前端 57/0/0 + tsc 0。
> **冻结红线**：全程零 Frozen 文件改动（AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult/ErrorCodes/AgentEventType），零 schema 改动，零新 ErrorCode，零新 SSE 契约，零新 QA_* 端点。
> **实现 commit**：`813e33b`（仅 4 文件：3 源码/测试 + 本报告）。

---

## 1. Summary

本阶段不重构、不重设计——对 R5 既有 Phase 2 运行时（编排 + Gate）做**收敛、硬化、闭环、契约收口**。依据 R7 审计发现的 3 个 Phase 2 关联缺陷 + 实现指令 §11/§12/§14/§18 要求，共实施 **3 项源码变更 + 8 项集成测试**，全部变更落于 **3 个文件**（+180/-2 行）：

| 变更 | 文件 | 内容 |
|---|---|---|
| ① Gate INCONCLUSIVE 域闭合（R7 QA-04 / §11） | [QaReleaseGate.java](backend/src/main/java/com/aigamelab/domain/QaReleaseGate.java) | `STATUSES` 常量补入 `"INCONCLUSIVE"`（已存在于 AUTOMATIC_RESULTS，正被写入 status 列） |
| ② 跨 lead 隔离硬化（§14）+ fallback 修正（R7 QA-03） | [QaOrchestratorService.java](backend/src/main/java/com/aigamelab/qa/QaOrchestratorService.java) | `checkSubrunStatus` 增加归属校验；fallback 改用 `findByLeadTask`（修复 task/run id 混淆） |
| ③ deadline 测试基础设施（§12） | [QaOrchestratorService.java](backend/src/main/java/com/aigamelab/qa/QaOrchestratorService.java) | 包私有 `setDeadlineForTest()`，不改生产语义 |
| ④ 8 项集成测试（§10/§12/§13/§18 治理验证） | [QaPhase2OrchestrationTest.java](backend/src/test/java/com/aigamelab/qa/QaPhase2OrchestrationTest.java) | J/K/L/M/N 组，覆盖域闭合、隔离、deadline、child 预算、cancel 限制 |

**Scope 红线全部守住**：零 Frozen 文件、零 schema 表、零 ErrorCode、零 SSE EventType、零新 API 端点、零前端改动。

---

## 2. Git 基线

| 项 | 值 |
|---|---|
| 前 HEAD | `f61e0a8`（R7 docs-only 审计基线） |
| 回归基线 | 后端 620/0/0，前端 57/0/0 + tsc 0 |
| 实现 commit | `813e33b`（4 文件 +331/-2；前 HEAD `f61e0a8`） |
| 变更文件 | 仅 3 文件（QaReleaseGate.java +1/-1，QaOrchestratorService.java +19/-2，QaPhase2OrchestrationTest.java +161/0） |
| 终态回归 | 后端 **628/0/0** BUILD SUCCESS；前端 **57/0/0** + tsc 0 |
| 工作区其他变更 | 其他会话 staged 变更（.java-version / README / BUILD_ENVIRONMENT / scripts / R6-R7 勘误 / task-state / tech-debt / FindingRepository / TestPlanRepository）——本阶段不触碰 |

---

## 3. 实现细节

### 3.1 Gate INCONCLUSIVE 域闭合（R7 QA-04 修复，§11 域一致性）

**问题**：`QaReleaseGateService.evaluate()` 超时/缺失角色路径返回 `automaticResult="INCONCLUSIVE"`，`QaOrchestratorService.completeOrchestration()` 将其作为 **status 与 automatic_result 双参数**传入 `gateRepo.decide()` 并落库到 status 列；但 `QaReleaseGate.STATUSES = [PENDING, PASS, CONDITIONAL_PASS, FAIL]` 不含该值——域不闭合，下游过滤/校验契约不一致。

**修复**：`STATUSES` 补入 `"INCONCLUSIVE"`。这不是新状态名（INCONCLUSIVE 已存在于 AUTOMATIC_RESULTS 且正被写入 status 列，§11 禁止新增状态名，此处为既有写值补入域定义）。schema 层 status TEXT 列无 CHECK 约束，DB 兼容无需迁移。

```java
public static final java.util.List<String> STATUSES = java.util.List.of(
        "PENDING", "PASS", "CONDITIONAL_PASS", "FAIL", "INCONCLUSIVE");
```

**域闭合验证**（测试 J1）：`AUTOMATIC_RESULTS ⊆ STATUSES` 成立；`overrideGate` 的 finalResult 校验（QaController）同样接受 INCONCLUSIVE。

### 3.2 checkSubrunStatus 跨 lead 隔离 + fallback 修正（§14 / R7 QA-03）

**问题 1（隔离）**：`checkSubrunStatus(leadTask, subRunId)` 回写路径未验证 assignment 归属——若传入其他 lead 的子 Run id，可越权回写其 assignment 状态（写对方 lead 的 assignment.status 列）。

**修复**：回写前验证 `leadTask.id().equals(a.leadTaskId())`，不归属则抛 `40401 notFound`（K1 测试锁定）。

**问题 2（R7 QA-03 fallback）**：assignment 未按 sub_task 命中时，fallback 曾用 `findByParentRun(leadTask.id())`——把 **Task ID 当 Run ID** 传给 parent run 查询，语义错误（当前主路径恒命中，缺陷不可达但属潜在错误查询）。

**修复**：fallback 改用 `findByLeadTask(leadTask.id())` 并按 `subRunId` 精确过滤（K2 测试锁定回归：主路径不受影响）。

### 3.3 deadline 测试基础设施（§12）

`WALL_CLOCK_BUDGET_MS=300_000`（冻结语义，进程内 ConcurrentHashMap，R5 §9）不可在测试中等待。新增**包私有** `setDeadlineForTest(leadTaskId, deadlineEpochMs)`，仅用于测试构造超时场景；生产路径 deadline 仍由首次 `startSubrun` 固化，语义零改变。

---

## 4. 测试（+8，总计 628）

| 组 | 测试 | 验证目标 |
|---|---|---|
| J | `gateStatusDomainClosure` | §11 域闭合：STATUSES ⊇ AUTOMATIC_RESULTS |
| J | `gateResultInconclusiveOnTimeoutPersisted` | 红线：deadline 超时 → gate 持久化为 INCONCLUSIVE，**绝不 PASS** |
| K | `checkSubrunStatusCrossLeadIsolation` | §14 隔离：跨 lead 访问 → 40401 |
| K | `checkSubrunStatusOwnLeadStillWorks` | 回归护栏：本 lead 主路径不受隔离校验影响 |
| L | `startSubrunRejectedWhenDeadlineExceeded` | §12：deadline 超时 → 拒绝启动新 role（40901） |
| L | `completeOrchestrationFreezesRunningOnDeadline` | §12：deadline 超时 → RUNNING 冻结 TIMEOUT → gate INCONCLUSIVE |
| M | `childRunBudgetAllocated` | §18：child 预算 50 steps / 100000 tokens 正常分配（不 bypass BudgetGuard） |
| N | `cancelDoesNotCascadeToChildren_documentedLimitation` | §13：取消不级联（孤儿风险文档化，不做级联取消） |

**红线验证结论**：`TIMEOUT → INCONCLUSIVE`（非 PASS）路径由 J2/L2 双测试锁定；`QaReleaseGateService.evaluate()` 优先级链复核确认 FAIL > TIMEOUT→INCONCLUSIVE，不存在 TIMEOUT→PASS 路径。

---

## 5. Contract Audit（Step F 结果）

| 维度 | 结果 | 证据 |
|---|---|---|
| Frozen Boundary（8 文件） | ✅ PASS | `git diff` 零输出（AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult/ErrorCodes/AgentEventType） |
| Schema | ✅ PASS | `git diff schema.sql` 零输出，0 新表（H-2） |
| ErrorCode | ✅ PASS | 6 码不变：40001/40401/40901/42901/50201/50000（H-4） |
| SSE EventType | ✅ PASS | 26 值不变，零新增 QA_* 契约（H-3） |
| API Surface | ✅ PASS | /api/tasks 复用（H-10）；无 reevaluate 端点（H-11）；/api/qa/* 保持 5 端点（H-12）；QaController/RunController diff 零输出 |
| Project Isolation | ✅ PASS | Gate/assignment 仓库仅 `findByLeadTask` / `findByLeadTaskAndRole` / `findByLeadTaskAndConfig` 作用域查询，无裸 findAll（H-8） |
| Frontend | ✅ PASS | 零改动；现有前端仅消费 /api/qa/findings，无 gate/assignment 消费（§16 正确行为，不建新页面） |

**变更边界**：仅 3 文件 +180/-2（QaReleaseGate +1/-1；QaOrchestratorService +19/-2；QaPhase2OrchestrationTest +161/0）。

---

## 6. 修复的 R7 审计项

| R7 项 | 严重度 | 状态 | 处理 |
|---|---|---|---|
| QA-02 deadline 零测试覆盖 | MEDIUM | ✅ **已修复** | L/J 组 4 项测试覆盖拒绝/冻结/INCONCLUSIVE |
| QA-03 checkSubrunStatus fallback id 混淆 | LOW | ✅ **已修复** | 改用 findByLeadTask + subRunId 过滤（K 组测试） |
| QA-04 INCONCLUSIVE 域不闭合 | LOW | ✅ **已修复** | STATUSES 补入（J1 测试） |
| QA-06 normalizeLeadDecision 误判 | INFO | ⏸️ **保持 defer** | 子串匹配 `"NOT INCONCLUSIVE — PASS"` 误判为 INCONCLUSIVE；语义否定检测需 regex 边界 vs 语义判断，属模糊修复，不进本阶段 |
| SP-01 / TD-02 ToolExecutor dispatch 无白名单校验 | HIGH | ⏸️ **保持 defer** | ToolExecutor 为冻结文件；白名单已在 resolver 侧（DefaultAgentTypeResolver.toolWhitelist + AgentLoop.selectToolSpecs）生效，是本阶段批准的强制边界（H-9bis） |

---

## 7. Deferred / Known Limitations

| 项 | 来源 | 状态 |
|---|---|---|
| 编排 deadline 进程内（ConcurrentHashMap），JVM 重启丢失 | FB-04 / R-02 | ⏸️ 已知限制，MVP 单 JVM 语义；迁移 DB 为后续 Phase 候选（R7 §16 H 决策项） |
| cancel 不级联 → child 孤儿风险 | §13 | ⏸️ 文档化（N1 测试断言 gate 见 RUNNING child → INCONCLUSIVE），不做级联取消 |
| observe() 零历史回放，多轮闭环不成立 | FB-02 | ⏸️ DEFERRED——AgentLoop 冻结，**不声明 replayable 多轮 LLM 编排能力**（§36） |
| RUN_TIMEOUT 事件缺口 | FB-01 | ⏸️ 当前不可达（timeoutMs=-1），启用时间预算前需补 AgentEventType |
| dominantBuildShare 恒 null（build share 失衡分支永不触发） | QA-01 | ⏸️ 需 EvaluationService 接线，属 Phase 3 范围 |
| normalizeLeadDecision 子串误判 | QA-06 | ⏸️ defer（见 §6） |
| qa_release_gate 唯一约束缺失 | TD-R7-012 | ⏸️ schema 变更超本阶段范围（H-2 0 新表） |

---

## 8. Tech Debt 记录

- **td-inconclusive-statuses-closure**：已修复（QA-04）。域闭合作为既有写值的定义补全，非新状态名。
- **td-cross-lead-writeback-guard**：已修复（§14）。`checkSubrunStatus` 归属校验为 read/write 路径提供最小隔离栅栏。
- **td-tool-executor-whitelist**（SP-01）：**保留**。冻结文件不改；resolver 侧白名单为强制边界。若后续需全局 ToolExecutor 校验，须独立 Task 提影响分析（遵循 Tester 异常映射技术债处置原则）。
- **td-deadline-process-local**（FB-04）：**保留**。已知限制，DB 化迁移需新表或字段变更，按 Phase 3 候选处理。

---

## 9. 未做的事（Anti-scope）

本阶段**未**实施（§32 禁令 + §33 STOP 条件遵守）：未建新 DB 表；未改 frozen 文件；未新增 ErrorCode / SSE 契约 / QA_* 端点；未做 DAG / parallel / dynamic workflow；未做全局 ToolExecutor 重构；未做 project_id 全局迁移；未构建"未来可能需要的架构"；未删除或重写 R5 既有实现。

---

## 10. 验收建议

- **回归**：`JAVA_HOME="C:\Users\Administrator\.jdks\openjdk-24.0.1" mvn -o clean test` → 628/0/0；前端 `npm run test` + `tsc` → 57/0/0 + 0。
- **重点核对**：J2/L2 红线测试（TIMEOUT→INCONCLUSIVE 非 PASS）；K1 隔离测试；R6/R7 报告中已修复项的标记同步。
- **决策项**：QA-06、SP-01（ToolExecutor）、deadline DB 化、project_id 迁移等仍为待人工决策项（R7 §16），本阶段未越权推进。
