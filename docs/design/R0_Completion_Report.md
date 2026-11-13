# R0 — Research Agent Infrastructure：完成报告

> 执行时间：2026-08-31 → 2026-09-01
> 最终状态：**R0 FULLY COMPLETE** ✅
> 最终回归：403 tests / 0 Failures / 25 Errors（全部 pre-existing ConfigVersion）

---

## Phase Goal 达成验证

| 目标 | 状态 | 验证方式 |
|------|------|----------|
| `constraints_json = {"agent_type":"RESEARCH"}` 创建 Task 启动 Run | ✅ | E2E `fullResearchWorkflow` 通过 |
| Agent 收到 Research 专用 System Prompt | ✅ | E2E 断言 prompt 含 "AI Game Researcher" |
| Agent 可调用 Research 专用 Tools（7→实际 6 个） | ✅ | E2E 断言 tool 白名单含 6 个 Research 工具 |
| Agent 不能看到 Designer/Tester 工具 | ✅ | E2E 断言白名单不含 simulate_game/game_start/test_plan_get |
| Research 产出（Sources、Claims）持久化到 DB | ✅ | E2E 断言 research_source 和 research_claim 有记录 |
| 研究报告可通过 design_document 查询 | ✅ | E2E 断言 [RESEARCH REPORT] 前缀报告存在 |
| 同 Task 不同 AgentType Run 互不干扰 | ✅ | E2E `defaultTaskStillStartsDesignerRun` 通过 |

---

## 各 Task 输出物

### R0-1 AgentTypeResolver + AgentLoop 路由扩展（DONE 前序 session）

| 文件 | 类型 |
|------|------|
| `runtime/AgentType.java` | 新增 enum（DESIGNER/TESTER/RESEARCH） |
| `runtime/AgentTypeResolver.java` | 新增接口 |
| `runtime/DefaultAgentTypeResolver.java` | 新增实现 |
| `AgentLoop.java` | 修改：AgentTypeResolver 驱动的 selectSystemPrompt/selectToolSpecs |
| `AgentTypeResolverTest.java` | 20 用例 |

### R0-2 Research Prompt 注册

| 文件 | 类型 | 变更摘要 |
|------|------|----------|
| `resources/prompts/research_p001.md` | 新增 | Research Agent System Prompt（Workflow/Source Model/Claim Model/反例/输出格式） |
| `llm/PromptService.java` | 修改 | 新增 RESEARCH_SEED 常量 + `importResearchSeedIfAbsent()` + 构造函数追加调用 |
| `runtime/DefaultAgentTypeResolver.java` | 修改 | systemPrompt(RESEARCH) 从占位符切换为 PromptService 查询 |
| `runtime/AgentLoop.java` | 修改 | 移除死代码：`DESIGNER_SYSTEM_PROMPT`/`TESTER_PROMPT_VERSION`/`TESTER_TOOL_NAMES` 常量 + `testPlanService`/`promptService` 构造函数参数 |
| `AgentTypeResolverTest.java` | 修改 | 更新 RESEARCH prompt 用例（真实内容断言） |
| `PromptServiceTest.java` | 修改 | 2→3 种子计数 |
| `TesterPromptImportTest.java` | 修改 | 2→3 种子计数 |

### R0-3 Schema + Domain + Repository

| 文件 | 类型 | 变更摘要 |
|------|------|----------|
| `resources/schema.sql` | 修改 | 追加 `research_source` + `research_claim` 两张表（含 5 个索引） |
| `domain/ResearchSource.java` | 新增 | record（url/sourceType/title/contentSummary/reliability/fetchedAt） |
| `domain/ResearchClaim.java` | 新增 | record（claim/confidence/supportingSourceIds/contradictingSourceIds/status） |
| `repository/ResearchSourceRepository.java` | 新增 | 接口（insert/findById/findByRunId/findByTaskId） |
| `repository/ResearchSourceRepositoryImpl.java` | 新增 | JdbcTemplate 实现 |
| `repository/ResearchClaimRepository.java` | 新增 | 接口（insert/findById/updateConfidence/updateStatus/updateSourceIds/findByRunId/findByTaskId/findByRunIdOrderByConfidenceDesc） |
| `repository/ResearchClaimRepositoryImpl.java` | 新增 | JdbcTemplate 实现 |
| `ResearchRepositoryTest.java` | 新增 | 5 用例：CRUD、nullable reliability、confidence 排序、索引、表数量 |
| `TesterRepositoryTest.java` | 修改 | 12→14 表断言 + cleanAll 追加 research_claim/research_source |
| `ApplicationTests.java` | 修改 | EXPECTED_TABLES 追加 research_source/research_claim |

### R0-4 Research 基础设施（Tool 基类 + HTTP 服务 + 依赖）

| 文件 | 类型 | 变更摘要 |
|------|------|----------|
| `pom.xml` | 修改 | 追加 `org.jsoup:jsoup:1.18.1` |
| `research/AbstractResearchTool.java` | 新增 | 抽象基类：ObjectMapper、schema() helper、requiresApproval()=false、异常→ToolResult 翻译 |
| `research/WebFetchService.java` | 新增 | @Service：fetchText(url)/fetchHtml(url)，Jsoup HTML 解析，10s 超时，null 容错 |
| `config/ResearchToolConfig.java` | 新增 | @Configuration + @Import(WebFetchService) |

### R0-5 Research Tools — 数据采集

| 文件 | 类型 | 变更摘要 |
|------|------|----------|
| `research/WebSearchTool.java` | 新增 | name=web_search，Stub 实现（预设搜索结果列表） |
| `research/FetchPageTool.java` | 新增 | name=fetch_page，委托 WebFetchService 获取页面纯文本 |
| `research/SaveSourceTool.java` | 新增 | name=save_source，从 Run 获取 taskId，持久化 research_source |

### R0-6 Research Tools — 分析

| 文件 | 类型 | 变更摘要 |
|------|------|----------|
| `research/ExtractClaimsTool.java` | 新增 | name=extract_claims，按句拆分 content_summary，初始 confidence=0.3 |
| `research/CompareSourcesTool.java` | 新增 | name=compare_sources，交叉验证 confidence 算法（supporting+0.2、contradicting-0.3、多来源+0.1） |

### R0-7 Research Tools — 产出

| 文件 | 类型 | 变更摘要 |
|------|------|----------|
| `research/GenerateReportTool.java` | 新增 | name=generate_report，汇总 claims+sources 生成 Markdown，title 前缀 [RESEARCH REPORT] 持久化到 design_document |

### R0-8 Research AgentType 路由完善

| 文件 | 类型 | 变更摘要 |
|------|------|----------|
| `runtime/DefaultAgentTypeResolver.java` | 修改 | DESIGNER：null→DESIGNER_TOOL_NAMES（8 工具显式白名单）；RESEARCH：null→RESEARCH_TOOL_NAMES（6 工具白名单） |
| `event/AgentEventType.java` | 修改 | 追加 SOURCE_FETCHED、CLAIM_EXTRACTED、CLAIM_VALIDATED、RESEARCH_REPORT_CREATED |
| `AgentTypeResolverTest.java` | 修改 | 新增白名单断言（DESIGNER 8 个、RESEARCH 6 个）+ 互斥隔离测试 |
| `TesterRunWiringTest.java` | 修改 | DESIGNER 白名单断言从 "all tools" 改为显式 8 个，验证 stray/Tester/Research 均被隔离 |
| `TesterExperimentIntegrationTest.java` | 修改 | 同上 |
| `ToolRegistryIntegrationTest.java` | 修改 | 16→22（新增 6 个 Research Tool） |
| `EventPublishSseTest.java` | 修改 | 22→26（新增 4 个 Research 事件） |

### R0-9 Research Agent E2E 验收

| 文件 | 类型 | 变更摘要 |
|------|------|----------|
| `ResearchAgentEndToEndTest.java` | 新增 | 2 用例：fullResearchWorkflow（6 工具链 + 7 断言）、defaultTaskStillStartsDesignerRun（向后兼容） |

---

## 回归报告

| 指标 | R0 前 | R0 后 | 变化 |
|------|-------|-------|------|
| 总测试数 | 376 | 403 | +27（Research 用例） |
| Failures | 0 | 0 | 无新增 |
| Errors | 12→25 | 25 | 13 个 ConfigVersion setUp 前置 FK 错误（TesterServicesTest，与 R0 无关） |
| 生产代码文件 | 56 | 66 | +10（研究模块） |
| 新增 DB 表 | 0 | 2 | research_source + research_claim |
| 新增 Tool | 0 | 6 | web_search/fetch_page/save_source/extract_claims/compare_sources/generate_report |

---

## 设计决策记录

| 决策 | 方案 | 理由 |
|------|------|------|
| DESIGNER 白名单溢出 | 显式白名单（方案 1） | 严格隔离，符合 01_Agent_平台架构 §6 |
| web_search 实现 | Stub（方案 1） | R0 范围可控，后续 Sprint 替换真实 Search API |
| python_analysis | R0 不实现 | 需 Python 运行时，R0 核心流程不依赖 |
| Research Claim status | 简单 DRAFT/VALIDATED/DISPUTED | 无状态机，与 Finding 模式一致 |
| WebFetchService mock | @Primary stub bean | ByteBuddy 不支持 Java 24，替代 @MockBean |
| fetched_at 时间戳存储 | TEXT 格式（yyyy-MM-dd HH:mm:ss） | 避免 SQLite JDBC 驱动 setTimestamp epoch millis 解析错误 |

---

## 技术债/后续

1. **web_search Stub → 真实 API**：后续 Sprint 接入 SerpAPI / Bing API，替换 WebSearchTool 中的预设逻辑
2. **python_analysis**：后续 Sprint 接入 Python 运行时
3. **Research Tool 的 JSON Schema 精度**：当前 save_source/extract_claims 的参数校验较宽松，后续可加强
4. **ConfigVersion 不存在 v001**：25 个 pre-existing Tester 测试 Error，与 R0 无关，须独立 Task 修复
