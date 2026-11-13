# QA P1-1 实施报告（最小闭环：AgentType → Route → Prompt → Tool → Finding）

> 归属：QA P1 Sprint，Task P1-1（`QA P1 Implementation Audit — ACCEPTED` 后首个实施任务）。
> 状态：**QA P1-1 COMPLETE — WAITING FOR HUMAN ACCEPTANCE**。
> 生成时间：2026-09-10。

## 1. Status

```text
QA P1-1 COMPLETE — WAITING FOR HUMAN ACCEPTANCE
```

- 完整回归：**490 tests / 0 Failures / 0 Errors**（顺序 full-suite）；方法级随机 seed=7 亦全绿；类级随机仅 R-001 既有豁免项复现（TD-QA-R-001，非本次引入）。
- 未触碰 Frozen Boundary；未新增 QA 表；未创建平行 QA Finding Domain；未实现 Phase 2 / Release Gate / QA Dashboard。

## 2. 背景修正：QA P0 = NOT FOUND

Audit（`docs/QA_P1_IMPLEMENTATION_AUDIT.md`）已确认：**QA P0 声明交付物在代码库中实际不存在**（AgentType 无 QA、无 QA Prompt 种子、无 QA Tool、TaskController 白名单仅三值）。P1-1 在此真实基线上从零建立最小闭环，不重新声称 P0 已完成。

## 3. 目标达成

> 一个 QA Task 可以被正确识别为 QA Agent，并通过现有 AgentLoop 执行 QA Tool（finding_report），最终创建并持久化 Finding。

该声明已由测试 `QaP1_1Test.qaTaskThroughAgentLoopCreatesFinding` 端到端验证。

---

## 4. 实现改动清单（Changed Files）

| 文件 | 改动 | 类型 |
|---|---|---|
| `backend/src/main/java/com/aigamelab/runtime/AgentType.java` | 枚举追加 `QA` | 主代码 |
| `backend/src/main/java/com/aigamelab/runtime/AgentTypeResolver.java` | javadoc 同步允许值含 QA | 主代码（接口未变） |
| `backend/src/main/java/com/aigamelab/runtime/DefaultAgentTypeResolver.java` | `QA_PROMPT_VERSION` / `QA_TOOL_NAMES` / `systemPrompt(QA)` / `toolWhitelist(QA)` / 非法值错误信息随枚举扩展 | 主代码 |
| `backend/src/main/java/com/aigamelab/api/TaskController.java` | agentType 允许值文案含 QA（校验逻辑经 `AgentType.valueOf` 自动覆盖） | 主代码 |
| `backend/src/main/java/com/aigamelab/llm/PromptService.java` | `QA_SEED_VERSION` / 构造器调用 `importQaSeedIfAbsent()` / 幂等导入方法 | 主代码 |
| `backend/src/main/resources/prompts/qa_p001.md` | **新增** QA System Prompt（inactive 种子） | 资源 |
| `backend/src/test/java/com/aigamelab/qa/QaP1_1Test.java` | **新增** 17 项验收测试 | 测试 |
| `backend/src/test/java/com/aigamelab/llm/PromptServiceTest.java` | 行数断言 4→5（+qa_p001） | 测试 |
| `backend/src/test/java/com/aigamelab/llm/TesterPromptImportTest.java` | 行数断言 4→5（+qa_p001） | 测试 |

**未改动**（既有基础设施，全部复用）：`AgentLoop`、`ToolExecutor`、`RunStateMachine`、`BudgetGuard`、`LoopDetector`、`ToolResult`、`ErrorCodes`、`FindingService`、`FindingRepository`、`Finding`、`Finding.Evidence`、`FindingReportTool`、`schema.sql`、前端。

---

## 5. AgentType

`AgentType.QA` 加入既有枚举（[AgentType.java](../backend/src/main/java/com/aigamelab/runtime/AgentType.java)），未新建第二套 Agent Type；`DESIGNER` / `TESTER` / `RESEARCH` 语义与行为不变（测试 `legacyAgentTypesUnchanged` 守护）。

## 6. Routing

`DefaultAgentTypeResolver.resolve()` 解析规则未变——仍优先读 `constraintsJson.agent_type`，缺失时回退二值逻辑（ACTIVE TestPlan→TESTER / 否则→DESIGNER）。`AgentType.valueOf("QA")` 在枚举加入后自动识别 `agent_type=QA`；**回退逻辑不产生 QA**（防止既有任务被错误路由成 QA，符合"不允许穷举 switch + 默认 fallback 误路由"约束）。

- TaskController `POST /api/tasks` 经 `AgentType.valueOf` 自动放行 `agentType=QA`（错误文案同步更新）。
- `systemPrompt(QA)` → `PromptService.getByVersion("qa_p001")`（与 Tester/Research 同机制）。
- `toolWhitelist(QA)` → `{finding_report}`。

## 7. Prompt

- 新增 `backend/src/main/resources/prompts/qa_p001.md`，内容覆盖：QA 职责、工作流程（ANALYZE_TASK → ASSESS_EVIDENCE → IDENTIFY_GAP → REPORT）、Evidence/sessionId 主锚点铁律、finding_report 使用约束。
- `PromptService` 新增 `QA_SEED_VERSION="qa_p001"`，构造器调用 `importQaSeedIfAbsent()`——与 tester/research/designer_p002 完全同模式的幂等导入（表内无该版本才插入，inactive，不抢 designer p001 的 active 位）。
- 未硬编码绕过 PromptService 的 QA Prompt；QA System Prompt 经 `PromptVersion` / active 机制取用。

## 8. Tool

**复用现有 `finding_report`，未创建重复 Tool。**

理由（遵循"优先扩展现有 Tool 的允许角色"）：
- `finding_report` 语义（结构化 Finding + Evidence，经 FindingService → FindingRepository 落库）完全覆盖 QA 上报需求；
- 其执行链路（`FindingReportTool` → `FindingService.report` → 校验 → `FindingRepository.insert`）与 QA 要求完全一致；
- 创建平行 `qa_finding_report` 会复制 17 字段 Finding + Evidence 组装 + Service 校验逻辑，违反"禁止平行体系"。

实现方式：仅在 `DefaultAgentTypeResolver` 的 QA 白名单加入 `finding_report`。AgentLoop 的 `selectToolSpecs`（既有机制，冻结文件内）按 `toolWhitelist(QA)` 过滤后，QA 的 LLM 上下文仅暴露该工具。

## 9. Tool Isolation（最小要求）

**白名单矩阵**（`DefaultAgentTypeResolver.toolWhitelist` 实测）：

| Agent | finding_report | 判定 |
|---|---|---|
| QA | ✅ 允许 | ALLOW |
| TESTER | ✅ 允许（共享） | ALLOW |
| DESIGNER | ❌ 拒绝（不在白名单） | DENY |
| RESEARCH | ❌ 拒绝（不在白名单） | DENY |

`finding_report` 设计为 QA + Tester 共享工具，故按共享矩阵验证（QA/TESTER 均 ALLOW），未错误锁死为 QA-only。

实现说明：本轮实现走既有**白名单机制**（AgentLoop 暴露层过滤），**未**修改 ToolExecutor dispatch 层（Frozen Boundary，禁止触碰）也未引入全局权限重构（本轮禁止范围）。Audit 已知 dispatch 层无角色校验的缺口（spec 层白名单是 AgentLoop 运行期的实际拦截点）——在既有机制下 QA Tool 已被隔离，dispatch 层加固留待后续 Task 评估，已登记为技术债。测试 `QaP1_1Test` 第 9-14 项守护矩阵。

## 10. Finding 创建链路

```
QA Task → AgentLoop → LLM(tool_calls=finding_report) → ToolExecutor → FindingReportTool
       → FindingService.report（severity/category/source 白名单 + configVersion 存在性 + Evidence 结构校验）
       → FindingRepository.insert → finding 表（含 evidence_json）
```

- 未复制任何校验逻辑；`FindingService` / `FindingRepository` / `Finding` / `Finding.Evidence` 全复用。
- Evidence 走 `FindingReportTool.buildEvidence`：sessionId 主锚点 + runId + toolCallIds + seed/overrides + tick。
- 无平行 `QaFinding` / `QaEvidence` / `QaFindingRepository`。
- 无新增 QA 表；schema.sql 未动。

## 11. 测试结果

新增测试（`backend/src/test/java/com/aigamelab/qa/QaP1_1Test.java`，17 项，全过）：

| 类别 | 覆盖 | 数量 |
|---|---|---|
| AgentType | QA 存在 / 既有三值不变 | 2 |
| Resolver | QA Task→QA / Designer/Tester/Research 回归 | 4 |
| Prompt | qa_p001 可获取 / systemPrompt(QA)=qa_p001 / active 不变量 | 3 |
| Tool Isolation | 白名单矩阵 6 项（ALLOW×2 + DENY×2 + 最小集 + 互斥） | 5 |
| Finding | finding_report 直接调用落库（字段+Evidence 完整） | 1 |
| AgentLoop E2E | QA Task→AgentLoop→finding_report→Finding 落库 | 1 |

另更新既有断言：`PromptServiceTest` / `TesterPromptImportTest` 行数 4→5（qa_p001 种子加入后的预期变化）。

### Full Regression

| 运行方式 | tests | failures | errors | skipped |
|---|---|---|---|---|
| 顺序 `mvn -o test` | **490** | **0** | **0** | 0 |
| JUnit 方法级随机（seed=7） | **490** | **0** | **0** | 0 |
| surefire 类级随机 | 490 | 1（`RepositoryTest.eventReplayAfterIdAndRecent` = **R-001 既有豁免**，TD-QA-R-001，非本次引入） | 0 | 0 |

基线对照：P1-1 前全量 473 → P1-1 后 490（+17 新增），全部通过。

## 12. Scope Compliance

- ✅ 无 Multi-Agent orchestration（未实现 QA_LEAD / QA_TESTER / QA_RESEARCHER / QA_ANALYST / SubRun / 子任务编排）
- ✅ 无 SubRun
- ✅ 无 Release Gate（无 qa_release_gate 表 / Service / Gate 状态机 / 发布阻断）
- ✅ 无 QA Dashboard
- ✅ 无新增 QA 数据库表（schema.sql 未修改）
- ✅ 无平行 Finding Domain（复用 Finding/Evidence/Repository/Service）
- ✅ 未触碰 Frozen Boundary（AgentLoop / RunStateMachine / BudgetGuard / LoopDetector / ToolExecutor / ToolResult / ErrorCodes 零改动）
- ✅ 未破坏 Designer P0-P8 / Tester T 系列 / Research R1 已验收行为（回归全绿）
- ✅ 未引入新 Agent Runtime / 新 Task/Run 系统 / 未复制 Research 系统
- ✅ 前端零改动（FindingsPage 既有页面可直接查看 QA Finding）

## 13. 技术债登记

| 编号 | 说明 | 后续建议 |
|---|---|---|
| TD-QA-P11-001 | AgentLoop 运行期 QA Tool 隔离依赖 spec 层白名单；ToolExecutor dispatch 层无角色校验（Audit §8 已知缺口，本轮因 Frozen Boundary 不做 dispatch 层加固） | 后续 Task 单独评估：是否在 ToolExecutor 之前增加一层"按 AgentType 校验工具名"的运行时守卫（需触碰/绕开冻结边界时先 STOP 上报） |

## 14. Remaining Work（后续 P1 / Phase 2，本轮不做）

- **P1-2**：Finding Evidence 能力（如果验收需要收紧 expected/actual 等）。
- **P1-3**：QA REST API（qa 命名空间查询端点，若需要）。
- **P1-4**：QA Frontend（若需要独立 QA 页面；当前 FindingsPage 已可用）。
- **P1-5**：Isolation/Regression 深化。
- **P1-6**：P1 整体 Acceptance。
- Phase 2（Proposal 基线，未来）：QA Team 多 Agent 编排 / Release Gate / 子任务系统。

---

## 附录：验收 Checklist（P1-1 §16）

- [x] AgentType.QA 存在
- [x] QA route 可用（constraintsJson `agent_type=QA` → AgentType.QA）
- [x] qa_p001 可通过 PromptService 获取
- [x] QA Tool 正确复用（finding_report，QA+TESTER 共享）
- [x] QA Tool isolation 有测试（矩阵 6 项）
- [x] QA 能创建 Finding（直接调用 + AgentLoop E2E 双路径验证）
- [x] Evidence 能持久化（sessionId/seed/toolCallIds/tick 反序列化验证）
- [x] 没有创建平行 QA Finding Domain
- [x] 没有新增 QA 表
- [x] 没有触碰 Frozen Boundary
- [x] 没有实现 Phase 2 Multi-Agent
- [x] 没有实现 Release Gate
- [x] 没有新增 QA Dashboard
- [x] 新增测试全部通过（17/17）
- [x] 完整 regression 通过（490/0/0 顺序 + 随机）
- [x] 文档更新完成（本文档）
