# Sprint T7 验收报告（Checkpoint Report）

**状态：✅ COMPLETE → WAITING_ACCEPTANCE**
**范围遵守：全部 T7 冻结红线达标；构建完整"配置 → 测试 → Finding → 回归差异"闭环；未进入 T8。**

---

## 1. 执行范围与阶段顺序

| 阶段 | 任务 | 状态 | 交付物 |
|------|------|------|--------|
| T7-1 | GameSessionService × ConfigVersion 校验对齐 | ✅ DONE（验证，零改生产代码） | 契约验证（start() 40401 走非 critical 路径） |
| T7-2 | Finding 跨版本逻辑身份 | ✅ DONE | `FindingIdentity` + `FindingFingerprintService`（9 测试） |
| T7-3 | Finding 回归对比服务 | ✅ DONE | `FindingRegression`(Item) + `FindingRegressionService`（5 测试） |
| T7-4 | Config 提案 → 审批 → 回归 E2E | ✅ DONE | `ConfigProposalApprovalRegressionIntegrationTest`（3 测试） |
| T7-5 | TestReportCreateTool 回归摘要增量 | ✅ DONE | `TestReportCreateTool` 5 参增量 + `TestReportRegressionSectionIntegrationTest`（3 测试） |
| T7-6 | 回归对比 REST Endpoint | ✅ DONE | `TesterController` 新增 `GET /api/findings/regression` + 前端 `ConfigRegressionPage`（7 测试） |
| T7-7 | Challenge Matrix × Regression 收口 | ✅ DONE | `TesterRegressionIntegrationTest`（5 测试，#7~#11） |

执行顺序：**T7-1 → T7-2 → T7-3 → T7-4 → T7-5 → T7-6 → T7-7**，连续 Sprint，仅末尾提交**单一 T7 Checkpoint**。

---

## 2. 各任务交付明细

### T7-1 GameSessionService × ConfigVersion 校验对齐（验证）
- 验收目标：T6-2 §1 已确认 `Task.constraints_json.configVersion → TestPlan.configVersion → Finding.config_version` 单一事实源。
- 结论：`GameSessionService.start()` 经 `GameStartTool` → `ConfigVersionService.findByVersion()` 在未知版本抛 `BusinessException(40401)` → `AbstractTesterTool` 翻译为非 critical `ToolException` → `tool_call=FAILED`、Run 继续。该冻结链路契约正确，**无需新代码**。
- 边界：**未修改** `GameSessionService` / `GameStartTool` / `ConfigVersionService` 任何生产代码；无 schema 变更。验证证据见 T7-7 / T7-4（基线与被测版本均取自真实 `ConfigVersionService`）。

### T7-2 Finding 跨版本逻辑身份
- **`backend/.../domain/FindingIdentity.java`**（新增）：纯逻辑标识（无 DB 映射），三元组 `scenarioId + category + normalizedTitle`；常量 `PLACEHOLDER`。
- **`backend/.../tester/FindingFingerprintService.java`**（新增，`@Service`）：`fingerprint(Finding) → FindingIdentity`；`normalizeTitle` = `title.trim().toLowerCase().replaceAll("\\s+", " ")`；null/blank 抛 `IllegalArgumentException`。
- 设计：T-DB1 DDL Review 明确"去重是 Service/人责"，故**不修改 schema**，纯 Service 层；对冻结 `FindingRepository` 零影响。
- 测试 `FindingFingerprintServiceTest` **9/9 PASS**。

### T7-3 Finding 回归对比服务
- **`backend/.../domain/FindingRegressionItem.java`**（新增 record）：`fingerprint / status(NEW|FIXED|PERSISTED|CHANGED) / oldSeverity / newSeverity / title / scenarioId / category`。
- **`backend/.../domain/FindingRegression.java`**（新增 record）：`oldConfigVersion / newConfigVersion / items / *Count`；`of(...)` 工厂按 items 统计四类。
- **`backend/.../tester/FindingRegressionService.java`**（新增，`@Service`）：`diff(old, new)` 取两组 Finding → `pickHighestSeverityByFingerprint`（同键取最高 severity，BLOCKER(0)>HIGH>MEDIUM>LOW>INFO）→ 分类 NEW/FIXED/PERSISTED/CHANGED → 稳定排序 PERSISTED<CHANGED<FIXED<NEW。纯内存，不改 DB/schema/冻结服务。
- 测试 `FindingRegressionServiceIntegrationTest` **5/5 PASS**（真实 DB）。FIXING×1：severity 排序原取最不严重，经 `dedupByFingerprintPicksMaxSeverity` 捕获后改为 `<`（BLOCKER index 0 胜）。

### T7-4 Config 提案 → 审批 → 回归 E2E
- 闭合 T6 配置链路：PENDING 提案 → `ApprovalService.approve()` → 新建 append-only `ConfigVersion`（latest）→ 两版各产 Finding → `diff()` 输出四类。
- 测试 `ConfigProposalApprovalRegressionIntegrationTest` **3/3 PASS**。FIXING×2：① 共享 per-class 静态临时 DB 版本号跨 @Test 递增 → 改用 `latestVersion()`/`seqOf()` 动态取值；② Case 3 逻辑反向（v001 有 v002 无 → FIXED 非 NEW）→ 修正断言。

### T7-5 TestReportCreateTool 回归摘要增量
- **修改 `TestReportCreateTool.java`（冻结 T3-3 Tool，仅增量）**：新增可选 `baselineVersion` 参数（jsonSchema + doExecute）；构造器 4→5 参（注入 `FindingRegressionService`）；提供时附"## 回归对比（baseline → new）"Markdown + payload/事件附 `regressionNew/Fixed/Persisted/Changed` + `baselineVersion`。未改既有报告生成/落库/事件语义；无新 Tool、无新 endpoint。
- 测试 `TestReportRegressionSectionIntegrationTest` **3/3 PASS**。FIXING×2：① 报告正文读 `content_md`（非 `content`）；② 测试 Finding 补 `runId`（findByFilter 按 runId 过滤）。

### T7-6 回归对比 REST Endpoint
- **修改 `TesterController.java`（冻结 T4-5 7 端点，仅追加 1 端点）**：`GET /api/findings/regression?oldVersion=&newVersion=`；参数缺失抛 `BusinessException(INVALID_ARGUMENT)`；构造器 4→5 参（注入 `FindingRegressionService`）；既有 7 端点未动。
- **前端配套**：`types.ts`（FindingIdentity/FindingRegressionItem/FindingRegression）、`api.ts`（`getFindingRegression`）、`pages/ConfigRegressionPage.tsx`（版本下拉 + 4 StatBadge + 结果表，用 `react-query` 与既有栈一致）、`App.tsx`（Nav + Route `/regression`）。
- 验证：后端 `TesterControllerTest` **7/7 PASS**（既有端点未破坏）；前端 `tsc --noEmit` 0 error + `vitest` 16/16 PASS。

### T7-7 Challenge Matrix × Regression 收口
- 测试 `TesterRegressionIntegrationTest` **5/5 PASS**（真实 DB，复用 T7-2/3 服务层，不修改冻结文件）：
  - #7 PERSISTED：同缺陷 severity 不变 → `persistedCount==1`
  - #8 CHANGED：LOW→HIGH → `changedCount==1`
  - #9 FIXED：旧有新版无 → `fixedCount==1`，newSeverity=null
  - #10 NEW：新版引入 → `newCount==1`，oldSeverity=null
  - #11 四类齐全：4 状态计数各 1、items 含全部
- 配合 T7-4（审批→回归）/ T7-5（报告摘要）覆盖完整闭环。

---

## 3. 红线合规核查（T7 冻结清单）

| 红线 | 核查结果 |
|------|----------|
| 不改 schema（无 DDL） | ✅ 未新增/修改任何表；逻辑身份纯 Service 层 |
| 不改 Finding/Evidence 契约 | ✅ `Finding` record 未改；仅新增独立 `FindingIdentity`/`FindingRegression` |
| 不改 GameSessionService（除 T7-1 验证外） | ✅ T7-1 为零改生产的验证 Task |
| 不改 8 Tester Tools（除 TestReportCreateTool 增量） | ✅ 仅 TestReportCreateTool 新增可选参数 + 注入既有 Service，未重写 |
| 不改 GameAdapter SPI / FakeGameEnvironment | ✅ 冻结文件零触碰 |
| 不改 tester_p001 / AgentLoop 双轨 / Tool subset | ✅ 未触碰 |
| 不改 TEST_* Event 枚举 | ✅ 仅复用既有 `TEST_REPORT_CREATED` |
| 不改 Tester REST API（仅追加 1 端点） | ✅ 既有 7 端点未动，新增 `/findings/regression` 为授权范围 |
| 不改 DB / 错误码契约 | ✅ ErrorCodes 仅调用 `INVALID_ARGUMENT`（既有），未新增 |
| 不新增依赖 / 第二架构 | ✅ 无 pom 变更、无新第三方库 |
| 不改并行 Designer WIP | ✅ 未修改、未隔离、未绕过 |

---

## 4. 测试结果汇总

### T7 专项（隔离 JDK24，5 测试类）
```
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0  →  BUILD SUCCESS
  - FindingFingerprintServiceTest:                        9 passed
  - FindingRegressionServiceIntegrationTest:             5 passed
  - ConfigProposalApprovalRegressionIntegrationTest:     3 passed
  - TestReportRegressionSectionIntegrationTest:          3 passed
  - TesterRegressionIntegrationTest:                     5 passed
```

### 前端
```
typecheck (tsc --noEmit): EXIT 0
test      (vitest run)   : 16 passed (App 2 + SSE 5 + ConfigDiffPage 9)
```
（ConfigRegressionPage 经 tsc 类型覆盖 + App 路由集成校验；既有 16 测试全过，无回归）

### 后端全量 `mvn test`（回归说明）
```
Tests run: 371, Failures: 0, Errors: 13, Skipped: 0  →  BUILD FAILURE
```
- **13 errors 全部为 pre-existing 全量测试隔离/上下文污染问题（TD-P8-1-001），非 T7 引入**，证据：
  1. **隔离运行同一 4 个类（JDK24）= 29/29 PASS**（ConfigControllerTest 9 + InvariantIntegrationTest 3 + TesterReadToolsTest 8 + TesterWriteToolsTest 9）；
  2. 错误成员与 `docs/state/task-state.md` P8-1-4 节记录的 TD-P8-1-001 **逐字节一致**（TesterWriteToolsTest 7 + TesterReadToolsTest 2 + InvariantIntegrationTest 3 + ConfigControllerTest 1）；
  3. 12 个 `40401 ConfigVersion 不存在: v001` 来自 `GameSessionService.start()` 路径，而 T7 仅改 `TestReportCreateTool`（可选参数）/ `TesterController`（新端点）构造器，均**不触碰** v001 校验路径；
  4. 其余 **358 个后端测试全过**（含 T7 专项 25 + T6 基线 333 + 其他），T7 **零新增失败**。

---

## 5. 已知限制 / 透明度声明

1. **TD-T7-001（测试隔离技术债）**：full `mvn test` 的 13 errors 根因为全量 Spring 测试上下文/DB 状态污染（per-class 临时 DB 在大量测试顺序执行时 v001 种子未就绪），与 P8-1 TD-P8-1-001 同源。属测试基建，非 T7 业务缺陷，不现在修（按红线不顺手改测试基建）；建议后续独立 Task 统一 temp DB 播种策略。
2. **TD-T6-001 仍有效**：`FindingService.report()` 未强校验 `configVersion` 真实存在（冻结代码 gap），T7 未处理（非本 Sprint 范围，继续登记）。
3. **回归端点参数校验**：`GET /api/findings/regression` 对缺失参数抛 `INVALID_ARGUMENT`，对不存在版本返回空对比（diff 两组均空 → 全 0 项），符合预期。
4. **整库未纳入 git**：当前 `git status` 显示 `backend/ frontend/ docs/` 等仍 untracked（初始 commit 仅含 README 骨架），T7 改动随整库在人工验收后统一提交，非 T7 责任。

---

## 6. 裁决结论

- ✅ T7-1 ~ T7-7 全部完成并通过验收测试（25/25 专项 PASS）。
- ✅ 完整闭环交付：配置版本（T6 链路）→ Tester Finding（T3-T6）→ 跨版本逻辑身份（T7-2）→ 回归 diff 服务（T7-3）→ 提案审批回归（T7-4）→ 测试报告回归摘要（T7-5）→ REST 端点 + 前端页（T7-6）→ 挑战矩阵收口（T7-7）。
- ✅ 全部 T7 冻结红线达标，无越界（仅 1 个追加授权端点 + 1 个冻结 Tool 的可选参数增量 + 纯 Service 层）。
- ⚠️ 全量 13 errors = pre-existing TD-P8-1-001 隔离问题，**非 T7 引入、零新增失败**（隔离运行 29/29 直接排除）。
- 🚫 未进入 T8。
- **进入 WAITING_ACCEPTANCE**：等待用户裁决是否接受 T7，或指示后续动作（含 TD-T7-001 是否立独立 Task 修复）。
