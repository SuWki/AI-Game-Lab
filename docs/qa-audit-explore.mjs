export const meta = {
  name: 'agl-qa-audit-explore',
  description: '全面并行探索 AGL 代码库与文档，为 QA 架构提案收集事实证据',
  phases: [
    { title: '代码层审计', detail: 'runtime/event/api/config/tester/research/repository/frontend' },
    { title: '文档层审计', detail: 'architecture/projects/reports/prompts' },
  ],
}

// ===== Phase 1: 代码层 =====
phase('代码层审计')

// 1. Runtime 剩余组件
const codeRuntime = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 Java 文件并返回详细研究备忘录：

文件列表（backend/src/main/java/com/aigamelab/）：
- runtime/BudgetGuard.java
- runtime/LoopDetector.java
- runtime/RunRecoveryService.java
- runtime/AgentTypeResolver.java（接口）
- domain/RunStatus.java
- domain/Step.java
- domain/ToolCall.java
- domain/AgentEvent.java

返回必须包含：
1. BudgetGuard.check() 完整判定逻辑（维度：steps/tokens/time，目标状态）。
2. LoopDetector.detect() 算法（窗口大小、哈希依据）。
3. RunRecoveryService.recoverOnStartup() 恢复逻辑。
4. AgentTypeResolver 接口方法签名。
5. RunStatus 全部枚举值。
6. Step/ToolCall/AgentEvent record 字段列表。
7. 与 QA 多角色编排相关的缺口（单 Agent per Run 限制、无 orchestration）。
8. 每项的「可复用 / 需扩展 / 缺失」结论。
引用具体文件路径与方法签名。`, {label: 'code-runtime', phase: '代码层审计'})

// 2. Event + SSE + API
const codeEventApi = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 Java 文件并返回详细研究备忘录：

文件列表（backend/src/main/java/com/aigamelab/）：
- event/PersistingAgentEventPublisher.java
- event/SseEmitterRegistry.java
- event/SsePayload.java
- event/DomainEventEnvelope.java
- event/AgentEventListener.java
- api/SseController.java
- api/DevEventController.java
- api/TaskController.java
- api/RunController.java
- api/ConfigController.java
- api/DashboardController.java
- api/GlobalExceptionHandler.java
- api/ExperimentController.java
- api/TesterController.java

返回必须包含：
1. 每个 Controller 的端点列表（HTTP方法 + 路径 + 参数 + 返回类型），分段列出。
2. SSE 全局事件流架构：SseEmitterRegistry 如何管理 SSE 连接，SsePayload 结构。
3. PersistingAgentEventPublisher 如何写入 agent_event + 推送 SSE。
4. AgentEventListener 如何消费事件（具体 listener 实现）。
5. DomainEventEnvelope 结构。
6. GlobalExceptionHandler 的错误映射（每个异常→HTTP状态码）。
7. 与 QA 相关的结论：QA Lead 是否需要新增端点？SSE 是否足够支持 QA 多 Agent 事件？
8. 明确的「可复用 / 需扩展 / 缺失」三段结论。
引用具体文件路径与方法签名。`, {label: 'code-event-api', phase: '代码层审计'})

// 3. Config + Experiment + Sim
const codeConfigExpSim = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 Java 文件并返回详细研究备忘录：

文件列表（backend/src/main/java/com/aigamelab/）：
- config/ConfigVersionService.java
- config/ConfigDiffer.java
- config/MergePatchApplier.java
- config/ApprovalService.java
- config/ResearchToolConfig.java
- experiment/ExperimentService.java
- experiment/EvaluationService.java
- experiment/AsyncSimulationRunner.java
- sim/GameSimulator.java（如果存在）
- sim/BatchSimulator.java（如果存在）
- sim/Aggregator.java（如果存在）
- sim/GameOutcome.java
- sim/BuildState.java（如果存在）
- sim/model/SimParams.java
- sim/model/Targets.java

返回必须包含：
1. ConfigVersionService：核心方法列表（getCurrent/findLatest/findByVersion/findAll/createVersion），v001 种子导入逻辑。
2. ConfigDiffer：diff 算法核心，输出格式（unified diff / JSON）。
3. ApprovalService：approve/reject 全链路，如何创建新版本 + 回填 ChangeProposal + 唤醒 Run。
4. ExperimentService：create() 签名、幂等逻辑、同步/异步模式。
5. EvaluationService：evaluate() 签名、target 校验逻辑（targetWinRate/maxDominantBuildShare/acceptableBuildDpsVariance）、返回的 EvaluationResult 结构。
6. Sim 模块：GameSimulator/BatchSimulator/Aggregator 的核心方法、指标维度。
7. QA Numerical Analyst 可复用的：哪些现有 Experiment/Simulation/Evaluation 能力可直接用于 QA 数值分析。
8. 与 QA 实验驱动测试相关的「可复用 / 需扩展 / 缺失」结论。
引用具体文件路径与方法签名。`, {label: 'code-config-exp-sim', phase: '代码层审计'})

// 4. Tester 全部组件
const codeTester = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 Java 文件并返回详细研究备忘录：

文件列表（backend/src/main/java/com/aigamelab/tester/）：
- AbstractTesterTool.java
- GameStartTool.java
- GameObserveTool.java
- GameActTool.java
- GameCollectEventsTool.java
- GameCollectMetricsTool.java
- FindingReportTool.java
- TestReportCreateTool.java
- TestPlanGetTool.java
- GameSessionService.java
- InvariantEngine.java
- FindingFingerprintService.java
- FindingRegressionService.java
- TesterEnvConfig.java

返回必须包含：
1. 8个 Tester Tool 的 name/schema/requiresApproval/核心逻辑，逐个列表。
2. AbstractTesterTool 的公共基类逻辑：参数提取、schema 组装、异常翻译。
3. GameSessionService：sessionId↔runId 登记逻辑、start/observe/act/drainEvents/metrics 委托。
4. InvariantEngine：谓词白名单列表、enrich() 逻辑、语法限定。
5. FindingFingerprintService：fingerprint() 方法、FindingIdentity 三元组结构。
6. FindingRegressionService：diff() 算法摘要（NEW/FIXED/PERSISTED/CHANGED）。
7. TestReportCreateTool：baselineVersion 可选参数、回归摘要。
8. TesterEnvConfig：Spring Bean 配置方式。
9. QA 可复用的 Tester 能力清单：哪些工具/服务可直接被 QA 角色调用。
10. 「可复用 / 需扩展 / 缺失」三段结论。
引用具体文件路径与方法签名。`, {label: 'code-tester', phase: '代码层审计'})

// 5. Research 全部
const codeResearch = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 Java 文件并返回详细研究备忘录：

文件列表（backend/src/main/java/com/aigamelab/）：
- research/AbstractResearchTool.java
- research/WebSearchTool.java
- research/FetchPageTool.java
- research/SaveSourceTool.java
- research/ExtractClaimsTool.java
- research/CompareSourcesTool.java
- research/GenerateReportTool.java
- research/WebFetchService.java
- domain/ResearchSource.java
- domain/ResearchClaim.java
- repository/ResearchSourceRepository.java
- repository/ResearchClaimRepository.java（接口）

以及 prompts：
- backend/src/main/resources/prompts/research_p001.md

返回必须包含：
1. 6个 Research Tool 的 name/schema/核心逻辑，逐个列表。
2. AbstractResearchTool 基类设计。
3. WebSearchTool 的当前实现（Stub? 真实 API?）。
4. WebFetchService：fetchText/fetchHtml 的行为（超时、解析方式）。
5. ResearchSource/ResearchClaim domain record 字段列表。
6. ResearchClaim.status 枚举：DRAFT/VALIDATED/DISPUTED。
7. research_p001.md 的完整 system prompt 内容（或关键摘要）。
8. QA 与 Research 的协作边界：Research 产出可被 QA 消费的哪些数据（Sources/Claims/Reports）。
9. 「可复用 / 需扩展 / 缺失」三段结论。
引用具体文件路径与方法签名。`, {label: 'code-research', phase: '代码层审计'})

// 6. GameEnv（FakeGameEnvironment + GameAdapter）
const codeGameEnv = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 Java 文件并返回详细研究备忘录：

文件列表（backend/src/main/java/com/aigamelab/gameenv/）：
- GameAdapter.java
- FakeGameEnvironment.java
- GameSessionHandle.java
- GameObservation.java
- GameAction.java
- GameEvent.java
- GameMetrics.java
- GamePhase.java
- UnknownSessionException.java

返回必须包含：
1. GameAdapter 接口方法签名（start/observe/act/drainEvents/metrics）。
2. FakeGameEnvironment 核心逻辑：确定性 tick 状态机、seed 注入、故障注入机制。
3. GameObservation record 字段（含 invariantViolations）。
4. GameAction record 字段（actionType 开放字符串）。
5. GameEvent record 字段。
6. GameMetrics record 字段（ticksElapsed/damage/kills/deaths/actions/uptime）。
7. GamePhase 枚举值。
8. QA Player Agent 可利用的能力：FakeGameEnvironment 支持哪些 playerProfile（SCRIPTED/RANDOM）、overrides 机制。
9. 与 QA Player Agent / Exploit Hunter 的「可复用 / 需扩展 / 缺失」结论。
引用具体文件路径与方法签名。`, {label: 'code-gameenv', phase: '代码层审计'})

// 7. Repository + LLM 层
const codeRepoLlm = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 Java 文件并返回详细研究备忘录：

文件列表（backend/src/main/java/com/aigamelab/）：
- repository/RunRepository.java（接口）
- repository/TaskRepository.java（接口）
- repository/StepRepository.java（接口）
- repository/ToolCallRepository.java（接口）
- repository/ConfigVersionRepository.java（接口）
- repository/ExperimentRepository.java（接口）
- repository/ChangeProposalRepository.java（接口）
- repository/PromptVersionRepository.java（接口）
- repository/DesignDocumentRepository.java（接口）
- llm/LlmGateway.java
- llm/DeepSeekClient.java
- llm/GatewayRetry.java
- llm/PromptService.java
- llm/LlmException.java
- tool/GameTool.java
- tool/ToolResult.java
- tool/ToolContext.java
- tool/ToolException.java

返回必须包含：
1. 每个 Repository 接口的方法签名（方法名+参数+返回类型），一句话概括用途。
2. LlmGateway 接口方法签名。
3. DeepSeekClient 实现细节（constructor、Complete 实现、ConditionalOn）。
4. GatewayRetry 策略（500/429/解析失败 的重试次数/退避策略）。
5. PromptService 的 importSeedIfEmpty / importTesterSeedIfAbsent / importResearchSeedIfAbsent / getByVersion 方法。
6. LlmException 的 error code 体系。
7. GameTool 接口定义（name/jsonSchema/requiresApproval/execute）。
8. ToolResult 记录结构（status/summaryText/payloadJson）。
9. ToolContext 的使用方式。
10. 「可复用 / 需扩展 / 缺失」三段结论（针对 QA 增加新 AgentType 的需求）。
引用具体文件路径与方法签名。`, {label: 'code-repo-llm', phase: '代码层审计'})

// 8. Frontend
const codeFrontend = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 TypeScript/React 文件并返回详细研究备忘录：

文件列表（frontend/src/）：
- App.tsx
- main.tsx
- api.ts
- types.ts
- useGlobalSse.ts
- pages/FindingsPage.tsx
- pages/TestPlanPage.tsx
- pages/ConfigRegressionPage.tsx
- pages/DashboardPage.tsx
- pages/RunTracePage.tsx
- pages/ConfigDiffPage.tsx
- pages/ExperimentCenterPage.tsx
- components/Badge.tsx
- components/Drawer.tsx
- components/DefinitionList.tsx

返回必须包含：
1. App.tsx 的路由配置（Route→Page 映射）。
2. api.ts 的全部函数列表（函数名+端点+用途）。
3. types.ts 的全部接口/类型定义（Finding/TestPlan/Scenario/Evidence/Regression 相关）。
4. useGlobalSse.ts 的 SSE 订阅机制。
5. 每个页面的核心功能和数据展示（1-2 行概括）。
6. 前端基础设施：是否已支持按 AgentType 分组展示？是否有 QA 相关的页面占位？
7. QA 前端需求的「可复用 / 需扩展 / 缺失」结论（QA Run 页面、角色 Agent 结果展示、Release Gate UI）。
引用具体文件路径与函数签名。`, {label: 'code-frontend', phase: '代码层审计'})

// ===== Phase 2: 文档层 =====
phase('文档层审计')

// 9. 架构文档
const docArchitecture = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 Markdown 文档并返回研究备忘录：

文件列表：
- docs/architecture/02_共享基础设施规范.md
- docs/architecture/05_部署与运行架构.md
- docs/architecture/06_文档与代码归属规范.md
- docs/architecture/ARCHITECTURE_RULES.md
- docs/architecture/DOCUMENTATION_RULES.md
- docs/index.md
- docs/state/工作流.md（前100行已读，读取完整版）

返回必须包含：
1. 02 共享基础设施规范：核心规则摘要（哪些是共享的、哪些不允许各自实现）。
2. 05 部署架构：当前部署模式、未来扩展路径。
3. 06 文档归属：文件命名规则、文档组织方式。
4. ARCHITECTURE_RULES：关键规则列表。
5. DOCUMENTATION_RULES：文档管理规则。
6. 工作流.md：Sprint/Checkpoint/Auto-Accept 核心机制。
7. 与 QA 新增 AgentType 相关的架构约束（什么可以做、什么被禁止）。
8. 任何新增 Agent 的标准要求（01 文档 §11 已定义 10 项，这里是否补充？）。
引用具体文件名和章节号。`, {label: 'doc-architecture', phase: '文档层审计'})

// 10. Designer + Tester 项目文档（frozen contracts）
const docProjects = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 Markdown 文档并返回研究备忘录：

文件列表：
- docs/projects/01_AI_Game_Designer/产品定义.md
- docs/projects/01_AI_Game_Designer/技术设计.md
- docs/projects/01_AI_Game_Designer/开发任务树.md
- docs/projects/02_AI_Game_Tester/产品定义.md
- docs/projects/02_AI_Game_Tester/技术设计.md
- docs/projects/02_AI_Game_Tester/架构设计.md
- docs/projects/02_AI_Game_Tester/开发任务树.md
- docs/projects/02_AI_Game_Tester/T-DB1_DDL_Review.md
- docs/projects/03_AI_Research_Agent/产品定义.md
- docs/projects/03_AI_Research_Agent/开发任务树.md
- docs/projects/PROJECT_TEMPLATE/README.md

返回必须包含：
1. Designer 产品定义：核心能力概要。
2. Tester 产品定义：核心能力概要（功能边界、不在 Tester 范围的内容）。
3. Tester 技术设计：核心架构（§编号+摘要），特别是：§6 数据模型、§8 Prompt、§12 挑战矩阵、§14 二期能力（R1-R5）、§53 事件定义。
4. Tester 架构设计：关键设计决策。
5. Research 产品定义：核心能力。
6. Research 开发任务树：已完成的 Sprint 列表。
7. T-DB1 DDL Review：最终批准的两表 DDL 概要（列数/索引/外键）。
8. 项目模板 PROJECT_TEMPLATE：标准文档结构。
9. QA 可以继承的 Tester 冻结契约清单（哪些表/工具/服务/API/事件是 QA 可直接消费的）。
10. 从这些文档中识别出 QA 设计的约束条件（不得修改/不得覆盖的内容）。
引用具体文档名和章节号。`, {label: 'doc-projects', phase: '文档层审计'})

// 11. Reports + Tech Debt
const docReports = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下 Markdown 文档并返回研究备忘录：

文件列表：
- docs/reports/FINAL_RELEASE_CANDIDATE_REPORT.md
- docs/reports/R0_Completion_Report.md（已部分读取，读完整版）
- docs/reports/P3_incremental_report.md
- docs/reports/P5_experiment_service_report.md
- docs/reports/P3-4_experiment_document_tools_report.md
- docs/reports/P8_Dependency_Scope_Audit.md
- docs/reports/Sprint_P6-4_Checkpoint_Report.md
- docs/reports/Sprint_P7_Checkpoint_Report.md
- docs/reports/Sprint_P8-1_Checkpoint_Report.md
- docs/reports/Sprint_P8-2_Checkpoint_Report.md
- docs/reports/Sprint_T7_Checkpoint_Report.md
- docs/state/tech-debt.md（已完整读取，复核）

返回必须包含：
1. FINAL_RELEASE_CANDIDATE_REPORT：最终发布候选状态、所有 Agent 的完成度、发布决策。
2. 所有 Sprint 报告的最终回归数字（tests / pass / fail），作为 QA 实施的测试基线。
3. tech-debt.md 当前所有未清偿技术债列表（TD 编号 + 问题 + 是否阻塞 QA）。
4. R0 完成后的设计决策记录（与 QA 相关的决策）。
5. QA 是否能基于当前 FROZEN 基线直接开始实施（有无 blocker）。
引用具体文件名和章节号。`, {label: 'doc-reports', phase: '文档层审计'})

// 12. Prompts
const docPrompts = await agent(`
你正在为 AGL QA 架构提案收集证据。请完整阅读以下文件并返回研究备忘录：

文件列表：
- backend/src/main/resources/prompts/designer_p001.md
- backend/src/main/resources/prompts/tester_p001.md
- backend/src/main/resources/prompts/research_p001.md
- backend/src/main/resources/docs/game_design.md

返回必须包含：
1. designer_p001.md 的完整 System Prompt 内容（逐段摘要）。
2. tester_p001.md 的完整 System Prompt 内容（逐段摘要）。
3. research_p001.md 的完整 System Prompt 内容（逐段摘要）。
4. game_design.md 的内容概要。
5. 三个 Agent Prompt 的共同结构模式（身份、工作流程、铁律、输出格式）。
6. QA Lead Prompt 设计的建议：基于现有模式，QA Lead 的 System Prompt 应包含哪些段落。
7. 每个 Prompt 中工具调用的约定（工具名→描述的映射风格）。
引用文件名和关键段落。`, {label: 'doc-prompts', phase: '文档层审计'})

// ===== 综合输出 =====
log('代码层 + 文档层审计完成。开始综合...')

return {
  codeRuntime: codeRuntime,
  codeEventApi: codeEventApi,
  codeConfigExpSim: codeConfigExpSim,
  codeTester: codeTester,
  codeResearch: codeResearch,
  codeGameEnv: codeGameEnv,
  codeRepoLlm: codeRepoLlm,
  codeFrontend: codeFrontend,
  docArchitecture: docArchitecture,
  docProjects: docProjects,
  docReports: docReports,
  docPrompts: docPrompts,
}
