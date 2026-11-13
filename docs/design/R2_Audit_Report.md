# Research R2 Implementation Audit

> 审计范围：R2 Research Agent 全链路（6 Tools + Data Model + API + Frontend + Prompt/Routing + Tests + Evidence Chain + Architecture）
> 审计方式：8 路并行深度审计工作流 + 关键 CRITICAL 发现人工逐行复核
> 审计基准：`backend/src/test` 新鲜 surefire **443 tests / 0 Failures / 0 Errors**（2026-09-09，JDK 24.0.1）；Frontend tsc ✓ / vitest 16 ✓
> 审计判定：R1 验收全部成立、R2 交付物架构与隔离成立（0 条 FROZEN 违规），但存在 **1 个 CRITICAL 数据完整性缺陷**、多项 MEDIUM/HIGH 质量债与证据链缺口。绝大部分可采用最小化修复（不动 schema、不动冻结文件）。

---

## 1. 当前实际代码状态

### 后端（Java 21, Spring Boot 3.3.5, SQLite/JdbcTemplate）

| 类别 | 文件 | 状态 |
|---|---|---|
| 工具基类 | `research/AbstractResearchTool.java` | R0-4 完整 |
| 六大 Research Tool | `WebSearchTool ／ FetchPageTool ／ SaveSourceTool ／ ExtractClaimsTool ／ CompareSourcesTool ／ GenerateReportTool` | **2 STUB, 1 PARTIAL, 3 DONE(带限)** |
| 抓取服务 | `research/WebFetchService.java` | 8000 字符截断 |
| Domain | `domain/ResearchSource.java`, `domain/ResearchClaim.java` | claim 源 ID 为裸 `String` |
| Repository | `ResearchSourceRepository(+Impl)`, `ResearchClaimRepository(+Impl)` | JDBC, 只读查询齐备 |
| Config | `config/ResearchToolConfig.java` | 注册 6 tools |
| API | `api/ResearchController.java` | 5 个只读 GET（R1-2） |
| Designer 消费侧 | `tool/AbstractDesignerResearchTool.java`, `GetResearchContextTool.java`, `GetResearchClaimsTool.java` | Designer whitelist 8→10（R2-1 ✅） |
| Prompt | `resources/prompts/research_p001.md`（~30 行中文） | RESEARCH agent，7 节输出契约 |
| 事件/SSE | `event/` 包 + `api/SseController.java` | 4 个 RESEARCH 事件真实发布（R2-2 ✅） |
| Runtime | `AgentTypeResolver / DefaultAgentTypeResolver / AgentLoop / ToolExecutor / PromptService / ToolRegistry` | 全部冻结或被冻结环委托，0 违规 |

### 前端（React 18 + TS + Vite, react-query v3）

| 文件 | 内容 | 状态 |
|---|---|---|
| `pages/ResearchPage.tsx` | 434 行: Run 选择 + 3 Tab + 2 Drawer +「以该研究发起 Designer」 | R1 交付，**无专属测试** |
| `useGlobalSse.ts` | 全局 SSE hook，`useSseDrivenRefresh`（149-174 行）自带降级轮询 | hook 声称复用但页面未实际调用 |
| `api.ts / types.ts` | research fetchers / 类型 | R1 |
| `pages/WorkflowPage.tsx` | R2 Task 流程 + 研究引用回链（218 行, 不带 runId） | R2 |

### 测试（6 个 Research 专属测试类，28 tests，全 PASS）

`ResearchControllerTest`(10), `ResearchAgentEndToEndTest`(2), `ResearchEventPublishTest`(5), `ResearchToDesignerE2ETest`(1), `ResearchRepositoryTest`(5), `DesignerResearchToolsTest`(5)。全库基线 **443/0F/0E**。

---

## 2. R1 验收结果复核

R1 六项目标全部复核通过，**无一失效**：

| R1 验收项 | 现行证据 | 复核 |
|---|---|---|
| 修复 25 个 R0 测试 Error → 403/0F/0E | 基线 443/0F/0E; `AbstractRepositoryTest.resetDbToCleanState()` + `ensureSeedV001()` 在位 | ✅ |
| 5 个只读 Research REST 端点 | `ResearchController.java` 全量存在，40001/40401 映射正确 | ✅ |
| Research 前端页 | `ResearchPage.tsx` Run 选择 + 3 Tab + Drawer | ✅ |
| SSE Research 事件可观测 | `useGlobalSse.ts` 白名单 4 事件 + `isResearchEvent` + `invalidateResearch` | ✅ |
| Designer/Tester 向后兼容 | 443/0F/0E; DESIGNER whitelist 8→10 仅加法 | ✅ |
| `DesignDocumentRepository.findByRunId` | 实现存在，供 `listReports`/`GetResearchContextTool` 使用 | ✅ |

**R2 授权项复核**：Research→Designer 共享工件消费、`get_research_context`/`get_research_claims`、Traceability 链（`research_claim.id` → tool_call result_json → `change_proposal.reason` → `design_document.content_md`）全部交付且经 `ResearchToDesignerE2ETest` 验证。无 schema 变更、无新服务、无 DAG。**R2 整体 CLEAN**。

---

## 3. Research 六大 Tool 现状

| Tool | 状态 | DB 写入 | 关键局限（附证据） |
|---|---|---|---|
| `WebSearchTool` | **STUB** | 无 | 返回伪造结果 `https://example.com/search/N`（[WebSearchTool.java:43-57](backend/src/main/java/com/aigamelab/research/WebSearchTool.java#L43-L57)）；`numResults` 截断到 5；`totalResults` 回显请求数非实际结果数（误导字段） |
| `FetchPageTool` | DONE(带限) | 无 | 无 URL scheme/SSRF 检查（`URI.create` 可吃 `file://`）；8000 字符截断**无截断标志**；失败分支无可测覆盖 |
| `SaveSourceTool` | DONE(带限) | `research_source` INSERT | `sourceType` 不校验 5 值枚举；`reliability` 不夹取 [0,1]；**无同 URL 去重** → 同页多次保存产生 N 行 |
| `ExtractClaimsTool` | **STUB** | `research_claim` INSERT | 正则机械断句；固定初始置信度 0.3；**`:87` 写入 `["source:N]`——畸形 JSON**（未闭合引号）；同源重跑无去重 |
| `CompareSourcesTool` | PARTIAL | 3 次独立 UPDATE | 确定性计数公式夹取 [0,1]；**同一 source id 可同时进 supporting+contradicting**（自相净 −0.1）；无源存在性/归属校验；3 个 UPDATE 非原子；整体替换覆盖 extract 阶段来源溯源 |
| `GenerateReportTool` | DONE(带限) | `design_document` INSERT（title `[RESEARCH REPORT]`） | 扁平模板：**无 claim↔source 关联渲染**；DRAFT 与 VALIDATED 混排「支持证据」（误导）；无置信度阈值；无 markdown 转义；prompt 承诺 7 段只渲染 4 段+统计；**正文不嵌入 claim id / source id** |

---

## 4. Source / Claim / Report 数据关系

```
task (id PK) 1──< run (id PK, task_id FK NOT NULL) 1──< research_source (run_id/task_id FK NOT NULL, url, source_type, title, content_summary, reliability, fetched_at)
                                                   1──< research_claim   (run_id/task_id FK NOT NULL, claim, confidence,
                                                                         supporting_source_ids TEXT("JSON array of research_source.id"),
                                                                         contradicting_source_ids TEXT, status, created_at)
design_document (id PK, run_id FK NULL, task_id FK NULL, title, content_md)  ← GenerateReportTool 复用为 [RESEARCH REPORT]
```

| 关系 | 实现方式 | 强度 |
|---|---|---|
| task → run → research | run.task_id FK + 双表双 FK + 5 索引 | ✅ 最强 |
| claim → source | `supporting/contradicting_source_ids` 两列 **TEXT JSON**, 非 FK、无 join 表 | ❌ 最弱 |
| claim ↔ report | **无任何结构关联**；仅 title 前缀 `[RESEARCH REPORT]` 约定（TD-R1-001） | ❌ 无 |
| report → claim/source | 正文不嵌入 id, `design_document` 无贡献列 | ❌ 不可追溯 |

**实测断言（均已人工复核）：**
1. `task→run→research` 所有权主轴健全（最强部分）。
2. **claim→source 双格式并存**：`ExtractClaimsTool` 写 `["source:1]`（非法），`CompareSourcesTool` 写 `[1]`（合法）。`AbstractDesignerResearchTool.parseSourceIds`（[:155-165](backend/src/main/java/com/aigamelab/tool/AbstractDesignerResearchTool.java#L155-L165)）用正则 `\d+` 同时容忍两种格式——**掩盖了缺陷**。
3. report 身份是字符串前缀约定（`GenerateReportTool.java:31,81` / `GetResearchContextTool.java:94` / `ResearchController.java:108-110`）。
4. 无反向追溯：由 source 找 claims 需扫全表 JSON；由 report 找 claims 需全文搜索 content_md。
5. 无完整性约束：claim 可引用不存在或其他 run/task 的 source id。

---

## 5. Evidence Chain 缺口

**判定：物理完整性中断（有残留轨迹）。**

| # | 缺口 | 严重度 | 证据 |
|---|---|---|---|
| **GAP-1** | **引文畸形 JSON（CRITICAL）**：extract 写入 `["source:1]`，除非被 compare_sources 改写为 `[1]`，前端 `countJsonArray()`（[ResearchPage.tsx:386-393](frontend/src/pages/ResearchPage.tsx#L386-L393)）对未比对 claim 返回 **0** | **CRITICAL** | `ExtractClaimsTool.java:87`（已复核）vs `CompareSourcesTool.java:138-146` |
| GAP-2 | 无 claim→source 解析端点：`GET /api/research/claims/{id}` 返回裸 TEXT 源 ID, 客户端须手工 parse + 逐个 GET | HIGH | `ResearchController.java` |
| GAP-3 | report 不含 claim 引用：只渲染文字+置信度, 无 claim id | HIGH | `GenerateReportTool.java:131-134`（已复核） |
| GAP-4 | 无 report→claim 关联：无 junction 表、无 JSON 列 | MEDIUM | — |
| GAP-5 | 无状态转移审计：compare 原地覆盖 status, 无 previous_status/history | MEDIUM | `CompareSourcesTool.java:89-97` |
| GAP-6 | 「交叉验证」实为 LLM 输入即信任：无工具级内容比对, 非确定性 | MEDIUM | `CompareSourcesTool.java:18-27` |
| GAP-7 | 置信度与 source.reliability 无交互：0.2 的 speculation 源与 0.95 的官方源同等加成 | MEDIUM | `SaveSourceTool` + `CompareSourcesTool` |
| GAP-8 | 无证据链聚合端点（claim+source 一体返回） | MEDIUM | `ResearchController.java` |
| GAP-9 | JSON blob 类型不稳定 + DB 无约束 | MEDIUM | `ResearchClaim.java:15-16` |
| GAP-10 | DRAFT 永不自愈：extract 后流程中止则 claim 永远 0.3/DRAFT + 畸形引文 | MEDIUM | `ExtractClaimsTool.java:84-88` |

**最小修复路径（本期可实施，非 schema 变更）：** FIX-1 修 `ExtractClaimsTool.java:87` 为合法 `[<id>]` + 存量行一次性迁移 + 回归断言；FIX-2 新增 claim→sources 解析端点；FIX-3 report 每断言 bullet 前缀 `[claim#N]`；FIX-4/5/7 中期；FIX-6 文档化 LLM 委托语义。

---

## 6. Research Quality 缺口

| # | 缺口 | 严重度 | 证据 |
|---|---|---|---|
| Q1 | 抽取无去重：同源重跑/跨源近似句产生重复 claims, 污染计数与置信度算术 | HIGH | `ExtractClaimsTool.java:75-95` |
| Q2 | 无源 claims 不可审查：compare_sources 整体覆盖静默丢失 extract 来源溯源 | HIGH | `ExtractClaimsTool.java:84-88` + `CompareSourcesTool.java:83-85` |
| Q3 | 矛盾检测非算法化：由 LLM 断言, 工具无内容比对；claim 间冲突发现不存在 | MEDIUM | §5 GAP-6 |
| Q4 | 置信度非证据驱动：reliability/来源类型/来源数不入公式, 仅为计数加权 | MEDIUM | §5 GAP-7 |
| Q5 | 「≥2 独立源」是 prompt-软约束, 工具无机制性兜底 | LOW-MED | `research_p001.md` 铁律 1 |
| Q6 | web_search stub 使「基于真实工具数据」被占位数据满足 | LOW(已知) | `WebSearchTool.java:43-57` |

---

## 7. Report Quality 缺口

| # | 缺口 | 严重度 | 证据 |
|---|---|---|---|
| R1 | 证据 grounded 不足：`generate_report` 仅 question+summary 入参,「结论」段为 LLM 自由文本, 不与 claims/sources/置信度交叉校验 | MEDIUM | `GenerateReportTool.java:25-43` |
| R2 | **Fact/Inference/Uncertain 三分缺席**：prompt 仅二元「已证实/推测」, claim 无 epistemic class 字段 | MEDIUM | `research_p001.md` 铁律 5 |
| R3 | **prompt↔tool 契约漂移（HIGH）**：prompt（[research_p001.md](backend/src/main/resources/prompts/research_p001.md)）承诺 7 段, 工具只渲染 4 段+统计（缺「最合理模型/假设」「不确定性说明」「后续研究建议」） | HIGH | prompt 输出格式 1-7 vs `GenerateReportTool.java:106-182` |
| R4 | claim→source 引用不渲染：claims 与 sources 是两块互不相连列表 | MEDIUM | `GenerateReportTool.java:106-182` |
| R5 | 引用不可追溯：正文无 claim id/source id；无 markdown 转义；DRAFT 与 VALIDATED 混排 | MEDIUM | `GenerateReportTool.java:124-160` |

---

## 8. 可复用性缺口

**当前可复用且健康（运行时链路已由 E2E 证明）：**
- Designer 消费链：`get_research_context/get_research_claims` 从 `task.constraintsJson.researchSourceTaskId` 解析并返回可追溯 id。
- 所有权作用域：`/api/research/*` 按 runId/taskId 过滤可用。
- 模式可复制：只读消费工具 + Task 显式引用 + 可追踪 claim id 的模板已确立（QA 未来照抄）。

**QA 级复用不可行（缺口）：**

| # | 缺口 | 影响 |
|---|---|---|
| U1 | 无 SQL 可 join 的 provenance（claim→source / report→claims 全为 JSON 文本/前缀/prose） | QA 无法跑 join/histogram |
| U2 | 数据卫生差：`["source:N]` 与 `[N]` 并存 → 未来 json_each 查询崩坏 | GAP-1 下游 |
| U3 | report 身份仅为标题前缀（TD-R1-001） | 无法支撑 report 生命周期 |
| U4 | 缺 QA 所需字段（claim 无 origin source 列、source 无 published_at 等） | QA 引用检索需爬 TEXT |
| U5 | decision→claim 仅 prose（`change_proposal.reason` 无 FK） | QA 「哪些决策依赖 claim X」不可查 |
| U6 | task→task research 链接是 constraintsJson JSON 字符串, 无 DB 关系、无 agent_type=RESEARCH 校验 | SQL 层不可见 |
| U7 | QA 无消费工具（`AbstractDesignerResearchTool` 命名暗示 Designer-only 但机制通用） | QA 需加平行工具 |

**强调：不提前实现 QA Multi-Agent；QA Team 属未来阶段。**

---

## 9. 建议的 R2 Task Tree

```
P0: TA 修复 ExtractClaimsTool 畸形 JSON（无依赖, 阻塞一切下游证据链可信度）
        │
P1: TB CompareSourcesTool 硬化（依赖 TA）
        ├── TC SaveSourceTool 枚举/范围/URL 去重（无依赖, 并行）
        ├── TD 证据链解析端点 + 前端 drill-down（依赖 TA）
        ├── TE ResearchPage 实时性/健壮性（无依赖, 并行）
        ├── TF report↔prompt 契约对齐 + report 内嵌 claim id（依赖 TA）
        └── TG extract/report 去重（依赖 TA; 或与 TA 合并执行）
P2:    TH WebSearchTool 真实搜索接入（产品授权 + 密钥）
        ├── TI TaskController researchSourceTaskId agent_type==RESEARCH 校验（无依赖）
        └── TJ designer_p002 激活（产品授权, 单独 Task）
P2+:   TK provenance 硬化（report_type 列 / claim_source join 表 / CHECK 约束, 需 schema 授权, 本期不做）
```

依赖原则：**TA 是一切证据链 UI/API 的前置**；TB/TD/TF 读 TA 修复后的干净数据；TH/TJ 需产品决策；TK 明确不属本期（schema 冻结）。

---

## 10. 每个 Task 的验收标准

| Task | 验收标准 |
|---|---|
| **TA（CRITICAL）** | ① extract 写入的 `supporting_source_ids` 恒为合法 JSON `[<id>]`；② 存量畸形行经幂等迁移全部 `[<id>]`；③ 新测试断言存储值匹配 `^\d+([,]\d+)*$` 形式；④ `countJsonArray` 对 extract-only claim 返回正确计数；⑤ 全库 443+ 测试全绿 |
| TB | ① 拒绝不存在的 source id；② 拒绝不属于同一 run/task 的 source id；③ 同一 id 不得同时进 supporting+contradicting（互斥校验）；④ 3 UPDATE 失败语义文档化或合并原子语句；⑤ 测试覆盖 DISPUTED 门、0.0/1.0 夹取、恰 2 支持的 +0.1 |
| TC | ① sourceType 非 5 枚举值 → ToolResult.failure；② reliability 夹取 [0,1]；③ 同 URL 同 run 重复保存返回既有 sourceId；④ 单测覆盖 3 分支 |
| TD | ① 新增 `GET /api/research/claims/{id}/sources` 返回 `{claim, supportingSources[], contradictingSources[]}`；② ClaimsTable 源计数可点击 drill-down；③ SourceDetail 增「被引用断言」区块；④ 新增 ResearchPage 前端测试；⑤ 无 schema 变更 |
| TE | ① ResearchPage 改用降级轮询；② 「刷新」同时 invalidate `["runs"]`；③ 订阅 RUN_* 事件；④ 默认 latest-run 显式 max-id；⑤ 4 查询暴露 error 态, 空/错/载三态可区分 |
| TF | ① prompt 7 段与渲染对齐（补 3 缺段, 可选 args 传入）；② report 每 claim bullet 带 `[claim#N]`；③ DRAFT 与 VALIDATED 分区块；④ 无 markdown 转义风险；⑤ prompt↔tool 契约测试 |
| TG | ① 同 run 同源二次 extract 不产生重复 claims；② report 对近似重复 claims 折叠；③ 测试：双跑 extract 行数不翻倍 |
| TH | ① 真实搜索 API（SerpAPI/Bing）stub 移除；② 无密钥时明确报错而非伪造；③ `totalResults` 真实计数；④ 密钥环境变量化 |
| TI | ① `createTask` 校验 researchSourceTaskId 指向 task 的 `agent_type=="RESEARCH"`, 否则 40001；② 测试：指向 DESIGNER/TESTER 被拒；③ 不破坏既有正向流 |
| TJ | ① designer_p002 激活且提示词含 research 指导；② 仅经 prompt-version 选择生效（冻结行 `DefaultAgentTypeResolver.java:40` 一字不改）；③ 产品白纸黑字授权 |
| TK（未来） | schema 变更授权书 + 追加式 DDL + 迁移；本期明确**不做** |

---

## 11. 文件修改计划

| Task | 新增文件 | 修改文件 |
|---|---|---|
| TA | 幂等迁移脚本（非 schema.sql） | `ExtractClaimsTool.java:87`；测试扩展 |
| TB | （无） | `CompareSourcesTool.java`；测试 |
| TC | （无） | `SaveSourceTool.java`；测试 |
| TD | `frontend/src/pages/ResearchPage.test.tsx` | `ResearchController.java`；`api.ts`/`types.ts`/`ResearchPage.tsx` |
| TE | （无） | `ResearchPage.tsx`；`ResearchPage.test.tsx` 扩展 |
| TF | （无） | `GenerateReportTool.java`；`research_p001.md`（精简对齐）；测试 |
| TG | （无） | `ExtractClaimsTool.java`/`GenerateReportTool.java`；测试 |
| TH | （无） | `WebSearchTool.java`；`config/`（密钥 bean） |
| TI | （无） | `api/TaskController.java`；`TaskControllerTest` 扩展 |
| TJ | （无） | `designer_p002.md` 精修；激活路径（严禁改冻结行） |
| TK | （未来） | schema.sql（需解除冻结授权） |

**明确不改**：`AgentLoop`、`RunStateMachine`、`BudgetGuard`、`ToolExecutor`、`ToolResult`、`ErrorCodes`、`designer_p001`、`DESIGNER_SYSTEM_PROMPT`、tester whitelist+p001、T4/T5 目录。

---

## 12. DB 是否需要变化

**结论：本期无需 schema DDL 变更（schema.sql 自 R0 后冻结，继续冻结）。**

| 项 | 判定 | 说明 |
|---|---|---|
| schema.sql DDL | **不改** | 零新表/新列/新索引 |
| 数据迁移 | **需一次性 UPDATE**（非 DDL） | 存量 `supporting_source_ids` 中 `["source:N]` → `[N]`（幂等 SQL），作为 TA 的一部分 |
| 索引 | 不改 | 现有 5 索引够 MVP |
| 供未来（追加式） | STATUS CHECK、reliability CHECK、`report_type` 列、`claim_source` join 表 | 全部归 TK, 附 schema 变更授权书 |

---

## 13. Prompt 是否需要变化

| 项 | 判定 | 说明 |
|---|---|---|
| `research_p001.md` | **精简对齐**（TF） | 7 段报告结构保留；3 缺段改可选 args 传入；如需 Fact/Inference/Uncertain 则加一句三分类指令（纯 prompt 层） |
| `designer_p002.md` | **激活 = 独立 Task（TJ），本期不动** | 当前 Designer 生产 LLM 看不到 research 工具指导（`DESIGNER_SYSTEM_PROMPT` 冻结行不含 research）；E2E 是经 FakeLlmGateway 脚本而非 LLM 推理通过 |
| `designer_p001.md` | **不改** | 冻结 |

---

## 14. Tool 是否需要变化

| Tool | 判定 | 变更内容 |
|---|---|---|
| `ExtractClaimsTool` | **改（必须）** | TA 修 `:87` 合法 JSON；TG 同 run 同源去重 |
| `CompareSourcesTool` | **改（建议）** | TB 源存在性+归属校验、支持/反驳互斥；GAP-6 语义文档化 |
| `SaveSourceTool` | **改（建议）** | TC sourceType 枚举、reliability 夹取、同 URL 去重 |
| `GenerateReportTool` | **改（建议）** | TF 3 缺段可选 args + `[claim#N]` 引用 + 分块 + 转义 |
| `WebSearchTool` | **改（P2 授权）** | TH 真实 API；其余不改 |
| `FetchPageTool`/`WebFetchService` | **不改本期** | SSRF 守卫、截断标志列后续安全硬化 Task |
| 新建工具 | 本期无需新 GameTool | 解析端点是 Controller 逻辑，非 LLM 工具 |

---

## 15. API / Frontend 是否需要变化

| 层 | 变更 | 归属 |
|---|---|---|
| API | `GET /api/research/claims/{id}/sources` 新增（claim + 解析后 sources 一体返回） | TD |
| API | 既有端点语义（invalid status、runId+taskId 同传）**不改本期**，记测试补覆盖 | 可选 |
| API | 无 POST/PUT/DELETE 保持（写入走 Agent 工具） | — |
| Frontend | ClaimsTable 源计数 → drill-down；SourceDetail 增「被引用断言」 | TD |
| Frontend | ResearchPage 降级轮询；刷新 invalidate `["runs"]`；RUN_* 订阅；max-id 选最新；error 三态 | TE |
| Frontend | `WorkflowPage.tsx:218` 回链补 `researchSourceTaskId`（不补则落到最新 run, 与引用 run 可能不符） | TE(小) |

---

## 16. 测试计划

| Task | 新测试覆盖 |
|---|---|
| TA | `supporting_source_ids` 值形式断言；迁移脚本幂等重跑；extract-only claim 前端计数 |
| TB | DISPUTED 门、0.0/1.0 夹取、恰 2 支持 +0.1、不存在/异 run source id 拒绝、同 id 双列表互斥 |
| TC | sourceType 非枚举失败、reliability 夹取、同 URL 去重 |
| TD | `ResearchPage.test.tsx`（照 ConfigDiffPage.test.tsx：MockEventSource+act+waitFor）；Controller 解析端点正/反例 |
| TE | degraded→polling 切换；刷新 invalidate `["runs"]`；max-id 选择；查询失败 error 态 |
| TF | report 结构断言（3 缺段、`[claim#N]` 前缀、DRAFT 分块）——prompt↔tool 契约锁 |
| TG | 双跑 extract 行数不翻倍；report 重复 claim 折叠 |
| 隔离补强 | 跨 run/task 排除断言；by-id 越权无作用域测试 |
| 回归边界 | 全库 443+ tests 全绿；`AgentTypeResolverTest` 20、`ToolRegistryIntegrationTest`=24、T4/T5 frozen 快照不变 |

---

## 17. Frozen Boundary 检查

**结论：R1/R2 期间 0 违规；本期建议的 10 个 Task 亦不触碰冻结面，除 TK（需授权）外。**

| 冻结契约 | 本期触碰? | 备注 |
|---|---|---|
| `AgentLoop` / `RunStateMachine` / `BudgetGuard` / `ToolExecutor` / `ToolResult` | **不入** | 完全冻结 |
| `schema.sql` | **不入**（TK 需解除冻结） | TA 只做数据迁移 UPDATE, 非 DDL |
| `ErrorCodes` | **不入** | 40001/40401/… 全保留 |
| `designer_p001` / `DESIGNER_SYSTEM_PROMPT` | **不入**（TJ 走 prompt-version 选择, 一行不碰） | 仅加法白名单已合规 |
| tester_p001 + 8 Tester tools + T4/T5 | **不入** | 已 FROZEN/ACCEPTED |

唯一允许触碰且建议触碰：4 个 Research Tool、`ResearchController`、`TaskController`（非冻结列表成员, 但变更须保持加法语义）、`research_p001.md`、前端 ResearchPage/api.ts/types.ts。

---

## 18. 风险

### 技术风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| TA 数据迁移误伤合法行（`[1,2]` 与 `["source:N]` 混布） | HIGH | 先 `WHERE supporting_source_ids LIKE '["source:%'` 精确匹配畸形前缀；迁移幂等 |
| compare_sources 非原子 3-UPDATE 中途失败 → 状态不一致 | MEDIUM | 文档化失败语义；或合并为单 UPDATE |
| research_p001↔tool 对齐方向选错 → 再次契约漂移 | MEDIUM | 以「工具补齐缺段为可选 args」为主, prompt 仅加三分类一句；契约测试锁结构 |
| 前端实时性改动破坏既有 SSE 白名单双处 fork-lift | LOW | 改动范围限定页面层；全局 hook 不动 |

### 范围风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| TJ（designer_p002 激活）未经授权被捆绑 | MEDIUM | 独立 Task + 产品白纸黑字；本期标记 BLOCKED-ON-PRODUCT |
| TH（真实搜索 API）引入外部依赖与密钥 | MEDIUM | 独立 Task + 密钥环境变量 + 降级策略；不授权则 stub 维持 |
| TK（provenance 硬化）被告解除 schema 冻结 | MEDIUM | 追加式/append-only；需单独授权书；不并入本期 |
| TD 新增端点超越「最小」 | LOW | 端点仅做解析聚合, 不加写操作 |

### 回归风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 现有 443 tests 中「兼容读取畸形格式」的接受型断言与 TA 冲突 | MEDIUM | TA 先审计既有断言, 逐条迁移为新合法格式期望；拒绝「兼容写入」妥协 |
| Global Regression=DIRTY（T4/T5） | 已知 | 每 Task 合并前跑全库 surefire + 冻结防线 |
| status 语义调整影响既有消费者 | LOW | 本期不改语义, 仅文档化 |

### 不改的清单（防过度工程）
- 不改 `AgentLoop/RunStateMachine/BudgetGuard/ToolExecutor/ToolResult/schema.sql/ErrorCodes/designer_p001/DESIGNER_SYSTEM_PROMPT/tester 全线`
- 本期不建 `claim_source` join 表、不加 `report_type`、不加 `origin_source` 列（全部 TK）
- 不改 web_search stub（除非 TH 授权）
- 不对 `ResearchController` 现有端点做破坏性语义变更
- 不把 SSE 全局 replay（无 Last-Event-ID）列入本期

---

**R2 AUDIT COMPLETE — WAITING FOR HUMAN ACCEPTANCE**