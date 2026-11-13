# QA P1-4 实施报告（Finding 多维过滤前端接入 + WorkflowPage QA 入口）

> 归属：QA P1 Sprint，Task P1-4（`QA P1-3 — ACCEPTED` 后按 Task 输入实施）。
> 状态：**QA P1-4 COMPLETE — WAITING FOR HUMAN ACCEPTANCE**。
> 生成时间：2026-09-14。
> 前置依赖：P1-1 ✅、P1-2 ✅、P1-3 ✅（`/api/qa/findings` 已获人工批准）。
> 附注：先期 R3-B2 并行会话已实现 WorkflowPage QA 入口（2 行改动 + 6 测试），
> 本轮审计确认后保留、不重复实现；本轮新增 Finding 多维过滤前端接入。

## 1. Status

```text
QA P1-4 COMPLETE — WAITING FOR HUMAN ACCEPTANCE
```

- 前端改动：`api.ts`（端点 + FindingFilter 8 维扩展）、`FindingsPage.tsx`（过滤 UI 8 维）。
- 新增测试：`FindingsPage.test.tsx` **6 项**。
- 前端完整回归：**41 tests / 0 Failures / 0 Errors**（6 个测试文件全绿）。
- TypeScript 类型检查：**0 errors**（`tsc -b --noEmit`）。
- 后端零改动；后端回归：**551 tests / 0 Failures / 0 Errors**（与 P1-3 基线一致）。

## 2. 目标

> 将现有 QA REST / Finding 能力以最小必要范围接入现有前端：QA Task 入口（R3-B2 已有）+ Finding 多维过滤查询前端化（本轮）。

## 3. Baseline（P1-4 Audit 结果）

### A. R3-B2 已存在内容（先期并行会话，本轮审计确认后保留）

| 文件 | 内容 | 判定 |
|---|---|---|
| `frontend/src/pages/WorkflowPage.tsx` | `AGENT_TYPES` 含 `"QA"`；`agentTone("QA") → "info"` | ✅ 满足 §3 QA 入口，保留 |
| `frontend/src/pages/WorkflowPage.test.tsx` | 6 项 QA 入口测试（下拉/提交/回归/引用/渲染/未知类型） | ✅ 满足 §9 QA Entry，保留 |
| 后端 `TaskController` / `AgentType` | 已放行 QA（P1-1） | ✅ 复用，不改 |

### B. 本轮审计发现的真实缺口（R3-B2 报告的误导性描述）

R3-B2 报告声称 "FindingsPage 已是 QA Finding 视图"，**经审计不成立**：

- `api.listFindings()` 实际请求 `GET /api/findings`（Tester 3 参端点），**全前端无任何 `/api/qa/findings` 调用**（grep 证实）；
- `FindingFilter` 仅 3 维（configVersion/severity/runId），缺 category/source/status/runIds/testPlanId/taskId；
- FindingsPage 过滤 UI 仅 2 个控件（configVersion + severity）。

### C. 缺口清单 → 本轮改动

| 缺口 | 改动 |
|---|---|
| G1 `api.listFindings` 走错端点 | 改为 `GET /api/qa/findings`（已批准） |
| G2 过滤维度不足 | `FindingFilter` 扩为 8 维；FindingsPage UI 补齐 8 控件 |
| G3 无 Finding 前端测试 | 新增 `FindingsPage.test.tsx` 6 项 |

## 4. 实现改动清单（Changed Files）

| 文件 | 改动 | 类型 |
|---|---|---|
| `frontend/src/api.ts` | `listFindings` 端点 `/api/findings` → `/api/qa/findings`；`FindingFilter` 3 维 → 8 维（configVersion/severity/category/source/status/runIds/testPlanId/taskId）；runIds 多值重复参数（IN 语义） | 前端主代码 |
| `frontend/src/pages/FindingsPage.tsx` | 过滤 UI 从 2 控件扩至 8 控件（configVersion + severity/category/source/status 下拉 + runIds 逗号分隔 + testPlanId/taskId 数字输入），全部复用既有 filter 状态与 AND 语义 | 前端主代码 |
| `frontend/src/pages/FindingsPage.test.tsx` | **新增** 6 项验收测试（QA 端点/filter 转换/多维 AND/渲染/empty/error） | 前端测试 |

**未改动**：`WorkflowPage.tsx`（R3-B2 已满足）、`App.tsx`（路由无变化，无新 `/qa` SPA）、`types.ts`（Finding 17 字段已完整）、`TestPlanPage.tsx`（`api.listFindings()` 无参调用在 QA 端点上语义不变——空过滤返回全部）、后端全部文件。

## 5. API 使用（§5 / §13）

| 端点 | 本轮使用 | 来源 |
|---|---|---|
| `GET /api/qa/findings` | ✅ FindingsPage 列表查询（8 维参数，AND 语义，runIds 多值 IN） | 已获人工批准的既有 API（2026-09-14），**未扩张** |
| `GET /api/findings/{id}` | 复用（既有 `api.getFinding`） | 既有 |
| `POST /api/findings/{id}/status` | 复用（既有 `api.updateFindingStatus`，Finding 详情抽屉） | 既有 |
| `POST /api/tasks`（agentType=QA） | 复用（WorkflowPage，R3-B2） | 既有 |

**未新增任何 `/api/qa/*` endpoint。** Finding lifecycle 权威仍在后端 `FindingService`（前端零迁移规则逻辑）。

## 6. 数据状态（§7）

- **Loading**：复用既有 `isLoading && <p>加载中…</p>`。
- **Empty**：复用既有"无 Finding"空态（`findings.length === 0 && !isLoading`）。
- **Error**：复用既有"加载失败：`Error.message`"红字提示；`api.unwrap` 将后端错误码包络（40001/40401/40901…）透传为 `API <code>: <message>`，不静默。未新建 ErrorCode。

## 7. Project Isolation（§8）

前端不做跨 Project 伪造；本轮无权限/RBAC 改动。现有前端架构对 project context 无显式承载（单 Project 中台），无新增技术债。

## 8. 测试结果

### 8.1 前端（vitest，6 文件 41 项全绿）

| 文件 | 项数 | 来源 |
|---|---|---|
| `useGlobalSse.test.tsx` | 10 | 既有 |
| `ConfigDiffPage.test.tsx` | 7 | 既有 |
| `WorkflowPage.test.tsx` | 6 | R3-B2 |
| `ResearchPage.test.tsx` | 6 | 既有 |
| **`FindingsPage.test.tsx`** | **6** | **P1-4 新增** |
| `App.test.tsx` | 2 | 既有 |

新增 6 项覆盖（§9 QA Findings 要求）：

1. 首屏请求走 `GET /api/qa/findings`（空过滤无 query）；
2. filter → query parameters（configVersion + severity 同传）；
3. 多过滤条件保留（category/source/status/runIds×2/testPlanId/taskId 全维度 AND 同传，runIds 多值 IN）；
4. 列表 + 详情抽屉渲染（复用既有 FindingDetail）；
5. empty state；
6. error state（后端 40001 包络 → "加载失败"，不静默）。

### 8.2 后端（§10）

`mvn -o test`：**551 / 0 Failures / 0 Errors**（与 P1-3 基线一致，零改动、零回归）。

## 9. Scope Compliance

- ✅ 无新 DB table / schema 未动
- ✅ 无新 ErrorCode
- ✅ Frozen Boundary 零改动（AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult）
- ✅ ToolExecutor / AgentLoop 未重构
- ✅ P1-2 Finding lifecycle 未修改（前端无状态迁移规则，状态权威在后端）
- ✅ 无 QA 平行 Domain（继续复用 Finding type / FindingsPage / api.listFindings）
- ✅ 无新 `/api/qa/*`（未扩张；`/api/qa/findings` 为已批准既有端点）
- ✅ 无 P1-5/P1-6/Phase 2 提前实现（无 Release Gate / Multi-Agent / QA_LEAD / SSE 重构）
- ✅ 无新 `/qa` SPA / 无 QaFindingsPage（复用现有 FindingsPage，§4）

## 10. 技术债登记

| 编号 | 说明 | 状态 |
|---|---|---|
| TD-QA-R-001 | 共享 static DB + `sqlite_sequence` 不重置（类级随机序 flaky） | 既有豁免，保持 |
| TD-QA-P11-001 | ToolExecutor dispatch 层无角色校验 | 既有，保持（Frozen Boundary 范围内） |
| 前端既有（X-5/X-6/X-7） | 死导出 / 页面测试覆盖 / 查询键不一致 | 既有，保持（R3-B2 记录） |

本轮无新增技术债。

## 11. 附录：验收 Checklist（P1-4 §16）

- [x] R3-B2 已有实现完成审计（保留，不重复）
- [x] QA Workflow/Task 入口可用（WorkflowPage QA 选项 + 6 测试）
- [x] QA Findings 前端可用（FindingsPage 走 `/api/qa/findings`）
- [x] 复用现有 Finding UI/domain（FindingsPage / Finding type / api client，无 QaFindingsPage）
- [x] `/api/qa/findings` 正确调用（8 维参数，AND，runIds IN）
- [x] 8 维 filter 正确工作 / UI 完整覆盖
- [x] Loading / Empty / Error 正常
- [x] QA entry / Findings tests PASS（6+6）
- [x] Backend `mvn -o test` PASS（551/0/0）
- [x] Frozen Boundary 未修改
- [x] Schema 未修改
- [x] ErrorCodes 未增加
- [x] 无新的 `/api/qa/*`
- [x] 无 QA 平行 Domain
- [x] 无 P1-5/P1-6/Phase 2 提前实现
- [x] `docs/QA_P1_4_IMPLEMENTATION_REPORT.md` 完整

---

## 12. Findings API Compatibility Closure

> 生成时间：2026-09-15。
> 触发原因：P1-4 初版将 `api.listFindings()` 端点从 `GET /api/findings`（Tester）
> 改为 `GET /api/qa/findings`（QA），而 `listFindings` 是共用方法，导致
> TestPlanPage（Tester 场景）静默切到 QA 端点，破坏 Tester 回归。

### 12.1 Closure 审计结果

| 项目 | 事实 |
|---|---|
| **`api.listFindings` 实际调用方** | `TestPlanPage.tsx:105`（`api.listFindings()` 无参，Tester 场景"关联 Findings"） |
| **`api.listQaFindings` 实际调用方** | `FindingsPage.tsx:25`（QA 8 维过滤场景） |
| **`api.getFinding` / `api.updateFindingStatus`** | 共用，指向 `/api/findings/{id}` 和 `/api/findings/{id}/status`，未变动，Tester 和 QA 共用 |
| **`api.getFindingRegression`** | `ConfigRegressionPage.tsx`（R7-6 端点），未变动，不受影响 |

**结论**：`listFindings` 是**共享方法**——TestPlanPage（Tester）和 FindingsPage（QA）
都使用。P1-4 初版将共用方法指向 `/api/qa/findings`，导致 Tester 路径被静默污染。

### 12.2 修复方案：Endpoint Separation

按 Closure §2 规定——共用方法必须拆分，不得一个方法承载两个不同 API endpoint：

| 改动 | Before | After |
|---|---|---|
| **`api.listFindings(filter: FindingFilter)`** | `GET /api/qa/findings`（P1-4 初版错误） | `GET /api/findings`（恢复 Tester 原 3 参端点） |
| **`api.listQaFindings(filter: QaFindingFilter)`** | 不存在 | `GET /api/qa/findings`（QA 8 维端点，新增） |
| **`FindingFilter`** | 8 维（P1-4 初版错误） | 3 维：`configVersion / severity / runId`（恢复 Tester 原定义） |
| **`QaFindingFilter`** | 不存在 | 8 维：`configVersion / severity / category / source / status / runIds / testPlanId / taskId`（新增） |
| **`FindingsPage.tsx`** | `api.listFindings(filter)` + `FindingFilter` | `api.listQaFindings(filter)` + `QaFindingFilter` |
| **`TestPlanPage.tsx:105`** | `api.listFindings()`（已随 api.ts 恢复） | 无变更，继续 `api.listFindings()` → `GET /api/findings` |

**改动性质**：纯前端 API client endpoint separation，无新 domain（QaFinding/QaEvidence），
无 backend 改动，无 schema 改动，无 new `/api/qa/*` endpoint。

### 12.3 测试矩阵（回归）

**前端（`npx vitest run`）**：8 文件 / **53 tests / 0 Failures / 0 Errors**

| 文件 | 项数 | 来源 |
|---|---|---|
| `useGlobalSse.test.tsx` | 12 | 既有 |
| `ResearchPage.smoke.test.tsx` | 7 | 既有 |
| `ConfigDiffPage.test.tsx` | 9 | 既有 |
| `WorkflowPage.test.tsx` | 6 | R3-B2 |
| `FindingsPage.test.tsx` | 6 | P1-4 |
| `ResearchPage.test.tsx` | 6 | 既有 |
| **`api.test.ts`** | **5** | **Closure 新增** |
| `App.test.tsx` | 2 | 既有 |

Closure 新增 `api.test.ts` 5 项覆盖：
1. `api.listFindings()` 无参 → `/api/findings`（无 query）
2. `api.listFindings({ configVersion, severity, runId })` → `/api/findings?configVersion=...&severity=...&runId=...`（Tester 3 参）
3. `api.listQaFindings()` 空过滤 → `/api/qa/findings`（无 query）
4. `api.listQaFindings(8-dim)` → 全维度 AND query，runIds 多值 IN（`runIds=1&runIds=2`）
5. 互不污染：先调 listQaFindings 再调 listFindings，Tester URL 不含 `category/status/runIds/testPlanId/taskId`

**TypeScript 类型检查**：`npx tsc -b --noEmit` → **0 errors**。

**后端（`mvn -o test`）**：**551 / 0 / 0**（与 P1-3 基线一致，Closure 未改动后端任何文件）。

### 12.4 Closure 端点映射总结

| 前端方法 | 后端端点 | 调用方 | 职责 |
|---|---|---|---|
| `api.listFindings(FindingFilter)` | `GET /api/findings` | TestPlanPage（Tester） | Tester 3 参查询 |
| `api.listQaFindings(QaFindingFilter)` | `GET /api/qa/findings` | FindingsPage（QA） | QA 8 维 AND 查询 |
| `api.getFinding(id)` | `GET /api/findings/{id}` | 共用 | Finding 详情 |
| `api.updateFindingStatus(id, status)` | `POST /api/findings/{id}/status` | 共用（FindingsPage） | 状态流转（后端权威） |
| `api.getFindingRegression(oldV, newV)` | `GET /api/findings/regression` | ConfigRegressionPage | 版本回归（R7-6） |

**无交叉污染**：Tester 仅用 `/api/findings`；QA 仅用 `/api/qa/findings`；共用端点（详情/状态/回归）均指向 `/api/findings`，两方安全共用。

### 12.5 最终状态

```text
QA P1-4 COMPLETE — WAITING FOR HUMAN ACCEPTANCE
```

- 前端文件改动：`api.ts`（拆分 listFindings / listQaFindings + FindingFilter / QaFindingFilter）、`FindingsPage.tsx`（改用 listQaFindings + QaFindingFilter）
- 新增测试：`api.test.ts` 5 项（Endpoint Separation 回归）；既有 `FindingsPage.test.tsx` 6 项（QA 集成）+ `WorkflowPage.test.tsx` 6 项（QA 入口）
- 前端回归：**53 tests / 0 F / 0 E**（+5 Closure 新增，既 48 项全绿）
- TypeScript：**0 errors**
- 后端回归：**551 / 0 / 0**（零改动）
- Frozen Boundary：未改动
- Schema：未改动
- ErrorCodes：未增加
- 新 `/api/qa/*`：未新增（`/api/qa/findings` 已获人工批准）
- Finding lifecycle：前端零迁移规则，后端权威不变
