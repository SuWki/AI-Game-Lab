# QA P1-5 实施报告（Isolation & Regression Deepening）

> 归属：QA P1 Sprint，Task P1-5（Isolation & Regression Deepening）。
> 状态：**QA P1-5 COMPLETE**（§20：不单独发 WAITING FOR HUMAN ACCEPTANCE，等待 P1-6 全 Phase 完成后统一发）。
> 生成时间：2026-09-15。
> 前置依赖：P1-1 ✅、P1-2 ✅、P1-3 ✅、P1-4 ✅（含 Closure ✅）。
> 冻结边界：本阶段零生产代码改动，纯隔离回归 + 测试补强 + 文档。

---

## A. Audit

审计范围按 §2 规定逐一覆盖，先读代码、再写测试。

### 实际审计的组件

| 组件 | 审计内容 | 审计结论 |
|---|---|---|
| `AgentType.java` | 枚举值集合：DESIGNER / TESTER / RESEARCH / QA（单值） | 无新问题；QA 值由 P1-1 显式添加 |
| `DefaultAgentTypeResolver.java` | resolve() 路径：显式 agent_type → ACTIVE TestPlan → TESTER → DESIGNER；**回退永远不会落到 QA** | 确认 A1/A2/A3 隔离缺口存在，需补测试 |
| `AgentTypeResolver.toolWhitelist()` | QA={finding_report}；TESTER 包含 finding_report；DESIGNER/RESEARCH 不含 | 确认 B1 隔离缺口存在，需补测试 |
| `FindingReportTool.java` | execute() 写 Finding + Evidence | 复用无问题，session validation 正确 |
| `FindingService.java` | lifecycle 白名单（P1-2 固定）、findByQaFilter（P1-3）、validateFilter | P1-2 规则未被 P1-4/Closure 改动影响 |
| `FindingRepositoryImpl.java` | findByQaFilter 动态 SQL、runIds IN 参数绑定 | 正确，无副作用 |
| `schema.sql` | finding 表结构：无 project_id（单项目架构）；test_plan.status 默认 'DRAFT' | project_id 不存在；ACTIVE TestPlan 必须显式 INSERT |
| `TesterController.java` | GET /api/findings（3 参：configVersion/severity/runId） | 隔离无问题；QA-only 参数通过 Spring 忽略 |
| `QaController.java` | GET /api/qa/findings（8 维 AND 过滤） | 隔离无问题；与 Tester 端点独立 |
| `TestPlanService.java` | findActiveByTask 按 status='ACTIVE' 过滤 | DRAFT 的 TestPlan 不触发 TESTER 回退 |
| `ErrorCodes.java` | 6 个码：40001/40401/40901/42901/50201/50000 | 无新增 |
| `api.ts`（前端） | listFindings → /api/findings；listQaFindings → /api/qa/findings | Closure 已正确分离 |
| `TestPlanPage.tsx` | AssociatedFindings（行 105）调用 api.listFindings()（无参）→ /api/findings | 正确；不调用 /api/qa/findings |
| `FindingsPage.tsx` | 调用 api.listQaFindings(filter) → /api/qa/findings | 正确；不调用 /api/findings |

### P1-1 / P1-2 / P1-3 / P1-4 / P1-4 Closure 授权外改动审计

审计结论：**P1-1 至 P1-4 + Closure 全部按授权范围实施，未发现未经授权的额外改动。**

### 隔离缺口发现

审计过程中确认以下 **6 个隔离缺口**，在本轮测试中填补：

| 缺口 | 含义 | 填补测试 |
|---|---|---|
| Agent 显式覆盖回退 | 无测试证明 QA 显式指定优先于 ACTIVE TestPlan 回退 | A1 |
| 回退永远不会落到 QA | 无测试显式断言 "default fallback + ACTIVE plan → TESTER，且结果 ≠ QA" | A2 |
| 回退无 plan 时不可能返回 QA/RESEARCH | 无测试显式断言 "default fallback + 无 plan → DESIGNER，且结果 ≠ QA/RESEARCH" | A3 |
| QA 白名单全矩阵互斥 | 无单一测试覆盖 QA/TESTER/DESIGNER/RESEARCH 四方 finding_report 边界 | B1 |
| QA Finding 跨端点可见 | 无测试证明 QA 创建的 Finding 在两个 REST 端点均可查到（共享表验证） | C1 |
| Finding 共存无交叉污染 | 无测试证明 QA + Tester Finding 写入同一表后各端点按过滤正确返回 | C2 |
| Tester 端点忽略 QA 参数 | 无测试证明 /api/findings 忽略 category 等 QA-only 参数 | D1 |
| OPEN→FIXED 迁移 40901 | 无 Controller 层测试覆盖 P1-2 关键非法边界 OPEN→FIXED | E1 |
| Missing Finding status → 40401 | 无测试覆盖 POST /api/findings/{id}/status 不存在的 Finding | E2 |
| ErrorCodes 数量守卫 | 无测试防止 P1-5 引入新 ErrorCode | F1 |

---

## B. Isolation Matrix

本轮通过测试断言验证的隔离矩阵：

| Boundary | QA | TESTER | DESIGNER | RESEARCH |
|---|---|---|---|---|
| finding_report（白名单） | ALLOW | ALLOW | DENY | DENY |
| default agent route | ❌ 永不回退 | ✅ TestPlan-driven | fallback | explicit only |

### B1 白名单矩阵证据

```java
// QaP1_5Test.qaToolWhitelistMatrix()
assertThat(qa).containsExactly("finding_report");        // QA = {finding_report} exactly
assertThat(qa).isSubsetOf(tester);                        // QA ⊆ TESTER
assertThat(designer).doesNotContain("finding_report");   // DESIGNER ∩ QA = ∅
assertThat(research).doesNotContain("finding_report");   // RESEARCH ∩ QA = ∅
```

### A1/A2/A3 Agent 路由证据

```java
// A1: explicit agent_type=QA + ACTIVE TestPlan → QA（不是 TESTER）
Task task = newTask("{\"agent_type\":\"QA\"}");
insertActiveTestPlan(task.id());
assertThat(resolver.resolve(task)).isEqualTo(AgentType.QA);

// A2: default（无 agent_type）+ ACTIVE TestPlan → TESTER（不是 QA）
Task task = newTask("{}");
insertActiveTestPlan(task.id());
assertThat(resolver.resolve(task)).isEqualTo(AgentType.TESTER);

// A3: default（无 agent_type）+ 无 plan → DESIGNER（不是 QA/RESEARCH）
Task task = newTask("{}");
assertThat(resolver.resolve(task)).isEqualTo(AgentType.DESIGNER);
```

---

## C. API Matrix

本轮验证并断言的端点分离：

| Consumer | Endpoint | 调用方 | 验证方式 |
|---|---|---|---|
| QA | `/api/qa/findings` | FindingsPage.tsx `api.listQaFindings()` | C1：findReport 创建后 GET /api/qa/findings 可见；TestPlanPage.test.tsx 证明 Tester 不调用此端点 |
| TESTER | `/api/findings` | TestPlanPage.tsx `api.listFindings()` | C1：findReport 创建后 GET /api/findings 可见；D1：category 参数被忽略 |

### 端点分离不回归

- P1-4 Closure 已将 `api.listFindings()` 恢复为 `GET /api/findings`（Tester 3 参）
- P1-5 无任何前端改动，端点分离保持不变
- TestPlanPage.test.tsx (P1-5 新增) 验证 Tester 页面绝不调用 `/api/qa/findings`：
  ```typescript
  findingCalls.forEach((c) => expect(c).not.toContain("/api/qa/findings"));
  ```

---

## D. Data Isolation

### project_id 行为

审计结果：**finding 表中无 project_id 列**。AGL 为单项目架构（Single-Project）。

```sql
-- schema.sql finding 表结构
finding(
  id, run_id, scenario_id, config_version, severity, category,
  source, title, expected, actual, repro_steps, evidence_json,
  status, test_plan_id, task_id, reproduction_rate, created_at
)
-- 无 project_id
```

| 行为 | 结论 |
|---|---|
| QA 写入 Finding | ✅ 正常落库，scenario_id（NOT NULL）正确填充 |
| Tester 写入 Finding | ✅ 原有行为不变（P1-2/P1-3 已验证） |
| QA 查询 | ✅ 通过 `/api/qa/findings` 8 维过滤查询共享表 |
| Tester 查询 | ✅ 通过 `/api/findings` 3 参查询共享表 |
| 跨端点可见 | ✅ C1 证明：同一 Finding 在两个端点均可见 |
| 过滤隔离 | ✅ C2 证明：QA 和 Tester Finding 按 configVersion 过滤，无交叉污染 |

**结论：单项目架构下，shared Finding domain 的隔离通过端点层过滤实现，不需要 project_id。**

---

## E. Regression

### 测试结果

#### 后端（mvn -o test）

| 运行条件 | 测试数 | Failures | Errors | 结果 |
|---|---|---|---|---|
| P1-5 之前基线 | 551 | 0 | 0 | BUILD SUCCESS |
| 顺序执行（mvn -o clean test） | **561** | **0** | **0** | BUILD SUCCESS |
| 方法级随机 seed=7 | **561** | **0** | **0** | BUILD SUCCESS |
| 类级随机 runOrder=random | **561** | **1** | **0** | BUILD FAILURE* |

*类级随机失败项：`RepositoryTest.eventReplayAfterIdAndRecent:315`（Expected size 3 but was 5）——此为既有 TD-QA-R-001（共享 static DB + sqlite_sequence 不重置），类级随机序下偶发。两次重运行结果一致，同一测试类。§15 明确规定本轮不得修复 TD-QA-R-001，已登记在 F 节。

**delta：+10（新增 QaP1_5Test 10 项）。**

#### 前端（npx vitest run）

| 运行条件 | 测试数 | Failures | Errors | 结果 |
|---|---|---|---|---|
| P1-5 之前基线 | 53 | 0 | 0 | PASS |
| 9 文件全量回归 | **56** | **0** | **0** | PASS |

**delta：+3（新增 TestPlanPage.test.tsx 3 项）。**

#### TypeScript 类型检查

| 命令 | 结果 |
|---|---|
| `npx tsc -b --noEmit` | **0 errors** |

### 首次全量回归 flaky 注记

首次 `mvn -o clean test` 运行出现 561 tests / 1F / 27E，失败全部来自 `ResearchControllerTest`（ApplicationContext load failure threshold exceeded），与 P1-5 无关——同一测试类在单独运行时通过（13/0/0）。重新运行时 561/0/0 BUILD SUCCESS。判定为环境/context-load flakiness（JDK 24 + SpringBootTest 并行上下文缓存竞争），非 P1-5 引入。

### 新增测试明细

#### QaP1_5Test.java（后端 10 项）

| 测试 ID | 隔离维度 | 覆盖 |
|---|---|---|
| A1 | Agent | explicit QA + ACTIVE TestPlan → QA（非 TESTER） |
| A2 | Agent | default fallback + ACTIVE TestPlan → TESTER（≠ QA） |
| A3 | Agent | default fallback + 无 plan → DESIGNER（≠ QA/RESEARCH） |
| B1 | Tool | 4 型白名单全矩阵：QA={finding_report}, QA⊆TESTER, QA∩DESIGNER=∅, QA∩RESEARCH=∅ |
| C1 | Data | QA 创建的 Finding 在 /api/qa/findings 和 /api/findings 均可见 |
| C2 | Data | QA + Tester Finding 共存，各端点过滤无交叉污染 |
| D1 | REST | /api/findings 忽略 category（QA-only param），3 参契约不变 |
| E1 | Lifecycle | OPEN→FIXED 非法迁移 → 40901 + DB 状态不变 |
| E2 | Lifecycle | POST /api/findings/{id}/status Missing Finding → 40401 |
| F1 | Contract | ErrorCodes 6 码不变（40001/40401/40901/42901/50201/50000） |

#### TestPlanPage.test.tsx（前端 3 项）

| 测试 | 覆盖 |
|---|---|
| 端点隔离 | AssociatedFindings 调用 GET /api/findings，绝不调用 /api/qa/findings |
| 页面渲染 | TestPlan 元信息 + scenarios 正确渲染 |
| 客户端过滤 | 有 testPlanId 匹配的 Finding 渲染表格，testPlanId 不匹配的被过滤 |

---

## F. Technical Debt

### 本轮发现的既有债务（保持不变）

| 编号 | 说明 | 状态 |
|---|---|---|
| TD-QA-R-001 | RepositoryTest 共享 static DB + `sqlite_sequence` 不重置（类级随机偶发失败） | OPEN / 豁免，类级随机复现（E 节），按 §15 不修复 |
| TD-QA-P11-001 | ToolExecutor dispatch 层无角色校验 | OPEN / Frozen Boundary 内，本轮不修 |
| X-5 | useSseDrivenRefresh / useRunSse 死导出 | OPEN / 已知 |
| X-6 | 页面测试覆盖不足 | OPEN / 已知 |
| X-7 | configVersions vs config-versions 查询键不一致 | OPEN / 已知 |

### 本轮新增债务

**无。**

本阶段零生产代码改动，不引入新债务。

---

## G. Scope

### 本轮做什么

- ✅ 审计隔离边界（§2 要求，先读代码）
- ✅ 隔离回归测试（后端 10 项 + 前端 3 项，证明 Agent / Tool / Data / REST / Lifecycle / Contract 边界）
- ✅ 全量回归（顺序 / 方法级随机 seed=7，后端 561/0/0，前端 56/0/0）
- ✅ 文档（本文档）

### 本轮不做什么

| 禁止项 | §16 依据 | 是否触碰 |
|---|---|---|
| 新 QA Agent | §16 禁止 | ❌ 未触碰 |
| QA_LEAD | §16 禁止 | ❌ 未触碰 |
| Multi-Agent | §16 禁止 | ❌ 未触碰 |
| Release Gate | §16 禁止 | ❌ 未触碰 |
| Phase 2 | §16 禁止 | ❌ 未触碰 |
| 新 QA Tool | §16 禁止 | ❌ 未触碰 |
| 新 API | §16 禁止 | ❌ 未触碰 |
| 新 DB table | §16 禁止 | ❌ 未触碰 |
| schema redesign | §16 禁止 | ❌ 未触碰 |
| ToolExecutor 重构 | §4 / §17 禁止 | ❌ 未触碰 |
| AgentLoop 修改 | §17 禁止 | ❌ 未触碰 |
| ErrorCodes 新增 | §11 / §17 禁止 | ❌ 未触碰 |
| P1-2 lifecycle 修改 | §9 / §17 禁止 | ❌ 未触碰 |

### Frozen Boundary 状态

| 冻结对象 | 是否修改 |
|---|---|
| AgentLoop | ❌ |
| RunStateMachine | ❌ |
| BudgetGuard | ❌ |
| LoopDetector | ❌ |
| ToolExecutor | ❌ |
| ToolResult | ❌ |
| ErrorCodes | ❌ |
| schema.sql | ❌ |
| FindingService lifecycle | ❌ |

**本轮明确未进入 Phase 2。**

---

## §19 执行规则

P1-5 完成后继续执行 P1-6（Acceptance / Contract Matrix），不等待人工验收。
只有整个 QA P1 Phase（P1-1..P1-6）全部完成后才输出：

```
QA P1 COMPLETE — WAITING FOR HUMAN ACCEPTANCE
```

---

## Appendix: Service & Endpoint 验证清单

| Service/Endpoint | 上一轮审计 | 本轮结论 |
|---|---|---|
| AgentTypeResolver (explicit QA) | P1-1 | ✅ 无变更 |
| DefaultAgentTypeResolver (回退逻辑) | P1-1 | ✅ 回退永远不落入 QA |
| AgentTypeResolver.toolWhitelist (QA) | P1-1 | ✅ {finding_report} exactly |
| FindingReportTool (execute) | P1-1 | ✅ 按 session validation 写入 |
| FindingService (lifecycle) | P1-2 | ✅ P1-2 matrix 不变 |
| FindingService (expected/actual) | P1-2 | ✅ Evidence required 字段不变 |
| FindingRepository (findByQaFilter) | P1-3 | ✅ 8 维 AND + runIds IN 参数绑定 |
| QaController (GET /api/qa/findings) | P1-3 | ✅ 包络 ApiResp，40001/40401 一致 |
| TesterController (GET /api/findings) | 既有 | ✅ 3 参契约不变 |
| api.listFindings (/api/findings) | P1-4 Closure | ✅ Tester 端点 |
| api.listQaFindings (/api/qa/findings) | P1-4 Closure | ✅ QA 端点 |
| FindingsPage (8 维过滤 UI) | P1-4 | ✅ 不回归 |
| TestPlanPage (关联 Findings) | 既有 | ✅ /api/findings 不变 |
| ErrorCodes (6 码) | 既有 | ✅ 数量和数值不变 |
| Finding 表结构 (无 project_id) | 既有 | ✅ 单项目架构，端点层过滤 |
