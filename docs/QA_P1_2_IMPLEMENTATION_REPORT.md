# QA P1-2 实施报告（Finding Evidence 数据质量 + Finding 最小生命周期）

> 归属：QA P1 Sprint，Task P1-2（`QA P1-1 — ACCEPTED` 后按 Task 输入实施）。
> 状态：**QA P1-2 COMPLETE — WAITING FOR HUMAN ACCEPTANCE**。
> 生成时间：2026-09-10。

## 1. Status

```text
QA P1-2 COMPLETE — WAITING FOR HUMAN ACCEPTANCE
```

- 完整回归（顺序 full-suite）：**512 tests / 0 Failures / 0 Errors**；方法级随机 seed=7 亦全绿；类级随机仅 R-001 既有豁免项复现（TD-QA-R-001，与 P1-1 基线逐项一致，非本次引入）。
- 未触碰 Frozen Boundary；未修改 schema.sql；未新增任何 DB 表；未新增 ErrorCode；未重写 FindingFingerprintService；未改动 PF-003 configVersion 校验。
- 达成方式：**纯应用层约束收紧**——`FindingService` 的两处追加校验 + `FindingReportTool` 的 schema 必填声明，Finding/Evidence 17+7 字段结构保持不变。

## 2. 目标

> 让现有 Finding/Evidence 成为可靠的 QA 基础设施：Evidence 不再携带无意义对比内容；Finding 状态流转受最小合法迁移矩阵约束。不改动 QA 最小闭环架构。

## 3. 实现改动清单（Changed Files）

| 文件 | 改动 | 类型 |
|---|---|---|
| `backend/src/main/java/com/aigamelab/tester/FindingService.java` | ① `validateExpectedActual`/`hasEvidence`：携带 Evidence 时 expected/actual 非 null 非 blank（40001）；② `LEGAL_TRANSITIONS` 最小迁移矩阵 + `updateStatus` 从白名单直写升级为"合法边校验"（越权 40901） | 主代码 |
| `backend/src/main/java/com/aigamelab/tester/FindingReportTool.java` | jsonSchema：`expected`/`actual` 加入 required 列表，description 声明"携带 Evidence 时必填"（JsonSchemaValidator 在 ToolExecutor dispatch 前置拒绝） | 主代码 |
| `backend/src/test/java/com/aigamelab/qa/QaP1_2Test.java` | **新增** 22 项验收测试（A-Evidence 9 / B-Lifecycle 合法 5 / C-Lifecycle 非法 6 / D-事务一致性+P1-1 回归 2） | 测试 |
| `backend/src/test/java/com/aigamelab/tester/ConfigVersionTestPlanEvidenceIntegrationTest.java` | finding_report 调用补 expected/actual（2 处） | 测试 fixture |
| `backend/src/test/java/com/aigamelab/tester/TesterExperimentIntegrationTest.java` | finding_report 调用补 expected/actual | 测试 fixture |
| `backend/src/test/java/com/aigamelab/tester/TesterLongRunBudgetLoopIntegrationTest.java` | finding_report 调用补 expected/actual | 测试 fixture |
| `backend/src/test/java/com/aigamelab/tester/TestReportExperimentResultIntegrationTest.java` | finding_report 调用补 expected/actual（2 处） | 测试 fixture |
| `backend/src/test/java/com/aigamelab/tester/TesterEndToEndTest.java` | finding_report 调用补 expected/actual | 测试 fixture |
| `backend/src/test/java/com/aigamelab/tester/TesterChallengeTest.java` | finding_report 调用补 expected/actual（2 处） | 测试 fixture |
| `backend/src/test/java/com/aigamelab/tester/TesterEventPublishTest.java` | finding_report 调用补 expected/actual（2 处） | 测试 fixture |
| `backend/src/test/java/com/aigamelab/tester/TesterWriteToolsTest.java` | testReportCreate 前置 finding_report 补 expected/actual | 测试 fixture |

**未改动**（全部复用）：`Finding`、`Finding.Evidence`（17+7 字段结构冻结不动）、`FindingRepository`、`schema.sql`、`FindingFingerprintService`、`ConfigVersionService`（PF-003 校验原样）、`AgentLoop`、`ToolExecutor`、`RunStateMachine`、`BudgetGuard`、`LoopDetector`、`ToolResult`、`ErrorCodes`、前端。

## 4. Evidence 实际约束（P1-2.1）

**不新增字段**（Evidence 保持 confirmed 7-field 结构：sessionId/runId/toolCallIds/observationSnapshot/tick/seed/overrides）。

实际收紧点（[FindingService.validateExpectedActual](../backend/src/main/java/com/aigamelab/tester/FindingService.java)）：

1. **触发条件**：仅当 Finding **确实携带 Evidence** 时校验。`hasEvidence()` 判定标准：`evidenceJson` 非 null、非 blank、非 `"{}"`，且可反序列化为 `Evidence`、`sessionId` 非 blank。
2. **约束内容**：
   - `expected`：null 或 blank（含纯空白 `"   "`）→ **DENY 40001**；
   - `actual`：null 或 blank（含纯空白 `"   "`）→ **DENY 40001**。
3. **不误伤既有路径**（可证明的事实来源，非凭空新增）：
   - 无 Evidence 登记的 MANUAL Finding（`evidenceJson="{}"`，expected/actual 为 null）仍放行 —— `TesterServicesTest.manualFinding_withoutEvidence_allowed` 既有已验证行为；
   - `evidenceJson=null` 亦走无 Evidence 路径，不受影响（QaP1_2Test A8/A9 守护）。

### 4.1 finding_report schema 同步

[FindingReportTool.jsonSchema](../backend/src/main/java/com/aigamelab/tester/FindingReportTool.java) 的 required 由原来的 8 项扩为 10 项：

```text
前: [sessionId, scenarioId, configVersion, severity, category, title, reproSteps]
后: [sessionId, scenarioId, configVersion, severity, category, title, expected, actual, reproSteps]
```

即 LLC（ToolExecutor 前置 JsonSchemaValidator）现在就拒绝 missing expected/actual 的调用，Service 层再兜底拒绝 blank 值。双层防御：**工具参数层拒绝缺参，Service 业务层拒绝空参**。

## 5. Finding Lifecycle：实际状态枚举 + 合法迁移矩阵（P1-2.2）

### 5.1 实际状态枚举（以代码为准，非 Task 示意）

`Finding.STATUSES = [OPEN, CONFIRMED, DISMISSED, FIXED]`（[Finding.java](../backend/src/main/java/com/aigamelab/domain/Finding.java)）。

### 5.2 实际迁移矩阵（[FindingService.LEGAL_TRANSITIONS](../backend/src/main/java/com/aigamelab/tester/FindingService.java)）

| 当前状态 | 合法目标 | 说明 |
|---|---|---|
| `OPEN` | `CONFIRMED`, `DISMISSED` | 人工确认 或 驳回 |
| `CONFIRMED` | `FIXED`, `DISMISSED` | 确认后标记已修复；驳回已确认项 |
| `DISMISSED` | （无）**终态** | 不可再迁移（不回退、不复活） |
| `FIXED` | （无）**终态** | 不可再迁移（含 FIXED→OPEN 回退） |

**事实来源**（按要求以仓库当前状态、Service 行为、已有测试、文档为权威，Task 内的 OPEN→CONFIRMED/OPEN→DISMISSED/CONFIRMED→FIXED/DISMISSED→OPEN 仅是示意）：
- 人类已 ACCEPTED 的 `docs/QA_P1_IMPLEMENTATION_AUDIT.md` 权威迁移矩阵（LINE 125-126）：合法边 OPEN→CONFIRMED / OPEN→DISMISSED / CONFIRMED→FIXED / CONFIRMED→DISMISSED（"驳回已确认项"）；非法边 OPEN→FIXED（未确认不得直接修复）、CONFIRMED→OPEN（已确认不可回退）、DISMISSED→* 与 FIXED→*（终态不可再迁移）。
- **示意矩阵中 "DISMISSED→OPEN 合法" 与 Audit 权威矩阵冲突，已裁决：以 Audit 为准，DISMISSED 为终态**（同时与 FIXED 终态对称）。
- 应用层用 `Map<当前状态, Set<合法目标>>` 表达，**无迁移表、无 lifecycle 表、无状态历史表**，schema.sql 零改动。

### 5.3 校验位置

`updateStatus(id, status)`（原为"白名单直写"）：

```
require(id)                          // 不存在 → 40401；且以真实当前状态为校验源
→ status ∈ Finding.STATUSES          // 否 → 40001（既有行为不变）
→ from = 当前状态（null 兜底 OPEN）
→ from ∈ LEGAL_TRANSITIONS && 目标 ∈ 合法目标集   // 否 → 40901（状态机违规）
→ findingRepo.updateStatus(id, status)  // 仅合法边到达写库
→ require(id) 回读返回
```

## 6. 错误行为（Error Contract）

| 情形 | ErrorCode | 输出 | 测试 |
|---|---|---|---|
| expected/actual 为 null/blank（携带 Evidence） | `40001 INVALID_ARGUMENT` | `expected 必填（…不得为 null/blank）` / `actual 必填（…）` | A2-A7 |
| 状态值不在白名单（如 `WONTFIX`） | `40001 INVALID_ARGUMENT` | `status 非法: …` | B5 |
| 非法状态迁移 | `40901 CONFLICT` | `Finding 状态迁移非法: from → to（合法迁移边: …）` | C1-C5 |
| Finding 不存在 | `40401 NOT_FOUND` | `Finding 不存在: id` | C6 |
| Evidence 非携带场景 | 不触发新校验 | 走既有规则 | A8/A9 |

**未新增任何 ErrorCode。** 既有错误体系足够表达：
- 参数/数据内容非法 → `40001`（现有 `INVALID_ARGUMENT` 语义）
- 状态机违规/资源冲突 → `40901`（项目既有约定：`TestPlanService.activate` 双重激活即抛 `CONFLICT`；`ErrorCodes` javadoc 明确"状态机违规/资源冲突"）——与 Task 要求"用既有错误契约拒非法迁移"一致。

## 7. 事务一致性（§十一）

**结论：既有 Service 边界可保证 "Finding 更新 + Evidence 校验" 无半成功状态，无需重构任何事务管理。**

证据链：
- `report()`：`validate(f)`（含 `validateExpectedActual` 及全部白名单/PF-003 校验）**先于** `findingRepo.insert(f)` 执行。校验抛异常时 DB 零写入——不存在"已插入行但 Evidence 非法"的半成功态。写库是单语句 INSERT，SQLite 语句级原子（WAL + busy_timeout 既有配置）。
- `updateStatus()`：`require(id)` → 白名单 40001 → 迁移矩阵 40901，**全部校验通过后才执行单语句 UPDATE**。非法迁移在写库前即抛出 → DB 状态不被篡改、无部分更新。
- Tool 链路 `FindingReportTool`：`buildEvidence` → `FindingService.report`（校验在 insert 前）→ 成功后才发 `FINDING_CREATED` 事件。校验失败 → ToolException（工具级 FAILED，非 critical，Run 继续——既有 ToolExecutor 收口行为不变量）。

**事务边界复用**：无 `@Transactional` 作用域问题——校验与时序天然构成"先验后写"，与既有 FindingService 单语句原子写一致，未引入多语句跨事务窗口。

## 8. 测试结果

新增 `QaP1_2Test`（22 项，全过）：DB 隔离用 `QaTestFixture` per-class 临时 DB（顺带合规 Audit 的 PF-002 强制项），`@BeforeEach` 清理 finding/tool_call/step/agent_event/test_plan/run/task，保留 prompt_version/game_config_version 种子。

| 类别 | 覆盖 | 数量 |
|---|---|---|
| A. Evidence 校验 | valid→ALLOW；expected=null/blank/whitespace→DENY(40001)+`findingCount()==0`；actual 同理；无 Evidence MANUAL 仍放行；evidenceJson=null 放行 | 9 |
| B. Lifecycle 合法迁移 | OPEN→CONFIRMED / OPEN→DISMISSED / CONFIRMED→FIXED / CONFIRMED→DISMISSED 全部 ALLOW＋DB 状态回读验证；非法状态值→40001 | 5 |
| C. Lifecycle 非法迁移 | OPEN→FIXED→40901＋DB 不变；CONFIRMED→OPEN→40901＋DB 不变；DISMISSED 终态→4 目标全 40901；FIXED 终态→4 目标全 40901；自环→40901；不存在→40401 | 6 |
| D. 事务 + P1-1 回归 | finding_report 带 blank actual → ToolException 且 0 行落库（无半成功）；finding_report 完整 expected/actual → Finding+Evidence 落库（sessionId/seed 反序列化验证） | 2 |

**既有 fixture 更新**：8 个测试文件、12 处 `finding_report` 调用补齐 expected/actual（均携带真实证据，语义一致；NULL 校验不触发误改）。

### Full Regression（§十 D）

| 运行方式 | tests | failures | errors | skipped |
|---|---|---|---|---|
| 顺序 `mvn -o test`（`mvn -o clean test-compile` 先行） | **512** | **0** | **0** | 0 |
| JUnit 方法级随机（seed=7） | **512** | **0** | **0** | 0 |
| surefire 类级随机 | 512 | 1（`RepositoryTest.eventReplayAfterIdAndRecent` = **R-001 既有豁免**，TD-QA-R-001，与 P1-1 基线逐项一致，非本次引入） | 0 | 0 |

基线对照：P1-1 490 → P1-2 512（+22 新增，其中 QaP1_2Test 22；另有 8 个 fixture 文件更新后仍全绿）。
类级随机豁免说明：按 Audit 技术债表显式豁免——"TD-QA-R-001 保持豁免记录、不由 P1 借机修共享 static DB"，P1-2 不越界。

## 9. P1-1 回归（§十三）

QA 最小闭环 **回归通过**：
- QaP1_1Test 17/17 全绿（QA Task→QA Agent→qa_p001→finding_report→Finding→Evidence→DB 链路的端到端断言不变）；
- QaP1_2Test D2 直接验证 QA Tool→FindingService 仍创建合法 Finding+Evidence（evidence_json 反序列化 sessionId="qt-ok"、seed=42）；
- 8 个既有 Tester/Tester-Integration fixture 经 added-args 修正后全绿——证明收紧没有破坏任何既有调用方。

## 10. Scope Compliance（P1-2 绝对禁止逐项核验）

- ✅ 无 QA API（未实现任何 `/api/qa/*` 端点 —— P1-3 范围）
- ✅ 无 Frontend 改动（P1-4 范围；既有 FindingsPage 不变，新增约束无前端影响）
- ✅ 无 Multi-Agent（未实现 QA_LEAD / QA_TESTER / QA_RESEARCHER / QA_ANALYST / SubRun / 子任务编排）
- ✅ 无 Release Gate（无 gate 表 / 状态机 / 发布阻断逻辑）
- ✅ 无新增 QA 数据模型 / 表（无 QaFinding / QaEvidence / QaFindingRepository / QaLifecycle；0 张新表；schema.sql 未动）
- ✅ 无平行 Finding Domain（继续复用 Finding / Evidence / FindingService / FindingRepository）
- ✅ 未触碰 Frozen Boundary（AgentLoop / RunStateMachine / BudgetGuard / LoopDetector / ToolExecutor / ToolResult / ErrorCodes 零改动）
- ✅ 未重写 FindingFingerprintService、未改 PF-003 configVersion 校验（"强化约束，不改变既有业务身份"）
- ✅ 未新增 ErrorCode（40001/40901/40401 既有契约复用）
- ✅ 无大规模 Finding refactor、无 Transaction 重构
- ✅ 8 条 STOP 条件全部未触发

## 11. 技术债登记（本轮无新增）

| 编号 | 说明 | 状态 |
|---|---|---|
| TD-QA-R-001 | 共享 static DB + `sqlite_sequence` 不重置（类级随机序 flaky） | 既有豁免，保持（P1 不修） |
| TD-QA-P11-001 | ToolExecutor dispatch 层无角色校验 | 既有，保持（Frozen Boundary 范围内） |

## 12. Remaining Work（后续，本轮不做）

- **P1-3**：QA REST API（qa 命名空间查询端点，若需）。
- **P1-4**：QA Frontend（若需独立 QA 页面）。
- **P1-5**：Isolation/Regression 深化。
- **P1-6**：P1 整体 Acceptance。

---

## 附录：验收 Checklist（P1-2 §十六）

- [x] Evidence 不新增字段；仅收紧 expected/actual 必填（携带 Evidence 时），null/blank/纯空白均不可能生成无效 Evidence
- [x] 合法 expected+合法 actual（携带 Evidence）→ 接受
- [x] Evidence 在 Finding 创建路径校验失败 → 无半成功态（复用既有事务边界/先验后写）
- [x] Lifecycle 迁移矩阵以仓库真实枚举 + Audit 权威矩阵为事实来源（4 状态 / 4 合法边 / 2 终态），非 Task 示意
- [x] 非法迁移抛既有错误契约 40901（状态机违规约定）；非法状态值 40001；不存在 40401
- [x] 未新增 ErrorCode
- [x] 未修改 schema.sql；0 张新表；无生命周期/迁移/Evidence/QA/状态历史表
- [x] 未重写 FindingFingerprintService；未改 PF-003 configVersion 校验
- [x] 未触碰 Frozen Boundary；未重构 Transaction
- [x] 新增测试全部通过（QaP1_2Test 22/22；QaP1_1Test 17/17 回归）
- [x] 完整回归：顺序 512/0/0、方法级随机 seed=7 512/0/0、类级随机仅 R-001 既有豁免（与 P1-1 基线一致）
- [x] 文档更新完成（本文档）