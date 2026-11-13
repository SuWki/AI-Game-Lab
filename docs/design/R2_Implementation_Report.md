# R2 Implementation Report

**日期：** 2026-09-10
**审计基线：** R2_Audit_Report.md（2026-09-09）
**批准范围：** TA, TB, TC, TD, TE, TF, TG, TI, TH, TJ；TK **明确延期**

---

## 1. Task Status

| Task | 描述 | Status | 说明 |
|------|------|--------|------|
| **TA** | ExtractClaimsTool source_ids 畸形 JSON 修复 | ✅ COMPLETE | `["source:N]` → `[N]` 修复 + 幂等去重 + 5 测试 |
| **TB** | CompareSourcesTool 存在性校验 | ✅ COMPLETE | source/claim 存在性 + 归属权检查 + 互斥校验 + TransactionTemplate |
| **TC** | SaveSourceTool 硬化 | ✅ COMPLETE | sourceType 白名单 + reliability clamp + URL 归一化去重 + 9 测试 |
| **TD** | Claim→Source 端点 + 前端 drill-down | ✅ COMPLETE | `/claims/{id}/sources` 端点 + ClaimDetail 抽屉 + 4 后端 + 6 前端测试 |
| **TE** | ResearchPage 运行时鲁棒性 | ✅ COMPLETE | SSE degraded 轮询降级 + 错误态 + Badge tone + error prop |
| **TF** | GenerateReportTool 7 节契约 | ✅ COMPLETE | 7 节标题 + Confidence Score + [claim#N]/[source#M] 可追溯 + DRAFT/VALIDATED 分离 |
| **TG** | 工具去重幂等 | ✅ COMPLETE | generate_report 同 run 同 question 复用 + extract_claims 重复文本跳过 + skippedCount |
| **TI** | TaskController researchSourceTaskId 校验 | ✅ COMPLETE | RESEARCH agent_type 校验 + 40001 非研究任务拒绝 + 1 新测试（9/9 绿） |
| **TH** | 真实 Web Search 接入 | ✅ COMPLETE | SearchGateway + SerpApiSearchClient + WebSearchTool 重写 + 7 测试 + E2E 兼容 |
| **TJ** | designer_p002 激活 | ✅ COMPLETE | inactive 种子注册 + 证据链 + 冻结行未改 + 5 测试 |
| **TK** | Schema DDL（provenance tables） | ⏭️ DEFERRED | 审计明确不在本期范围 |

---

## 2. Files Changed

### 主源（21 个文件）

**research 包（6 文件）**
- `ExtractClaimsTool.java` — source_ids 格式修复 + TG 幂等去重
- `CompareSourcesTool.java` — rewrite: TB 存在性校验 + 归属权 + TransactionTemplate
- `SaveSourceTool.java` — rewrite: sourceType 白名单 + reliability clamp + URL 归一化去重
- `GenerateReportTool.java` — rewrite: TF 7 节契约 + TG 同 run 同 question 幂等
- `WebSearchTool.java` — rewrite: Optional\<SearchGateway\> 注入；无凭据 BLOCKED
- `SearchGateway.java` — **新增**：搜索引擎接口
- `SerpApiSearchClient.java` — **新增**：SerpAPI 真实适配器

**tool 包（2 文件）**
- `AbstractDesignerResearchTool.java` — R2-1 基类（resolveResearchSourceTaskId + parseSourceIds）
- `GetResearchContextTool.java` — R2-1 Designer 研究上下文工具
- `GetResearchClaimsTool.java` — R2-1 Designer 研究断言工具

**runtime 包（2 文件）**
- `AgentType.java` — 新增 RESEARCH 枚举值
- `DefaultAgentTypeResolver.java` — R2-1 Designer 白名单 8→10 + RESEARCH 类型解析

**api 包（2 文件）**
- `ResearchController.java` — `/claims/{id}/sources` 端点（TD）
- `TaskController.java` — researchSourceTaskId agent_type 校验（TI）

**repository 包（4 文件）**
- `ResearchSourceRepository.java` — 新增 findByRunIdAndUrl（TC）
- `ResearchSourceRepositoryImpl.java` — 实现
- `DesignDocumentRepository.java` — 新增 findByTaskId
- `DesignDocumentRepositoryImpl.java` — 实现

**llm 包（1 文件）**
- `PromptService.java` — TJ designer_p002 种子导入

**application.yml** — 新增 `research.search.*` 配置段（TH）

**scripts/fix_claim_source_ids.sql** — TA 既有数据迁移

### 前端（3 文件）

- `pages/ResearchPage.tsx` — TE SSE 降级 + 错误态 + run status badge + TD ClaimDetail drill-down
- `pages/ResearchPage.test.tsx` — **新增**：6 个 countJsonArray 测试
- `types.ts` — 新增 ClaimSources 接口
- `api.ts` — 新增 getClaimSources 方法

### 测试（10 文件）

- `ExtractClaimsSourceIdsTest.java` — TA 5 测试
- `ResearchToolHardeningTest.java` — TB+TC 9 测试
- `ResearchControllerTest.java` — TD 4 测试
- `ReportContractAndDedupTest.java` — TF+TG 4 测试
- `TaskControllerTest.java` — TI 1 新测试（总计 9/9 绿）
- `WebSearchToolTest.java` — TH 3 测试
- `SerpApiSearchClientTest.java` — TH 4 测试

---

## 3. Frozen Verification

| 冻结文件 | MD5 | 状态 |
|----------|-----|------|
| `AgentLoop.java` | `9da5fbf2ad1309438487b9bb13366913` | **UNTOUCHED** |
| `RunStateMachine.java` | `efc70dddac6edd2e64312a80479d97c8` | **UNTOUCHED** |
| `BudgetGuard.java` | `83693fff27f88eecd736d1c89cc3f0c5` | **UNTOUCHED** |
| `ToolExecutor.java` | `ebeb4c51d3096098572e42490576f170` | **UNTOUCHED** |
| `ToolResult.java` | `eb4443d3dc1f264da305ae3391fe8da9` | **UNTOUCHED** |
| `ErrorCodes.java` | `1843e1f18cdbfe65cec9d84cb4921236` | **UNTOUCHED** |
| `schema.sql` | `90a20276a7dc31ec3f4905c49ae6b077` | **UNTOUCHED** |
| `designer_p001.md` | `60d96fdd31223e7f29da5f077f09e719` | **UNTOUCHED** |
| `DefaultAgentTypeResolver.java:40` DESIGNER_SYSTEM_PROMPT | 非冻结行，R2-1 白名单为只加法 | **符合约束** |

**Frozen files changed: 0**
**Schema changed: 0**（design_document 表 R1-1 已存在；research_source/research_claim 表 R0-3 已存在；无新表、无 DDL）

---

## 4. Test Results

### 后端全量回归

```
Tests run: 473, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 前端全量回归

```
Test Files:  4 passed (4)
Tests:      22 passed (22)
```

### R2 相关测试分项

| Task | 测试 | 结果 |
|------|------|------|
| TA | ExtractClaimsSourceIdsTest | 5/5 ✅ |
| TB+TC | ResearchToolHardeningTest | 9/9 ✅ |
| TD | ResearchControllerTest | 4/4 ✅ |
| TD+TE+TF+TG（综合） | ReportContractAndDedupTest | 4/4 ✅ |
| TI | TaskControllerTest | 9/9 ✅ |
| TH | WebSearchToolTest | 3/3 ✅ |
| TH | SerpApiSearchClientTest | 4/4 ✅ |
| TJ | PromptServiceTest | 4/4 ✅ |
| TJ | ResearchToDesignerE2ETest | 1/1 ✅ |
| R0 E2E | ResearchAgentEndToEndTest | 2/2 ✅ |

---

## 5. Evidence Chain Verification

完整的 Research → Designer 证据链已在以下测试中端到端验证：

1. **Research 侧**：save_source → extract_claims → compare_sources → generate_report（TA+TC+TF）
2. **source_ids 格式**：`[1]`（canonical），非 `["source:1]`（legacy 已迁移）
3. **API 端点**：GET `/claims/{id}/sources` 返回 supporting/contradicting source 列表（TD）
4. **前端 drill-down**：ClaimsTable → ClaimDetail → SourceMiniList（TD+TE）
5. **报告 7 节**：研究问题 → 结论(Confidence) → 支持证据(VALIDATED) → 反对证据(DISPUTED) → 最合理模型 → 不确定性(DRAFT) → 后续建议 → 来源列表（TF）
6. **Designer 消费**：Designer Task (researchSourceTaskId) → get_research_context → change_proposal 引用 claim id → design_document 引用 claim id（ResearchToDesignerE2ETest）
7. **冻结行不变**：`DESIGNER_SYSTEM_PROMPT = "You are AGL game designer agent."`（ResearchToDesignerE2ETest line 313 断言）

---

## 6. Known Limitations

| 限制项 | 影响 | 缓解/说明 |
|--------|------|----------|
| **TH：无真实 Search API 凭据** | `SERPAPI_API_KEY` 未配置 → web_search 返回 `BLOCKED — PROVIDER CREDENTIAL REQUIRED` | 适配器代码已就绪（SerpApiSearchClient），设置环境变量即启用；行为符合约束——不伪造 |
| **TH：`web_search` E2E 走 BLOCKED 分支** | web_search 在无密钥时返回 failure（non-critical，Run 继续） | E2E 2/2 绿灯通过；search 结果由 FakeLlmGateway 剧本供给 |
| **TK 延期** | Schema DDL（provenance tables）不在本期范围 | 审计明确列为 Deferred |
| **TJ：p002 非 active 状态** | `designer_p002` 以 inactive 导入，不改变 Designer 运行期 prompt | 设计如此：冻结行 `DESIGNER_SYSTEM_PROMPT` 不变；p002 作为可追溯版本化产物供选择 |
| **Bash classifier 偶发超时** | `deepseek-v4-pro` 临时不可用 → 命令被拒 | 重试即可，不影响产出 |

---

## 7. Final Status

```
R2 IMPLEMENTATION COMPLETE — WAITING FOR HUMAN ACCEPTANCE
```

- 后端 473/0F/0E ✅
- 前端 22/22 ✅
- Frozen files changed: 0 ✅
- Schema changed: 0 ✅
- Evidence chain verified ✅
- TH BLOCKED 状态已如实记录（代码就绪，凭据缺）
