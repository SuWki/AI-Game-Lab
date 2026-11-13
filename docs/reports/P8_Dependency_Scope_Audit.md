# P8 Dependency & Scope Audit Report

**状态：WAITING_FOR_HUMAN_CHECKPOINT**
**性质：纯只读审计，未修改任何代码/文档/schema**
**日期：2026-08-28**

---

## 1. P8 Task Inventory（来源：docs/projects/01_AI_Game_Designer/开发任务树.md）

| Task ID | 正式名称 | 目标 | 前置依赖 | 涉及层 | 当前状态 |
|---------|---------|------|---------|--------|---------|
| P8-1 | 一键启动脚本 | `scripts/dev.bat`/`dev.sh` + `reset-db.bat` + README 运行说明 | P0-1, P0-4 | 后端脚手架 / 前端脚手架 / DevOps | **未实现**（`scripts/` 目录不存在） |
| P8-2 | 金色路径 E2E | 手动剧本：提交"平衡三种 Build"→观察 Trace→审批提案→复测对比→设计文档产出；首次使用真实 DeepSeek API | **全部功能 Task** | 全栈（Backend + Frontend + LLM） | **部分就绪**（见 §2 依赖矩阵） |
| P8-3 | 性能验收 | 100k 局基准计时 + 同 seed 双跑比对写入 README 基准表 | P4-4 | 后端（sim）| **测试文件已存在**（`SimulationPerfTest.java`）；未手动跑并写入 README |
| P8-4 | 异常路径演练 | 断 API Key→FAILED+50201；Cancel→CANCELLED；杀进程重启→恢复；maxSteps 触顶 | P8-2 | 后端（Runtime）| **后端机制已就绪**（50201/CANCELLED/resume/maxSteps 全部在 AgentLoop+RunControlService 中实现）；未按 03 剧本手动演练 |

---

## 2. Dependency Matrix（逐项审计）

### P8-1 — 一键启动脚本

| 检查项 | 状态 | 详情 |
|--------|------|------|
| P0-1 后端可启动 | ✅ READY | `mvn spring-boot:run` 工作，v001 种子导入 |
| P0-4 前端可启动 | ✅ READY | `npm run dev` 工作，代理 `/api`/`/sse` → :8080 |
| scripts/ 目录 | ❌ 缺失 | `scripts/dev.bat` / `dev.sh` / `reset-db.bat` 不存在 |
| README 运行说明 | ✅ 已有 | README.md 含 `快速开始` / npm 脚本表；缺自动化脚本入口 |

**判定：READY** — 纯 DevOps 任务，零代码依赖，可立即执行。

---

### P8-2 — 金色路径 E2E

| 检查项 | 状态 | 详情 |
|--------|------|------|
| ConfigVersionService v001 种子 | ✅ 存在 | 启动时导入 `config/v001.json`（幂等） |
| ConfigController 4 endpoints | ✅ 存在 | `versions` / `proposals` / `approve` / `reject`（P6-4 人工授权执行） |
| TaskController `POST /{id}/runs` | ✅ 存在 | 委托 `RunControlService.start()` |
| RunController GET /runs, /runs/{id} | ✅ 存在 | P7-3 交付 |
| RunControlService cancel/resume | ✅ 存在 | `cancel()` 协作式 + `resume()` WAITING_APPROVAL→RUNNING |
| AgentLoop LLM 调用 | ✅ 存在 | DeepSeekClient + GatewayRetry（P2-2/P2-3） |
| ToolRegistry 16 tools | ✅ 存在 | Designer 12 + Tester 8（P3 全部完成） |
| ConfigDiffer + MergePatch | ✅ 存在 | P6-2 交付 |
| ApprovalService approve/reject | ✅ 存在 | P6-3 交付 |
| ExperimentService + AsyncRunner | ✅ 存在 | P5-1/P5-3 交付 |
| EvaluationService | ✅ 存在 | P5-2 交付 |
| DesignDocumentTool | ✅ 存在 | P3-4 交付 |
| SSE 事件推送 | ✅ 存在 | SseController + AgentEventPublisher（P1-6） |
| DashboardPage | ✅ 存在 | P7-1 交付 |
| RunTracePage | ✅ 存在 | P7-3 交付 |
| ExperimentCenterPage | ✅ 存在 | P7-4 交付 |
| ConfigDiffPage（审批） | ✅ 存在 | P7-5 交付 |
| FindingsPage | ✅ 存在 | T5-1 交付 |
| TestPlanPage | ✅ 存在 | T5-2 交付 |
| **前端"提交任务"页面** | ⚠️ **缺失** | 前端无 Task 提交/Run 启动 UI；只能通过 `curl POST /api/tasks/{id}/runs` 或 `TaskController` 操作 |
| **v001 fixture 问题（12 Tests）** | ⚠️ **后端 DIRTY** | `InvariantIntegrationTest`(3) + `TesterReadToolsTest`(2) + `TesterWriteToolsTest`(7) 因测试未 seed v001 而 `Tool [40401] ConfigVersion 不存在: v001`；与 P8 非直接相关，但 `mvn test` 全量不绿 |
| **真实 DeepSeek API Key** | ⚠️ **运行时** | P8-2 明确为"全项目首次调用真实 DeepSeek API"；需 `application.yml` 配置有效 `api-key`；非代码缺陷 |
| **P6-4 文档冲突（TD-005）** | ⚠️ **已收敛** | 实际代码端点（`versions`/`proposals`/`approve`/`reject`）= 03 范围；02 §9 列 `current`/`versions/{version}/diff` 与实际不同；已由人工授权 P6-4 解决；**02 文档未更新与实际代码对齐**（登记在 TD-005） |
| **ToolResult 冻结** | ✅ 无冲突 | 手动剧本不依赖 ToolResult 结构变更 |
| **Error Contract 冻结** | ✅ 无冲突 | 50201/40401/40901 全部由 GlobalExceptionHandler 透传 |

**判定：AMBIGUOUS** — 后端全部功能就绪；两个运行时条件（API Key、Task 提交入口）+ 12 个 fixture 失败。无设计 blocker，但需裁决：

1. P8-2 前端是否需要新增"提交任务"页面，还是仅用 `curl` 手动操作？
2. 12 个 fixture 失败是否在 P8 之前修复，还是隔离跳过（当前已有隔离手段）？

---

### P8-3 — 性能验收

| 检查项 | 状态 | 详情 |
|--------|------|------|
| P4-4 100k<60s 验证 | ✅ 已通过 | 22.976s（P4 Sprint 已验收） |
| SimulationPerfTest.java | ✅ 存在 | `@Tag("perf")` 默认跳过；手动 `-Dgroups=perf` 触发 |
| v001.json 配置 | ✅ 存在 | `config/v001.json`，P4-0 调平后可用 |
| 同 seed 双跑复现 | ✅ 已通过 | P4 Sprint 验证 winCount/avgDps 一致 |
| README 基准表 | ❌ 缺失 | 性能数字未写入 README（P4-4 验收通过但未入文档） |

**判定：READY** — 可立即执行，仅需手动跑 + 写 README。03 依赖 P4-4 已满足。

---

### P8-4 — 异常路径演练

| 检查项 | 状态 | 详情 |
|--------|------|------|
| 50201 错误码 | ✅ 存在 | `ErrorCodes.java` + `GlobalExceptionHandler` 映射 50201→502 |
| AgentLoop CANCELLED 终态 | ✅ 存在 | `RunControlService.cancel()` + `cancelled()` 协作式检查 |
| AgentLoop resume | ✅ 存在 | `RunControlService.resume()` WAITING_APPROVAL→RUNNING→重提交 loop |
| BudgetGuard maxSteps | ✅ 存在 | `AgentLoop` 检查 `currentStep >= maxSteps` → COMPLETED |
| TIMEOUT 终态 | ✅ 存在 | `RunControlService` 已处理 |
| agent_event 记录 | ✅ 存在 | PersistingAgentEventPublisher 落库全部枚举 |
| **P8-2 前置** | ⚠️ **同 P8-2** | 依赖 P8-2 完成后才有完整链路可演练 |

**判定：READY（取决于 P8-2）** — 后端机制全部就绪，可随 P8-2 同 Sprint 手动演练。

---

## 3. Backend Gap Analysis

| 能力 | Service | Repository | REST Endpoint | 状态 |
|------|---------|------------|--------------|------|
| ConfigVersion CRUD | ConfigVersionService | ConfigVersionRepository | GET /versions, GET /proposals | ✅ 就绪 |
| Config Diff + Merge | ConfigDiffer + MergePatchApplier | — | — | ✅ 就绪（P6-2） |
| Approve / Reject | ApprovalService | ChangeProposalRepository | POST approve/reject | ✅ 就绪 |
| Run 启动 | RunControlService | RunRepository | POST /tasks/{id}/runs | ✅ 就绪 |
| Run Cancel | RunControlService | RunRepository | POST /runs/{id}/cancel | ✅ 就绪 |
| Run Resume | RunControlService | RunRepository | POST /runs/{id}/resume | ✅ 就绪 |
| Run 读取 | — | RunRepository | GET /runs, GET /runs/{id} | ✅ 就绪 |
| LLM 调用 | DeepSeekClient + GatewayRetry | — | — | ✅ 就绪（需真实 API Key） |
| Experiment 创建 | ExperimentService | ExperimentRepository | POST /experiments/run | ✅ 就绪 |
| Experiment 分析 | EvaluationService | ExperimentRepository | GET /experiments/{id} | ✅ 就绪 |
| Design Document 写入 | CreateDesignDocumentTool | DesignDocumentRepository | — | ✅ 就绪（Tool 层） |
| Finding 报告 | FindingReportTool + FindingService | FindingRepository | GET /findings | ✅ 就绪（Tester） |

**缺失 Endpoint（P8 不需要但 02 文档设想）**：
- `GET /api/config/current` — 02 §9 列出但实际未实现（P6-4 按 03 范围落地，不含此端点）；P8 手动剧本可用 `GET /api/config/versions` 替代
- `GET /api/config/versions/{version}` — 同上，02 列出但未实现；P8 不直接需要

**结论**：P8 所需全部后端能力已就绪，无缺失 Service/Repository/Endpoint。

---

## 4. Frontend Gap Analysis

| 页面 | 状态 | P8-2 需要？ |
|------|------|------------|
| DashboardPage | ✅ P7-1 | 可观测 |
| RunTracePage | ✅ P7-3 | 观察 Trace |
| ExperimentCenterPage | ✅ P7-4 | 复测对比 |
| ConfigDiffPage | ✅ P7-5 | 审批提案 |
| FindingsPage | ✅ T5-1 | 观察 Finding |
| TestPlanPage | ✅ T5-2 | 观察 Plan |
| **Task 提交页** | ❌ **缺失** | 手动剧本"提交平衡三种 Build"需要 Task → Run 启动；前端无此入口 |

**P8-2 前端缺口**：`POST /api/tasks/{id}/runs`（TaskController）仅暴露后端 REST，无前端 UI。03 文档 P8-2 为"手动剧本"，可通过 `curl` 补偿。但若要求全流程前端可视，需新增 Task 提交页（不在 03 P8 范围内）。

---

## 5. Design Conflicts（冻结契约核查）

| 冻结项 | P8 要求 | 冲突？ | 详情 |
|--------|--------|--------|------|
| ConfigVersion append-only | P8-2 手动剧本经 ApprovalService 创建新版本 | ✅ 无冲突 | 正常使用 createVersion（append-only） |
| current = latest | P8-2 复测对比经 findLatest() | ✅ 无冲突 | |
| 无 active/activate/rollback | P8 不涉及 | ✅ 无冲突 | P8-2 手动剧本不需 rollback |
| ToolResult 冻结 | P8 不改 ToolResult | ✅ 无冲突 | |
| Error Contract 冻结 | P8-4 演练 50201 由既有 Handler 透传 | ✅ 无冲突 | |
| schema.sql 冻结 | P8 不改 DB | ✅ 无冲突 | |
| Tester lane 冻结 | P8 不触碰 Tester 代码 | ✅ 无冲突 | P8-2 可观察 Tester Run 但不修改 |
| TD-P3-001（RESOLVED） | P8 不涉及 | ✅ 无冲突 | |
| TD-006（DPS 方差代理） | P8-2 可观察 Experiment 图表 | ✅ 无冲突 | 可用，但无 P8 特定影响 |
| TD-005（P6-4 文档冲突） | ⚠️ 文档未更新 | **注意** | 实际代码=03 范围（versions/proposals/approve/reject），02 §9 仍列 current/versions/{version}/diff；P8-2 手动剧本不依赖冲突端点，不阻塞，但 02 文档应最终修正 |
| **DESIGN BLOCKER?** | **无** | ✅ | P8 不要求突破任何冻结契约 |

---

## 6. Scope / Red Lines

### P8 可以修改

| 文件类型 | 范围 |
|----------|------|
| 新增 `scripts/dev.bat` / `dev.sh` / `reset-db.bat` | P8-1 范围 |
| 修改 `README.md` | 添加 P8-3 性能基准表 + E2E 运行说明 |
| 既有测试文件（仅 12 个 fixture 失败） | 为 `InvariantIntegrationTest` / `TesterReadToolsTest` / `TesterWriteToolsTest` 补 v001 seed setup（若选择修复） |
| 后端测试新增（可选） | 为 P8-4 异常路径补充自动化测试（非 03 要求，03 要求手动剧本） |

### 明确禁止修改

| 文件/模块 | 禁止原因 |
|-----------|---------|
| `backend/src/main/java/` 全部生产代码 | P8 为 E2E/性能/脚本，不改生产逻辑 |
| `schema.sql` | 冻结 |
| `domain/*.java` | 冻结 |
| `repository/*.java` | 冻结 |
| `runtime/AgentLoop.java` | 冻结 |
| `runtime/RunControlService.java` | 冻结 |
| `tool/*.java`（所有 Tool） | 冻结 |
| `config/*.java`（P6 服务层） | 冻结 |
| `llm/*.java` | 冻结 |
| `sim/*.java` | 冻结 |
| `tester/*.java` | 冻结（Tester lane） |
| `frontend/src/pages/*.tsx` | P8 不改前端页面（除非裁决增加 Task 提交页） |
| `frontend/src/api.ts` | 冻结 |
| P7 各页面 | 冻结 |

---

## 7. Test Strategy

### P8-1（启动脚本）
- **类型**：手动验证
- **验收**：两条命令全流程可用

### P8-2（金色路径 E2E）
- **类型**：手动剧本（03 要求）
- **验收**：蓝图 §21 验收 1~7 全过；除 Approve 无人工干预
- **前提条件**：
  1. 有效 DeepSeek API Key 已配置
  2. v001 已 seed（启动自动导入）
  3. Task 已创建（可通过 `curl POST /api/tasks` 或直插 DB）

### P8-3（性能验收）
- **类型**：`SimulationPerfTest`（@Tag("perf")）+ README 基准表
- **当前状态**：P4-4 已验收（22.976s），仅需写入 README
- **不修复** 12 个 v001 fixture 失败（与性能无关）

### P8-4（异常路径演练）
- **类型**：手动剧本（03 要求）
- **逐项**：
  1. 断 API Key → Run FAILED + 50201 事件 ✅ 机制就绪
  2. Cancel → CANCELLED ✅ 机制就绪
  3. 杀进程重启 → RUNNING Run 恢复 / WAITING_APPROVAL 保持 ✅ 机制就绪
  4. maxSteps=3 小预算 → BudgetGuard 触顶 ✅ 机制就绪

### 全量回归
- **当前状态**：355 run / 12 Errors（fixture 问题，非回归缺陷）
- **建议**：12 个 fixture 失败可在 P8 Sprint 内修复（最小 diff：为 `AbstractRepositoryTest` 添加 `ConfigVersionService` seed 调用），不影响生产代码冻结

---

## 8. Recommended Sprint Breakdown

### Sprint P8-1：脚手架 + 性能基准（低风险，无裁决）

| 项目 | 内容 |
|------|------|
| **Task** | P8-1（一键启动脚本）+ P8-3（性能验收） |
| **依赖** | P0-1/P0-4（已满足）+ P4-4（已通过） |
| **修改范围** | 新增 `scripts/dev.bat` / `dev.sh` / `reset-db.bat`；修改 `README.md`（性能基准表） |
| **验收标准** | 两条命令全流程可用；100k 局基准写入 README |
| **风险** | 低 |
| **是否需人工裁决** | 否 |

### Sprint P8-2：金色路径 + 异常路径 E2E（中风险，可能需裁决）

| 项目 | 内容 |
|------|------|
| **Task** | P8-2（金色路径 E2E）+ P8-4（异常路径演练） |
| **依赖** | P8-2 要求"全部功能 Task"（已满足：P0~P7 全部 DONE） |
| **前置条件** | 需裁决：① API Key 配置；② Task 提交入口（curl 或前端页）；③ 12 fixture 失败处理方式 |
| **修改范围** | 可选修复 `InvariantIntegrationTest` / `TesterReadToolsTest` / `TesterWriteToolsTest` 的 v001 fixture（最小 diff：调用 `ConfigVersionService.ensureSeed()`） |
| **验收标准** | 蓝图 §21 验收 1~7 全过；异常路径四场景逐项打勾 |
| **风险** | 中（首次真实 LLM 调用；网络/Key/速率限制） |
| **是否需人工裁决** | **是** — 以下三点需人工决定（见 §9） |

---

## 9. BLOCKED / READY / AMBIGUOUS Summary

| Task | 判定 | 阻塞原因 / 条件 |
|------|------|----------------|
| P8-1 | **READY** | 纯 DevOps，可立即执行 |
| P8-3 | **READY** | P4-4 已验收；仅需手动跑 + 写 README |
| P8-2 | **AMBIGUOUS** | 见下方"Required Human Decisions" |
| P8-4 | **READY（随 P8-2）** | 机制全部就绪；需 P8-2 完成后链路可测 |

---

## 10. Required Human Decisions

### 决策 1：P8-2 前端 Task 提交入口

**问题**：P8-2 手动剧本要求"提交'平衡三种 Build'"→启动 Run。后端 `POST /api/tasks/{id}/runs`（TaskController）已存在，但前端无 Task 提交页/Run 启动按钮。

**选项**：
- A）**仅用 curl**：`curl -X POST http://localhost:8080/api/tasks/{id}/runs`，前端不改（03 文档为"手动剧本"，未要求前端提交页）
- B）**新增 Task 提交页**：在前端新增 Run 启动 UI（需实现，不在 03 P8 范围内）

**建议**：选项 A（符合 03 文档"手动剧本"定位）

---

### 决策 2：12 个 v001 fixture 失败处理方式

**问题**：`InvariantIntegrationTest`(3) + `TesterReadToolsTest`(2) + `TesterWriteToolsTest`(7) = 12 个测试因 `ConfigVersion 不存在: v001` 报 40401。根因：测试 `@BeforeEach` 调用 `game_start` 传 `configVersion="v001"`，但测试 DB 无 v001 行（`ConfigVersionService.ensureSeed()` 仅在 Spring 启动 CommandLineRunner 中调用，测试绕过该路径）。

**选项**：
- A）**在 P8 Sprint 内修复**：为 `AbstractRepositoryTest`（测试基类）添加 `ConfigVersionService.ensureSeed()` 调用；最小 diff，不改生产代码
- B）**隔离跳过**：保持现状，P8-2 手动测试前单独跑一次确保 v001 存在（通过 `CommandLineRunner` 正常启动自动 seed）
- C）**不修复**：保持 DIRTY 状态，P8-2 手动测试不依赖这 12 个测试

**建议**：选项 A（修复成本极低，3~5 行代码；让全量回归 CLEAN，为 P8-2 打下干净基线）

---

### 决策 3：02 文档 P6-4 与实际代码不一致修正时机

**问题**：02 §9 仍列 `GET /api/config/current` / `GET /api/config/versions/{version}` / `GET /api/config/versions/{version}/diff`，但实际 P6-4 实现的端点为 `GET /config/versions` / `GET /config/proposals` / `POST approve/reject`（按 03 范围）。TD-005 已登记。

**选项**：
- A）**P8 期间修正 02 文档**：将 §9 端点列表更新为实际代码（不含 current/versions/{version}/diff）
- B）**P8 后独立修正**：保持 TD-005 登记，由设计方决定是否在 02 中保留或删除这些端点设想
- C）**不修正**：TD-005 持续登记，P8 不受影响

**建议**：选项 B（P8 不触碰 02 文档；TD-005 继续登记；02 修正属设计方职责）

---

### 决策 4：P8 Sprint 切分确认

**问题**：建议拆为 P8-1（脚手架+性能）和 P8-2（E2E+异常路径）两个 Sprint。

**选项**：
- A）**拆两 Sprint**（建议）：P8-1 低风险可立即执行；P8-2 需人工参与（API Key、手动剧本）
- B）**合并单一 Sprint**：一次交付全部 P8（P8-1 可并入 P8-2）

**建议**：选项 A（P8-1 可先行完成，不等人工；P8-2 等人工准备 API Key + 裁决上述问题后执行）

---

## 11. 附录：v001 fixture 失败根因分析

### 受影响测试（12 个）

| 测试类 | 失败数 | 根因 |
|--------|--------|------|
| `InvariantIntegrationTest` | 3 | `startWith()` 调 `game_start(configVersion="v001")` → ConfigVersionService 查 v001 → 40401 |
| `TesterReadToolsTest` | 2 | `startSession()`/`startSessionOwnedBy()` 同上 |
| `TesterWriteToolsTest` | 7 | `startSession()` 同上 |

### 根因

这些测试继承 `AbstractRepositoryTest`（Spring 测试基类），启动时创建临时 SQLite 库。`ConfigVersionService.ensureSeed()` 由 `CommandLineRunner` 调用，但测试 Spring context 可能跳过或未正确触发该 Runner（或测试用独立 DataSource 绕过 Runner）。结果：`game_config_version` 表空，`ConfigVersionService.findByVersion("v001")` 返回空 → 40401。

### 修复方案（若裁决 A）

在 `AbstractRepositoryTest` 的 `@BeforeEach` 中注入 `ConfigVersionService` 并调用 `ensureSeed()`（幂等，空表才导入），确保每个测试 DB 有 v001 行。约 3~5 行代码改动，零生产代码影响。

---

**审计完成。STOP — WAITING_FOR_HUMAN_CHECKPOINT。**
