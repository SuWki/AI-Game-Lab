# AGL 技术债登记

> 依据《AI 协作开发工作流》§5.3：任何为完成 Task 而采取的临时方案必须在此登记。
> 每条包含：问题 / 原因 / 当前解决方式 / 后续建议。解决后请将条目移至文末"已清偿"并注明 Task。

## 登记中的技术债

### TD-VALIDATION-20261112 · 验收程序独立性与源码构建关联（OPEN，P1）

- 问题：LocalChangeService 允许修改声明源码范围内的测试程序，回归仅要求原检查 ID 存在；相同 ID 可配上变弱的断言。LocalEvidenceService 保存源码/产物哈希，但未证明运行产物由本次源码构建。自动修改的“已修复”结论因此仍需人工复核。
- 证据口径：本轮源码审查确认约束缺失，未执行攻击复现；现有独立样例通过不代表这两个缺口已经解决。
- 当前处理：保留现有 VALIDATED 的注册检查含义，不将其等同于目标改进达标；补 [可信验证设计](../platform/IMPROVEMENT_VALIDATION_DESIGN.md)。人工审阅尤其关注验收脚本、基准数据和实际执行入口。
- 清偿标准：VAL-01 的冻结独立验收与恒真/同 ID 改断言反例通过；ADAPT-01 在干净工作区构建并绑定本次产物，旧构建不能冒充；实际证据文件可追溯，旧结果证据等级不被重写。


### TD-LOCAL-20261112 · 本地测试的版本绑定与平台证据接线（PARTIAL）

- 已交付：通用注册命令执行、SQLite 记录、中台、任务 runId 与证据绑定、取消/超时/有界队列。见 [平台协议](../platform/LOCAL_GAME_TEST_EXECUTION.md)。
- 本轮已实现：声明源码及产物 SHA256、固定本地版本、结构化检查导入既有 TestPlan/Finding、独立 QA 判定、5 个受限 Agent 工具、人工审批和隔离修改回归。Runtime 授权与协议回放已修正，见 [交付报告](../reports/THREE_BATCH_DELIVERY_2026-11-12.md)。
- 剩余：第二个真实游戏可移植接入；工具链/依赖闭包指纹；按真实指标设计与旧模拟 ReleaseGate 的业务映射。目前本地 QA 不伪造旧模拟指标。
- 运行边界：单实例本机可信目录；非执行沙箱，无法保证清理脱离进程树的后台进程。重启只标记 INTERRUPTED，不重放或重新附着。
- 清偿标准：第二个独立游戏目标复用版本清单与证据协议；旧实验引用固定版本；结构化 Finding 可从中台追溯到具体执行和证据。不能因样例测试通过就关闭。

### TD-REV-20261111 · 可靠运行与业务闭环专项（OPEN）

- **登记时间**：2026-11-11，用户要求全局评估与后续开发规划。
- **证据来源**：[当次项目评估](../reports/PROJECT_REVIEW_2026-11-11.md) REV-01..14；其中竞态为源码推导，待专项测试复现。
- **原因**：早期固定 Fake 剧本和阶段冻结保留了执行骨架缺口，业务扩展未同步完善协议、持久化、状态与证据消费。
- **当前解决方式**：A 已实现；B 的共享 Runtime 部分已实现（协议批次、观察恢复、条件终态、实际 retry、HTTP 超时、配置快照）。D 的 Run 线程/队列与 Run 控制 UI 已收口；并非所有实验/QA 调度均已替换。真实供应商验收仍缺，不将该专项整体关闭。C、Research 网络边界、QA 内存 deadline、远程/多项目授权仍待专项。

| 子项 | 问题 | 后续任务 |
|---|---|---|
| A（P0，代码已修复） | 函数 envelope、完整回合回放、冻结白名单；有生产 Resolver 与请求驱动测试 | RT-01 / RT-02 / RT-05；真实模型待验收 |
| B（P1，Runtime 已修复） | 整批持久化、审批观察、终态 CAS、实际 retry、超时与配置快照 | RT-03..08 实现；RT-09 真实供应商 / EX-04 继续验收 |
| C（P1） | 指标口径混用、显式版本回退、异步失败缺追踪、Gate 证据未固定（REV-09..10）；DPS 代理沿用 TD-006 登记 | EX-01..06 |
| D（P1） | 抓取网络/大小边界、内存 deadline、无限线程/队列、关键 UI 缺口（REV-11..13） | RT-08 / SAFE-01 / QA-01 / UX-01..02 |
| E（条件项） | 本地监听边界、远程身份授权、多项目隔离（REV-14） | OPS-01；部署/多项目需求触发专项 |

- **清偿标准**：按 [开发路线](../design/DEVELOPMENT_ROADMAP.md) 对应验收记录逐项关闭；不能仅因全量回归通过而整体清偿。此处是状态登记，评估报告保存证据，路线保存执行步骤。

### TD-001 · JDK 21 借用 VS Code 插件内置 JRE

- **登记时间**：2026-08-26（P0-1 收尾修正时）
- **问题**：本机系统默认 Java 为 JDK 8，无法编译 Spring Boot 3.3（需要 JDK 21）。
- **原因**：机器上早期未安装独立的 JDK；曾用 VS Code Java 插件内置 Temurin 21.0.11 临时借用（2026-08-26 起）。**后续（2026-09-17，R8 核对）实际构建 JDK 已迁移至 `C:\Users\Administrator\.jdks\openjdk-24.0.1`（JDK 24.0.1，`--release 21` 构建）。**
- **风险**：系统级 `JAVA_HOME` 仍指向 JDK 8（`C:\Program Files\Java\jdk-1.8`），不修正将导致新终端 `mvn clean test` 直接编译失败；其他工具（CI、IDE 配置）无法复用此路径约定。
- **当前解决方式**：R8-A7 已建立 `docs/BUILD_ENVIRONMENT.md`（Required JDK 21+、JAVA_HOME 修正指引）+ `scripts/check-env.sh/.bat`（一键自检）。构建时需 `JAVA_HOME` 指向 `C:\Users\Administrator\.jdks\openjdk-24.0.1`。
- **后续建议**：安装独立 JDK 21（如 Temurin/Zulu），设为系统 `JAVA_HOME`，删除本条。

### TD-002 · Maven 本地仓库位于非默认路径

- **登记时间**：2026-08-26（P0-1 收尾修正时）
- **问题**：Maven 本地仓库配置在 `D:\apache-maven-3.9.10\repo`（非用户目录默认 `.m2/repository`）。
- **原因**：疑似全局 settings.xml 曾被自定义以节省 C 盘空间。
- **风险**：换机或重装 Maven 时依赖缓存与 `settings.xml` 约定丢失，构建环境不可复现。
- **当前解决方式**：无特殊处理，当前可用。
- **后续建议**：确认 `%USERPROFILE%\.m2\settings.xml` 中的 `<localRepository>` 配置已被文档化；或迁移回默认路径。

### TD-003 · ToolRegistry 生产/测试上下文共存，工具注册无清理机制

- **登记时间**：2026-08-26（Sprint P1 收尾）
- **问题**：`ToolRegistry` 是 Spring 单例，测试通过 `registry.register(new StubTool(...))` 注入替身工具；同 JVM 多个 `@SpringBootTest` 类共享容器时注册的工具跨用例残留（各测试类以 `contains()` 守卫规避重复注册）。生产环境无此问题（真实工具在启动期注册），但测试隔离依赖约定而非机制。
- **原因**：P1 阶段无工具注销/unregister API，也未按 profile 隔离测试工具。
- **风险**：未来测试若依赖"某工具未注册"的初始状态会互相污染；误注册同名工具会被静默拒绝（duplicate-name 抛 IllegalStateException 仅在同容器内触发）。
- **当前解决方式**：测试均用 `if (!registry.contains(name))` 守卫 + 唯一命名（如 ev_x/x2）。
- **后续建议**：为 ToolRegistry 增加 `unregister(name)` 或测试专用 reset 钩子（可随 P3 Tool 层落地一并处理）。

### TD-004 · DeepSeekClient Bean 条件依赖 api-key 非空判定

- **登记时间**：2026-08-26（Sprint P2 收尾）
- **问题**：`@ConditionalOnProperty(name="api-key")` 对空串占位（`${DEEPSEEK_API_KEY:}` 默认值）仍判成立，导致测试上下文同时出现 deepSeekClient+fakeLlmGateway 两个 LlmGateway Bean（NoUniqueBeanDefinitionException）。已补自定义 `Condition`（ApiKeyPresent：非空白串才注册）修复。
- **原因**：ConditionalOnProperty 无空串语义，yml 占位默认值与"未配置"无法区分。
- **风险**：低——已修复；但未来新增类似可选 Bean 时易重蹈覆辙。
- **当前解决方式**：DeepSeekClient.ApiKeyPresent 自定义 Condition。
- **后续建议**：若 P8 前引入第二个 provider，考虑统一 LlmGateway 的多 Bean 选择策略（@Primary + 配置路由）。

### TD-005 · 02 技术设计 vs 03 任务树：P6-4 ConfigController 文档不一致

- **登记时间**：2026-08-27（Sprint P6 收尾）
- **问题**：`docs/projects/01_AI_Game_Designer/技术设计.md` 中额外列出了 P6-4 `ConfigController`（`current` / `versions/{version}/diff` 等 REST Endpoint），但 `docs/projects/01_AI_Game_Designer/开发任务树.md` 仅定义 P6-1/P6-2/P6-3，且无任何 REST/P6-4 任务。两文档对 P6 范围存在冲突。
- **原因**：02 设计文档在 P6 任务树冻结前已写入更宽范围的 ConfigController 设想，03 任务树后续收敛为"仅服务层逻辑、不暴露 REST"的 P6-1~P6-3。
- **影响**：若未来照 02 实现 P6-4，会与本 Sprint 冻结的 scope（禁止任何 REST Endpoint、禁止 P6-4）冲突。本 Sprint 严格按 03 执行，未实现 P6-4 / ConfigController。
- **当前解决方式**：P6-4 视为文档不一致项，**本 Sprint 不实现**，按用户指令记入技术债；ConfigVersion 语义按冻结定义（append-only 全量快照，current = findLatest()，无 active/activate/rollback）。
- **后续建议**：由责任方（设计/架构）裁决 02 与 03 的口径，统一后在 02 或 03 二选一删除/保留 P6-4；若确需 P6-4，应作为独立 Sprint（P6 扩展或并入 P7/P8 前端）重新评估范围与验收，不应在本 Sprint 内补做。注意 P6-4 若落地须配套 Controller 事务/鉴权/事件，与 02 设想的纯 diff 接口不同。

### TD-006 · P5-2 acceptableBuildDpsVariance 用胜率方差代理（02 §13 与 SimulationResult 字段不一致）

- **登记时间**：2026-08-27（Sprint P5 收尾）
- **问题**：02 技术设计 §13 列出平衡目标 `acceptableBuildDpsVariance`（字面："各 build DPS 可接受方差"），但 `SimulationResult.buildDistribution`（`sim/SimulationResult.java`）仅暴露 per-build `winRate` / `ciLow` / `ciHigh`，**无 per-build avgDps**；P5-2 无法直接计算"各 build DPS 方差"。
- **原因**：02 §13 的 buildDistribution 字段规格与"各 build DPS 方差"目标不匹配；P4 聚合器未输出 per-build DPS，仅输出 per-build 胜率与全局 avgDps。
- **影响**：P5-2 若强行实现字面 DPS 方差须修改 P4（Aggregator 增 per-build DPS 累加），违反 P5 红线"不修改 P4 / 不顺手重构"。故采用**跨 build 胜率方差**作为平衡度代理——胜率越分散代表 build 越失衡，语义对齐"平衡度"目标，且完全基于现有字段、可立即验证。
- **当前解决方式**：`EvaluationService.buildWinRateVariance()` 计算总体方差（÷N），与 `maxDominantBuildShare` 同属"平衡度"维度校验；代理解释已写进 `docs/reports/P5_experiment_service_report.md` §3 设计说明。
- **后续建议**：若后续需要严格 DPS 方差，应在 P4 Aggregator 增加 per-build DPS 累加（属 P4 增强，非 P5 范围），再于 P5-2 替换为 `per-build avgDps` 方差；届时 TD-006 可清偿。当前代理不阻塞任何功能。

### TD-QA-R-001 · RepositoryTest.eventReplayAfterIdAndRecent 依赖绝对自增 id（随机顺序偶发失败）

- **登记时间**：2026-09-04（PF-004 基线验证期间定位）
- **问题**：`RepositoryTest.eventReplayAfterIdAndRecent` 硬编码假设 `agent_event.id` 从 1 开始（`findByRunIdAfter(1, 2, 1000)` 期望返回 3 条，即 evt-3/4/5）。`AbstractRepositoryTest` 用共享 `static final Path DB`（7 个子类共用同一临时 DB），`DELETE` 不重置 `sqlite_sequence`；工具/集成类经 `AgentEventListener` 写 `agent_event` 把 id 推进到 15+。随机类顺序下该用例在它们之后执行时，新插 5 条拿到 id 16-20，全部 `> afterId=2` → 返回 5 条而非 3 条（Expected size 3 but was 5）。
- **原因**：共享 DB + AUTOINCREMENT 顺序漂移 + 用例隐含"绝对 id"假设，属既有测试基建设计（TD-T7-001 同一根因族）。
- **风险**：仅在特定随机组合下偶发；顺序 full-suite 与两种随机 ordering 复跑（JUnit 方法级 seed=7 / surefire 类级）均全绿，未复现。不阻塞 QA-S1 准入门。
- **当前解决方式**：PF-004 归因并豁免（见 `docs/reports/QA_PREFLIGHT_BASELINE.md` §4.1），无代码改动。
- **后续建议**：QA-S1（或独立测试基建 Task）改为不依赖绝对 id（如按 `runId` 隔离断言、先读当前 max id），或每类独立临时 DB（对齐 PF-002 选型 A）。

### TD-QA-PROP-001 · QA 提案对既有技术债的影响分析（独立 Task 前置）

> 📌 **背景**：Phase 2 QA 系统架构提案（`docs/QA_SYSTEM_ARCHITECTURE_PROPOSAL.md` §18）审查了既有技术债对 QA Team 实施的影响。本节仅为**影响分析登记**，不修复任何技术债；按 AGL 规则（工作流 §40）任何清偿须作为独立 Task 由人工授权后进行。

- **登记时间**：2026-09-01（QA 架构提案审查时）
- **影响结论**：

| 既有技术债 | QA 影响 | 建议处理时机 |
|------------|---------|-------------|
| TD-003 ToolRegistry 测试隔离无清理机制 | 中等：QA 新增 4 个编排 Tool 后测试注册隔离更复杂 | QA-S1 实施时评估，不作为前置 blocker |
| TD-T6-001 FindingService 未强制校验 configVersion 真实存在 | 中等：QA Finding 可能指向不存在的 configVersion | ~~建议在 QA-S1 前清偿~~ **已清偿（PF-003，2026-09-04）** |
| TD-T7-001 Full-suite Spring Context/DB 状态污染 | 高：QA 复用 TestPlanService，可能继承同一 fixture 问题 | ~~建议在 QA-S1 前排查~~ **已由 AbstractRepositoryTest FK-safe 清理 + ensureSeedV001 修复；PF-002-A 另供 QA 独立 DB fixture（2026-09-04 基线 GREEN）** |
| TD-P8-1-001 Tester v001 fixture 未初始化 | 高：QA Player Agent 复用同一 TestPlanService/GameSessionService 链路，可能继承同一 fixture 问题 | 建议在 QA-S1 前作为独立 Task 排查 |

- **当前解决方式**：TD-T6-001 / TD-T7-001 由 2026-09-04 QA Preflight（PF-003 / PF-002）清偿；本表其余影响分析仍有效。
- **后续建议**：QA-S1 Sprint 启动前，由人工批准一个「QA 前置技术债清偿」独立 Task，按上表优先级处理剩余项（TD-P8-1-001 / TD-003）；已清偿项按 `docs/reports/QA_PREFLIGHT_BASELINE.md` 基线纳入准入门自检。

---

## 已清偿

### TD-T6-001 · Tester Evidence 未强制引用真实 ConfigVersion（T6-2 集成发现）

- **登记时间**：2026-08-28（Sprint T6 收尾，T6-2 集成验证期间）
- **问题**：`FindingService.report()`（冻结的 T3-1/T4 契约）仅校验 `configVersion` 非空 + 白名单，未校验该 `configVersion` 是否真实存在于 `game_config_version` 表，`finding_report` 可凭任意非空串（如 `"v999"`）落库 Finding。
- **清偿 Task**：**PF-003**（QA Preflight，2026-09-04）——`FindingService` 注入 `ConfigVersionService`，`validate()` 在 non-blank 校验后追加存在性校验：`configService.findByVersion(...).orElseThrow(() -> BusinessException.notFound(...))`（40401）。`AbstractTesterTool` 翻译为非 critical `ToolException` → `tool_call=FAILED`、Run 继续。T6-2 §2 断言同步更新为"拒绝 v999 落库"。
- **验证**：`TesterServicesTest` 新增 `report_unknownConfigVersion_rejected` / `report_validConfigVersion_stillPasses`；`ConfigVersionTestPlanEvidenceIntegrationTest` §2 全链验证；full-suite 443/0/0 全绿。
- **后续建议**：已清偿，无需额外操作。

### TD-P8-2-001 · advanceStepIfRunning 必须在 ToolExecutor 之前调用

- **登记时间**：2026-08-31（Sprint P8-2 执行中发现）
- **问题**：AgentLoop.tool_calls 分支原来在 toolExecutor.execute() 之后才调 advanceStepIfRunning，但 ToolExecutor 会将 Run 状态从 RUNNING 改为 WAITING_APPROVAL，导致 advanceStepIfRunning 的 `WHERE status='RUNNING'` 匹配失败、currentStep 未推进；resume 时 Step INSERT 与已有 seq 冲突（UNIQUE constraint on run_id+seq）。
- **原因**：ToolExecutor.execute() 在 suspend 场景下会调用 stateMachine.transition(RUNNING→WAITING_APPROVAL)，发生在 advanceStepIfRunning 之前。
- **当前解决方式**：将 advanceStepIfRunning 移到 toolExecutor.execute() 之前（此时 status 仍为 RUNNING），确保 currentStep 在任何工具执行前已推进。P8-2 E2E 验证通过。
- **后续建议**：已修复清偿，无需额外操作。
