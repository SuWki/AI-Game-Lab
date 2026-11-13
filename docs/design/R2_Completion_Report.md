# R2 — Research → Designer 产物消费链：完成报告

> 执行时间：2026-09-02 ~ 2026-09-03
> 最终状态：**R2 FULLY COMPLETE — WAITING FOR HUMAN ACCEPTANCE** ✅
> 最终回归：**Backend 441 tests / 0 Failures / 0 Errors / BUILD SUCCESS**；**Frontend tsc ✓ / vitest 16 passed / vite build ✓**

---

## Phase Goal 达成验证

| 目标（R2 授权 Planning） | 状态 | 验证方式 |
|------|------|----------|
| 真正运行 Research→Designer 跨 Agent 协作链（Research Agent 产物 → Designer Agent 消费） | ✅ | `ResearchToDesignerE2ETest`：真实 AgentLoop 驱动双 Run，get_research_context 读取研究产物，proposal/design doc 引用同一条 claim id |
| UI 创建 Research Task → 研究产出 → 用户据此发起 Designer → 设计决策可观测 | ✅ | `TaskControllerTest` 8 用例全绿；`WorkflowPage` 创建/启动/状态 UI；`ResearchPage`「以该研究发起 Designer」一键跳转 |
| Research 事件经 Tool → Publisher → agent_event → SSE → 前端实时可见 | ✅ | `ResearchEventPublishTest` 5 用例全绿（真实 Tool 落库后发事件）；`ResearchPage` SSE 按事件 invalidate 三组查询 |
| Designer 白名单 additive 追加研究消费工具，无研究关联时行为不变 | ✅ | `DesignerResearchToolsTest`、`AgentTypeResolverTest`、`ToolRegistryIntegrationTest`（totalToolCount=24）全绿 |
| 完成一条可追溯链：Research Claim → Evidence → Designer Reasoning → Decision → Change Proposal → Design Document | ✅ | `ResearchToDesignerE2ETest` 断言同一条 claim id 依次出现在 research_claim → get_research_context result_json → change_proposal.reason → design_document.content_md |
| 全量回归不破坏 R0/R1 | ✅ | Backend 441/0F/0E（R1 413 基线 +28）；Frontend tsc/vitest/build 全过 |

---

## 各 Task 完成情况

### R2-1：Designer 研究消费工具（Artifact Consumption）

> 架构原则：Research Agent → Research Artifact → Designer Research Tool → Designer Agent（产物消费），**非** agent-to-agent 直呼。协作靠 Task/Run + Research Artifact + 显式引用。

**新增生产文件：**

| 文件 | 职责 |
|------|------|
| `tool/AbstractDesignerResearchTool.java` | 两个只读研究工具公共基类：run→task→constraintsJson.researchSourceTaskId 解析；未关联返回 linked=false（向后兼容）；异常→ToolException 统一翻译 |
| `tool/GetResearchContextTool.java` | `get_research_context`：读取研究 Run/来源/断言/报告概览（含全部 traceable id），输出有界（来源 300 字符 / 报告 800 字符摘录，不 dump 整库） |
| `tool/GetResearchClaimsTool.java` | `get_research_claims`：按 status/confidenceMin/limit 细粒度读断言；limit 默认 20 硬上限 100 |
| `resources/prompts/designer_p002.md` | research-enhanced 提示词（**inactive** 种子版本，不触碰冻结的 designer_p001） |

**关联解析链路（不新增运行时接线）：** AgentLoop/ToolExecutor 冻结，ToolContext 只携带 runId → 研究 Task 由 `task.constraintsJson.researchSourceTaskId` 显式声明 → AbstractDesignerResearchTool 经 run → taskId → constraintsJson 解析。未关联时工具返回明确「未关联」结果而非抛错，Designer 无研究上下文行为与 R2 前完全一致。

**冻结契约遵守：** `designer_p001` 未修改；仅经 PromptService 种子机制追加 `designer_p002`（inactive）。Designer 白名单从 8 add 到 10（只增不减）。

**测试：** `DesignerResearchToolsTest`（5：linked/unlinked/过滤/限制/只读）；`ToolRegistryIntegrationTest` totalToolCount=24；`AgentTypeResolverTest` designerToolWhitelistHas10Tools；`TesterRunWiringTest`/`TesterExperimentIntegrationTest` EXPECTED_DESIGNER_TOOLS 含 2 研究工具；`PromptServiceTest` designerP002SeededInactiveAndResearchAware；`TesterPromptImportTest` countRows=4。

### R2-2：Research 事件真实发布 + ResearchPage 刷新修复

> 全链路：Tool → AgentEventPublisher → AgentEventListener → agent_event → SSE → ResearchPage。

**4 个 Research Tool 落库后真实发布事件（镜像 Tester 既有模式）：**

| 工具 | 事件 | 负载关键字段 |
|------|------|------|
| `SaveSourceTool` | `SOURCE_FETCHED` | sourceId / url / sourceType / title / taskId |
| `ExtractClaimsTool` | `CLAIM_EXTRACTED` | sourceId / sourceTitle / extractedCount / taskId |
| `CompareSourcesTool` | `CLAIM_VALIDATED` | claimId / status / previousConfidence / newConfidence / supportingCount / contradictingCount |
| `GenerateReportTool` | `RESEARCH_REPORT_CREATED` | documentId / title / claimsCount / sourcesCount / taskId |

**前端修复：** `ResearchPage` 的 SSE 事件 handler 与「刷新」按钮原先调用 `invalidateQueries({ queryKey: ["research-"] })` —— react-query v3 以**数组前缀**匹配，字面量 `["research-"]` 不命中任何已注册 key `["research-sources", runId]`。修复为统一 helper `invalidateResearch()`，分别 invalidate `research-sources` / `research-claims` / `research-reports`。

**测试：** `ResearchEventPublishTest`（5：4 类事件各自发布 + 事件类型枚举仍有效）。用真实工具执行 + `eventRepo.findByRunIdAfter` 断言类型与 payload 携带产出 id，证明 Tool→Publisher→listener→agent_event 全链真实贯通（非 mock）。

### R2-3：Task 生命周期 REST + 工作流 UI + 一键发起 Designer

**后端（`api/TaskController.java`，R2-3 范围内补齐校验与事件）：**

| 端点 | 职责 |
|------|------|
| `POST /api/tasks` | 创建 Task；agentType→constraintsJson.agent_type；researchSourceTaskId 仅 DESIGNER 可带 + 引用存在性校验；title/goal/agentType 必填；发布 `TASK_CREATED`（runId=null 走 /sse/global） |
| `GET /api/tasks` | Task 列表（工作流页驱动） |
| `POST /api/tasks/{id}/runs` | 既有 T6-1 Run 启动端点 |

**前端：** 新增 `WorkflowPage`（任务创建表单 + Task 列表 + 最新 Run 状态 + 启动 Run 按钮；`?researchSourceTaskId=` URL 参数预填 DESIGNER+研究引用）；`App.tsx` 挂载 `/workflow` 导航；`api.ts` 增 `listTasks/createTask/startRun/parseTaskAgentType/parseResearchSourceTaskId`；`types.ts` 增 `TaskRow`；`ResearchPage`「以该研究发起 Designer」按钮跳转并携带引用（不复制 ID）。修复 `WorkflowPage` `Td` 组件接受 `style` prop（TSC TS2322）。

**新增测试（本 Phase 补全）：** `TaskControllerTest`（8：RESEARCH 创建 → constraintsJson.agent_type；DESIGNER+researchSourceTaskId 携带；非 DESIGNER 带引用→40001；引用不存在→40401；缺 title/goal/agentType→40001；非法 agentType→40001；listTasks 排序；TASK_CREATED 事件落库）。
> 说明：R2-3 的 TaskController / WorkflowPage / api.ts / types.ts / App 挂载在树中已实现，本次完成验收补齐（后端测试此前缺失）并确认前端全链路编译通过。

### R2-4：Research→Designer 业务 E2E（可追溯链证明）

`ResearchToDesignerE2ETest` —— 经**真实 Runtime**（AgentLoop + RunControlService + ToolRegistry + Repositories + FakeLlmGateway）驱动**两个真实 Run**：

```
① Research Task（agent_type=RESEARCH）→ Run 1（AgentLoop 剧本 save_source→extract_claims→compare_sources→generate_report）
   → research_source / research_claim / [RESEARCH REPORT] design_document 落库，捕获 claim 实际 id + 原文

② Designer Task（agent_type=DESIGNER，constraintsJson.researchSourceTaskId=研究 Task id）→ Run 2
   → AgentLoop 真实执行 get_research_context（工具读取研究产物）
   → create_change_proposal（reason 引用 claim id + 原文）
   → create_design_document（content 引用 claim id + 原文）

③ 断言（同一条 claim id 贯穿 5 处）：
   research_claim.id  ==  get_research_context.result_json.claims[i].id  ==  change_proposal.reason「claim #X」
                      ==  design_document.content_md「claim #X」
```

同时断言 Designer 白名单含两个研究工具（additive）且系统提示仍为冻结的 `"You are AGL game designer agent."`。**结果：1/1 通过。**

---

## 实际修改文件汇总

**Backend（9 生产文件新增/修改、6 测试文件新增、若干既有测试同步）：**

| 文件 | 类型 |
|------|------|
| `main/.../tool/AbstractDesignerResearchTool.java` | 新增（R2-1） |
| `main/.../tool/GetResearchContextTool.java` | 新增（R2-1） |
| `main/.../tool/GetResearchClaimsTool.java` | 新增（R2-1） |
| `main/.../resources/prompts/designer_p002.md` | 新增（R2-1，inactive） |
| `main/.../llm/PromptService.java` | 修改（seeds designer_p002） |
| `main/.../runtime/DefaultAgentTypeResolver.java` | 修改（DESIGNER_TOOL_NAMES +2 → 10） |
| `main/.../research/SaveSourceTool.java` | 修改（R2-2 publish） |
| `main/.../research/ExtractClaimsTool.java` | 修改（R2-2 publish） |
| `main/.../research/CompareSourcesTool.java` | 修改（R2-2 publish） |
| `main/.../research/GenerateReportTool.java` | 修改（R2-2 publish） |
| `test/.../tool/DesignerResearchToolsTest.java` | 新增（5） |
| `test/.../research/ResearchEventPublishTest.java` | 新增（5） |
| `test/.../api/TaskControllerTest.java` | 新增（8） |
| `test/.../research/ResearchToDesignerE2ETest.java` | 新增（1） |
| `test/.../tool/ToolRegistryIntegrationTest.java` 等 | 修改（期望值同步 24 工具 / 10 Designer 工具 / 4 prompts） |

**Frontend（1 页面新增、3 页面/公共文件修改、1 测试同步）：**

| 文件 | 类型 |
|------|------|
| `pages/WorkflowPage.tsx` | 新增（R2-3） |
| `pages/ResearchPage.tsx` | 修改（R2-2 invalidate 修复 + R2-3 一键发起按钮） |
| `App.tsx` | 修改（/workflow 挂载） |
| `api.ts` | 修改（task CRUD + parse helpers） |
| `types.ts` | 修改（TaskRow） |
| `App.test.tsx` | 修改（Workflow 导航断言） |

---

## 回归报告

| 指标 | R1 后 | R2 后 | 变化 |
|------|-------|-------|------|
| 总测试数 | 413 | **441** | +28 |
| Failures | 0 | 0 | 无 |
| Errors | 0 | **0** | 无 |
| BUILD SUCCESS | ✅ | ✅ | 保持 |
| 新增生产文件 | 1 | +9 | R2-1/R2-2 |
| 新增前端页面 | 1 | +1 | WorkflowPage |
| 新增 DB 表 | 0 | 0 | 遵守「只增不减」，R2 无 Schema 变化 |

**新增测试分解（+28）：** DesignerResearchToolsTest +5；ResearchEventPublishTest +5；TaskControllerTest +8；ResearchToDesignerE2ETest +1；既有类同步（AgentTypeResolverTest 20 / ToolRegistryIntegrationTest 5 / TesterRunWiringTest / TesterExperimentIntegrationTest / PromptServiceTest 4 / TesterPromptImportTest 5）增量。

---

## 可追溯链真实性声明（本 Phase 核心判据）

**Research→Designer Artifact Consumption 是否真正实现？—— 已实现。** ✅

依据（非文字声明，是可执行断言）：
1. Designer Run 的 `get_research_context` 工具由 AgentLoop **真实执行**，其 `tool_call.result_json` 经 `assertTraceableChain` 断言包含与 `research_claim` **实际一致**的 claim id + 原文——证明研究产物确实被 Designer 运行时消费，而非空读/占位。
2. `change_proposal.reason` 与 `design_document.content_md` 中的「claim #X」与 research_claim 记录为**同一条** id——证明研究证据进入了设计决策落地的产物。
3. 链路中的每一步（claim → tool 读取 → proposal → design doc）都以**持久化行**形式存在，可经 UI / REST / Run Trace 观测，非进程内共享内存。

**边界如实说明：** 本 E2E 中 FakeLlmGateway 负责扮演「读档后推理」的 LLM 决策（tool 执行结果本身经 ToolExecutor 存库）；真实 LLM 的在线消费由 designer_p002（inactive，异常时可激活）承载。运行时可追溯链（工具读取 + 产物引用）已是真实链路。

---

## Frozen Contract 回归情况

| 约束 | 结论 |
|------|------|
| AgentLoop / RunStateMachine / RunControlService | ✅ 未修改 |
| AgentTypeResolver（resolve/systemPrompt/toolWhitelist 签名） | ✅ 未修改接口；仅 DefaultAgentTypeResolver 数据（Designer 白名单 add +2） |
| BudgetGuard / LoopDetector / ToolExecutor / ToolResult / ToolContext | ✅ 未修改 |
| designer_p001 | ✅ 未修改；仅追加 inactive designer_p002 种子 |
| ConfigVersion 语义 / Error Contract（40001/40401/…） | ✅ 未修改 |
| AgentEventType 既有枚举值 | ✅ 未修改（R2 使用既有 RESEARCH_* 4 值） |
| R0/R1 REST/Frontend/SSE 合同 | ✅ 未破坏；SSE 仅消费端新增 key handler |
| DB Schema append-only | ✅ 零 Schema 变化 |
| parent_run_id / child_run_id / workflow / DAG / agent-to-agent | ✅ 未引入 |
| Tester T4/T5/T6/T7 | ✅ 未触碰 |

---

## STOP 条件核查

| STOP | 是否触发 | 说明 |
|------|------|------|
| STOP-1 冻结契约破坏 | 否 | 见上表 |
| STOP-2 共享产物不可行 | 否 | research_claim/design_document 可溯源落库，链路成形 |
| STOP-3 Schema 变更 | 否 | 复用 design_document（[RESEARCH REPORT] 前缀，TD-R1-001 注册） |
| STOP-4 产品歧义 | 否 | 经 R2 授权裁决判定 |
| STOP-5 外部服务依赖 | 否 | web_search 保持 Stub（TD 延后项）；本 E2E 不触发 |
| STOP-6 范围爆炸 | 否 | 无 Scheme 变化、无通用编排引擎 |

---

## Technical Debt（本 Phase 登记 / 延续）

| 编号 | 描述 | 建议 |
|------|------|------|
| **TD-R1-001**（延续） | 研究报告用 `title LIKE '[RESEARCH REPORT]%'` 前缀标识 | 后续独立 report_type/表 |
| **TD-R1-002**（延续） | 共享静态测试库跨类隔离弱点（测试层已根治） | per-class 临时 DB |
| **TD-R0-遗留**（延续） | web_search Stub | 接真实 Search API |
| **TD-R2-001** | designer_p002 为 inactive 种子，未对生产 Designer Run 激活 | 若上线 research-enhanced Designer 提示词，经 Prompt 版本切换激活（不在 R2 冻结范围） |

---

## Deferred Items（延后项）

1. **真实 Search API 接入** —— web_search Stub 保持（Ruling #1 / TD-R0-遗留）。
2. **designer_p002 激活** —— 仅作为 inactive 版本化提示词存在；激活与否是产品决策，非 R2 验收项。
3. **Research 前端 tab 级 taskId 过滤 / 跨 Run 归并** —— 后续优化，非验收必需。
4. **get_research_context 全文检索 / 引用计数可视化** —— 可追溯性增强，非 R2 必需。

---

## 实际 Scope 变化

**最终 Scope 与原 R2 授权一致，无扩大、无收缩。**

命令内增量（授权允许的补齐类）：
- `TaskControllerTest` 后端测试缺失补全（R2-3 验收验证需要，未改生产语义）。
- 既有 Tester/Designer 测试期望值同步到 24 工具 / 10 Designer 工具 / 4 Prompt 行（R2-1 数据变动的测试层匹配）。

「因为可以优化/顺手重构」导致的越界改动均未发生；无通用工作流引擎、无 agent-to-agent 消息、无 Run DAG。

---

## 设计决策记录

| # | 决策 | 依据 |
|---|------|------|
| R2-D1 | 研究→设计协作采用「产物消费 + 显式引用」：Designer 经 `get_research_context`/`get_research_claims` 读取 Research 落库产物，而非 agent-to-agent 消息 | R2 授权架构原则：协作靠 Task/Run + Research Artifact + 显式引用；禁止直呼/编排 |
| R2-D2 | 关联由 `task.constraintsJson.researchSourceTaskId` 声明，经 run→task→constraintsJson 解析 | AgentLoop/ToolExecutor 冻结，ToolContext 只带 runId；不新增运行时接线 |
| R2-D3 | Designer 白名单 additive 追加 2 只读工具（8→10），不删不改既有工具 | R2 授权「Tools: add Designer-only read tools」；向后兼容（无研究关联行为不变） |
| R2-D4 | 新增提示词为 inactive `designer_p002` 种子，冻结 `designer_p001` 不动 | R2 授权「MUST NOT modify designer_p001; MAY add designer_p002」 |
| R2-D5 | 4 个 Research 事件由工具在落库后真实发布（镜像 Tester 模式），SSE 消费端只改前端 key handler | R2-2 判据「必须 ACTUALLY publish」，全链 Tool→Publisher→listener→SSE 贯通 |
| R2-D6 | 新增 `TaskControllerTest` 8 用例补齐 R2-3 验收（不重复 T6-1 的 startRun 集成，避免 Fake 覆盖真实链路） | R2 授权测试自主 + 禁止 mock 核心行为 |
| R2-D7 | Research→Designer E2E 用 FakeLlmGateway 扮演「读档后推理」的 LLM 决策，运行时链路（工具读取+产物引用）真实 | 测试确定性；工具执行结果经 ToolExecutor 存库可断言，非 mock 核心 |

## 人工验收清单

| 验收项 | 如何验证 |
|--------|----------|
| Research Task 创建 + Run 启动 | `POST /api/tasks`（agentType=RESEARCH）→ `POST /api/tasks/{id}/runs` → `GET /api/runs/{id}` |
| 研究产物可观测 | `GET /api/research/sources?taskId=X` / `claims` / `reports`；前端 Research 页三 Tab |
| 一键发起 Designer | Research 页「以该研究发起 Designer」→ Workflow 页 URL 携带 `researchSourceTaskId`、预填 DESIGNER |
| Designer 消费研究产物 | Run Trace 中 Designer Run 的 `get_research_context` tool_call 返回含研究 claim id + 原文 |
| 设计决策可追溯 | Change Proposal reason 与 Design Document content 引用同一条 claim id（`/api/runs/{id}` 或 DB 查询） |
| 向后兼容 | 无 researchSourceTaskId 的 Designer Task 行为与 R2 前一致（DesignerResearchToolsTest unlinked 用例） |
| 全量回归 | Backend `mvn test` ≥441/0F/0E；Frontend `npx tsc --noEmit` + `npx vitest run` + `npx vite build` |

## 结论

**R2 FULLY COMPLETE — WAITING FOR HUMAN ACCEPTANCE.**

Research→Designer 协作链已可运行：用户从 UI 创建 Research Task → 研究产出可经 REST/SSE 观测 → 一键「以该研究发起 Designer」 → Designer 经 `get_research_context` 真实消费研究产物 → 产出引用同一条研究 claim 的 Change Proposal 与 Design Document。全链可追溯（claim id 贯穿 research_claim → tool result → proposal → design doc），向后兼容（无研究关联时 Designer 行为不变），全量回归 441/0F/0E + 前端全绿。