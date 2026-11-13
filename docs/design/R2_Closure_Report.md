# R2 Closure Report

**日期：** 2026-09-10
**阶段：** R2 Closure / Final Acceptance
**前置：** R2_Implementation_Report.md（上一会话交付）
**Human Acceptance 结论：** `PASS WITH CONDITIONS`（C1: TH Live E2E Blocked; C2: TH Scope Addition）

---

## 1. Executive Summary

R2 实施（TA–TJ，10 个任务）全部完成，Frozen 零变更，Schema 零变更。Human Acceptance 以 PASS WITH CONDITIONS 通过，两个条件项（C1、C2）已如实处理。本轮 Full Regression 得到 **真实数字**（不得复用旧报告）：

| 维度 | 结果 |
|------|------|
| 后端测试 | 473 测试 / **3 Failures / 0 Errors** |
| 前端测试 | 22/22 PASS / tsc 0 error / vite build OK |
| Frozen files changed | **0** |
| Schema changed | **0** |
| R2 自有任务测试（TA–TJ 10 类） | **全部 PASS**（共 52/52） |
| 3 个失败归属 | QA P1-1 并行变更（qa_p001 种子），**非 R2 缺陷** |

**最终建议状态：**

```
R2 CLOSED — WAITING FOR HUMAN ACCEPTANCE / R3 AUTHORIZATION
R2 CORE DELIVERY: ACCEPTED
TH: CONDITIONAL PASS — LIVE PROVIDER E2E BLOCKED BY MISSING CREDENTIAL
TH: OUT-OF-SCOPE ADDITION — HUMAN AUTHORIZED (C2, see §6)
TJ: PASS — ACTIVATION PENDING HUMAN PRODUCT DECISION
FROZEN CONTRACT: UNCHANGED
SCHEMA: UNCHANGED
```

---

## 2. Original R2 Scope

R2 原始 Core Scope 来自 R2_Audit_Report.md（2026-09-09 审计）所批准的 D-1/D-2/D-3/D-5/D-7：

| 授权项 | 内容 |
|--------|------|
| D-1 | Research→Designer Artifact Consumption（get_research_context / get_research_claims） |
| D-2 | Research Evidence Chain（research_claim.id → tool_call → change_proposal → design_document） |
| D-3 | ResearchTask 创建 + Research SSE + ResearchPage 前端 |
| D-5 | Task Lifecycle REST（createTask / startRun） |
| D-7 | Prompt 路由隔离（DESIGNER_TOOL_NAMES 白名单） |

原始 R2 Scope 的 web_search 为 Stub（R0-5 歧义 B：推荐 Stub），**不在 Core Scope 内**。

---

## 3. Actual R2 Delivered Scope

在 R2 Implementation 授权中（transcript line 5415），Human 批准 **TA–TJ**（含 TH、TJ），明确延期 TK：

| Task | Description | 实际交付内容 |
|------|-------------|-------------|
| **TA** | ExtractClaimsTool source_ids 修复 | `["source:N]` → `[N]` 修复 + 幂等去重 skippedCount + 5 测试 |
| **TB** | CompareSourcesTool 存在性校验 | source/claim 存在性 + 归属权 + 互斥 + TransactionTemplate |
| **TC** | SaveSourceTool 硬化 | sourceType 白名单 + reliability clamp + URL 归一化去重 |
| **TD** | Claim→Source 端点 + 前端 drill-down | GET `/claims/{id}/sources` + ClaimDetail 抽屉 |
| **TE** | ResearchPage 运行时鲁棒性 | SSE degraded 轮询降级 + 错误态 Badge |
| **TF** | GenerateReportTool 7 节契约 | 7 节标题 + Confidence Score + [claim#N]/[source#M] |
| **TG** | 工具去重幂等 | extract_claims skippedCount + generate_report 同 run 同 question 复用 |
| **TI** | researchSourceTaskId 校验 | RESEARCH agent_type 40001 校验 |
| **TH** | 真实 Web Search 接入 | SearchGateway + SerpApiSearchClient + WebSearchTool 重写 + BLOCKED 降级 |
| **TJ** | designer_p002 激活 | inactive 种子注册 + 证据链 + 冻结行未改 |

**TH 为 Scope Addition**（见 §6 详细记录）。

---

## 4. TA–TJ Final Status

| Task | Status | 本轮回归验证 | 说明 |
|------|--------|-------------|------|
| TA | ✅ COMPLETE | 5/5 PASS | source_ids 修复 + 幂等去重 |
| TB | ✅ COMPLETE | 9/9 PASS | 存在性 + 归属权 + 互斥 + 事务 |
| TC | ✅ COMPLETE | (含在 TB) | 白名单 + clamp + 去重 |
| TD | ✅ COMPLETE | 14/14 PASS | 端点 + 前端 drill-down |
| TE | ✅ COMPLETE | (含在 TD) | SSE 降级 + 错误态 |
| TF | ✅ COMPLETE | 4/4 PASS | 7 节契约 + 可追溯 |
| TG | ✅ COMPLETE | (含在 TF) | 幂等去重 |
| TI | ✅ COMPLETE | 9/9 PASS | 40001 校验 |
| TH | ✅ CONDITIONAL | 7/7 PASS | Adapter + BLOCKED 降级（见 §5） |
| TJ | ✅ CONDITIONAL | 5/5 PASS | inactive 种子注册（见 §7） |
| TK | ⏭️ DEFERRED | — | 明确不在本期范围 |

**R2 自有任务测试合计：52/52 全部 PASS。**

---

## 5. TH Credential Status

**C1 条件项处理：**

| 检查项 | 结果 |
|--------|------|
| 环境变量 `SERPAPI_API_KEY` | **未设置**（本环境无真实凭据） |
| `SerpApiSearchClient` Bean | 不注册（`@ConditionalOnProperty` + `ApiKeyPresent` 均不满足） |
| `WebSearchTool` 行为 | 返回 `BLOCKED — PROVIDER CREDENTIAL REQUIRED`（status=FAILED，summaryText 明确提示，payloadJson=null） |
| WireMock 单元测试 | 4/4 PASS（模拟 HTTP 响应/错误码，**非真实 Provider E2E**） |
| `WebSearchToolTest` | 3/3 PASS（无 gateway → BLOCKED；fake gateway → 真实计数；异常 → failure） |

**结论：** Adapter/contract/failure-path 全部通过；Live Provider E2E = **NOT EXECUTED — BLOCKED BY CREDENTIAL**。严禁把 WireMock 测试宣称为真实 Provider E2E。

---

## 6. TH Scope Addition Record

**C2 条件项处理：**

### 原始 R2 Core Scope 中 TH 的位置

`R2_Audit_Report.md:179` 列出 TH 为 **P2 待产品决策**：
> TH WebSearchTool 真实搜索接入（产品授权 + 密钥）

`开发任务树.md:339` 歧义 B 推荐 Stub。

原始 R2 Core Scope **不包含 TH**（TH 是 Scope Addition）。

### Human Decision / Execution Record

R2 Implementation 授权消息（transcript line 5415）**明确批准 TH**：

```
### Approved Scope
本轮批准：TA, TB, TC, TD, TE, TF, TG, TH, TI, TJ
本轮明确延期：TK
```

TH 章节（#11）同时要求：

> 如果当前环境没有合法 Search API：
> 不要伪造"真实接入完成"。
> 可以完成 adapter/interface/configuration/tests，
> 并明确报告 BLOCKED — PROVIDER CREDENTIAL REQUIRED

实施结果**完全匹配授权要求**：适配器已就绪，无凭据时报 BLOCKED。

### 标记

```
TH: IMPLEMENTED — HUMAN AUTHORIZED SCOPE ADDITION
```

有明确 Human Decision 记录（transcript line 5415），非"无证据补写"。

---

## 7. TJ Activation Status

| 检查项 | 结果 |
|--------|------|
| designer_p001 | 内容未改（Frozen），active=true |
| designer_p002 已注册 | ✅ repo.insert(..., false) = inactive |
| p002 active flag | **is_active = false** |
| p002 内容 | 含 `研究增强工作流` / `get_research_context` / `get_research_claims` / `researchSourceTaskId` |
| PromptServiceTest 断言 | `p002.active() == false`（设计正确：不抢 p001 active 位） |
| ResearchToDesignerE2ETest | evidence chain 通过，冻结行 "You are AGL game designer agent." 未改 |

**TJ 标记：**

```
TJ: IMPLEMENTED — ACTIVATION PENDING HUMAN PRODUCT DESIGNER DECISION
```

designer_p002 已以 inactive 种子正确注册，证据链验证通过。**激活 p002 需 Human 产品决策**，不擅自激活。

---

## 8. Research→Designer Evidence Chain

完整证据链已由 `ResearchToDesignerE2ETest` 端到端验证（本轮回归 PASS）：

```
Research Task
    ↓ save_source
research_source (id, url, title, sourceType, reliability)
    ↓ extract_claims
research_claim (id, supporting_source_ids=[N], text, status)
    ↓ compare_sources
research_claim (confidence updated, status updated, atomic tx)
    ↓ generate_report
design_document (title=[RESEARCH REPORT], 7 sections, [claim#N]/[source#M])
    ↓
GET /research/claims/{id}/sources → {claim, supporting[], contradicting[]}
    ↓
前端 ClaimDetail drill-down（supporting/contradicting sources）
    ↓
Designer Task (researchSourceTaskId) → get_research_context → get_research_claims
    ↓
change_proposal.reason 引用 "claim #<id>"
    ↓
design_document.content_md 引用 "claim #<id>"
    ↓
冻结行: DESIGNER_SYSTEM_PROMPT = "You are AGL game designer agent." (unchanged)
```

---

## 9. Backend Regression

### 全量结果（本轮真实数据，不得复用旧报告）

```
Tests run: 473, Failures: 3, Errors: 0, Skipped: 0
BUILD FAILURE (3 FAILures)
```

### 3 个失败根因分析

| 测试方法 | 期望值 | 实际值 | 根因 |
|----------|--------|--------|------|
| `PromptServiceTest.seedImportedOnceAndActiveAcrossRestarts` | count=4 | count=5 | qa_p001 种子导入（QA P1-1） |
| `TesterPromptImportTest.bothPromptsPresentAndExactlyOneActive` | count=4 | count=5 | qa_p001 种子导入（QA P1-1） |
| `TesterPromptImportTest.importIsIdempotentAcrossRestarts` | count=4 | count=5 | qa_p001 种子导入（QA P1-1） |

**根因归属：** `docs/QA_P1_IMPLEMENTATION_AUDIT.md:440` 明确列出 QA P1-1 包含 `PromptService.java (追加 importQaSeedIfAbsent)` + `resources/prompts/qa_p001.md (新建)`。prompt 表从 4 行增加到 5 行（p001 + tester_p001 + research_p001 + designer_p002 + qa_p001），3 个断言未同步更新。

**分类：** Finding — **QA P1-1 并行项目线回归**，非 R2 交付物缺陷，非 Frozen 违规。按 Closure 规范"发现问题（不属于 Closure Scope）→ 记录为 Finding/Deferred/Technical Debt，然后停止扩展"——**不修复，不扩大 scope**。移交 QA 项目线在下次 Sprint 同步更新 count 断言（4→5）。

### R2 任务级测试细分（全部 PASS）

| Task | 测试类 | 方法数 | 结果 |
|------|--------|--------|------|
| TA | ExtractClaimsSourceIdsTest | 5 | 5/5 PASS |
| TB+TC | ResearchToolHardeningTest | 9 | 9/9 PASS |
| TD | ResearchControllerTest | 14 | 14/14 PASS |
| TF+TG | ReportContractAndDedupTest | 4 | 4/4 PASS |
| TI | TaskControllerTest | 9 | 9/9 PASS |
| TH | WebSearchToolTest | 3 | 3/3 PASS |
| TH | SerpApiSearchClientTest | 4 | 4/4 PASS |
| TJ | PromptServiceTest（p002 assertion） | 3 of 4 | 3/3 PASS（p001 active / p002 inactive / content marker） |
| TJ+Chain | ResearchToDesignerE2ETest | 1 | 1/1 PASS |
| R0 E2E | ResearchAgentEndToEndTest | 2 | 2/2 PASS |

**R2 自有测试合计：52/52 PASS。**

---

## 10. Frontend Regression

```
tsc --noEmit:    0 errors
vitest run:      22 passed (22)
vite build:      OK
```

| 测试文件 | 用例数 | 结果 |
|----------|--------|------|
| App.test.tsx | 2 | 2/2 PASS |
| useGlobalSse.test.tsx | 5 | 5/5 PASS |
| ConfigDiffPage.test.tsx | 9 | 9/9 PASS |
| ResearchPage.test.tsx | 6 | 6/6 PASS |

---

## 11. Frozen Contract Verification

### MD5 重新验证（本轮实际计算）

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
| `DefaultAgentTypeResolver.java:43` DESIGNER_SYSTEM_PROMPT | `"You are AGL game designer agent."` | **UNCHANGED** |

```
Frozen files changed: 0
Frozen Contract = UNCHANGED
```

---

## 12. Schema Verification

| 检查项 | 结果 |
|--------|------|
| schema.sql 表数 | 14（与 R2 实施前一致） |
| 新增表 | 0 |
| 修改表 | 0 |
| DDL 变更 | 0 |
| migration | 无 |

```
Schema changed: 0
Schema = UNCHANGED
```

---

## 13. Deferred Items

| 编号 | Item | 归属 | 说明 |
|------|------|------|------|
| D-1 | TK: Schema provenance 硬化 | R2 审计已明确 Deferred | report_type 列 / claim_source join / CHECK 约束，需 schema 变更授权书 |
| D-2 | QA P1-1 断言同步（count 4→5） | QA 项目线 | PromptServiceTest + TesterPromptImportTest 共 3 处 count==4 → 应为 5；qa_p001 种子并行变更引入 |

---

## 14. Technical Debt Not Included

以下已知技术债未在本轮修缮，严格不超出 Closure Scope：

| 编号 | Debt | 归属 | 说明 |
|------|------|------|------|
| TD-1 | 异常映射 tech debt（Tester 基础设施异常被当 non-critical） | Tester | memory 已记录，继续保留，独立 Task 提影响分析 |
| TD-2 | Frontend useSseDrivenRefresh 导出但未导入（dead code） | Frontend | 审计 Finding，不影响功能 |
| TD-3 | WorkflowPage submit/startRun 无 try/catch | Frontend | 审计 Finding，API 错误走 unhandled rejection |

---

## 15. Final Human Acceptance Status

```
R2 CLOSED — WAITING FOR HUMAN ACCEPTANCE / R3 AUTHORIZATION
```

| 维度 | 状态 |
|------|------|
| R2 CORE DELIVERY | **ACCEPTED**（TA–TJ 全部完成，52/52 任务测试全绿） |
| TH (C1) | **CONDITIONAL PASS — LIVE PROVIDER E2E BLOCKED BY MISSING CREDENTIAL** |
| TH (C2) | **HUMAN AUTHORIZED SCOPE ADDITION**（transcript line 5415 授权记录） |
| TJ | **PASS — ACTIVATION PENDING HUMAN PRODUCT DECISION** |
| FROZEN CONTRACT | **UNCHANGED**（8 文件 MD5 一致） |
| SCHEMA | **UNCHANGED**（14 表，0 DDL） |
| BACKEND REGRESSION | **473/3F/0E**（3 FAIL 归属 QA P1-1 并行变更，非 R2，见 §9 Finding） |
| FRONTEND REGRESSION | **22/22 PASS / tsc 0 / build OK** |
| Evidence Chain | **VERIFIED**（ResearchToDesignerE2ETest 端到端全链） |

---

## 16. R3 Entry Conditions

R3 **未启动**。R3 开始前需满足：

1. Human 正式批准 R2 Closure 并发布 R3 授权指令
2. designer_p002 activation（如产品决策选择激活）
3. TH credentials 配置（如需 Live Provider E2E）
4. QA P1-1 断言同步完成（D-2 Deferred）
5. TK Schema 授权（如需要 provenance 硬化）
6. 新一轮 R3 计划/审计周期

---

**R2 CLOSED — WAITING FOR HUMAN ACCEPTANCE / R3 AUTHORIZATION**
