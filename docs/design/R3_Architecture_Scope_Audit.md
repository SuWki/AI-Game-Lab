# R3 Architecture / Scope Audit

**日期：** 2026-09-10
**阶段：** R3 Planning（Architecture / Scope Audit，非 Implementation）
**前置：** R2_CLOSED — WAITING FOR HUMAN ACCEPTANCE / R3 AUTHORIZATION（`R2_Closure_Report.md`）
**范围红线：** 本轮无生产代码改动、无 schema 改动、无 prompt 激活、无 designer_p002 激活、无真实 Provider 凭据配置、无 QA Implementation。
**审计方法：** 6 域只读审计（6-agent 并行 workflow + 后续 inline 核验），**以实际代码为准**，所有测试数字为**本轮实测**。

---

## 1. Current System State（当前系统状态）

### 1.1 基线总览（本轮实测）

| 维度 | 结果 | 验证方式 |
|------|------|----------|
| 后端全量回归 | **512 tests / 0 Failures / 0 Errors / 0 Skipped** | `mvn -o clean test`（JDK 24，BUILD SUCCESS，本轮实测） |
| 前端 | tsc --noEmit 0 errors；vitest 22/22 PASS；vite build OK | 前端 agent 核验 + 既有报告 |
| Frozen files changed | **0**（8 文件 MD5 全部一致） | 本轮 MD5 重算 |
| Schema changed | **0**（14 表） | 本轮检查 |
| 4 个 Agent 类型 | DESIGNER / TESTER / RESEARCH / **QA** | `AgentType.java:16-21` |
| Prompt 种子 | 5 个：p001(active) + tester_p001 + research_p001 + designer_p002 + qa_p001(均 inactive) | `PromptService` 启动日志（本轮实测 5 条导入） |
| Tool 白名单 | Designer 10 / Tester 8 / Research 6 / QA 1 | `DefaultAgentTypeResolver` |

**关键对比：**
- R2 Closure 报告时点：473/3F/0E（3 FAIL 为 QA P1-1 并行变更导致的 stale 断言）
- QA P1-1 收尾（12:33）：490/0F/0E
- **本轮 R3 审计实测：512/0F/0E** —— QA P1-2（+20 测试）与 8 组 Tester 测试适配已合入全绿。

### 1.2 三大泳道（Designer / Tester / Research）冻结状态

- **Designer**：P0-P8 全 COMPLETE，Sprint 无运行。designer_p001.md frozen-clean（grep 0 research/qa 引用）。8 核心工具 + 2 R2 research 消费工具 = 白名单 10。
- **Tester**：T1-T7 FROZEN（V1 FINAL FREEZE 2026-08-31）。8 工具、8 REST 端点、118 @Test。finding 表 = Tester 核心工件。
- **Research**：R0 FROZEN + R1/R2 交付。6 工具、research_source/research_claim 表、TH 真实搜索适配器就绪但 Live E2E BLOCKED。

### 1.3 QA 当前状态（本轮实测）

**QA P1-1 已 COMPLETE**（`docs/QA_P1_1_IMPLEMENTATION_REPORT.md`，12:33）：
- AgentType.QA 枚举、qa_p001 inactive 种子、QA 路由、finding_report 复用、17 测试、490/0/0。

**QA P1-2 已实施但无独立文档（本轮重大发现）**：

| 文件 | mtime | 内容 |
|------|-------|------|
| `tester/FindingService.java` | 09-10 16:19 | +LEGAL_TRANSITIONS 生命周期矩阵 + Evidence expected/actual 数据质量校验（40901 越权 / 40001 质量） |
| `tester/FindingReportTool.java` | 09-10 16:32 | schema 补 expected/actual 必填描述 |
| `qa/QaP1_2Test.java` | 09-10 16:39 | 新增 20 项验收测试（A 质量×9 / B 合法迁移×5 / C 非法迁移×6 / D 事务+回归×2） |
| 8 组 Tester 测试 | 16:34–17:15 | 补齐 expected/actual 参数适配新校验 |

**QA P1-2 影响链**：FindingService 行为收紧 → 8 组既有 Tester 测试补充 expected/actual → 全量 512/0/0 仍绿。这是**对 Tester 冻结契约内 FindingService 应用层的合理收紧**（QA Architecture Freeze 明确复用 Finding 域），但**无 P1-2 实施报告文档**，task-state 亦未更新。

**QA P1-3/P1-4/P1-5/P1-6 尚未开始**（P1 未整体 closure）。

---

## 2. Project State Reconciliation（项目状态对账）

### 2.1 文档 vs 代码差异（以实际代码为准）

| 文档声称 | 代码实际 | 判定 |
|----------|----------|------|
| task-state.md: "PROJECT STATUS: RELEASE CANDIDATE / NEXT ACTION: HUMAN FINAL ACCEPTANCE / 不创建 P9" | 实际 R2 已交付、QA P1-1/P1-2 已实施、前端 9 页面存在 | **文档严重滞后**（task-state.md 最后实质更新停在 Designer/Tester Sprint，未反映 R 系列与 QA 线） |
| QA_ARCHITECTURE_FREEZE: QA = NOT STARTED | QA P1-1 + P1-2 已实施 | 快照时点（09-03）后 QA 已推进，Freeze 文档需标注 |
| QA_P1_IMPLEMENTATION_AUDIT: P1-2 "Finding Evidence" 需新建 filter 扩展 | P1-2 实际落地为 Evidence 质量 + Lifecycle（filter 扩展未做） | P1-2 实际范围与 audit 定义有偏差（以代码为准） |
| R2_Closure_Report: 473/3F/0E | 本轮实测 512/0F/0E | **旧报告数字不得复用于新报告**，R3 以 512 为准 |

### 2.2 Git 基线缺失（CRITICAL）

```
git status --short:
 M README.md
?? .gitignore
?? backend/
?? docs/
?? frontend/
?? scripts/
```

**整个 `backend/`、`frontend/`、`docs/`、`scripts/` 均未纳入版本控制**（仅一个 "Initial commit" 含 README）。Frozen MD5 有文件系统锚点但**无 VCS 锚点**——任何漂移无法通过 git diff 追溯。这是 R3 最大基础设施风险。

---

## 3. Frozen Contract Verification（冻结契约核验）

### 3.1 Frozen 文件 MD5（本轮重算）

| 冻结文件 | MD5 | 状态 |
|----------|-----|------|
| `runtime/AgentLoop.java` | `9da5fbf2ad1309438487b9bb13366913` | **UNTOUCHED** |
| `runtime/RunStateMachine.java` | `efc70dddac6edd2e64312a80479d97c8` | **UNTOUCHED** |
| `runtime/BudgetGuard.java` | `83693fff27f88eecd736d1c89cc3f0c5` | **UNTOUCHED** |
| `runtime/ToolExecutor.java` | `ebeb4c51d3096098572e42490576f170` | **UNTOUCHED** |
| `tool/ToolResult.java` | `eb4443d3dc1f264da305ae3391fe8da9` | **UNTOUCHED** |
| `common/ErrorCodes.java` | `1843e1f18cdbfe65cec9d84cb4921236` | **UNTOUCHED** |
| `resources/schema.sql` | `90a20276a7dc31ec3f4905c49ae6b077` | **UNTOUCHED** |
| `prompts/designer_p001.md` | `60d96fdd31223e7f29da5f077f09e719` | **UNTOUCHED** |

**Frozen files changed: 0。Frozen Contract = UNCHANGED。**

### 3.2 Schema（本轮核验）

- 14 表：task, run, step, tool_call, experiment, game_config_version, change_proposal, agent_event, design_document, prompt_version, test_plan, finding, research_source, research_claim
- 0 新增 / 0 修改 / 0 DDL
- **Schema = UNCHANGED**

### 3.3 已知受控差异（非违约）

- **Designer 白名单 8→10**：R2-1 只读 research 消费工具，additive，经 R2 授权，非冻结行变更。
- **4 张 post-P0 表**（test_plan/finding/research_source/research_claim）：均为各线人工批准的追加建表，非漂移。

---

## 4. Research R2 Post-Closure Verification（Research R2 关闭后核验）

| 检查项 | 结果 |
|--------|------|
| Research 6 工具 | 全部存在（save_source / extract_claims / compare_sources / generate_report / web_search / fetch_page） |
| Research REST | ResearchController + ClaimsSourceController 就绪 |
| Evidence Chain | `ResearchToDesignerE2ETest` 验证通过（claim→source→report→designer 消费） |
| 前端 ResearchPage | Sources/Claims/Reports 三 tab + ClaimDetail drill-down + SSE 降级 |
| **TH Live E2E** | **BLOCKED BY CREDENTIAL**（`SERPAPI_API_KEY` 未设置；`SerpApiSearchClient` Bean 不注册；web_search 返回 BLOCKED 文本） |
| TH 适配器 | SearchGateway + SerpApiSearchClient 就绪，WireMock 单测 4/4（非真实 E2E，严禁冒充） |

**结论**：Research 已稳定关闭。无 R2 后新改动（research 包最后 mtime 09-10 09:34 = TH 收尾）。Live Provider E2E 仍缺凭据，保留 BLOCKED 记录，不弱化。

---

## 5. QA Current-State Audit（QA 现状审计）

### 5.1 QA 已具备的闭环（P1-1 + P1-2 后）

```
QA Task (agent_type=QA)
  → AgentType.QA 解析
  → systemPrompt = qa_p001 (inactive)
  → toolWhitelist = {finding_report}（复用 Tester 工具，无平行体系）
  → FindingReportTool → FindingService（现含 P1-2 质量+Lifecycle 校验）
  → FindingRepository → finding 表
```

**5 项闭环验证全部 PASS**（QaP1_1Test 17 + QaP1_2Test 20 = 37 项，全绿）。

### 5.2 QA 尚未具备的（P1 剩余 + Phase 2）

| 缺口 | 说明 |
|------|------|
| **P1-3 QA REST API** | 无 QA 命名空间端点；Finding filter 缺 run_id IN (...)/task_id/status 多维（audit 已定义，未实现） |
| **P1-4 QA Frontend** | WorkflowPage `AGENT_TYPES = ["RESEARCH","DESIGNER","TESTER"]` **缺 QA**（[WorkflowPage.tsx:8](frontend/src/pages/WorkflowPage.tsx#L8)）→ 用户无法从 UI 创建 QA Task（F1） |
| **P1-5 Isolation 深化** | ToolExecutor dispatch 层无角色校验（TD-QA-P11-001，已在既有机制下以 spec 层白名单为拦截点） |
| **P1-6 P1 整体 Acceptance** | 无 P1 Acceptance Report；task-state 未更新 |
| **QA Architecture Freeze 的两张表** | qa_role_assignment / qa_release_gate → 属 schema migration，须人工批准（本轮禁止） |
| **QA 报告文档缺口** | QA P1-2 已实施但无 P1-2 实施报告；P1 无完整 sprint 收口文档 |

### 5.3 QA 与既有域的交互面（合规确认）

- 复用 Finding/Evidence/Repository/Service/Tool——零平行 QA Domain ✅
- 未触碰 Frozen Boundary（AgentLoop/ToolExecutor/RunStateMachine/BudgetGuard/ToolResult/ErrorCodes 零改动）✅
- schema.sql 未动 ✅
- 8 组 Tester 测试适配为 P1-2 收紧的必然结果（FindingService 行为变化），且未修改 Tester 生产冻结文件 ✅

---

## 6. Cross-Track Findings（跨轨道发现）

| # | 严重级 | 发现 | 归属 | 证据 |
|---|--------|------|------|------|
| X-1 | **CRITICAL** | 无 Git 基线：backend/docs/frontend 全部 untracked，Frozen 契约无 VCS 锚点，任何漂移无法 git 追溯 | 基础设施 | `git status --short`（本轮） |
| X-2 | HIGH | WorkflowPage AGENT_TYPES 缺 QA → UI 无法创建 QA Task | Frontend | [WorkflowPage.tsx:8](frontend/src/pages/WorkflowPage.tsx#L8) vs `AgentType.java:20` |
| X-3 | MEDIUM | QA P1-2 无独立实施文档，task-state 未更新 → P1 整体状态不可追溯 | QA | docs/ 无 QA_P1_2_* 报告（本轮 glob 确认） |
| X-4 | MEDIUM | REPLAY_TRUNCATED SSE 事件前端未监听 → 长断连重连后静默数据漂移 | Frontend | `SseController.java:92` 发布 vs `useGlobalSse.ts:72-99` 未订阅 |
| X-5 | LOW | `useSseDrivenRefresh` / `useRunSse` 死导出；ExperimentCenterPage 无 SSE；DashboardPage 内联 fetchStats 不走 api.ts | Frontend | `useGlobalSse.ts:138-174`，agent 核验 |
| X-6 | LOW | 6/9 页面零测试覆盖（DashboardPage/FindingsPage/WorkflowPage/RunTracePage/ExperimentCenterPage/TestPlanPage/ConfigRegressionPage） | Frontend | agent 核验 |
| X-7 | LOW | `configVersions` vs `config-versions` 查询键不一致（ConfigRegressionPage:35 vs ConfigDiffPage:40） | Frontend | agent 核验 |
| X-8 | INFO | Designer 白名单 10 vs 产品定义 8（additive R2 授权差异，非违约） | Designer | 记录留痕 |

---

## 7. R3 Candidate Tasks（候选任务池）

> 从 §5/§6 缺口 + 既有 Deferred 收口池生成。每项含完整 Task 属性（R3 规范要求）。

### C-01 建立 Git 基线（当前全部代码入库）
- **Goal**：把 backend/ frontend/ docs/ scripts/ 全部纳入 git（首个 baseline commit），为 Frozen 契约提供 VCS 锚点，替代纯文件系统 MD5。
- **Current evidence**：git status 显示全部 untracked（§6 X-1）。
- **Why now**：R3 及后续任何 Sprint 的"Frozen Contract Verification"都依赖可 diff 的基线；无 VCS 锚点 = 无防漂移能力。
- **Dependencies**：无（基础最优先）。
- **Files affected**：`.gitignore`（补 data/、target/、node_modules/）、git add 全量。
- **Tests required**：无新测试；验证 `git status --short` 干净 + 首个 commit 记录。
- **Frozen impact**：零（不改任何代码，只入库）。
- **Schema impact**：零。
- **Acceptance criteria**：全树入库；`git status` clean；8 个 Frozen MD5 可在 commit 中定位；不提交 DB 文件与 target/node_modules。

### C-02 QA P1-3 收口：Finding 多维过滤 + QA 命名空间端点（可选）
- **Goal**：按 `QA_P1_IMPLEMENTATION_AUDIT.md` P1-3 定义实现 QAController（或扩展 TesterController filter）暴露 runId[]/taskId/status 过滤。
- **Current evidence**：audit §P1-3 完整定义；FindingRepositoryImpl 已支持 task_id 但 Controller 未暴露。
- **Why now**：QA 若要"按 Run 聚合 Findings"（Phase 2 前置），需要多维查询入口。
- **Dependencies**：QA P1-2（已完成）。
- **Files affected**：`api/QaController.java`（新）/ `TesterController.java`（扩展 filter）。
- **Tests required**：QaControllerTest 或 TesterControllerTest 扩展（8~12 项）。
- **Frozen impact**：零（纯追加）。
- **Schema impact**：零。
- **Acceptance criteria**：runId[]/taskId/status 过滤可用；回归 512+ 保持全绿。
- **优先级**：P1（Phase 2 前置）——但可 **Deferred 到 R3 内 QA 专线**，见 §8 建议。

### C-03 QA P1-4 收口：WorkflowPage 加入 QA 入口
- **Goal**：WorkflowPage AGENT_TYPES 加入 "QA"，UI 可创建 QA Task。
- **Current evidence**：[WorkflowPage.tsx:8](frontend/src/pages/WorkflowPage.tsx#L8) 缺 QA；后端 TaskController 已放行 QA（X-2）。
- **Why now**：修复"后端支持 QA 但 UI 无法触达"的不对称；QA P1-2 已能让 QA 产出 Finding，UI 缺入口使能力不可用。
- **Dependencies**：C-02（可选，最小实现可不依赖）。
- **Files affected**：`frontend/src/pages/WorkflowPage.tsx`（+1 常量值）、`WorkflowPage.test.tsx`（新，补 6/9 页零覆盖的一部分）。
- **Tests required**：WorkflowPage 渲染 3 agent 选项 → 4 选项。
- **Frozen impact**：零。
- **Schema impact**：零。
- **Acceptance criteria**：WorkflowPage 可选 QA；创建 agent_type=QA 任务成功；vitest 新增用例绿。

### C-04 QA P1 整体 Closure 文档收口
- **Goal**：补齐 QA P1-2 实施报告 + P1 整体 Acceptance Report + task-state 更新（QA 线状态落档）。
- **Current evidence**：P1-2 代码已合入（512/0/0）但无文档（X-3）。
- **Why now**：P1 未 closure 则 Phase 2 无正式准入依据。
- **Dependencies**：C-02/C-03 完成（可选）或与 P1-1 报告同档。
- **Files affected**：`docs/QA_P1_2_IMPLEMENTATION_REPORT.md`（新）、`docs/state/task-state.md`。
- **Tests required**：无。
- **Frozen impact**：零。**Schema impact**：零。
- **Acceptance criteria**：P1-2 报告含 512/0/0 实测数字；task-state 反映 QA P1 状态。

### C-05 REPLAY_TRUNCATED SSE 前端订阅
- **Goal**：useGlobalSse 订阅 REPLAY_TRUNCATED，重连超 1000 事件时前端提示并全量刷新。
- **Current evidence**：后端发布（X-4），前端未监听。
- **Why now**：消除静默数据漂移，Research/Findings 页实时性可靠性。
- **Dependencies**：无。**Files affected**：`useGlobalSse.ts` + 1 测试。
- **Acceptance criteria**：事件进入命名数组；重连截断后触发 refetch；vitest 绿。

### C-06 TH Live Provider E2E（凭据到位后）
- **Goal**：配置 `SERPAPI_API_KEY` 后执行真实 web_search E2E。
- **Current evidence**：适配器就绪，无凭据 BLOCKED（§4）。
- **Why now**：C1 条件项的 Live 验证，需 Human 提供凭据。
- **Dependencies**：Human 配置凭据。**Frozen impact**：零。
- **Acceptance criteria**：真实搜索返回结果 / 错误按契约映射；E2E 测试用例由 BLOCKED 改为 LIVE。

### C-07 前端测试覆盖补全（X-6 中的核心页）
- **Goal**：WorkflowPage / FindingsPage / RunTracePage 至少各 1 个冒烟测试。
- **Why now**：6/9 页零覆盖 = 前端回归无保障。
- **Dependencies**：无。**Frozen impact**：零。

### C-08 Designer Whitelist Drift 决策记录（非修复）
- **Goal**：正式记录 Designer 白名单 8→10 为 R2 授权 additive 差异（X-8），防未来审计误判。
- **Why now**：任务树/产品定义写 8，代码 10，需一份 Decision Record 终止争议。
- **Dependencies**：无。**Frozen impact**：零（只写文档）。

### C-09 designer_p002 激活产品决策（Deferred，不实现）
- **Goal**：Human 裁决是否激活 p002（当前 inactive，TJ 状态维持）。
- **Why now**：TJ 已注册但 ACTIVATION PENDING。**非 R3 实施项**，仅状态保留。

### C-10 TK Schema Provenance 硬化（Deferred，R2 已明确）
- **Goal**：report_type 列 / claim_source join / CHECK 约束。
- **Why now**：R2 Audit 已 Deferred，需 schema 授权书。

### C-11 TD-T6-001 清偿（configVersion 存在性校验）
- **Goal**：FindingService.report() 注入 ConfigVersionService 校验，TD-T6-001 关闭。
- **Note**：QA P1-2 已加 evidence 质量校验，TD-T6-001 是相邻的既有缺口；建议并入 QA 线或独立 Task。

### C-12 TD 技术债影响分析（Tester 异常映射）
- **Goal**：Tester 基础设施异常被当 non-critical 的独立影响分析 Task。
- **Current evidence**：memory 记录（tester-exception-mapping-tech-debt），延续保留。

---

## 8. Recommended R3 Task Tree（推荐 R3 任务树）

```
R3（推荐聚焦：QA P1 收口 + 基础设施基线）
│
├── R3-A 基础设施基线（P0）
│   └── R3-A1 建立 Git 基线（C-01）
│
├── R3-B QA P1 收口（P1）
│   ├── R3-B1 QA P1-3 Finding 多维过滤 + QA 端点（C-02）
│   ├── R3-B2 QA P1-4 WorkflowPage QA 入口（C-03）
│   └── R3-B3 QA P1 整体 Closure 文档收口（C-04）
│
├── R3-C 前端可靠性（P2）
│   ├── R3-C1 REPLAY_TRUNCATED SSE 订阅（C-05）
│   ├── R3-C2 核心页冒烟测试（C-07）
│   └── R3-C3 SSE 死导出/API 模式/查询键一致性清理（X-5/X-7）
│
└── R3-D 决策记录与收口（P2 / 文档）
    ├── R3-D1 Designer Whitelist Drift Decision Record（C-08）
    ├── R3-D2 TH 凭据就绪后 Live E2E（C-06，BLOCKED 状态维持）
    └── R3-D3 既有 Deferred 清单状态复查（C-09/C-10/C-11/C-12 挂账，不实施）
```

**R3 不包含**（明确排除，等待人工授权）：
- ❌ QA Phase 2（Multi-Agent 编排 / Release Gate / qa_role_assignment / qa_release_gate）——需 schema migration 授权 + P1 closure
- ❌ designer_p002 激活——产品决策
- ❌ TH Live E2E——凭据
- ❌ 任何 Frozen 文件修改 / schema DDL

---

## 9. Must Do / Should Do / Deferred

| 分类 | Task | 理由 |
|------|------|------|
| **Must Do** | R3-A1 Git 基线 | CRITICAL（X-1），无 VCS 锚点则一切 Frozen 验证都是口说 |
| **Must Do** | R3-B3 QA P1 Closure 文档 | P1 无收口则 R3 主线的 QA 演进无准入依据 |
| **Should Do** | R3-B1 QA P1-3（多维 filter） | Phase 2 前置，成本低、纯追加 |
| **Should Do** | R3-B2 QA P1-4（UI 入口） | 修复后端/前端不对称，QA 能力可触达 |
| **Should Do** | R3-C1 REPLAY_TRUNCATED | 数据可靠性，前端小改动 |
| **Should Do** | R3-D1 Whitelist Drift 记录 | 防未来误判，纯文档 |
| **Deferred** | R3-C2/C3、R3-D2/D3、C-09~C-12 | 非阻塞；凭据/授权/产品决策依赖 |

---

## 10. Dependency Graph（依赖图）

```
R3-A1 (Git 基线) ──► R3-B3 (P1 Closure) ──► (Phase 2 准入基础)
R3-B1 (QA 端点) ──► R3-B2 (UI 入口)
R3-C1/C2/C3 相互独立，均无前置
R3-D1 独立（文档）
R3-D2 依赖 Human 凭据
R3-D3 挂账复查
```

- 无硬性阻塞链：R3-A1 先行（基础），其余可按泳道并行。
- Phase 2（QA 编排）的完整前置链：`P1 Closure → schema 授权 → Phase 2 设计 → Phase 2 实施`。

---

## 11. Risk Assessment（风险评估）

| 风险 | 等级 | 缓解 |
|------|------|------|
| 无 Git 基线继续漂移不可追溯 | **HIGH** | R3-A1 优先 |
| QA P1-2 无文档 → P1 状态认知分歧 | MEDIUM | R3-B3 |
| UI 无法创建 QA Task → QA 能力不可用 | MEDIUM | R3-B2 |
| SSE 重连静默数据漂移 | MEDIUM | R3-C1 |
| QA P1 未 closure 即并行推进 Phase 2 | MEDIUM | 本报告 §12 明确闸门 |
| TH Live E2E 仍缺凭据（C1 未了结） | MEDIUM（已知） | 状态如实保留，不弱化 |
| P1-2 对 Tester 测试的适配在 future Sprint 被误判为越权 | LOW | §5.3 合规确认 + C-08 类 Decision Record 模式 |

---

## 12. Acceptance Criteria（验收标准）

**R3 主任务验收：**
1. `git status --short` clean（R3-A1）
2. 后端回归 ≥512 全绿（任何 R3 任务不得引入失败）
3. Frozen 8 文件 MD5 不变；schema 14 表不变
4. QA P1-3 端点可用 + P1-4 UI 可选 QA + P1-2 报告落档（R3-B 全组）
5. WorkflowPage 测试从 0 → ≥1 个；REPLAY_TRUNCATED 订阅生效（R3-C 全组）
6. 所有测试数字使用**本轮实测**，禁止复用旧报告

**R3 闸门（后续 Phase 2 准入）：**
- ✅ R3-B 全组完成（QA P1 CLOSED）
- ✅ Human 批准 schema migration（qa_role_assignment / qa_release_gate）
- ✅ Human 单独签发 Phase 2 授权令（Multi-Agent orchestration 是本轮明确 NON-GOAL）

---

## 13. Final Recommendation（最终建议）

### R3 主题判定（对 Questions A–D 的回答）

**Question A：QA Phase 2 是否已具备开始 Implementation 的条件？**
**否。** QA P1 尚未整体 closure（P1-3/P1-4 未实现、P1-2 无报告、task-state 未更新）；Phase 2 需要 schema migration（qa_role_assignment / qa_release_gate），按 QA Architecture Freeze 必须人工批准后执行。前置条件未齐。

**Question B：R3 = QA Phase 2 implementation 是否合理？**
**不合理。** Phase 2（Multi-Agent 编排 / Release Gate）是跨轨道大架构，需要：P1 closure → schema 授权 → 设计 → 实施。把它作为 R3 Implementation 会让 R3 在授权链断裂（无 schema 授权、无 P1 收口）的情况下开工，违反本轮全部范围红线。

**Question C：如果不可以，需要先完成哪些 prerequisite？**
1. R3-A1 Git 基线（CRITICAL 基础设施）
2. R3-B 全组（QA P1 收口：P1-3 端点 + P1-4 UI 入口 + P1 Closure 文档）
3. Human 批准 schema migration + 签发 Phase 2 授权令

**Question D：是否应先处理 QA P1 closure 再进入 Phase 2？**
**是。** P1 closure 是 Phase 2 的第一闸门。R3 推荐即以"QA P1 收口 + 基础设施基线"为唯一主线，Phase 2 作为 R3 之后的独立阶段待授权。

### R3 推荐任务树（§8 汇总）

```
R3 = QA P1 收口 + 基础设施基线（非 Phase 2）
  P0: R3-A1 Git 基线
  P1: R3-B1/B2/B3（QA P1 收口）
  P2: R3-C1/C2/C3（前端可靠性）+ R3-D1（决策记录）
  Deferred: R3-D2/D3 + C-09~C-12
```

### 最终状态

```
R3 PLANNING COMPLETE — WAITING FOR HUMAN ACCEPTANCE
R3 RECOMMENDED SCOPE: QA P1 收口（P1-3/P1-4/Closure）+ Git 基线 + 前端可靠性
R3 NON-GOAL: QA Phase 2 Multi-Agent / Release Gate / schema migration / p002 激活 / TH Live E2E
BACKEND BASELINE: 512/0F/0E（本轮实测，QA P1-2 已合入）
FROZEN CONTRACT: UNCHANGED
SCHEMA: UNCHANGED
NEXT ACTION: 等待 Human 裁决 R3 授权范围
```

---

**R3 PLANNING COMPLETE — WAITING FOR HUMAN ACCEPTANCE / R3 AUTHORIZATION**
