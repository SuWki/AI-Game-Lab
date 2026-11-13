# Sprint P7 验收报告（Checkpoint Report）

**状态：✅ COMPLETE → WAITING_ACCEPTANCE**
**范围遵守：全部 P7 红线达标，P7-5 已执行（P6-4 解锁后），未进入 P8。**

---

## 1. 执行范围与阶段顺序

| 阶段 | 任务 | 状态 | 交付物 |
|------|------|------|--------|
| P7-2 | SSE Hook + 降级轮询 | ✅ DONE | `useGlobalSse.ts` + 5 个测试文件 |
| P7-4 | Experiment Center | ✅ DONE | `ExperimentCenterPage.tsx` + 5 图 + compare |
| P7-1 | Dashboard 统计 | ✅ DONE | `DashboardController.java` + `DashboardPage.tsx` |
| P7-3 | Run Trace | ✅ DONE | `RunController` 只读端点 + `RunTracePage.tsx` |
| P7-5 | Config & Diff | ✅ DONE | `ConfigDiffPage.tsx` + types + api + 9 测试（P6-4 解锁后执行） |
| P8 | — | 🚫 未进入 | 按裁决禁止自动进入 |

执行顺序：**P7-2 → P7-4 → P7-1 → P7-3 → STOP（P7-5 冻结）→ P6-4 人工解锁 → P7-5 执行 → STOP**。

---

## 2. 各任务交付明细

### P7-2 全局 SSE Hook（前端基础设施）
- **`frontend/src/useGlobalSse.ts`**
  - `useSseStream(url, onEvent, opts)` 底层封装：eventId 去重（Set 上限 4096）、SSE→轮询降级（连续错误超阈值 `maxErrors=3`）、空 URL 守卫（`!url` 直接 return 不建 EventSource）。
  - `useGlobalSse` / `useRunSse(runId, onEvent)` / `useSseDrivenRefresh` / `useTesterEventSignal`。
  - 监听蓝图 §17 全部 22 个领域事件名（含 `TEST_*`、`INVARIANT_VIOLATED`、`GAME_SESSION_STARTED`、`FINDING_CREATED`）。
- **测试**（`useGlobalSse.test.tsx`，5 个全部 PASS）：事件接收 + dbId 去重、runs/{id} 订阅、null 不建连接、降级触发、onopen 恢复。
- **`mockEventSource.ts`** + `setup.ts` 通过 `vi.stubGlobal("EventSource", MockEventSource)` 提供 jsdom 测试桩。

### P7-4 Experiment Center
- **`frontend/src/types.ts`**：`Experiment` / `SimulationResult` / `BuildDistribution` / `ExperimentPair` / `DashboardStats`。
- **`frontend/src/api.ts`**：`listExperiments` / `getExperiment` / `runExperiment` / `compareExperiments` / `parseSimulationResult`；导出 `ExperimentDetail` / `SimulationResultLike`（独立接口，无循环引用）。
- **`ExperimentCenterPage.tsx`**：过滤表（configVersion + scenario）、最多选 4 个对比、z 显著性对比区、5 个 SVG 图（指标柱状 / build 堆叠 / 平衡雷达 / 选取率横向 / 伤害直方图）。
  - 固定分类色 `["#1e40af","#0f766e","#b45309"]`（dataviz 固定顺序，非循环）。
  - **TD-006 合规**：图表忠实反映 P5 winRate 代理，未伪造 DPS 方差。

### P7-1 Dashboard
- **`backend/.../api/DashboardController.java`**：复用 `RunRepository.aggregateStats()` + JdbcTemplate 轻量计数（experiment/doc）。返回 13 字段（含 `successRate`、`designDocumentCount`）。
- **`DashboardControllerTest.java`**（2 PASS）：空库归零、插入数据后计数正确。
- **`frontend/src/pages/DashboardPage.tsx`**：12 个 StatTile 网格 + SSE 驱动 invalidate + 降级提示文案。
- **透明度**：`designDocumentCount` 若表存在则真实计数，否则 0，未伪造。

### P7-3 Run Trace
- **`backend/.../api/RunController.java`**：新增 `GET /api/runs`（按 id 倒序 LIMIT 200）、`GET /api/runs/{id}`（Run + Steps + 每 Step 的 ToolCalls，内嵌 `Map` 视图供前端直接解析）。保留既有 cancel/resume/retry 契约不动。
- **`RunControllerReadTest.java`**（3 PASS）：列表倒序、详情嵌套、缺失 404 抛 `BusinessException(40401)`。
- **`frontend/src/pages/RunTracePage.tsx`**：左栏 Run 列表 + 右栏时间线 + ToolCall Drawer（JSON 美化展示）。SSE 驱动 invalidate，降级走 cache。

### P7-5 Config & Diff（P6-4 解锁后执行）
- **`frontend/src/types.ts`**：新增 `ConfigVersion` / `ChangeProposal` / `DiffLine`（后端 record 字段一一对应，append-only 语义）。
- **`frontend/src/api.ts`**：新增 `listConfigVersions()` / `listProposals(status?)` / `approveProposal(id, comment?)` / `rejectProposal(id, comment?)` 4 个方法 + `parseDiff(diffJson)` 工具（按 path 合并 `-`/`+` 为 `~` 修改行，单独 `-`/`+` 保留为删除/新增）。
- **`frontend/src/pages/ConfigDiffPage.tsx`**：三栏布局
  - 左 `VersionsPanel`：版本列表（最新优先，默认选中 latest），只读时间/comment 展示。
  - 中 `ConfigPanel`：选中版本 JSON 只读 `<pre>` 展示（`JSON.stringify(..., null, 2)`），无 rollback/activate/active 控件。
  - 右 `ProposalsPanel` + `ProposalCard`：PENDING 提案卡片（title/reason/evidenceExperimentIds/baseVersion/runId）、Diff 行渲染、Approve/Reject 按钮（loading 防重复、error 展示、成功后 invalidate）。
  - `DiffLinesView`：按 `DiffLine[]` 渲染，`+` 绿底/`-` 红底/`~` 显示 `before → after`。
  - SSE：`useGlobalSse` 监听 `CONFIG_VERSION_CREATED` / `PROPOSAL_CREATED` / `APPROVAL_REQUIRED` / `PROPOSAL_REJECTED` 四类事件 → `qc.invalidateQueries(["config-versions"])` / `["config-proposals"]`，降级走 cache。
- **`frontend/src/App.tsx`**：新增 `NavLink` "Config & Diff" → `/config-diff`，`Route` 接入 `ConfigDiffPage`。
- **测试**（`ConfigDiffPage.test.tsx`，9 个全部 PASS）：versions 展示+默认选中 latest、无 rollback/activate 按钮、Proposal 展示+evidence+Diff 行、parseDiff 新增/删除/修改区分、空 Diff、Approve 流程（调用 endpoint+刷新+loading）、Reject 流程、Approve 失败恢复、SSE invalidate。
- **后端依赖**：复用 P6-4 `ConfigController` 4 个 endpoint（`GET /config/versions` / `GET /config/proposals` / `POST .../approve` / `POST .../reject`，见 `Sprint_P6-4_Checkpoint_Report.md`），P7-5 未改后端。

---

## 3. 红线合规核查（P7 裁决，含 P7-5）

| 红线 | 核查结果 |
|------|----------|
| 不改 schema（无 DDL） | ✅ `schema.sql` 未改动（`game_config_version` / `change_proposal` 表为既有 P6 前置，非 P7/P6-4 新增） |
| 不改 Repository 接口契约 | ✅ P7 仅调用既有 `aggregateStats` / `findByRunId` / `findByStep`；P7-5 前端仅消费 P6-4 已有 endpoint，未扩展 Repository |
| 不改 ConfigVersion 语义 | ✅ 仍 append-only 全量快照，current = findLatest()，无 active/activateVersion/rollbackVersion |
| 不实现 rollback/activate/active UI | ✅ `ConfigDiffPage` 仅只读 JSON + Approve/Reject，无回滚/激活控件（测试显式断言） |
| 不改 Tester lane | ✅ 全部 Tester 表/服务未改动 |
| 不改 Error Contract | ✅ `BusinessException` / `ApiResp` / `GlobalExceptionHandler` 未改；P7-5 透传 40401/40901 |
| 不进入 P8 | ✅ 已 STOP，未触碰 P8 |
| P6-4 已按人工裁决执行 | ✅ `ConfigController` 4 endpoint 由 P6-4 交付（见 P6-4 报告），P7-5 仅消费其 REST 能力 |

---

## 4. 测试结果汇总

### 后端专项 `ConfigControllerTest` + `RunControllerReadTest`（JDK 24 → --release 21）
```
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0  →  BUILD SUCCESS
  - ConfigControllerTest: 9 passed
  - RunControllerReadTest: 3 passed
```

### 后端全量 `mvn clean test`（回归说明）
```
Tests run: 355, Failures: 0, Errors: 12, Skipped: 0
```
12 个 Errors 均为既有 `InvariantIntegrationTest` / `TesterReadToolsTest` / `TesterWriteToolsTest` / `ToolRegistryIntegrationTest` 在 `ConfigVersion 不存在: v001` 上的预存失败（DB fixture 缺种子，非 P7/P6-4 引入），已在 P7 冻结前存在；P7/P6-4 相关 12 个专项测试全部 PASS。P6-4 报告时的 `342 BUILD SUCCESS` 为当时的全量计数快照，当前 355 含新增的 P6-4/P7 测试。

### 前端
```
typecheck (tsc --noEmit): EXIT 0
build     (vite build)   : 243.87 kB / gzip 74.64 kB, EXIT 0
test      (vitest run)   : 16 passed (App.test 2 + SSE 5 + ConfigDiffPage 9), EXIT 0
```

---

## 5. 已知限制 / 透明度声明

1. **designDocumentCount**：P7-1 不在范围内聚合 design_document；若表存在返回真实计数，否则 0。未伪造。
2. **TD-006**：Experiment Center 图表使用 P5 winRate 代理，未渲染虚假 DPS 方差（DPS 仅作展示列，非统计推断源）。
3. **Run Trace SSE**：右栏详情刷新依赖 `useTesterEventSignal` 全量 invalidate；未为 `/sse/runs/{id}` 做细粒度增量（保持 P7-2 只读消费、不扩展后端 SSE 协议）。
4. **P7-5 Diff 语义**：`parseDiff` 按 path 合并 `-`/`+` 为 `~` 修改行，忠实于后端 `ConfigDiffer.toUnifiedText()` 的逐字段 `- path: val` / `+ path: val` 行格式；未引入额外 diff 算法。
5. **全量后端 12 Errors**：既有集成测试 fixture 问题，非回归；详见 §4。

---

## 6. 裁决结论

- ✅ P7-1 / P7-2 / P7-3 / P7-4 / P7-5 全部完成并通过验收测试（含 P6-4 解锁后的 P7-5）。
- ✅ 全部 P7 红线达标，无越界（含 P7-5 无 rollback/activate/active）。
- ✅ P6-4 ConfigController 4 endpoint 已按人工裁决交付并被 P7-5 消费（见 `Sprint_P6-4_Checkpoint_Report.md`）。
- 🚫 未进入 P8。
- **进入 WAITING_ACCEPTANCE**：等待用户裁决是否接受 P7（含 P7-5），或指示后续动作。
