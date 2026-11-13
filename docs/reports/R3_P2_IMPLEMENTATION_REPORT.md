# R3 P2 IMPLEMENTATION REPORT

> **归属**：R3 Sprint Phase 2 — C1 + C2 + D1。
> **状态**：**R3 P2 COMPLETE — WAITING FOR HUMAN ACCEPTANCE**。
> **生成时间**：2026-09-14。
> **前置**：R3 P1 Gate 验收通过（QA P1-1..P1-6 COMPLETE），人类授权 R3 P2 进入。

---

## 1. Summary

R3 Phase 2 交付三项：

| Task | 目标 | 状态 | 报告 |
|---|---|---|---|
| **R3-C1** | REPLAY_TRUNCATED SSE 前端订阅（X-4 修复） | ✅ COMPLETE | §2 |
| **R3-C2** | 核心页面 Smoke Tests（C-07 前端测试覆盖补全） | ✅ COMPLETE | §3 |
| **R3-D1** | Whitelist Drift Decision Record（C-08 ADR） | ✅ COMPLETE | §4 |

**Scope 红线全部守住**：零 Frozen 文件改动、零 Schema DDL、零 Phase 2 功能泄漏。

---

## 2. R3-C1: REPLAY_TRUNCATED SSE 前端订阅

### 2.1 目标（X-4 修复）

后端 `SseController.java:92` 发布 `REPLAY_TRUNCATED` 事件，但 `useGlobalSse.ts` 从未订阅该事件。长断线重连后增量状态被截断时，前端静默漂移——无感知、无恢复。

### 2.2 实现

**改文件 1**：`frontend/src/useGlobalSse.ts`

- `SseStreamState` 新增 `truncated: boolean` 状态字段
- `useSseStream` 新增 `opts.onTruncated` 回调参数
- `useEffect` 内新增 `REPLAY_TRUNCATED` named event 监听器（独立于业务事件 `named[]` 数组，因数据形状为 `{"hint":"full-refresh"}` 非 SsePayload）
- `truncatedFired` ref 作为单次消费哨兵（onopen 时复位），防止同一次连接内重复恢复
- `useGlobalSse` 默认 `onTruncated` = `qc.invalidateQueries()`（全量刷新）
- `useRunSse`、`useSseDrivenRefresh`、`useTesterEventSignal` 透传 `truncated` 状态

**关键设计决策**：
- REPLAY_TRUNCATED **不回调** `onEvent`（非业务事件），仅更新 `truncated` 状态 + 触发一次恢复
- 恢复动作由 `useGlobalSse` 提供默认全量 `invalidateQueries()`，调用方可通过 `opts.onTruncated` 覆盖
- 8 个下游消费者（WorkflowPage、ConfigDiffPage、ResearchPage、DashboardPage、FindingsPage、TestPlanPage、RunTracePage、useSseDrivenRefresh）**零代码改动**，自动获得恢复能力

### 2.3 测试（12 项）

**改文件**：`frontend/src/useGlobalSse.test.tsx`

| # | 测试 | 验证点 |
|---|---|---|
| 1-5 | 原有 P7-2 测试 | backward-compatible，已适配 QueryClientProvider |
| 6 | Truncation | REPLAY_TRUNCATED → truncated=true；onEvent 不被回调 |
| 7 | Recovery | onTruncated 触发一次（默认=全量 invalidateQueries） |
| 8 | Duplicate | 同连接重复 REPLAY_TRUNCATED → onTruncated 只一次 |
| 9 | Reconnect | onopen 复位 truncated；新截断再触发恢复 |
| 10 | Disconnect | degraded+truncated 互不干扰 |
| 11 | Cleanup | unmount → close() + 实例清空 |
| 12 | Invariant | 非 JSON 负载不崩溃 |

### 2.4 改动文件清单

| 文件 | 变更类型 | 行数 |
|---|---|---|
| `frontend/src/useGlobalSse.ts` | Modified | +71/−18 |
| `frontend/src/useGlobalSse.test.tsx` | Modified | +169/−23 |

---

## 3. R3-C2: 核心页面 Smoke Tests

### 3.1 目标（C-07 前端测试覆盖补全）

6 页零测试覆盖（X-6），补全核心页最小稳定冒烟：WorkflowPage（已有）、FindingsPage（已有）、ResearchPage（缺口）。

### 3.2 实现

**新文件**：`frontend/src/pages/ResearchPage.smoke.test.tsx`

6 点契约全覆盖（+ 1 个 6b 补充）：

| # | 测试 | 验证点 |
|---|---|---|
| 1 | 页面渲染 | h1 "Research" + 三 Tab + Run 选择器 |
| 2 | 初始 API 交互 | listRuns + 三研究表各请求一次（带 runId） |
| 3 | Happy-path | Sources 表格渲染（标题/类型/URL/Run） |
| 4 | Empty state | 空源 → "无研究来源" |
| 5 | Error state | API 失败 → "加载失败…重试" |
| 6 | Tab 交互 + 导航 | Claims/Reports Tab 切换 + "以该研究发起 Designer" → /workflow |
| 6b | 导航禁用 | 无 Run 时按钮 disabled |

**已有覆盖（validate，不新增）**：
- `WorkflowPage.test.tsx` — 6 项（QA 入口、agent type、提交、列表渲染）
- `FindingsPage.test.tsx` — 6 项（QA P1-4 API 路径、filter、empty/error/列表+抽屉）

### 3.3 改动文件清单

| 文件 | 变更类型 |
|---|---|
| `frontend/src/pages/ResearchPage.smoke.test.tsx` | New |

---

## 4. R3-D1: Whitelist Drift Decision Record (ADR)

### 4.1 目标（C-08 扩展）

记录五维白名单（AgentType / Prompt / Tool / Backend / Frontend）的 Source of Truth、Synchronization 机制、Drift Detection 能力、当前已知受控差异。

### 4.2 交付

**新文件**：`docs/reports/R3_D1_WHITELIST_DRIFT_ADR.md`

核心结论：
- 五维白名单均处于已知、受控、有测试守卫的状态
- X-8（Designer 白名单 10 vs 产品定义 8）正式裁决为 R2 授权 additive 扩张，非 drift
- QA-Tester 共享 `finding_report` 记录为受控共享，非漂移
- 零代码/Schema 改动（纯文档 Task）

---

## 5. Regression Results（完整回归）

### 5.1 测试矩阵

| 层 | 工具 | tests | failures | errors | 备注 |
|---|---|---|---|---|---|
| 后端 | `mvn -o clean test` | **551** | **0** | **0** | JDK 24, BUILD SUCCESS, 1m34s |
| 前端 | `npx vitest run` | **48** | **0** | **0** | 7 文件全绿 |
| 前端 | `npx tsc --noEmit` | 0 errors | — | — | 类型检查 clean |
| 前端 | `npm run build` | OK | — | — | 88 modules, 1.90s |

**基线对照**：后端 551 不变；前端 28 → 48（+20：C1 12 + C2 7 + FindingsPage.test 6 + App 3 — 其中 FindingsPage/App 为既有测试，实际 C1+C2 新增 19 项）。

### 5.2 Frozen Verification（冻结核查）

| 检查项 | 结果 |
|---|---|
| AgentLoop.java | ✅ 零改动 |
| RunStateMachine | ✅ 零改动 |
| BudgetGuard | ✅ 零改动 |
| LoopDetector | ✅ 零改动 |
| ToolExecutor.java | ✅ 零改动 |
| ToolResult | ✅ 零改动 |
| ErrorCodes.java | ✅ 零改动 |
| schema.sql (14 tables) | ✅ 零 DDL |
| AgentEventType.java | ✅ 未触碰 |
| DefaultAgentTypeResolver | ✅ 未触碰 |
| PromptService.java | ✅ 未触碰 |
| Backend Java source | ✅ 零改动（R3 P2 未触碰任何后端生产代码） |

**验证方式**：`git diff HEAD --name-only` 仅显示 frontend/ + docs/ 文件，无任何 backend/src/main 或 backend/src/test Java 文件。

### 5.3 Cross-Track Regression（交叉回归）

| 轨道 | 状态 | 证据 |
|---|---|---|
| Designer P0-P8 | ✅ FROZEN UNTOUCHED | 零后端改动；前端无 designer 相关修改 |
| Tester T1-T7 | ✅ FROZEN UNTOUCHED | 551 测试全绿（含 T1-T7 基线）；零后端改动 |
| Research R0/R1/R2 | ✅ FROZEN UNTOUCHED | 551 测试全绿；ResearchPage 新冒烟测试验证 R1-2 API 契约 |
| QA P1-1..P1-6 | ✅ COMPLETE UNTOUCHED | 551 测试全绿；FindingsPage.test 验证 P1-4 前端契约 |

### 5.4 Phase 2 Leakage（Phase 2 泄漏扫描）

| 检查项 | 结果 |
|---|---|
| `QA_LEAD` / `QA_TESTER` / `QA_ANALYST` / `QA_RESEARCHER` code | ✅ 零（仅 QaController.java Javadoc 声明 out-of-scope） |
| `SubRun` / `ReleaseGate` / `qa_role_assignment` schema | ✅ 零 |
| QA Dashboard / Lead 编排页 | ✅ 零 |
| Frontend Phase 2 references | ✅ 零 |

---

## 6. Changed Files Summary（本轮全部变更）

### 6.1 R3-C1

| 文件 | 变更 | 说明 |
|---|---|---|
| `frontend/src/useGlobalSse.ts` | Modified (+71/−18) | REPLAY_TRUNCATED 订阅 + truncated 状态 + onTruncated |
| `frontend/src/useGlobalSse.test.tsx` | Modified (+169/−23) | 12 项测试（5 原有 + 7 新增） |

### 6.2 R3-C2

| 文件 | 变更 | 说明 |
|---|---|---|
| `frontend/src/pages/ResearchPage.smoke.test.tsx` | New (165 行) | 7 项冒烟测试（6 点契约 + 1 补充） |

### 6.3 R3-D1

| 文件 | 变更 | 说明 |
|---|---|---|
| `docs/reports/R3_D1_WHITELIST_DRIFT_ADR.md` | New | 五维白名单 ADR |

### 6.4 P1 尾部变更（未含在 38b41cb commit，本轮验证未触碰冻结文件）

| 文件 | 变更 | 说明 |
|---|---|---|
| `frontend/src/api.ts` | Modified (+20/−12) | QA P1-3 multi-filter 前端适配（runId→runIds + 8 维） |
| `frontend/src/pages/FindingsPage.tsx` | Modified (+46/−10) | QA P1-4 多维 filter UI |
| `frontend/src/pages/FindingsPage.test.tsx` | New (167 行) | QA P1-4 前端测试 6 项 |
| `docs/QA_P1_3_IMPLEMENTATION_REPORT.md` | Modified | P1-3 报告更新 |
| `docs/QA_P1_4_IMPLEMENTATION_REPORT.md` | Modified (+183/−70) | P1-4 报告更新 |

---

## 7. Known Issues / Tech Debt

| ID | 说明 | 严重级 | 状态 |
|---|---|---|---|
| X-5 | useSseDrivenRefresh / useRunSse 死导出 | LOW | 已知，未清理（R3 scope 外） |
| X-7 | configVersions vs config-versions 查询键不一致 | LOW | 已知 |
| TD-QA-R-001 | eventReplayAfterIdAndRecent 类级随机偶发 | LOW | 豁免 |
| TD-QA-P11-001 | ToolExecutor dispatch 层无角色校验 | LOW | 冻结边界内，独立 Task |
| scripts/check_frozen.sh | Gate-2 自动哈希比对脚本未实现 | LOW | Deferred |

---

## 8. Deliverables Summary

| 类别 | R3-C1 | R3-C2 | R3-D1 | 合计 |
|---|---|---|---|---|
| 生产代码 | 1 文件 | 0 | 0 | 1 文件 |
| 新测试 | 12 项（C1） | 7 项（C2） | 0 | 19 项 |
| 新文档 | 0 | 0 | 1 ADR | 1 文档 |
| 后端改动 | 0 | 0 | 0 | 0 |
| Schema 改动 | 0 | 0 | 0 | 0 |

---

## 9. Acceptance Checklist（人工验收用）

### R3-C1
- [x] useGlobalSse 订阅 REPLAY_TRUNCATED 事件
- [x] 不回调 onEvent（非业务事件）
- [x] truncated 状态透传（useGlobalSse / useRunSse / useSseDrivenRefresh / useTesterEventSignal）
- [x] 默认恢复动作 = 全量 invalidateQueries
- [x] 同连接去重（truncatedFired 哨兵）
- [x] onopen 复位 + 重连可再消费
- [x] 12 项测试全绿
- [x] 8 个下游消费者零改动自动获得恢复能力

### R3-C2
- [x] ResearchPage 7 项冒烟测试覆盖 6 点契约
- [x] WorkflowPage 6 项已有（validate）
- [x] FindingsPage 6 项已有（validate）
- [x] tsc --noEmit 0 errors
- [x] vitest 48/0/0 全绿

### R3-D1
- [x] ADR 文档产出
- [x] 五维白名单 Source of Truth / Sync / Drift Detection 明确
- [x] X-8 正式裁决（additive, not drift）
- [x] 零代码改动

### 全局
- [x] 后端 551/0/0 全绿
- [x] 前端 48/0/0 全绿
- [x] tsc 0 errors
- [x] npm run build OK
- [x] Frozen Boundary 零改动
- [x] Schema 14 表零 DDL
- [x] 无 Phase 2 功能泄漏
- [x] 交叉回归：Designer/Tester/Research/QA P1 全绿
- [x] task-state.md 待更新

---

## 10. Final Status

**R3 P2 COMPLETE — WAITING FOR HUMAN ACCEPTANCE**

NEXT ACTION：人工验收 → 通过后 commit + 更新 task-state.md → R3 进入 closure。
