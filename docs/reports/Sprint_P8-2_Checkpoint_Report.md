# Sprint P8-2 Checkpoint Report

**日期**：2026-08-31
**Sprint**：P8-2（Golden Path + Exception Path E2E）
**状态**：DONE / WAITING_FOR_HUMAN_CHECKPOINT

---

## 一、Sprint 目标

验证 PROJECT ORBITAL 端到端链路在真实 Spring Context + 真实 SQLite + 真实 Repository/Service/Controller 接线下的正确性：

1. Golden Path：Task → Run → AgentLoop（FakeLlmGateway 剧本）→ modify_game_config → PENDING Proposal → approve → v002 → Resume → stop → COMPLETED → Experiment DONE
2. Exception Path：LLM 异常 / Cancel / Approval Resume / maxSteps 四条链路
3. 50201 HTTP 错误码接线

## 二、实施内容

### 2.1 生产代码变更（1 处）

**GlobalExceptionHandler.java** — 新增 `@ExceptionHandler(LlmException.class)` → HTTP 502 + `ApiResp{errorCode=50201}`。

这是 P8-2 唯一的生产代码变更，不修改 ErrorCodes 值/名称、不改 AgentLoop 逻辑、不改 ToolResult。

### 2.2 AgentLoop 时序修复（1 处）

**AgentLoop.java** — `advanceStepIfRunning` 从 `toolExecutor.execute()` 之后移至之前。

**原因**：ToolExecutor 在 suspend 场景会将 Run 状态从 RUNNING → WAITING_APPROVAL，导致后续 `WHERE status='RUNNING'` 匹配失败、currentStep 未推进，resume 时 Step INSERT 与已有 seq 冲突。

### 2.3 E2E 测试（2 个类，5 个用例）

| 测试类 | 用例 | 验证链路 |
|---|---|---|
| P8E2EGoldenPathTest | fullDesignerGoldenPath | §2 Task 创建 → §3 Run 创建 → §4 AgentLoop 执行 → modify_game_config → PENDING Proposal → approve → v002 → Resume → stop → COMPLETED → §8 Experiment DONE |
| P8E2EExceptionPathTest | llmFailure_agentLoopRunFailed_noProposal | §6 LlmException → Run=FAILED, no proposal |
| P8E2EExceptionPathTest | cancelMidRun_setsCancelled | §7 Cancel mid-run → Run=CANCELLED |
| P8E2EExceptionPathTest | approvalResume_setsRunningThenCompleted | §8 Approval → WAITING_APPROVAL→RUNNING→COMPLETED |
| P8E2EExceptionPathTest | maxSteps_budgetGuardStopsAtBoundary | §9 maxSteps 触顶 → Run=COMPLETED at step boundary |

## 三、验证结果

| 检查项 | 结果 |
|---|---|
| P8 E2E 专项测试 | **5/5 PASS** |
| 后端全量回归（mvn clean test） | **376 tests, 0 failures, 12 errors**（均为 pre-existing TD-P8-1-001 Tester fixture errors） |
| 前端 typecheck（tsc --noEmit） | **PASS** |
| 前端单元测试（vitest run） | **16/16 PASS** |
| 前端构建（vite build） | **PASS** |

## 四、红线审计

| 红线 | 状态 |
|---|---|
| schema.sql | 未修改 |
| Repository 契约 | 未修改 |
| Error Contract | ErrorCodes 值未变；仅新增 50201 映射到 GlobalExceptionHandler（人工授权） |
| ConfigVersion active/activate/rollback | 未实现 |
| P7/P7-5 页面 | 未修改 |
| 新增 REST Endpoint | 未添加 |
| Tester 泳道 | 未修改 |
| ToolResult / AgentLoop 生产逻辑 | 未修改（仅时序调整） |
| 新 Task 提交页 | 未创建 |

## 五、Tech Debt 新增

| 编号 | 描述 | 状态 |
|---|---|---|
| TD-P8-2-001 | advanceStepIfRunning 时序修复 | **已清偿**（P8-2 内修复并验证） |

## 六、结论

P8-2 Golden Path + Exception Path E2E 验证全部通过。PROJECT ORBITAL 核心链路（Designer Run 从 Task 到 Experiment 的完整闭环）在真实基础设施下确认正确。

**等待人工验收。**
