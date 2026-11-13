# AGL QA 架构人工评审 — 决策锁定（17 节 · 零代码修改轮）

> 2026-11-12 范围与状态说明：本文件保留对应阶段的设计/决策与准入条件，不能以其中历史表数、无编排、无回放或预算成功描述判断当前实现。当前模块入口见 [QA 设计](projects/04_AI_Game_Agent_Team/README.md)，共享 Runtime 的已授权变更见 [ADR](decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)。新生产评估以模块演进设计为准，旧 Gate 指标不因此重解释。

> **角色**：AI-Game-Lab Principal Architect + QA Architecture Reviewer
> **性质**：接口审核 / 决策锁定。本轮**不修改任何代码、不修改 DB Schema、不创建 Sprint、不修改 task-state、不实现任何 QA 生产代码**。
> **证据来源**：`docs/QA_ARCHITECTURE_AUDIT_REPORT.md`（10 维度审计，A1-A10 风险登记表）+ `docs/QA_SYSTEM_ARCHITECTURE_PROPOSAL.md`（提案）+ 5 个 verify 子代理对代码的逐行事实核验（concurrency / lifecycle / mapping / finding-config / frontend-sse，2026-09-02 workflow `wf_a1e816b0`）+ 主线程对关键文件的复核。
> **优先级原则**：以实际代码为准、文档为辅。凡无法从材料确定的事项标记 `INSUFFICIENT EVIDENCE`，不假设。

---

## 一、审查范围与约束（指令 §一）

本轮唯一任务是：对审计报告第 13 块风险登记表 A1-A10 逐项给出裁决建议（APPROVE / REJECT / DEFER / NEED_MORE_EVIDENCE），并输出指令 §十七 要求的 8 个结果块。除此之外不采取任何实现动作。

**绝对禁止（与既有限制合并的完整清单）**：
1. 禁止修改代码（AgentLoop.java / DefaultAgentTypeResolver.java / ExperimentService.java / Repository / Service / Controller / Tool / SSE / Frontend / 任何 Java / TS 文件）。
2. 禁止修改数据库（schema.sql / migration / DDL）。
3. 禁止修改现有架构实现（不修 C4 / TD-T7-001 / SSE / Role Mapping — 只给建议）。
4. 禁止创建 Sprint（QA-S1 等执行文件不建）。
5. 禁止修改 task-state（不更新 `docs/state/task-state.md`）。
6. 禁止创建 QA 生产代码（QA Agent / Tool / Service / Controller / Repository / Frontend）。
7. 禁止实现 Builder（只做可行性 / 架构分析，结论 = DESIGN ONLY）。

---

## 二、当前项目基线（指令 §二；依据实际文档）

| 基线 | 状态 |
|------|------|
| Designer P0-P8 | **FROZEN** |
| Tester T1-T7 | **FROZEN** |
| Research R0 | **FROZEN BASELINE** |
| QA | **NOT STARTED**（提案处于 `DRAFT — WAITING FOR HUMAN REVIEW`） |
| Builder | **DESIGN ONLY**（Phase 3 独立方向，本阶段不实现） |

**冻结代码边界（本轮与 QA 实施均不得触碰）**：AgentLoop / run / step / tool_call / task / agent_event / experiment / change_proposal / config_version 数据模型；ErrorCodes（业务异常）；现有 28 个 API Endpoint（`api/` 目录 9 个 Controller）；现有 14 张表 schema DDL；AgentEventType 既有 26 个枚举值（追加不改旧值）；现有 3 个 Prompt 文件；现有 22 个 GameTool；SSE 协议（P7-2 红线，扩展不等同破坏）。

---

## 三、领域与架构解读（指令 §三；核心事实）

- **AgentLoop 委托已存在**：`AgentLoop.java:242-250` `selectSystemPrompt(task)`→`agentTypeResolver.resolve()`→`systemPrompt(agentType)`；`selectToolSpecs(task)`→`resolve()`→`toolWhitelist(agentType)`。**QA AgentType 接入无需修改 AgentLoop**，只需扩展 `DefaultAgentTypeResolver`。
- **DefaultAgentTypeResolver 是穷举 switch**：`systemPrompt()` 3 分支（L120-131）、`toolWhitelist()` 3 分支（L141-147），无 default 分支——切换会破坏编译，天然保证 QA 扩展不漏 tool set。当前白名单：DESIGNER=8、TESTER=8、RESEARCH=6（research tools）。
- **AgentType.java 当前 3 值**：DESIGNER / TESTER / RESEARCH（L14-18）。QA 需 +4 → 共 7。
- **Run 无父子关系**：Run record 无 `parent_run_id`，全库无 parent/subrun 引用。QA 子 Run 关系需靠后端新增的 `qa_role_assignment` 表 + `QaOrchestratorService` 建立（均未实现，仅提案）。
- **QA 提案双表设计**：`qa_role_assignment`（subtask/subrun/role/status 4 态）+ `qa_release_gate`（decision 3 态），均未实现。
- **QA 事件 3 个**：QA_SUBTASK_CREATED / QA_ROLE_COMPLETED / QA_RELEASE_GATE_CREATED — 前后端均不存在，仅提案（枚举 26→29）。

---

## 四、QA 架构提案关键决策点（指令 §四；决策项清单）

见 §六 A1-A10 逐项裁决。额外主动检查见 §七（第 7.1-7.13 项）。

---

## 五、方法与证据来源（指令 §五）

| 证据 | 来源 | 可靠性 |
|------|------|--------|
| AgentLoop 委托链 | `AgentLoop.java:242-250`（主线程读取） | 代码事实 |
| Resolver 穷举 switch | `DefaultAgentTypeResolver.java:119-147`（主线程读取） | 代码事实 |
| Run 无 parent_run_id | Grep 全库零命中（verify:lifecycle） | 代码事实 |
| 前端事件数 | `useGlobalSse.ts:73-98` = **26 个**（主线程重读） | 代码事实（修正审计 stale 快照） |
| AgentEventType 枚举数 | `AgentEventType.java:9-38` = 26 值（主线程重读） | 代码事实 |
| Finding/Config 溯源缺口 | `FindingService.java:82-85`、`ExperimentService.java:62-64,87-88`、`GameSessionService.java:70-73`（verify:finding-config） | 代码事实 |
| 生命周期/TIMEOUT | `BudgetGuard.java:35-47`、`AgentLoop.java:52,116-120`（verify:lifecycle） | 代码事实 |
| 并发模型 | `RunControlService.java:40-44,123-129`、`application.yml:9-13`（verify:concurrency） | 代码事实 |
| 角色/工具/Prompt 映射矛盾 | proposal §1.3/§8.1/§8.3/§14/§16/§20.1/§20.3（verify:mapping **FAIL**） | 文档内部矛盾 |

凡代码未实现的（qa_* 工具、qa_role_assignment / qa_release_gate 表、QaController、QA 前端页）均按提案推断，已在各处标注。

---

## 六、A1-A10 逐项裁决（指令 §六；核心交付）

---

### A1 · AgentLoop 零修改

- **当前问题**：QA 提案 §7 基础设施清单 I10 把「AgentLoop 修改」列为核心改造项（高风险），暗示 QA 接入必须触碰 AgentLoop。
- **已验证事实**（代码）：
  - `AgentLoop.java:242-250` 已完全经 `agentTypeResolver.resolve(task)` 委托——AgentLoop 对 AgentType 无任何硬编码分支。
  - `selectSystemPrompt(task)` → `resolver.systemPrompt(agentType)`；`selectToolSpecs(task)` → `resolver.toolWhitelist(agentType)`。
  - 接入新 AgentType 的**唯一必须改动**是 `DefaultAgentTypeResolver` + `AgentType` 枚举（文件本身已在 QA-S1 计划内，非冻结批）。
- **架构风险**：无残余风险。若按提案原样改 AgentLoop，反而触碰冻结 runtime、引入回归。
- **方案 A**：按提案 I10 修改 AgentLoop（token 预算注入、事件增强等）。
- **方案 B**（推荐）：**AgentLoop 零修改**；只扩展 `DefaultAgentTypeResolver.systemPrompt()/toolWhitelist()` 各 +4 case + AgentType.java +4 枚举值 + 4 个新白名单 Set + 校验错误信息「允许值」字符串更新。QA-S1-1 验收标准含「resolver 无回归（既有 3 AgentType 行为不变）」。
- **推荐理由**：委托已存在，AgentLoop 修改是伪需求；零修改保住冻结边界且工程量最小。
- **对冻结边界的影响**：AgentLoop 不触碰；`DefaultAgentTypeResolver` 属基础设施扩展（QA-S1 范围内新增，不违反冻结）。
- **QA-S1 前是否必须**：否——本裁决即消除风险项。
- **决议**：**APPROVE**（零修改成立；I10 的实质性工作并入 A2 降级为验证任务）。

---

### A2 · I10 降级

- **当前问题**：提案把 I10 描述为「高风险基础设施改造」，会造成 Sprint 范围膨胀、验收错位。
- **已验证事实**：I10 的真实内容是「QA AgentType 接入」；同 A1，委托链证明接入只涉及 resolver 扩展，与问题 A1 相同证据。
- **架构风险**：若保留「高风险修改」定位，QA-S1 会被要求做本不需要的核心改动，且可能越界触碰 AgentLoop。
- **方案 A**：保留 I10 为高风险修改任务。
- **方案 B**（推荐）：降级为 **「零修改验证」任务**——验证 AgentLoop 委托链对 QA AgentType 开箱即用、resolver 扩展满足 QA 权限（此为 QA-S1-1 的验收落点）。
- **推荐理由**：消除伪需求，把评审注意力引向真正的风险（A3/A4/A7/A8）。
- **对冻结边界的影响**：无。
- **QA-S1 前是否必须**：否。
- **决议**：**APPROVE**（I10 降级为验证任务）。

---

### A3 · ExperimentService C4 回退（QA 数值分析正确性）

- **当前问题**：`ExperimentService.create()` 对 configVersion 存在**双重静默回退**：L62-64 `null→configService.getCurrent()`；L87-88 `findByVersion(cfgVersion).orElseGet(getCurrent)`。QA Numerical Analyst 若传入不存在的 configVersion，**实验行标签为传入版本，实际仿真跑的是 latest**——结论标记某版本、行为来自另一版本，数据可信度污染。
- **已验证事实**：
  - `ExperimentService.java:62-64,87-88` 双重 fallback 确认存在（verify:finding-config）。
  - 对照先例：`GameSessionService.start()` L70-73 `findByVersion().orElseThrow(40401)` **不 fallback**——同一 `tester` 包内已确立正确范式。
  - C4 属既有技术债（TD-QA-006 登记中），非本轮修复范围。
- **架构风险**（高）：QA 数值/平衡分析结论与配置版本脱钩，Gate 判定依据可疑。QA 数值分析正确性是 QA-S1 blocker 级。
- **方案 A**：修改 `ExperimentService`：`null` 时仍 fallback（兼容既有调用），但**存在性校验不过时抛 40401（不再 orElseGet latest）**。正确但触碰冻结代码，需人工授权为独立 Task。
- **方案 B**（推荐，本期合规路径）：**QA 工具层防御**——QA 编排 / `analyze_experiment` 入口在调 `ExperimentService` 前自行 `ConfigVersionService.findByVersion` 校验，拒绝不存在的 configVersion（返回 ToolException 而非继续）；同时把 C4 登记为既有基础设施债（TD-QA-006 保持），修复作为**独立 Task（非 QA-S1-2）**由人工授权。
- **推荐理由**：方案 A 语义正确但对 QA-S1 而言触碰冻结 `ExperimentService` 代价高；方案 B 让 QA-S1 先行、修复单独立项，两条路径都保证「configVersion 不存在 = 显式失败」，不产生落库假象。
- **对冻结边界的影响**：`experiment` 表 / ExperimentService / ExperimentController 状态机均不动（方案 B）；方案 A 若被授权则属独立变更。
- **QA-S1 前是否必须**：是（若 MVP 含 Numerical / Combat Analyst，数值分析可信度是 Gate 的基础）。
- **决议**：**APPROVE 方案 B**（QA 工具层防御 + C4 修复派生为独立 Task）。派生裁决见 §XIV。

---

### A4 · TD-T7-001 修复（full-suite 测试上下文污染）

- **当前问题**：全量套件有 **13 个 FK 错误**（`TesterServicesTest.setUp:43` 未先删子表就 `DELETE FROM run`）。QA 测试若继承 `AbstractRepositoryTest` 会继承同一 fixture 问题；QA 电测一旦加入，full `mvn test` 上下文污染可能加剧。
- **已验证事实**：TD-T7-001 登记在 `docs/state/tech-debt.md:83`；TD-QA-PROP-001 影响分析「高，QA-S1 前排查测试上下文播种策略」。
- **架构风险**：QA 电气化测试（QA-S4 E2E、Service 单测）无法在污染上下文里可信落地。
- **方案 A**：QA-S1 内修（违反「不顺手修复既有债」红线，且侵吞 QA-S1 预算）。
- **方案 B**（推荐）：**QA-S1 前独立前置 Task** 修复（先删子表再 `DELETE FROM run`；QA 测试不继承 `AbstractRepositoryTest` 的污染 fixture 策略）。由人工批准，独立 Task 编号不入 QA-S1。
- **推荐理由**：修复属既有 Tester 测试基建债，与 QA 实施正交；前置才能保证 QA 电测可信。
- **对冻结边界的影响**：只改测试 fixture 代码，不触碰运行时代码 / schema / 契约。
- **QA-S1 前是否必须**：**是**。
- **决议**：**APPROVE**（独立前置 Task；不并入 QA-S1）。

---

### A5 · TD-T6-001 清偿（Finding configVersion 真实性）

- **当前问题**：`FindingService.validate()` L82-85 仅校验 configVersion **非空/非 blank**，无存在性校验。QA Player 经 `finding_report` 工具可传入任意字符串（如 `'v999'`）成功落库，破坏「被测对象必须是 ConfigVersion（契约 1）」。回归对比时可能出现指向不存在版本的 Finding。
- **已验证事实**：
  - `FindingService.java:82-85` 仅非空校验；`FindingReportTool.java:84` 原样转发 LLM 参数；整条链路无 `ConfigVersionService` 参与（verify:finding-config）。
  - 先例存在：`GameSessionService.start() L70-73` 已做存在性校验并抛 40401。
  - `FindingFingerprintService.fingerprint()` **不使用 configVersion**（仅 scenarioId+category+title），故加存在性校验**不影响**冻结的 fingerprint/回归对比契约。
- **架构风险**（中）：Finding→不存在 configVersion 破坏契约 1；回归 diff 可能静默给出虚假 NEW/FIXED。
- **方案 A**：QA-S1 内清偿。
- **方案 B**（推荐）：**独立前置 Task**，在 `FindingService.report()` 注入 `ConfigVersionService.findByVersion` 做存在性校验，违反时抛 `BusinessException(NOT_FOUND)` → 经 `AbstractTesterTool` 转非 critical `ToolException` → `tool_call=FAILED`、Run 继续（与 A4 同批独立立项）。
- **推荐理由**：改动是添加校验（非重构），但不属本轮冻结允许；独立立项、QA-S1 前完成即可解除该债务对 QA Finding 溯源的影响。
- **对冻结边界的影响**：`FindingService` 契约行为从「接受任意非空」变「拒绝不存在」，属行为收紧；需人工授权作为独立 Task（**不默认**）。
- **QA-S1 前是否必须**：建议（与 A3 工具层防御二选协同：A5 是根治、A3 是 QA 侧兜底）。
- **决议**：**APPROVE**（独立前置 Task 立项；若人工不批，QA-S1 由 A3 方案 B 工具层兜底）。

---

### A6 · qa_release_gate DDL 修正（PENDING / manual_override / config 索引）

- **当前问题**：提案原 gate DDL 是 3 态（PASS/FAIL/…）无审计留痕设计，缺 PENDING、缺 `manual_override`、缺 config 版本索引，无法支撑「人工可审阅 + override 留痕 + 按版本追溯」的 Release Gate 语义。
- **已验证事实**：审计报告 §6.2 已给出修正 DDL（`PENDING/PASS/CONDITIONAL_PASS/FAIL` + `manual_override/override_comment/override_by/overridden_at` + `config_version` 索引）。提案 §14.7 明确 Release Manager 功能并入 QA Lead，Gate 判定内嵌 QA Lead Prompt + server 侧 `QaReleaseGateService`（未实现）。
- **架构风险**：Gate 语义不锁定，QA-S2/S3 契约漂移；人工审阅 / override 审计无从落地。
- **方案 A**：按提案原 3 态 DDL 实施。
- **方案 B**（推荐）：**修正 DDL 并入 QA-S1-2**（PENDING 起始态 + manual_override 审计列 + config_version 索引 + decision 4 态）。与 A7 合并同一 DDL 变更。
- **推荐理由**：一次性修正代价最低；Release Gate 是 QA 闭环终点，语义必须先于实现锁定。
- **对冻结边界的影响**：新增 `qa_release_gate` 表（新表非既有表），不影响既有 schema；`agent_event` 事件预留 QA_RELEASE_GATE_CREATED（26→29）。
- **QA-S1 前是否必须**：建议（QA-S1-2 内完成即可）。
- **决议**：**APPROVE**（并入 QA-S1-2）。

---

### A7 · qa_role_assignment.status + TIMEOUT 态

- **当前问题**：提案的 `qa_role_assignment` 只有 **4 态**（PENDING/RUNNING/COMPLETED/FAILED），与 run 状态机 **7 态**（含 TIMEOUT/CANCELLED）脱节。子 Run 一旦超时或取消，assignment 会**永远停在 RUNNING**，Gate 聚合与 QA Dashboard 出现假挂起。且运行时 `AgentLoop.finalizeRun` 只写 run 行，**不回写 assignment**——两套存储无自动同步/触发器/事件桥接。
- **已验证事实**（verify:lifecycle）：
  - run 的 run 行 `PENDING→RUNNING` 由 `AgentLoop.runInner` 入口负责（L107-110）；assignment 状态推进由提案的 `QaOrchestratorService` 维护，源码 grep `qa_` 零命中——运行时与 assignment 完全无耦合。
  - `RunStatus.TIMEOUT` 唯一产生路径是 `BudgetGuard.check()` 时间预算分支，且当前 `AgentLoop.timeoutMs=-1` 硬编码禁用 → **生产上 TIMEOUT 实际不可达**（见 §七 7.2）。CANCELLED 仅由人工 HTTP `POST /api/runs/{id}/cancel` 触发。
  - 提案未给 QA Lead 设计取消子 Run 的工具——QA 无法通过工具触发 CANCELLED。
- **架构风险**：assignment 状态与 run 状态漂移 → Gate 判定基于陈旧 assignment；MVP 若用 sequential 编排可缓解（子 Run 阻塞等在 Lead 内），但**状态一致性仍是契约级缺陷**。
- **方案 A**：保持 4 态，靠 Lead 轮询 `qa_check_subrun_status` 校正。
- **方案 B**（推荐）：**6 态**（PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED）+ 定义同步语义——**Gate/聚合一律以 run 行的最新 status 为准，assignment 状态仅作编排视图**（避免假挂起）；DDL 修正并入 QA-S1-2（与 A6 同批）。MVP 采用 sequential 编排时，子 Run 终态在 Lead 下一次读取点即收敛。
- **推荐理由**：不做依赖，跑真实的状态兜底最少；明确「以 run 行为准」消除双写漂移。
- **对冻结边界的影响**：新表 `qa_role_assignment`，不影响既有表。
- **QA-S1 前是否必须**：建议（DDL 冻结前定案）。
- **决议**：**APPROVE**（6 态 + 以 run 行为准；并入 QA-S1-2）。

---

### A8 · Role ↔ AgentType 映射澄清（§20.1 vs §14 冲突）

- **当前问题**：**Exploit Hunter 角色被提案两处文档分配到不同 AgentType**：§8.1 注释与 §20.1 描述归 **QA_TESTER**（游戏操作/漏洞探测）；§14.5 与 §9.2 归 **QA_RESEARCHER**。且**两种归属都与工具白名单冲突**（见下），迫使裁决。
- **已验证事实**（verify:mapping，**FAIL verdict**）：
  - 单一映射（按 §14）：QA_LEAD→QA Lead（含并入的 Release Manager）；QA_TESTER→Player Agent（3 人格）；QA_ANALYST→Combat + Numerical；QA_RESEARCHER→Exploit Hunter + UX Reviewer。
  - **Exploit Hunter → QA_RESEARCHER 冲突**：需要 `game_start/game_observe/game_act/game_collect_events/game_collect_metrics` 5 个 game 工具，q§8.3 QA_RESEARCHER 白名单（6 research + finding_report）**全无** → 缺 5 工具。
  - **Exploit Hunter → QA_TESTER 冲突**：QA_TESTER 白名单（8 个 Tester 工具）**已有全部 5 个 game 工具**，但缺 `web_search/fetch_page` 2 个（Exploit 需 Web 侦察）。缺 2 工具。
  - **UX Reviewer → QA_RESEARCHER 冲突**：需 `game_observe/game_collect_events/analyze_experiment` 3 个，QA_RESEARCHER 白名单缺失。
  - Combat/Numerical → QA_ANALYST **一致**（所需工具是白名单 7 的子集）。
- **架构风险**：白名单与角色需求不匹配 → Agent 调用被拒、Run 反复失败或重试；且 QA_ANALYST/QA_RESEARCHER 各自承担两角色却共用单 prompt/白名单，需 §9.3 ROLE_FOCUS 机制区分（switch 扩展固有盲区）。
- **方案 A**（推荐）：**裁决 Exploit Hunter → QA_TESTER**（采纳 §8.1/§20.1 归属；语义上基坑探测属「游戏激进操作」族；改动最小——QA_TESTER 白名单 8→10，仅补 `web_search/fetch_page`）。**UX Reviewer → QA_RESEARCHER**（QA_RESEARCHER 白名单补 3：`game_observe/game_collect_events/analyze_experiment`→10）。**修订 §14.5/§9.2 与 §8.1/§20.1 一致**；QA_ANALYST 维持覆盖率不变。
- **方案 B**：Exploit Hunter → QA_RESEARCHER（采纳 §14.5/§9.2），QA_RESEARCHER 白名单需补 8 个（5 game + analyze_experiment 相关），白名单过宽、语义偏离 Research 定位。
- **推荐理由**：方案 A 让游戏探测工具留在 Tester 族（工具集最贴近）、web 侦察仅小幅补 2；白名单总增量 5（A：+2 test +3 research）vs 方案 B（+8+3 research）更小且语义更清晰。
- **对冻结边界的影响**：仅影响**新增**的 QA AgentType 白名单；既有 DESIGNER/TESTER/RESEARCH 白名单不动。文档修订（§9.2/§14.5/§20.1）属提案修正，非冻结资产。
- **QA-S1 前是否必须**：是（Prompt 与白名单冻结前定案，否则 QA-S2 工具层无法验收）。
- **决议**：**APPROVE 方案 A**（含文档修订）。补充裁决：Player 人格数量仍未对齐（§10.2 PlayerProfile 列 5 人格 vs §14.2 只定义 3 个），见 §七 7.6 追加裁决。

---

### A9 · SSE 事件订阅（补 3 个 QA 事件）

- **当前问题**：审计报告 FE2/拖表曾记录「前端 useGlobalSse 仅 22 个、缺 Research 4」→ **已被证伪**；提案要求追加 3 个 QA 事件（QA_SUBTASK_CREATED / QA_ROLE_COMPLETED / QA_RELEASE_GATE_CREATED），需判定是硬依赖还是增强。
- **已验证事实**（verify:frontend-sse + 主线程重读）：
  - `useGlobalSse.ts:73-98` 现在 **26 个名字**，含 Research 4（SOURCE_FETCHED/CLAIM_EXTRACTED/CLAIM_VALIDATED/RESEARCH_REPORT_CREATED）；与后端 `AgentEventType.java:9-38`（26 值）**1:1 对齐**。前端**没有缺失任何已有事件**。
  - 3 个 QA 事件在前后端均**不存在**（仅提案，枚举 26→29）。
  - 既有能力已够 MVP：RUN_STARTED/RUN_COMPLETED/RUN_CANCELLED/RUN_FAILED 覆盖 QA 子 Run、FINDING_CREATED/TEST_REPORT_CREATED 覆盖 Finding 分布、TEST_PLAN_* 覆盖测试计划；`useTesterEventSignal`（过滤 TEST_*+INVARIANT+GAME_SESSION+FINDING）已被 4 页复用，QA 页可直接照抄（useGlobalSse.ts:181-193）。
  - App.tsx 改动最小 3 行（import + NavLink `/qa` + `<Route path='/qa'>`）；main.tsx 纯入口无需改。
- **架构风险**：无硬依赖风险；唯一语义缺口是 release_gate 行生成时点的精确通知（无 QA_RELEASE_GATE_CREATED 时靠 RUN_COMPLETED 后 refetch，gate 通常在 run 末尾生成，可接受仅有延迟）。
- **方案 A**：前端先行监听 3 个 QA 事件（**错误**——后端未发布，事件永不到达）。
- **方案 B**（推荐）：**MVP 无新增事件**——复用现有事件 + `useTesterEventSignal` 驱动 `/api/qa/*` 与 `/api/findings` 的 refetch；3 个 QA 事件作为低延迟增强在 **QA-S5 前后端同加**（AgentEventType + Publisher + useGlobalSse.named 数组各 +3，枚举 26→29；属 P7-2 SSE 协议**扩展**而非破坏）。
- **推荐理由**：读侧零硬依赖；低延迟增强独立成 QA-S5 优化项，不阻塞 QA 主链路。
- **对冻结边界的影响**：AgentEventType 追加不改旧值，符合既有追加惯例（T4-3/R0-8 同款）。
- **QA-S1 前是否必须**：否。
- **决议**：**APPROVE 方案 B**。修正动作：审计报告 FE2「22 个」标注为 stale 快照（见 §XIV 追加登记）。

---

### A10 · Role 工具数口径（§1.3「~12」vs §20.1「各 6/8/7/7」）

- **当前问题**：提案 §1.3 声称「新增 Tool ~12（编排×4 + Player×3 + Researcher×3 + Release Manager×2）」，与 §20.1 各 AgentType 6/8/7/7 以及 QA-S2-1「22+4=26」矛盾，导致 Sprint 估算与验收误判。
- **已验证事实**（verify:mapping）：
  - **真正新增 QA Tool 只有 4 个编排工具**：qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings（§10.1）；§16 QA-S2-1 断言 22+4=26 **交叉印证**。
  - Player Agent ×3 = 3 个人格（§14.2 3 个独立 Prompt 文件）**复用 Tester 8 工具**，非新工具。
  - Researcher ×3 全部复用（§10.4 标注「复用」）。
  - Release Manager ×2 = **幽灵项**（§14.7 已并入 QA Lead，§10 无任何对应工具设计）。
- **架构风险**：估算偏差（以为写 12 工具实写 4）；验收误判白名单。
- **方案 A**：保留「~12」，QA-S2 按 12 个验收。
- **方案 B**（推荐）：修正口径——**「新增 4 编排工具；角色间白名单复用不同」**；Prompt 数按 §七 7.5 = 8。
- **推荐理由**：与代码真实新增一致；避免 QA-S2 范围膨胀。
- **对冻结边界的影响**：无（纯文档口径）。
- **QA-S1 前是否必须**：否。
- **决议**：**APPROVE**（口径修正；§1.3/§20.3/§16.4 同步修订）。

---

## 七、主动检查项裁决（指令 §七）

### 7.1 AgentType ↔ Role ↔ Prompt ↔ Tool 映射一致性
见 **A8**（裁决 APPROVE 方案 A）。结论性事实：AgentType 共 7（3 现有 + QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER）；Role 名义 7（功能 6，Release Manager 并入 Lead）；映射见 §VI A8。**Exploit Hunter 双归属 = 文档内部矛盾（INCONSISTENT，需按 A8 方案 A 修订）**。

### 7.2 Prompt 数量重新计算
- **当前问题**：提案 §20.3 称「7 个」、§1.3 称「7（每角色 1）」、§16.4 称「角色 Prompt ×6」，三者互相矛盾且自相矛盾（§20.3 自列举 8 项）。
- **正确计算**：`qa_lead` + `qa_player_novice` + `qa_player_aggressive` + `qa_player_build_specialist` + `qa_combat_analyst` + `qa_numerical_analyst` + `qa_exploit_hunter` + `qa_ux_reviewer` = **8 个**。
- **裁决**：**Prompt = 8**（非 7）。修正 §1.3/§16.4/§20.3。注：Player×3 是 3 个独立 Prompt 文件（§14.2 明确）；二期的 CONSERVATIVE/EXPLOIT persona（§10.2）不在 MVP（见 7.6）。

### 7.3 Tool 数量重新计算
- **真正新增 = 4 个编排工具**（见 A10）；其余全为既有 22 工具复用。QA_LEAD 复用 get_game_config/create_design_document；QA_TESTER 复用 Tester 8；QA_ANALYST 复用 simulate_game/analyze_experiment/compare_experiments/game_collect_metrics/game_collect_events/finding_report/create_design_document（7）；QA_RESEARCHER 复用 Research 6 + finding_report（7）。
- **裁决**：新增 tool 数口径 = **4 编排放 22 工具池之上**；修正 §1.3「~12」。

### 7.4 QA Run/SubRun 生命周期（timeout / partial failure / incomplete child）— **父 Run 不能自动 PASS**
- **事实**：无父子 Run 关系；子 Run 不通知父 Run，父只能轮询（提案 §6.3 自认）；TIMEOUT 当前不可达（timeoutMs=-1）；CANCELLED 仅人工 HTTP。
- **裁决**：**父 Run 收口规则（Gate 语义）**：若任一分配角色子 Run 终态为 FAILED/TIMEOUT/CANCELLED，Lead 汇总时**不得自动 PASS**——须按 *部分失败* 产出门控结果（未见子 Run 成功→Gate=PENDING/FAIL，附子 Run 状态清单）；Lead 自己的 Run 状态只反映编排本身成败，**Gate 判定与 Run 状态解耦**（Gate 由 QaReleaseGateService 独立得出）。未见 TA 断言 `INCONCLUSIVE` 的配置版——标记 `INSUFFICIENT EVIDENCE`（提案未定义 Gate 非二进制判定路径，仅 CONDITIONAL_PASS 提及）。

### 7.5 Release Gate 语义（自动 vs 人工 vs 自动+override；谁可 override；PENDING 是否必要）
- **裁决**（基于审计 §6.2 + 提案 §14.7）：**默认 PENDING**（Lead 判定 ServerDecision 后可自动置 PASS/CONDITIONAL_PASS/FAIL，但进入 PENDING 供人工审阅）；**人工 override 允许**（Lead/RM——本系统并入 QA Lead 所在人工操作者，即 QA 负责人/Gate 审阅人），**必须留痕**（manual_override + override_by + override_comment + overridden_at）；**PENDING 必要**——无它无法表达「已判定待审」，Gate 不能从已判定直接到已确认。
- **裁决**：auto = Lead 提交 ServerDecision；human = 审阅 + override 留痕；authoritative final = manual_override 后的最终 decision。

### 7.6 Finding ↔ ConfigVersion 溯源（TD-T6-001、C4）
- **归类**：**既有基础设施债**，非「QA 前置基建 Task」，但 A5（根治）与 A3（QA 兜底）均需在 QA-S1 前就位。证据：`GameSessionService` 已有存在性校验先例；`FindingFingerprintService` 不使用 configVersion；既有 Agent 正常链路传的 configVersion 本就合法；`ExperimentService` fallback 属 Design 侧，数据污染影响 QA Research 分析。
- **裁决**：QA MVP 的 `finding_report` 传参源头必须是 Lead 从 configVersion list/当前版本派发（非 LLM 自由选），配合 A5/A3 双保险。避免 Finding→不存在的 configVersion 或 Experiment→静默 fallback latest。

### 7.7 并发模型（QA 提案编排）
- **真并发 vs 串行**：代码层面线程级并发存在（cached pool，每 Run 一线程），但**编排级健壮并发不支持**——无 join/通知（只能轮询）、LoopDetector 对同参轮询 3 次即误杀父 Run（WINDOW=3，strength 见 7.8）、时间预算禁用无子 Run 超时、Hikari 单连接把所有 DB 写串行化。
- **裁决（关键）**：**MVP 应改 sequential orchestration**：Lead 每次只起 1 个子 Run → `start→轮询完成→collect→下一角色`，参数随角色变化天然规避 LoopDetector，贴合单连接现实。真并行留二期（需 join 机制 + LoopDetector 白名单/豁免 + 子 Run 超时 + 连接池扩容 4 项基础设施）。
- 子问题逐项（来自 verify:concurrency）：
  1. 是否真并发支持：线程级 Y / 编排级 N。
  2. child run 如何启动：`RunControlService.start()` 复用 executor.submit，异步不阻塞。
  3. parent 如何 join：无机制；只能轮询 `qa_check_subrun_status`。
  4. timeout 谁负责：仅 `BudgetGuard` 时间预算分支；当前不可达。
  5. DB 事务跨线程：否；无 @EnableTransactionManagement，唯一 @Transactional 在导入期。
  6. SSE 排序：per-run 同步 publish→DB 全局 id→SSE id=dbId，可见必已持久化；跨 run 交错但可回放。
  7. WAL + Hikari 安全性：maximum-pool-size=1 + WAL + busy_timeout=5000 → 无 SQLITE_BUSY，无损坏风险；单连接是吞吐瓶颈非正确性失败。
  8. tool 执行并发：run 内串行（ToolExecutor 同步内联）；跨 run 可并发但串行在唯一连接。
  9. 并发上限/信号量：无；唯一隐性节流是单连接。
  10. 是否应改 sequential：**是**（理由同上）。

### 7.8 LoopDetector 对 QA 轮询的误杀半径（补充）
- **精确语义**（verify:lifecycle）：同一 run 内最近 3 次 tool_call 的 `(tool_name, arguments_json)` **完全一致**（SQL `ORDER BY id DESC LIMIT 3` 精确相等计数）即判循环→父 Run 强制 `COMPLETED('检测到无效循环')`。`qa_check_subrun_status(subRunId)` 若连续 3 次字节相同参数会触发。**豁免**：arguments_json 任何变化（换 subRunId、非数字字段、JSON 空白/字段序）即断开。sequential 编排每角色参数不同天然规避。
- **裁决**：QA Lead Prompt 明确「轮询时带上轮状态摘要/注释，保证 arguments 变化」，或接受 sequential 编排规避；LoopDetector 豁免机制列入二期（真并行前置项，需人工单独批准改动 LoopDetector）。

### 7.9 子 Run 无超时的运行风险（补充）
- `timeoutMs=-1` → 卡住的子 Run 会让 sequential 编排**无限等待**。**裁决（MVP 兜底）**：QaOrchestratorService 侧实现**编排超时**——Lead 每子 Run 上限轮询次数/墙钟（不做 Run 级 timeout，在 orchestration 层收口），超阶段视为该角色 FAILED 并继续或终止；启用 `AgentLoop.timeoutMs` 属二期（触碰 AgentLoop，需单独授权）。**这是 MVP sequential 编排必须自带的保护**。

### 7.10 Gate 判定与 Lead Run 状态解耦（补充）
- **裁决**：Gate 由 `QaReleaseGateService` 独立计算（读取 findings + 子 Run 终态 + 聚合），与 Lead Run 的 RUNNING/COMPLETED 状态**正交**；Lead Run 即便 COMPLETED，Gate 仍可为 FAIL/PENDING。防止「Run 完成=过 Gate」的假等价。

---

## 八、QA 数据模型裁决（指令 §八；模型表定案）

依据审计报告 §6（DB 审计）+ §VI A6/A7 裁决，QA 新增两张表（QA-S1-2 实施）：

**qa_role_assignment**
| 列 | 类型 | 说明 |
|----|------|------|
| id | INTEGER PK AUTOINCREMENT | |
| parent_run_id | TEXT NOT NULL | QA Lead Run（与 agent_event.run_id TEXT 惯例一致，见 TD-QA-004） |
| sub_task_id | TEXT NOT NULL | 关联 task 行 |
| sub_run_id | TEXT NULL | 实际子 Run，启动后回填 |
| role | TEXT NOT NULL | QA_LEAD/QA_TESTER/…/QA_RESEARCHER |
| player_profile | TEXT NULL | NOVICE/AGGRESSIVE/BUILD_SPECIALIST |
| status | TEXT NOT NULL DEFAULT 'PENDING' | **6 态**：PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED |
| result_summary | TEXT NULL | 角色产物（finding 数、结论摘要） |
| created_at / updated_at | TEXT | |

**qa_release_gate**
| 列 | 类型 | 说明 |
|----|------|------|
| id | INTEGER PK AUTOINCREMENT | |
| task_id / run_id / config_version | TEXT NOT NULL | **config_version 加索引** |
| decision | TEXT NOT NULL | PENDING / PASS / CONDITIONAL_PASS / FAIL |
| critical_bugs / high_bugs / medium_bugs / soft_lock_count / crash_rate / dominant_build_share | 数值 | 聚合证据 |
| server_decision | TEXT | QaReleaseGateService 自动判定（可=PENDING，待审） |
| lead_decision / lead_comment | TEXT NULL | 人工审阅意见 |
| manual_override | INTEGER DEFAULT 0 | 1=被人工覆盖 |
| override_by / override_comment / overridden_at | TEXT NULL | **override 审计留痕** |
| created_at | TEXT | |

状态推进：PENDING → (自动) PASS/CONDITIONAL_PASS/FAIL → (人工) 确认或 override；Gate 判定与 Run 状态解耦（7.10）。

---

## 九、QA 工具层裁决（指令 §九）

| 工具（新增 4 = 编排） | 语义 | 关键裁决 |
|----------------------|------|----------|
| qa_create_subtask | Lead 建子任务 | 创建 assignment（PENDING） |
| qa_start_subrun | 启动子 Run | 回填 sub_run_id + RUNNING；sequential 每次只起一个 |
| qa_check_subrun_status | 查询子 Run | **arguments 必须随调用变化**（7.8）；以 run 行为准 |
| qa_collect_findings | 聚合 Finding | 受 A5 兜底（configVersion 合法） |

复用白名单（A10 口径）：QA_LEAD = 2 编排 + get_game_config + create_design_document + 4 编排；QA_TESTER = Tester 8（**A8 后 + web_search/fetch_page=10**）；QA_ANALYST = 7 复用；QA_RESEARCHER = Research 6 + finding_report（**A8 后 +3=10**）。全按既有 22 工具池，零新增业务工具。

---

## 十、QA AgentType / Prompt 层裁决（指令 §十)

- **AgentType.java** +4：QA_LEAD / QA_TESTER / QA_ANALYST / QA_RESEARCHER（共 7）。
- **DefaultAgentTypeResolver**：`systemPrompt()` +4 case、`toolWhitelist()` +4 case、错误信息「允许值」字符串更新（QA-S1-1）。
- **Prompt 共 8 个**（7.2 裁决）：
  1. qa_lead_p001 — 含 Release Gate 判定路径、FAIL 优先缺陷策略、轮询参数变化约束（7.8）
  2–4. qa_player_novice / aggressive / build_specialist_p001 — 3 人格，复用 §14.2 设计
  5. qa_combat_analyst_p001
  6. qa_numerical_analyst_p001 — 含 C4 兜底说明
  7. qa_exploit_hunter_p001 — **归属 QA_TESTER（A8）**，文件命名 qa_exploit_hunter_p001.md（修正 §20.1 命名模式）
  8. qa_ux_reviewer_p001 — 归属 QA_RESEARCHER
- **§9.3 ROLE_FOCUS 机制**：QA_ANALYST / QA_RESEARCHER 各承担两角色，单 Prompt 内以 ROLE_FOCUS 载荷区分（switch 扩展固有盲区，须在 Prompt 中显式约束）。

---

## 十一、QA API 层裁决（指令 §十一）

- 端点设计沿用提案：`/api/qa/role-assignments`、`/api/qa/role-assignments/{id}`、`/api/qa/release-gates`、`/api/qa/release-gates/{id}`、`/api/qa/dashboard/stats`、`/api/qa/release-gates/{id}/approve`、`/api/tasks/{id}/qa-run`、`/api/tasks/{id}/qa-summary`。
- 全部隔离在 `/api/qa/` 前缀，**现有 28 端点零修改**（前端也据此锁定后端契约，避免 404）。

---

## 十二、前端层裁决（指令 §十二)

- **App.tsx 最小 3 行**：import QaDashboardPage + `<NavLink to='/qa'>` + `<Route path='/qa'>`；main.tsx 零修改。
- **独立 QaDashboardPage**（/qa）而非并入 DashboardPage：数据域隔离 + approve/interact 独立（审计 §2.8；verify:frontend-sse Q7）。
- **事件**：MVP **零新事件**（A9 方案 B）；`useTesterEventSignal` 复用驱动 `/api/qa/*`+`/api/findings` refetch；QA_* 3 事件 QA-S5 前后端同加（26→29）。
- **types.ts / api.ts**：追加 QaRoleAssignment / QaReleaseGate / QaDashboardStats 类型与 list/get/stats/approve/api 函数；**须与后端 QA-S2-3 同步落地**（单改前端会 404）。QaDashboardStats 响应形状未定稿 → `INSUFFICIENT EVIDENCE`，待 `/api/qa/dashboard/stats` 落地对齐。

---

## 十三、Release Gate 收口与人工审阅（指令 §十三）

- Gate 生命周期：`PENDING`（含 ServerDecision）→ `PASS`/`CONDITIONAL_PASS`/`FAIL`（Lead 自动提交）→ 人工审阅（确认 / override 留痕）。
- **谁可 override**：人工 Gate 审阅人（QA 负责人 / Release 决策人），非 Lean Agent 本身。
- **审计信息**：override_by / override_comment / overridden_at 必填。
- **FAIL 策略**：FAIL → 人工决定回退 / 生成新 ConfigVersion / 启动修复（Builder 为 Phase 3 输入），QA Run 链路不自动重试。
- 决策由 §VI A6 + A7 + §XIV 里程碑锁定。

---

## 十四、Contract Matrix（指令 §十四 §1.4 冻结表对齐）

| 冻结对象 | 契约内容 | QA 是否触碰 |
|---------|----------|-------------|
| AgentLoop | 生命周期 / 预算 / 状态机 | ❌ 零修改（A1） |
| run/step/tool_call/task | 数据模型 / 状态机 | ❌ 只读 |
| agent_event / AgentEventType(26) | 事件协议 | ❌ 追加不改旧值（26→29 属扩展） |
| experiment | ConfigVersion 引用语义 | ⚠️ C4 现状保留；QA 侧工具层兜底（A3） |
| change_proposal | 提案状态机（Builder 复用） | ❌ |
| config_version | append-only 全量快照 | ❌ 只读 |
| ErrorCodes | 业务异常语义 | ❌ 新异常可加（新增非变更） |
| 现有 28 Endpoint | REST 契约 | ❌ 仅新增 /api/qa/* |
| 现有 14 表 schema | DDL | ❌ 新增 qa_ 两表（非破坏既有） |
| 现有 22 GameTool | 工具实现 | ❌ 复用；新增工具仅 4 编排 |
| SSE 协议（P7-2） | 流式发布 | ❌ 扩展而非破坏 |

---

## 十五、技术债处理原则（指令 §十五）

| 技术债 | 状态 | QA 前处理 | 是否本轮修复 |
|--------|------|-----------|-------------|
| TD-P8-1-001 | **已解决**（seed 幂等） | 无需 | ❌ |
| TD-T6-001 | 存在 | **独立 Task 清偿**（A5） | ❌ 独立立项 |
| TD-T7-001 | 存在（13 FK errors） | **独立前置 Task**（A4） | ❌ 独立立项 |
| TD-QA-PROP-001 | 影响分析登记 | 依表评估 | ❌ 独立 Task |
| C4 (ExperimentService fallback) | 存在 | **QA 工具层兜底（A3）** + 独立修复 Task | ❌ 独立授权 |
| TD-QA-004 (run_id TEXT vs INTEGER) | 记录 | QA 沿用 TEXT 惯例 | ❌ |
| TD-QA-005 (LoopDetector 轮询误杀) | 待验证 | sequential 编排规避（7.8） | ❌ 二期豁免机制单独授权 |
| TD-QA-006 (C4 pending 裁决) | 待裁决 | 归 A3 → 独立 Task | ❌ |

**原则**：所有清偿均作为**独立 Task 由人工授权**，不并入 QA-S1 预算，不触碰冻结代码。

---

## 十六、严格禁止事项（指令 §十六；本轮已全部遵守）

| 禁止项 | 本轮状态 |
|--------|---------|
| 修改代码（Java/TS/任何源文件） | ✅ 未修改 |
| 修改 DB（schema/migration/DDL） | ✅ 未修改 |
| 修改现有架构实现（C4/TD-T7/SSE/Role Mapping） | ✅ 未修改 |
| 创建 Sprint / task-state 更新 | ✅ 未创建、未更新 |
| 创建 QA 生产代码 | ✅ 未创建 |
| 实现 Builder | ✅ 未实现 |

---

## 十七、必须输出的 8 个结果块（指令 §十七）

### 结果块 1 · Architecture Decision Summary

| 决策项 | 裁决 | 关键依据 |
|--------|------|----------|
| A1 AgentLoop 零修改 | **APPROVE** | 委托已存在（agentloop.java:242-250） |
| A2 I10 降级为验证 | **APPROVE** | 伪需求清理 |
| A3 C4 回退：QA 工具层兜底 | **APPROVE** | 与 GameSessionService 先例对立 |
| A4 TD-T7-001 独立前置 | **APPROVE** | 13 FK errors |
| A5 TD-T6-001 独立清偿 | **APPROVE** | fingerprint 不依赖 configVersion |
| A6 qa_release_gate DDL 修正 | **APPROVE**（并入 QA-S1-2） | PENDING+override+索引 |
| A7 qa_role_assignment 6 态 | **APPROVE**（并入 QA-S1-2） | run 行为准，防假挂起 |
| A8 Role↔AgentType 映射 | **APPROVE 方案 A** | Exploit→QA_TESTER；UX→QA_RESEARCHER |
| A9 SSE 补 3 事件 | **APPROVE 方案 B** | 26==26，QA_* 非硬依赖 |
| A10 工具数口径 | **APPROVE** | 新增=4 编排 |

### 结果块 2 · Preflight Clearance List

| # | 内容 | 类型 | 阻塞 |
|---|------|------|------|
| 1 | A1/A2 裁决（AgentLoop 零修改 + I10 降级） | 已裁决 | — |
| 2 | A8 映射定案（Exploit→QA_TESTER，UX→QA_RESEARCHER，白名单 +5） | 已裁决 | — |
| 3 | Prompt=8 / 新增 Tool=4 口径 | 已裁决 | — |
| 4 | 前端事件数修正 26（stale 22 澄清） | 已裁决 | — |
| 5 | **TD-T7-001 独立 Task 立项** | 需人工批准 | **QA-S1 电测** |
| 6 | **TD-T6-001 独立 Task 立项** | 需人工批准 | Finding 溯源（可由 A3 兜底） |
| 7 | **C4 修复独立 Task / 或确认 QA 工具层兜底足够** | 需人工批准 | QA 数值分析正确性 |
| 8 | `AgentLoop.timeoutMs` 启用 / 编排超时兜底 | 需人工选择 | 子 Run 卡死保护 |

### 结果块 3 · QA Architecture Freeze Candidate

**以本裁决修正后的 QA 提案**（§1.4 冻结约束 + A1-A10 修正）为 QA 冻结基线：
- AgentLoop 零修改；Resolver +4 AgentType（QA-S1-1）
- 新增工具仅 4 编排；白名单按 A8 方案 A（TESTER=10 / RESEARCHER=10 / ANALYST=7 / LEAD=6）
- Prompt=8；Gate=4 态 + override 留痕；assignment=6 态、以 run 行为准
- MVP 编排 = sequential；事件 = 复用（QA_* 3 个延后 QA-S5）
- 端点 = /api/qa/* 独立全集；前端 = 独立 /qa 页
- **冻结前置条件**：A4/A5/A3 独立 Task 获批或 QA 侧兜底就位

### 结果块 4 · Contract Freeze List

见 §十四 Contract Matrix 全部 11 行；均不触碰。

### 结果块 5 · Remaining Architecture Risks（按 P0/P1/P2）

**P0（阻断 QA-S1 启动 / Gate 可信度）**
1. **TD-T7-001** — 无干净电测上下文 → 独立前置 Task（A4）。
2. **Role↔白名单错配**（A8）— 不改白名单则 Agent 调用被拒 / Run 崩 → 按方案 A 修正白名单 + Prompt。
3. **assignment 状态漂移**（A7）— 假挂起 → 6 态 + 以 run 行为准。
4. **C4 数据污染**（A3）— QA 数值结论与 config 脱钩 → 工具层兜底必上。
**P1（QA-S1/S2 内）**
5. **Gate 语义未定前不得冻结 QA-S3**（A6）— PENDING/override 先入 DDL。
6. **LoopDetector 误杀半径**（7.8）— sequential + Prompt 约束规避；豁免二期。
7. **子 Run 无超时保护**（7.9）— orchestration 层轮询上限兜底。
8. **Player 人格数未对齐**（§10.2 5 vs §14.2 3）— MVP 取 3，二期再扩展。
**P2（后续）**
9. **3 个 QA_* 事件**（低延迟增强，QA-S5）。
10. **真并行编排**（join + LoopDetector 豁免 + 子 Run 超时 + 连接池扩容）——4 项基础设施，二期，需单独授权。
11. **`run` 表 parent_run_id**（当前无父子关系，靠 assignment 表弥补）。
12. **QaDashboardStats 响应形状**未定稿（`INSUFFICIENT EVIDENCE`）。

### 结果块 6 · Builder Feasibility — **DESIGN ONLY**

- 结论：**DESIGN ONLY**（Phase 3 独立方向）。证据：平台**无文件读写 / build/test 执行类工具**（ToolRegistry 只有只读/模拟 GameTool）；缺变更回滚/沙箱；缺 finding.status(FIXED) 联动；Phase 1 RFC 不允许新增这类工具。
- 可行愿景：受限 Builder 接受 configVersion + Finding 列表 → 生成 config patch → `create_change_proposal` → approve 后自动跑 QA 回归 → 新 Gate（复用现有 change_proposal 状态机）。**依赖 QA 完成（Release Gate 是 Builder 输入）**。

### 结果块 7 · Recommended Human Decisions

| 决策 | 建议 | 备注 |
|------|------|------|
| A1 Action | **APPROVE** | AgentLoop 零修改 |
| A2 Action | **APPROVE** | I10 降级 |
| A3 方法选择 | **APPROVE 方案 B**（工具层兜底）| C4 修复派生独立 Task，人工授权 |
| A4 独立 Task | **APPROVE** | TD-T7-001 前置 |
| A5 独立 Task | **APPROVE** | TD-T6-001 前置 |
| A6 DDL 修正 | **APPROVE**（进 QA-S1-2） | |
| A7 6 态 | **APPROVE**（进 QA-S1-2） | |
| A8 映射 | **APPROVE 方案 A** | 白名单 +5（TEST +2 / RES +3）；§9.2/§14.5/§20.1 修订 |
| A9 SSE | **APPROVE 方案 B** | 复用；QA_* 延后 QA-S5 |
| A10 口径 | **APPROVE** | 新增=4；Prompt=8 |
| C4 as separate task | **DEFER → 独立 Task** | 工具层兜底先行 |
| TD-T7-001 task | **DEFER → 独立前置** | A4 |
| SSE 3 events | **DEFER → QA-S5** | A9（低延迟增强） |
| Role↔AgentType mapping | **APPROVE 方案 A** | A8 |
| Prompt count=8 | **APPROVE** | 7.2 |
| Tool count=4 | **APPROVE** | A10/7.3 |
| Concurrency=sequential | **APPROVE** | MVP sequential；真并行二期 |
| Release Gate 语义 | **APPROVE**（PENDING+override 留痕+人工审阅）| A6/A7 |
| Builder | **FROZEN at DESIGN ONLY** | Phase 3 |

### 结果块 8 · STOP 块

```text
QA ARCHITECTURE REVIEW — COMPLETE
A1-A10: REVIEWED
Preflight: IDENTIFIED
Contracts: IDENTIFIED
Builder: DESIGN ONLY
NO CODE MODIFIED
NO SCHEMA MODIFIED
NO SPRINT STARTED
NO TASK-STATE MODIFIED
NEXT ACTION: HUMAN ARCHITECTURE DECISION
```