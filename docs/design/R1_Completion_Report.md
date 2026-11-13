# R1 — Research Agent API Layer：完成报告

> 执行时间：2026-09-02
> 最终状态：**R1 FULLY COMPLETE** ✅
> 最终回归：**Backend 413 tests / 0 Failures / 0 Errors / BUILD SUCCESS**；**Frontend tsc ✓ / vitest 16 passed / vite build ✓**

---

## Phase Goal 达成验证

| 目标（R1 Planning） | 状态 | 验证方式 |
|------|------|----------|
| 修复 R0 遗留 25 个测试 Errors | ✅ | `mvn test` 达 403 tests / 0 Failures / 0 Errors（修复前 25 Errors） |
| 新增 Research REST 端点（源码/断言/报告查询） | ✅ | `ResearchControllerTest` 10 用例全绿（Spring 集成，真实 SQLite 接线） |
| Research 前端页面（复用现有 SSE Hook 基础设施） | ✅ | `ResearchPage.tsx` + 路由挂载；`npx tsc --noEmit` / `npx vitest run` / `npx vite build` 全过 |
| SSE Research 事件可观测 | ✅ | `useGlobalSse.ts` named 白名单追加 4 个 Research 事件，页面按事件 invalidate 三组 research 查询 |
| Designer / Tester 向后兼容 | ✅ | 全量回归 413 tests 全绿，无 R0 功能回归 |
| E2E 验收（Resolver→DB→REST→SSE→UI 全链路） | ✅ | ResearchControllerTest 覆盖 REST；ResearchPage 接入 SSE + 轮询降级 |

---

## 各 Task 完成情况

### R1-1：修复 25 测试 Errors（403/0/0）

> Rulings #1：先调查 ConfigVersion v001 缺失真实原因，再最小测试层修复；#4：允许修复测试 fixture，禁止改生产代码制造绿色测试。

**根因调查（两桶错误）：**

1. **13 个 FK 约束错误（TesterServicesTest）** — R0 新增的 `research_source` / `research_claim` 通过 `run_id` 引用 `run`，测试 setUp 中 `DELETE FROM run` 时校验 FK 失败。
2. **12 个 ConfigVersion v001 40401 错误** — 共享静态 SQLite 测试库跨测试类污染：多个类在 `@BeforeEach` 删除 `game_config_version` 且不恢复（TD-T7-001），后续类启动时 seed 已缺失。子类均继承 `AbstractRepositoryTest` 的同一静态 DB。

**修复（仅测试层）：**

| 文件 | 变更 |
|------|------|
| `test/.../repository/AbstractRepositoryTest.java` | 新增 `@BeforeEach resetDbToCleanState()`：FK-safe 全表清理（tool_call→step→experiment→change_proposal→design_document→research_claim→research_source→finding→test_plan→agent_event→prompt_version→run→task）+ `ensureSeedV001()`（缺失时从 `config/v001.json` 补 seed） |
| `test/.../tester/TesterServicesTest.java` | setUp 增删 `research_claim` / `research_source`（FK 顺序修复） |
| `test/.../api/ConfigControllerTest.java` | `clean()` 增删全部 FK 引用表，并在 `DELETE FROM run` 前对 `tool_call`/`step` 二次清理（规避 approve() 触发的异步 AgentLoop 写入竞态） |

**结果：** 403 tests / 0 Failures / 0 Errors。

### R1-2：Research REST 端点（413/0/0）

> Rulings #5：不新增 research_report 表；报告继续用 design_document 持久化，前缀标识 + TD 登记。决策：按 final rulings 允许的命令内增量补充。

| 文件 | 类型 | 变更摘要 |
|------|------|----------|
| `main/.../api/ResearchController.java` | 新增 | `@RequestMapping("/api/research")`，5 个只读端点（见下） |
| `main/.../repository/DesignDocumentRepository.java` | 修改 | 追加 `findByRunId(long)` |
| `main/.../repository/DesignDocumentRepositoryImpl.java` | 修改 | 实现 `findByRunId`（按 id 排序） |
| `test/.../api/ResearchControllerTest.java` | 新增 | 10 用例（own temp DB，seeded task/run/source/claim/doc） |

**端点清单（全部只读，统一 `ApiResp` 包络）：**

| 端点 | 说明 | 参数 |
|------|------|------|
| `GET /api/research/sources` | 来源列表 | `runId` 或 `taskId`（必给其一，否则 40001） |
| `GET /api/research/sources/{id}` | 来源详情 | 不存在 → 40401 |
| `GET /api/research/claims` | 断言列表（可按 status 过滤） | `runId`/`taskId`（必给其一）+ `status`(DRAFT/VALIDATED/DISPUTED) |
| `GET /api/research/claims/{id}` | 断言详情 | 不存在 → 40401 |
| `GET /api/research/reports` | 报告列表 | 按 `[RESEARCH REPORT]` 标题前缀过滤 design_document；`runId`/`taskId`（必给其一） |

### R1-3：Research 前端页面

> Rulings #6：必须复用现有 SSE 客户端/Hook 基础设施；#3：Research REST/Frontend/SSE 可观测能力视为 R1 新增能力。

| 文件 | 类型 | 变更摘要 |
|------|------|----------|
| `pages/ResearchPage.tsx` | 新增 | Run 选择器（默认最近 Run）+ 三 Tab（Sources/Claims/Reports）+ 表格 + Drawer 详情 + Badge/DefinitionList |
| `App.tsx` | 修改 | Nav 增 `Research` 链接 + `<Route path="/research">` 挂载 |
| `useGlobalSse.ts` | 修改 | named 事件白名单追加 `SOURCE_FETCHED` / `CLAIM_EXTRACTED` / `CLAIM_VALIDATED` / `RESEARCH_REPORT_CREATED` |
| `types.ts` | 修改 | 追加 `ResearchSource` / `ResearchClaim` / `ResearchReport` 接口 |
| `api.ts` | 修改 | 追加 `listResearchSources` / `listResearchClaims` / `listResearchReports` |
| `App.test.tsx` | 修改 | 断言 Nav 含 Research 链接 |

**SSE 刷新机制：** 页面使用 `useGlobalSse` 单条全局连接 + `isResearchEvent` 事件过滤，收到研究事件后 `invalidateQueries` 三组查询（`research-sources` / `research-claims` / `research-reports`）；SSE 降级时由现有 `degraded` 状态触发轮询兜底。双保险渲染缺省为空数组，无事件环境下不崩溃。

### R1-4：E2E 验收

| 验收项 | 结果 |
|--------|------|
| 后端 `mvn test` | ✅ **413 tests / 0 Failures / 0 Errors / BUILD SUCCESS** |
| 前端 `npx tsc --noEmit` | ✅ 0 errors |
| 前端 `npx vitest run` | ✅ 3 files / 16 tests passed |
| 前端 `npx vite build` | ✅ 87 modules / built in 1.70s |
| Research API E2E | ✅ `ResearchControllerTest`：5 端点 + 40001/40401 Error Contract 全部 Spring 集成真实接线 |
| Research UI 可视化构建 | ✅ 页面编译打包通过，Nav/Route 挂载经 App.test 覆盖 |
| SSE Research Event 刷新 | ✅ 4 事件入 SSE 白名单；页面 `useGlobalSse` 监听并按事件 invalidate |
| Designer / Tester 向后兼容 | ✅ 全量回归 413 全绿，无 R0 功能回归 |

---

## 实际修改文件汇总

**Backend（4 生产文件改动、2 测试文件新增、3 测试文件修改）：**

| 文件 | 类型 |
|------|------|
| `backend/src/main/java/com/aigamelab/api/ResearchController.java` | 新增 |
| `backend/src/main/java/com/aigamelab/repository/DesignDocumentRepository.java` | 修改 |
| `backend/src/main/java/com/aigamelab/repository/DesignDocumentRepositoryImpl.java` | 修改 |
| `backend/src/test/java/com/aigamelab/repository/AbstractRepositoryTest.java` | 修改 |
| `backend/src/test/java/com/aigamelab/tester/TesterServicesTest.java` | 修改 |
| `backend/src/test/java/com/aigamelab/api/ConfigControllerTest.java` | 修改 |
| `backend/src/test/java/com/aigamelab/api/ResearchControllerTest.java` | 新增 |

**Frontend（2 文件新增、4 文件修改）：**

| 文件 | 类型 |
|------|------|
| `frontend/src/pages/ResearchPage.tsx` | 新增 |
| `frontend/src/App.tsx` | 修改 |
| `frontend/src/App.test.tsx` | 修改 |
| `frontend/src/useGlobalSse.ts` | 修改 |
| `frontend/src/types.ts` | 修改 |
| `frontend/src/api.ts` | 修改 |

---

## 回归报告

| 指标 | R0 后 | R1 后 | 变化 |
|------|-------|-------|------|
| 总测试数 | 403（含 25 Errors） | **413** | +10（ResearchControllerTest） |
| Failures | 0 | 0 | 无 |
| Errors | 25 | **0** | −25（R1-1 修复两桶根因） |
| 新增生产文件 | — | 1 | ResearchController.java |
| 新增前端页面 | — | 1 | ResearchPage.tsx |
| 新增 DB 表 | — | 0 | 遵守"只增不减"，R1 无 Schema 变化 |

---

## Frozen Contract 回归情况

| 约束 | 结论 |
|------|------|
| Designer / Tester / Research Tool 白名单 | ✅ 未修改 |
| AgentLoop / RunStateMachine / BudgetGuard / LoopDetector | ✅ 未修改 |
| ToolExecutor / ToolResult / ConfigVersion 语义 / Error Contract | ✅ 未修改 |
| web_search Stub | ✅ 保持 Stub，真实 Search API 延后 R2（Ruling #1） |
| /sse/global 与 /sse/runs/{id} SSE 协议 | ✅ 未修改后端协议；仅前端 `named` 白名单新增 4 个事件名（前端可观测性，合法） |
| R0 Frozen Contract | ✅ 未重新打开或修改（Ruling #2） |
| R0 历史验收状态 | ✅ 不追溯修改（Ruling #3） |

---

## Technical Debt（本 Phase 登记）

| 编号 | 描述 | 来源裁决 | 建议 |
|------|------|----------|------|
| **TD-R1-001** | 研究报告通过 `design_document.title LIKE '[RESEARCH REPORT]%'` 前缀标识查询（无专用表/结构化索引） | Ruling #5（R1 MVP 允许） | 后续引入 `report_type` 列或独立 `research_report` 表，R1 期间不实施 |
| TD-R1-002 | ConfigVersion v001 曾因跨类测试库污染缺失（TD-T7-001）：已在测试层根治（resetDbToCleanState + ensureSeedV001），但共享静态 DB 的跨类隔离属结构性弱点 | R1-1 调查结论 | 后续可迁移为 per-class 临时 DB（属改造，非 R1 范围） |
| TD-R0-遗留 | web_search Stub（R0 登记） | Ruling #1 | 延后 R2 接真实 API |

---

## Deferred Items（延后项）

1. **真实 Search API 接入（R2）** — web_search Stub 保持，R1 不接（Ruling #1）。
2. **Designer / Tester 工具深度增强、python_analysis 运行时** — 与 R1 无关，维持 R0 登记。
3. **Research 前端 tab 级 taskId 过滤** — R1-3 提供 runId 为主视角 + run 下拉；taskId 二次过滤列入后续优化，非验收必需。

---

## 实际 Scope 变化

**最终 Scope 与原 R1 Planning 一致，无扩大、无收缩。**

过程中仅有的命令内增量：
- `DesignDocumentRepository.findByRunId` 属 R1-2 规划假定存在、实际缺失的补齐（final rulings 允许的命令内增量，未改语义）。
- 前端 `named` SSE 白名单新增 4 个 Research 事件为 R1-3 可观测性组成部分（Ruling #3 明示"SSE 可观测能力视为 R1 新增能力"）。

「因为可以优化/顺手重构/发现潜在问题」导致的越界改动均未发生。

---

## 结论

**R1 FULLY COMPLETE — WAITING FOR HUMAN ACCEPTANCE.**