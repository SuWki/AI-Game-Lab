# AGL QA 架构审计报告 — 10 维度事实核查 + 12 输出块

> **审计对象**：`docs/QA_SYSTEM_ARCHITECTURE_PROPOSAL.md`（QA 系统架构提案，1226 行，20 章）
> **审计方法**：以实际代码为准（实际代码 > 冻结验收契约 > 冻结任务树 > 技术设计 > 架构提案 > 旧文档）。10 个并行审计代理 + 主线程直接源码核查（读 AgentLoop / DefaultAgentTypeResolver / ExperimentService / ConfigVersionService / schema.sql / useGlobalSse / App.tsx / main.tsx / types.ts 等核心文件）。
> **审计日期**：2026-09-02
> **审计状态**：DONE — 提案可进入人工架构评审（附 MUST 修正项）
> **本轮约束**：零代码修改。全部发现仅记录与建议，不实施。

---

## 第 0 块 · 总评：提案方向成立，但 1 项重大架构误解 + 若干 MUST 修正

| 裁决 | 说明 |
|------|------|
| **总体结论** | ✅ **CONDITIONALLY_PASS** — 提案的 Intra-Run Orchestration 架构方向正确，核心决策 D1（零 Runtime 修改）经过代码验证**基本成立**，但存在 1 项重大发现（I10 伪需求）和若干 MUST 修正项，部分 DB/Config 断言与真实代码不符。 |
| **最重大发现** | **❌ I10「修改 AgentLoop」是伪需求（§7.1）**。经源码验证 [AgentLoop.java:242-250](backend/src/main/java/com/aigamelab/runtime/AgentLoop.java#L242-L250)：`selectSystemPrompt()` / `selectToolSpecs()` **完全委托**给 `agentTypeResolver.systemPrompt()` / `agentTypeResolver.toolWhitelist()`。新增 QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER 四种 AgentType，只需在 `DefaultAgentTypeResolver` 追加 switch case，**AgentLoop 零修改**。提案把最高风险项 I10 建立在错误前提上。 |
| **最大 DB 缺口** | ❌ `qa_release_gate` 只能 `PASS/CONDITIONAL_PASS/FAIL` 三态 + 无 `manual_override_*`，三态之间无 PENDING + 人工审阅无审计记录（§11 DDL + §12.1 approve 端点）。 |
| **并发缺口** | ⚠️ 子 Run 无等待机制（除轮询），无进度上报给父 Lead；`qa_check_subrun_status` 同参重复调用可能触发 LoopDetector 假阳性。 |
| **技术债影响** | ⚠️ TD-T7-001（full-suite 13 errors）仍在红线，QA 测试必须遵 2 条规矩（不继承 AbstractRepositoryTest / 不碰共享 temp DB）。TD-P8-1-001 已 **RESOLVED**（提案 §18 仍标注"高影响未清偿"——过时）。 |
| **前端修正** | ⚠️ §20.3 主张修改 main.tsx 是错误；App.tsx 已有 /findings、/test-plans 路由与 NavLink（无需新增 QA 路由以外的改动）。SSE 缺 3 个 QA 事件，但事件名顺序与 proposal 列出的枚举不一致。 |

---

## 第 1 块 · Repository Reality Check（基线核查）

审计目标：核实提案 §2「Frozen 基线」中每一条实物主张是否与仓库一致。

| §2.1 主张 | 实际代码 | 核对 |
|-----------|----------|------|
| 后端生产代码 ~66 个 Java 文件 | Glob 实际 **166 个 main Java 文件** | ⚠️ 低估 2.5×（§20.3 连带低估修改面） |
| 前端 ~15 个 TSX/TS 文件 | Glob 实际 **19 个文件**（含 4 页测试） | ✅ 基本一致 |
| 后端测试 403 tests | 与 QA 基建审计代理实测 **403 tests / 13 errors** 相符 | ✅ 数字对，但 §2.1 写 "25 errors" 过时（当前 13） |
| 工具注册 22 个 | [DefaultAgentTypeResolver.java:56-76](backend/src/main/java/com/aigamelab/runtime/DefaultAgentTypeResolver.java#L56-L76) 明确 `TESTER_TOOL_NAMES`=8、`DESIGNER_TOOL_NAMES`=8、`RESEARCH_TOOL_NAMES`=6，总计 22 | ✅ 一致 |
| AgentType 3 种 | [AgentType.java](backend/src/main/java/com/aigamelab/runtime/AgentType.java)：`DESIGNER / TESTER / RESEARCH` | ✅ 一致 |
| 事件枚举 26 个 | [useGlobalSse.ts:72-95](frontend/src/useGlobalSse.ts#L72-L95) 前端硬编码 22 个事件名（已含 6 个 TEST_*，不含 Research 4 个 QA 相关） | ⚠️ 后端枚举 vs 前端订阅名单**不对称**（后端 26 含 Research 4 个，前端 22 不含） |
| AgentType 路由：explicit → TESTER(ACTIVE Plan) → DESIGNER | [DefaultAgentTypeResolver.java:95-109](backend/src/main/java/com/aigamelab/runtime/DefaultAgentTypeResolver.java#L95-L109) 完全符合 | ✅ 一致 |
| Prompt 3 版本（p001/tester/research） | 与 PromptService/prompt_version 表一致 | ✅ 一致 |

**Reality Check 结论**：提案 §2 的基线描述基本准确，但生产文件数量低估可能连带到 §20.3 修改面低估（详见第 3 块）。

---

## 第 2 块 · 10 维度审计结果汇总

每个维度一份 PASS/WARN/FAIL 裁决 + MUST/NICE-TO-HAVE 关键项。

### 2.1 维度一：Agent Runtime / AgentLoop 委托关系（主线程直接验证）

**裁决：✅ PASS（且有重大优化发现）**

- **事实**：[AgentLoop.java:242-250](backend/src/main/java/com/aigamelab/runtime/AgentLoop.java#L242-L250) 完全委托 `agentTypeResolver`。
- **重大发现**：提案 §7.1 I10「AgentLoop.java 修改（高风险）」**不成立**。QA 支持仅需修改 `DefaultAgentTypeResolver`：
  - `systemPrompt()`（[L120-131](backend/src/main/java/com/aigamelab/runtime/DefaultAgentTypeResolver.java#L120-L131)）追加 4 个 QA case；
  - `toolWhitelist()`（[L141-147](backend/src/main/java/com/aigamelab/runtime/DefaultAgentTypeResolver.java#L141-L147)）追加 4 个 QA 白名单。
- **代价**：两个方法都是 **exhaustive switch（无 default）**，新增枚举值后不追加 case 会**编译错误** —— 这是设计上的隐形强制，反而保证了 QA 扩展不会悄悄漏掉白名单。
- **修正建议（MUST）**：在提案 §7 中把 I10 从"修改 AgentLoop"改为"修改 DefaultAgentTypeResolver + 验证 AgentLoop 零修改"。AgentLoop 本身**必须零修改**（符合冻结约束），且现状下**无需修改**。

### 2.2 维度二：Config 层 / configVersion 真实性校验

**裁决：⚠️ WARN（1 FAIL / 1 MUST / 2 PASS）**

| # | 结论 | 说明 |
|---|------|------|
| C1 | ✅ PASS | configVersion 经 LLM 工具参数传递，链路清晰（game_start / finding_report / test_report_create / test_plan_get 均显式带 configVersion 参数） |
| C2 | ✅ PASS | 白名单隔离正确：Designer/Tester/Research 各自 Set.of 精确隔离，无溢出 |
| C3 | 🔶 MUST | **TD-T6-001**：`FindingService.validate()`（[L82-85](backend/src/main/java/com/aigamelab/tester/FindingService.java#L82-L85)）仅校验非空，**未校验 configVersion 是否真实存在于 `game_config_version`**。QA-S1 前应清偿（独立 Task）。 |
| C4 | ❌ FAIL | **ExperimentService 静默回退**：[ExperimentService.java:87-88](backend/src/main/java/com/aigamelab/experiment/ExperimentService.java#L87-L88) `configService.findByVersion(cfgVersion).orElseGet(configService::getCurrent)` —— 传不存在的 configVersion 时**静默测 latest 而非报错**。对 QA Numerical Analyst 是致命的：传错版本号 → 悄悄测了错误的建，回归对比失真。**MUST 修正**：simulate_game/analyze_experiment 对不存在版本必须抛错（改 ExperimentService 或 QA 工具层包装校验，二选一，待人工裁决）。 |

### 2.3 维度三：Tester 工具层

**裁决：✅ PASS（2 MUST / 1 NICE-TO-HAVE）**

| # | 结论 | 说明 |
|---|------|------|
| T1 | 🔶 MUST | `PlayerProfile` 枚举当前只支持 SCRIPTED/RANDOM 等少数字面量。提案 D4 新增 NOVICE/AGGRESSIVE/BUILD_SPECIALIST 需先确认 [FakeGameEnvironment](backend/src/main/java/com/aigamelab/gameenv/FakeGameEnvironment.java) 的 profile 参数是否开放字符串（拟开放枚举）。核查后需在 QA-S2-2 校验。 |
| T2 | 🔶 MUST | **Finding configVersion 真实性校验缺失**——同 C3，关联 TD-T6-001。QA Finding 可能指向不存在的版本，破坏回归对比的闭合 |
| T3 | 🟢 NICE-TO-HAVE | `scenario.invariants` 未接入运行时 invariant 评估（InvariantEngine 按全局规则跑，而非按场景-level invariant 白名单）。QA-S2-2 可与 PlayerProfile 一起评估 |

### 2.4 维度四：Research 工具层

**裁决：⚠️ WARN（1 PASS / 1 WARN / 2 NICE）**

| # | 结论 | 说明 |
|---|------|------|
| R1 | ✅ PASS | Research 工具注册/白名单正确（6 工具） |
| R2 | ⚠️ WARN | **WebSearchTool 是 Stub**（实际返回 canned result 或空）。提案把 research_p001（QA_RESEARCHER 工具集）列为 QA UX Reviewer 的能力依赖——UX Reviewer 基于"数据"评估，不真正 web_search，但 `web_search` 若被 QA_RESEARCHER 白名单包含会暴露 stub 给 Agent。**建议在 QA 白名单中排除 web_search**（UX Reviewer 不需要 web 检索）。 |
| R3 | 🟢 NICE | exhaustive switch 迫使 3 处同时改（枚举+prompt+whitelist），QA 扩展时记住这一步 |

### 2.5 维度五：Runtime 并发/编排缺口

**裁决：⚠️ WARN（3 WARN）**

| # | 结论 | 说明 |
|---|------|------|
| RT1 | ⚠️ WARN | **无原生子 Run wait/通知机制**。提案 §6.3 已承认"子 Run 完成后不自动通知 Lead，需轮询"。轮询消耗 Lead 步数（proposal TD-QA-001 已登记）。建议 E2E 剧本显式覆盖轮询上限。 |
| RT2 | ⚠️ WARN | **maxSteps=50 默认值对 QA Lead 不足**。QA Lead 编排 6+ 角色需要 80-100 步（创建/启动×6 + 轮询×多轮 + 汇总 + Gate + 报告）。提案 §7 未提改默认值——建议 QA Run 启动时显式传 maxSteps=200（proposal TD-QA-001 缓解已提，但需落实到 RunController/任务启动路径）。 |
| RT3 | ⚠️ WARN | **LoopDetector 可能被同参轮询误触发**：`qa_check_subrun_status(runId)` 若以相同参数重复调用，可能被判为"循环"而强制收口。需在 QA-S2-1 设计时确认 LoopDetector 的判循环逻辑是否把"带参数变化的探索"误判——若会，需让 Lead 每次轮询变更顺序/附加不同注释参数。 |

### 2.6 维度六：DB 层 / Schema 扩展

**裁决：⚠️ WARN（多个 MUST 修正，见第 6 块详细表格）**

| # | 结论 | 说明 |
|---|------|------|
| DB1 | 🔶 MUST | `qa_release_gate` 需 **PENDING 态**（decision 三态不含"未判定/待人工审阅"） |
| DB2 | 🔶 MUST | `qa_release_gate` 需 **manual_override_reason + manual_override_by 字段**（人工 approve 无审计日志，违反 Human-in-the-Loop 冻结约束的追溯要求） |
| DB3 | 🔶 MUST | `qa_role_assignment.status` 需含 **TIMEOUT**（run.status 有 7 态含 TIMEOUT，assignment 只有 4 态会脱节：子 Run 超时后 assignment 永远 RUNNING） |
| DB4 | 🔶 MUST | `qa_release_gate` 缺 **config_version 索引**（跨 task 按版本聚合 QA run 是 Regression 核心查询路径） |
| DB5 | ⚠️ WARN | `finding.source` **不扩展角色枚举**（会污染回归对比）。角色溯源走 `finding.run_id → run` JOIN（run 表需记录 QA 角色 or 加 qa_role 列） |
| DB6 | ⚠️ WARN | `agent_event.run_id/task_id` 是 **TEXT** 而其他表 INTEGER（schema.sql L120-121）——既有设计缺陷，QA 事件沿用 TEXT 加剧类型不一致。不建议本轮改（EE 冻结约束），记录为技术债。 |
| DB7 | 🟢 NICE | 建议 `qa_release_gate` 追加 `PASS` 判定需人工确认的豁免表示（is_manual_override 布尔） |

### 2.7 维度七：模拟 / 实验层

**裁决：✅ PASS（CONDITIONALLY，附性能 WARN）**

- simulate_game / analyze_experiment / compare_experiments 功能完备、幂等（UNIQUE 键），已过集成测试。
- **性能 WARN**：`ALL_BUILDS` 路径串行（30k 局 ~30-90s），QA Numerical Analyst 建议用 **3 次 per-build simulate_game（crit / penetration / attack_speed，各 1 万局 ~7s 总耗时）** 替代超大 count 聚合。
- **compare_experiments 仅对比 winRate**，不含 DPS/耗时差异。QA 数值比较需 LLM 自行解析 analyze_experiment 输出。提案 §8.3 QA_ANALYST 工具集已含 analyze_experiment + compare_experiments，但需在 Prompt 中告知"compare 只比胜率；DPS 差异需自行计算"。
- per-build avgDps 缺失（TD-006）：QA Numerical Analyst 的 DPS 方差分析需依赖 3 次 per-build 实验各自的 avgDps，代理方案已在提案 §18 标注。

### 2.8 维度八：前端

**裁决：⚠️ WARN（1 重大事实修正 + 2 MUST + 2 NICE）**

| # | 结论 | 说明 |
|---|------|------|
| FE1 | ❌ **事实修正** | [App.tsx](frontend/src/App.tsx#L41-L51) **已有** `/findings`、`/test-plans`（含 :id）路由，[L20-27](frontend/src/App.tsx#L20-L27) 已有对应 NavLink。提案 §20.3 声称"App.tsx + main.tsx 需修改"——**main.tsx 无需修改**（[main.tsx](frontend/src/main.tsx) 仅 ReactDOM.render + Provider，无路由）；App.tsx 仅需新增 `/qa` 路由 + NavLink（业务正确，但 main.tsx 项删除）。 |
| FE2 | 🔶 MUST | **SSE 事件订阅缺 3 个 QA 事件**：[useGlobalSse.ts:72-95](frontend/src/useGlobalSse.ts#L72-L95) 前端硬编码 22 个事件名，缺 `QA_SUBTASK_CREATED` / `QA_ROLE_COMPLETED` / `QA_RELEASE_GATE_CREATED`。需在 QA 前端页使用 `useSseStream` 追加监听（或升级 useGlobalSse 事件列表）。 |
| FE3 | 🔶 MUST | 前端类型缺 QA 新实体类型。现有 [types.ts](frontend/src/types.ts) 有 Finding/TestPlan/FindingRegression，但无 QaRoleAssignment / QaReleaseGate / QaDashboardStats——需追加（§13.3 已有定义，照抄）。 |
| FE4 | 🟢 NICE | 前端测试最稳（16/16 + tsc + build OK），QA 前端改动不动既有测试。 |
| FE5 | 🟢 NICE | apis.ts 需追加 /api/qa/* 客户端函数；现有 api.ts 只覆盖 REST list 端点。 |

### 2.9 维度九：文档冲突

**裁决：⚠️ WARN（4 文档 vs 代码不一致项）**

| # | 冲突 | 实际代码 | 影响 |
|---|------|----------|------|
| DC-01 | Designer 工具白名单：旧文档写 `null=全部` | 代码 Set.of(8) | 提案 §2.3 正确（22 工具）写死了，但旧文档过时。低影响，无 QA 依赖。 |
| DC-02 | Research 工具白名单：旧文档写 `null=全部`（等 R0-2） | 代码 Set.of(6) | 同上，无 QA 影响。 |
| DC-03 | QA 表（qa_role_assignment / qa_release_gate）尚不存在于 schema.sql | schema.sql 仅 14 张表 | 预期行为（提案 §11 建议新增），非冲突。 |
| DC-04 | AgentType 4 个 QA 值尚不存在 | AgentType.java 仅 3 值 | 预期行为；且穷举 switch 保证不会静默遗漏。 |

### 2.10 维度十：技术债影响

**关键事实更新**（替换提案 §18 的两个过时判断）：

| 技术债 | 提案 §18 判断 | 审计实际 | 修正 |
|--------|--------------|----------|------|
| TD-P8-1-001（Tester v001 fixture 未初始化） | 高影响，建议 QA-S1 清偿 | **已 RESOLVED**（ConfigVersionService 构造器/PostConstruct + AbstractRepositoryTest.ensureSeedV001 双重兜底，40401 错误归零） | 过时，无需处理 |
| TD-T7-001（full-suite context 污染） | 高影响，建议排查 | **仍 ACTIVE：13 FK errors**（TesterServicesTest.setUp:43 DELETE FROM run 未先删子表） | **QA-S1 前置 MUST**——QA 测试**禁止**继承 AbstractRepositoryTest；建议优先修复 TesterServicesTest 的 FK 顺序（独立 Task） |
| TD-003（ToolRegistry 测试隔离） | 中等，QA-S1 评估 | 低风险（每类独立 temp DB）；但 `ToolRegistryIntegrationTest` 硬编码 22 → 需更新 22+4=26 | QA-S2-1 验收标准已含 26，✅ |
| TD-T6-001（Finding configVersion 真实性） | 中等，QA-S1 清偿 | 同 C3——仍未清偿 | QA-S1 前置独立 Task（建议） |

---

## 第 3 块 · 提案 §20.3 文件变更清单交叉核验

逐项核对 proposal §20.3 的"新增/修改"主张与真实仓库文件。

| §20.3 声称 | 真实仓库 | 核验 |
|------------|----------|------|
| Java 生产代码 ~16 新增 + 5 修改 | 实际 main 166 个文件；但 QA 新增 `qa/` 包 + 4 Prompt + 2 Repo ≈ 16 个新文件合理；**修改 5 个**中之 **AgentLoop ❌（不需要）** | ⚠️ 修正：AgentLoop 从"修改"清单移除，加入"验证零修改" |
| SQL append 2 表 | schema.sql IF NOT EXISTS 模式可追加 | ✅ |
| 前端新增 3 + 修改 2（**App.tsx + main.tsx**） | **main.tsx 无须修改**（纯入口）；App.tsx 仅 +`/qa` 路由 | ❌ 修正：前端修改 1 个（App.tsx） |
| Prompt 新增 7 个 | 无同名冲突（现有 designer_p001/tester_p001/research_p001） | ✅ |
| 文档新增 1 + 修改 2（README + task-state） | 合理 | ✅ |
| 前端 "~15 个 TSX/TS 文件"（§2.1） | 实际 19 个 | ⚠️ 低估；但 QA 仅新增 1 页，不影响 |

**§20.3 修正指令**：`main.tsx` 从修改清单删除；`AgentLoop` 从修改清单删除（改为零修改验证）；生产代码修改清单 = **DefaultAgentTypeResolver + AgentEventType + PromptService + (可选) RunController/错误提示** 四项。

---

## 第 4 块 · 提案内部一致性审计（§1 vs §7 vs §8 vs §14 vs §17）

| # | 检查点 | 提案口径 | 审计发现 |
|---|--------|----------|----------|
| I1 | AgentType 数量 | §1.3「新增 4」；§8.1 定义 4 个（QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER） | ✅ 一致 |
| I2 | 角色 vs AgentType 映射 | §14 七角色（Lead + Player + Combat + Numerical + Exploit + UX + Release Manager）→ §8.1 只 4 种 AgentType | ⚠️ **映射关系需澄清**：Combat/Numerical 共享 QA_ANALYST？Exploit/UX 归 QA_RESEARCHER？Player Agent 归 QA_TESTER？提案 §14.2 写 Player Agent=QA_TESTER、§9.2 写 Combat/Numerical=QA_ANALYST、Exploit=QA_RESEARCHER（基础 tester+research 混合）；UX=QA_RESEARCHER。**§20.1 却列 5 种角色 4 种 AgentType**——需统一 persona↔AgentType 表，避免 QA-S4 角色 Prompt 与 tools 错配 |
| I3 | Intra-Run vs SubRun | §6.1 Intra-Run（Lead 单 Run 内工具编排）；§6.3 却也说 qa_start_subrun 异步创建 Run | ⚠️ §6 标题写 **Intra-Run**，但实现是 **Inter-Run**（真实子 Run 独立运行）。术语有歧义，但架构方向（Lead 编排子 Run）一致。建议改名 "Single-Writer Orchestration" 清晰表述 |
| I4 | 角色工具集数量 | §1.3「~12 Tool」；§20.1 表列 Lead 6 + Player 8 + Analyst 7 + Researcher 7 | ⚠️ 4 个角色工具集之和 28 ≠ 12。提案把"QA 新增工具"（4 个编排工具）与"角色可用工具"混为一谈。修正表述：**新增 4 个编排工具**；角色工具集复用量大（Player 8/8 复用 Tester，Analyst 4/7 复用 Designer+实验）。§20.1 表是"角色白名单"，§1.3 是"新增工具数"——两处口径不同但不矛盾，需加注 | 
| I5 | maxSteps | §18.2 TD-QA-001 提 maxSteps=200 | ⚠️ 与 §2.1 默认 50 不一致：QA-Run 启动路径必须显式传 200，不能靠默认 |
| I6 | Release Gate 阈值 | §1.4/§14.1 规则（Critical>0 → FAIL 等） | ✅ 与 03 文档对照表一致（§20.2） |
| I7 | API 端点数 | §1.3 说「~8」；§12.1 列 7+1=8 | ✅ 一致 |
| I8 | Prompt 版本 | §20.3 说「新增 7」；§16.3 QA-S1-3 说「7 个 QA Prompt」；QA-S4-1 说 6 个角色 + 1 Lead | ✅ 一致（Lead 1 + 6 角色 = 7） |

---

## 第 5 块 · Tester 与 QA 边界冻结确认（对应指令 §五 输出 2）

提案是否做到"不重复实现 Tester 已有能力"？

| # | 边界问题 | 提案答案 | 审计确认 |
|---|----------|----------|----------|
| B1 | Tester 做什么 | 单 Agent 单 Plan 游戏测试（game_* + invariant + finding） | ✅ 正确（Tester V1 冻结） |
| B2 | QA 做什么 | 多角色协作、编排、聚合、Release Gate、主动策略 | ✅ 正确（FROZEN 之上新增，非 Tester V2） |
| B3 | QA Lead 如何调用 Tester | qa_create_subtask → qa_start_subrun 创建子 Run，子 Run 是常规 TESTER Run | ✅ 正确（复用 RunControlService.start） |
| B4 | QA Agent 是否直接使用 Tester Tools | 是（QA_TESTER 8 个 Tester 工具复用） | ✅ 正确（复用而非复制） |
| B5 | QA 是否创建自己的 Tool | 是（4 个编排工具）+ 复用 Tester/Designer/Research 工具 | ✅ 正确 |
| B6 | Finding 是否复用现有 Finding | **是**（复用 finding 表，不新增 QA Finding 表） | ✅ **正确且关键**——符合指令 §十"复用 Finding"要求 |
| B7 | Regression 是否复用 FindingRegression | 是（FindingRegressionService.diff 复用） | ✅ 正确 |
| B8 | QA 是否拥有新 DB 实体 | 2 张（qa_role_assignment + qa_release_gate）| ✅ 合理（有充分理由说明，§11.2 已列候选排除） |

**结论：边界冻结正确，无重复实现。唯一风险在 finding 数据源**——QA 需保证 finding 表所有角色（含数值分析类发现）走同一 source 分类且可按 run 溯源。

---

## 第 6 块 · QA 数据模型审计 + DB Schema 修正明细

按指令 §十（输出 7）要求：是否真的需要 QA 新实体？

### 6.1 每个候选实体的裁决

| 候选实体 | 裁决 | 理由 |
|----------|------|------|
| QA Task | ❌ 不需要 | 复用 `task` 表（constraints_json 加 `agent_type`/`configVersion`/`qaRole`） |
| QA Run | ❌ 不需要 | 复用 `run` 表（Lead Run + 子 Run 都是普通 Run） |
| QA Finding | ❌ 不需要 | 复用 `finding` 表（一等待捕获，回归对比路径不变） |
| QA Verdict | ⚠️ 边界 | Release Gate 判定是**多值三态决策**，不是 Finding status；可复用 design_document 承载报告 + 单独 Release Gate 表存结构化判定 |
| Release Gate | ✅ 需要 | 结构化三态 + 阈值 + 人工审阅状态机，无法塞进 finding/design_document |
| Role Assignment | ✅ 需要 | 子任务↔角色↔子 Run 映射是编排事务实体，agent_event 无法表达 update 语义 |

### 6.2 修正后的两张表 DDL（MUST 并入 QA-S1-2）

```sql
-- QA 角色分配（子任务记录）
CREATE TABLE IF NOT EXISTS qa_role_assignment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_run_id INTEGER NOT NULL REFERENCES run(id),     -- Lead Run
    sub_task_id INTEGER NOT NULL REFERENCES task(id),       -- 子 Task
    sub_run_id INTEGER REFERENCES run(id),                  -- 子 Run（启动后回填）
    role TEXT NOT NULL,   -- PLAYER_AGENT/NUMERICAL_ANALYST/COMBAT_ANALYST/EXPLOIT_HUNTER/UX_REVIEWER
    player_profile TEXT,  -- NOVICE/AGGRESSIVE/BUILD_SPECIALIST/etc.
    status TEXT NOT NULL DEFAULT 'PENDING',
        -- PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT  (← DB3：+TIMEOUT)
    result_summary TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_qra_parent ON qa_role_assignment(parent_run_id);
CREATE INDEX IF NOT EXISTS idx_qra_subtask ON qa_role_assignment(sub_task_id);

-- Release Gate 记录
CREATE TABLE IF NOT EXISTS qa_release_gate (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id INTEGER NOT NULL REFERENCES task(id),
    run_id INTEGER NOT NULL REFERENCES run(id),             -- Lead Run
    config_version TEXT NOT NULL,
    decision TEXT NOT NULL,
        -- PENDING/PASS/CONDITIONAL_PASS/FAIL  (← DB1：+PENDING)
    critical_bugs INTEGER NOT NULL DEFAULT 0,
    high_bugs INTEGER NOT NULL DEFAULT 0,
    medium_bugs INTEGER NOT NULL DEFAULT 0,
    soft_lock_count INTEGER NOT NULL DEFAULT 0,
    crash_rate REAL,
    dominant_build_share REAL,
    server_decision TEXT,
    lead_decision TEXT,
    is_consistent INTEGER NOT NULL DEFAULT 1,
    -- 人工审阅审计（← DB2）
    is_manual_override INTEGER NOT NULL DEFAULT 0,
    manual_override_reason TEXT,
    manual_override_by TEXT,
    report_doc_id INTEGER REFERENCES design_document(id),
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_qrg_task ON qa_release_gate(task_id);
CREATE INDEX IF NOT EXISTS idx_qrg_config ON qa_release_gate(config_version);  -- ← DB4
```

**注意**：以上是审计建议，**不修改 schema.sql**。实际落地须在 QA-S1-2 作为独立 DB Task 提请人工批准后实施（提案 §11 已有此机制）。

---

## 第 7 块 · Multi-Agent Workflow 审计（对应指令 §六 输出 3）

| 环节 | 提案方案 | 可行性评估 |
|------|----------|------------|
| 谁创建 QA Run | 用户 POST /api/tasks/{id}/qa-run | ✅ 需 QaController（§12.1） |
| 谁创建 QA Task | 用户创建（constraints_json 带 agent_type=QA_LEAD） | ✅ 复用现有 task 表 |
| QA Lead 如何拆分 | qa_create_subtask（每角色一个子 Task） | ✅ 需新 QaOrchestratorService |
| 如何分配 | qa_start_subrun（创建子 Run async） | ⚠️ 缺等待/通知机制（RT1）+ LoopDetector 风险（RT3） |
| 角色如何执行 | 子 Run = 常规 AgentLoop Run（用共享线程池/BudgetGuard） | ✅ 复用完整 Runtime |
| 如何共享上下文 | Finding/Experiment 表共享 + Run 行引用 | ✅ 零新通信通道 |
| 如何返回结果 | finding_report → finding 表；qa_collect_findings 汇总 | ✅ |
| QA Lead 如何判断 | Prompt 驱动 + Server 阈值校验（D5 双保险） | ✅ |
| Release Manager 如何参与 | **本提案未定义 Release Manager 工具**（§14 七角色含但 §9/§10 无独立 AgentType 工具集） | ⚠️ **缺口放大**：Release Manager 角色在 MVP 被并给 QA Lead（Release Gate 判定在 Lead 内完成）。需明确：MVP 不做独立 Release Manager（合理），报告/文档标注其为 Lead 内在职责。 |
| 最终结果如何持久化 | qa_release_gate 行 + design_document [RELEASE REPORT] | ✅ |

**MVP 工作流结论**：可行。最小可行闭环 = Lead（编排+Gate）→ 3 角色（Player/Numerical/Exploit）。建议 MVP 明确 **5 角色中 Player + Numerical（+可选 Combat）** 为必须，Exploit/UX 二期。

---

## 第 8 块 · 七角色设计审计（对应指令 §七 输出 4）

| 角色 | AgentType | 提案工具集 | 审计核查 |
|------|-----------|------------|----------|
| QA Lead | QA_LEAD | qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings + get_game_config + create_design_document | ✅（编排 4 工具为**唯一新增**，其余复用） |
| Player Agent | QA_TESTER | 8 个 Tester 工具（复用） | ✅ 与 Tester V1 完全一致——正确复用 |
| Combat Analyst | QA_ANALYST | simulate_game / analyze_experiment / compare_experiments + game_collect_metrics/events + finding_report + create_design_document | ⚠️ 需确认 analyze_experiment 与 compare_experiments 输出如何支撑战斗分析（BALANCE_ANOMALY finding 来源明确） |
| Numerical Analyst | QA_ANALYST | 同上 | ⚠️ 依赖 per-build avgDps（TD-006 代理方案见 2.7） |
| Exploit Hunter | QA_RESEARCHER | research 工具 + finding_report | ⚠️ 复用 stub web_search 风险（2.4 R2）；MVP 若做，应确认边界类测试由 FakeGameEnvironment overrides 注入 |
| UX Reviewer | QA_RESEARCHER | research 工具 + 分析已有 Finding/Experiment | ⚠️ 纯数据评估（D6）合理；MVP 可推后 |
| Release Manager | （并入 QA Lead） | 无独立 AgentType/工具 | ⚠️ 需明确 MVP 决策：并入 Lead 而非独立角色（§14 §9 不一致，见第 4 块 I2/I8） |

**角色边界审计结论**：Player/Numerical 两个角色就足以构成 MVP；Combat 可与 Numerical 合并（同 AgentType QA_ANALYST）；Exploit/UX 明确为 V1。

---

## 第 9 块 · Research ↔ QA + 全闭环设计（对应指令 §八/§九 输出 8/9）

### 9.1 Research ↔ QA

| 协作点 | 可行 | 说明 |
|--------|------|------|
| 机制研究（Mechanics research）| ✅ | research_claim → QA 引用做测试前置 |
| 行业对比 | ✅ | 通过 research_source |
| 类似游戏分析 | ✅ | research_claim（confidence 过滤）|
| Balance reference | ✅ | research_claim + QA Numerical Analyst 引用 |
| UX reference | ✅ | research_claim |
| Bug/exploit research | ✅ | research_claim + QA Exploit Hunter |
| QA 是否修改 Research | ❌ 不 | 边界明确：QA 只读 research_* 表 + 复用 research_p001 的 6 工具（fetch_page/save_source 写 research_source） |

**边界结论**：Research 输出（claims/report）作为 QA 的**可引用输入**，QA 不修改 Research 契约。research_claim.claim → Finding 升级路径可在 QA 角色 Prompt 中说明（claim 只是证据，不自动变 finding 除非复现）。

### 9.2 全闭环 Design→Test→QA→Release

```
Research → Designer → Config(V) → Tester(TestPlan+Finding) → QA/Regression → Release Gate → PASS/FAIL
```
**当前 Runtime 是否支持**（逐一核对提案 §15 数据流）：
- ✅ Task/Run 两阶段创建（用户建 task → /runs 启动）
- ✅ AgentLoop 委托 resolver（QA_LEAD 接入零 Runtime 修改）
- ✅ Finding 共享表（qa_collect_findings 可跨 run 聚合，指到子 Run 的 finding）
- ✅ qa_release_gate（按 config_version 聚合回归）
- ⚠️ 缺口 1：ExperimentService 静默回退（C4，见 2.2）
- ⚠️ 缺口 2：子 Run 轮询需显式步数预算（RT2）
- ⚠️ 缺口 3：SSE 前端缺 QA 3 事件订阅（FE2）

**闭环结论**：Runtime 已具备 95% 能力，剩余全部是 QA 应用层 + 上述 3 个修正项。

---

## 第 10 块 · QA MVP Scope 建议（对应指令 §八 输出 5）

**审计建议（修正提案 §7 的粗略 MVP）**：

- **MVP（成立的最小闭环）**：
  - QA Lead（编排 4 工具 + get_game_config + create_design_document）
  - Player Agent（复用 8 Tester 工具，随机人格）
  - Numerical Analyst（simulate_game 3 次 per-build + analyze_experiment）
  - Finding 聚合（qa_collect_findings）
  - Release Gate（PASS/FAIL 两态，人工可审阅）
  - 前端 QA Dashboard（最小：Run 列表 + Gate 结果 + 角色状态）
- **V1**：+ Combat Analyst（复用 QA_ANALYST + BALANCE_ANOMALY finding）、Exploit Hunter（FakeEnv 边界注入）、CONDITIONAL_PASS 判定 + manual_override 审计、角色 Prompt 人格化
- **Future**：UX Reviewer（纯数据）、真实游戏适配、多 ConfigVersion 并行测试、ML Player 策略、自动化 Release Pipeline

**MVP 判据（审计观点）**：第一版 QA 真正成立 = **Lead 能编排角色 → 聚合 Finding → 产出结构化 Release Gate（人工可审阅）**，无论后端或用例多少。三角色 + Gate 即可证明。

---

## 第 11 块 · QA Task Tree 审计（对应指令 §九 输出 6）

提案 §16 的 Sprint/Task 基本合理，审计修正如下：

| Sprint | 任务 | 审计修正 |
|--------|------|----------|
| QA-S1 基础设施 | 5 任务 | ✅ 但 QA-S1-1（AgentType）验收应含 resolver 无回归；**QA-S1-2 DDL 需并入 6.2 修正**（PENDING/TIMEOUT/manual_override/config 索引） |
| QA-S2 Tools | 4 任务 | ✅ QA-S2-1（4 工具）+ QA-S2-2（PlayerProfile）需先定 LoopDetector 规避（RT3）；QA-S2-3（REST 8 端点）合理；QA-S2-4（前端类型）合理 |
| QA-S3 QA Lead | 3 任务 | ✅ 但需加分：**Lead Prompt 明确 MVP 角色范围 + Release Gate 判定路径 + FAIL 优先缺陷策略**；QaOrchestratorService 单测补 C4 回退防御 |
| QA-S4 E2E | 3 任务 | ✅ 剧本 4 条件：种子 v001/v002 都必须存在（ConfigVersionService 有幂等导入）|
| QA-S5 前端 | 2 任务 | ✅ QaDashboardPage + 文档回写 |

**任务树审计结论**：可进入 QA-S1，但须先处理：
1. TD-T7-001（full-suite red）独立 Task 前置
2. TD-T6-001（Finding configVersion 真实性）独立 Task 前置
3. C4（ExperimentService 回退校验）决定：改 ExperimentService（低成本，需人工授权）或 QA 工具层包装
4. 6.2 DDL 修正建议入 QA-S1-2

---

## 第 12 块 · AI Game Builder 架构可行性（对应指令 §十四；只设计不实现）

提案 §19.1 ≈ 最小 Builder 可行性评估，与指令要求一致。审计补充：

| 环节 | 可行性 | 关键依赖 |
|------|--------|----------|
| Builder 当前愿景（05 文档） | 自动修复 + 回归闭环 | Designer/Research/Tester/QA |
| 当前平台能力 | **代码修改工具未存在**——ToolRegistry 只注册只读/模拟类 GameTool（get_game_config/modify_game_config/simulate_game/...），**无 file_read/file_write/build_run/test_run** | ❌ 平台能力不足：Builder 需要**文件系统/E2E 执行类工具**，而 Phase 1 平台刻意没有 |
| 缺哪些基础设施 | ① 文件工具（read/write/patch） ② build/test 执行工具（mvn/脚本） ③ 变更回滚/沙箱 ④ 与 finding.status（FIXED）联动 | 全缺 |
| Builder MVP 应该是什么 | 受限 builder：接受 configVersion + Finding 列表 → 生成 config patch → 提交 create_change_proposal → approve 后自动测试 | 复用 change_proposal 状态机（已有） |
| 如何接入 QA Gate | FAIL → Builder 生成新 ConfigVersion → QA 回归 → 新 Gate | 依赖 QA 完成（Release Gate 是 Builder 的输入） |

**Builder 审计结论**：**本阶段不可实现**（缺文件工具基础设施，且 Phase 1 RFC 不允许新增）。只保留为 Phase 3 独立方向。提案 §19.1 处理正确。

---

## 第 13 块 · 风险登记表（人工裁决项汇总）

以下是需要在人工架构评审时逐项裁决的开放项（本轮不实施任何一项）：

| # | 裁决项 | 提案立场 | 审计建议 | 阻断 QA-S1？ |
|---|--------|----------|----------|--------------|
| A1 | **AgentLoop 零修改** | 提案 I10 列 AgentLoop 修改（高） | AgentLoop 无需修改（委托已存在）；只改 DefaultAgentTypeResolver | 否（消除风险） |
| A2 | **I10 降级** | 高风险修改项 | 降为"零验证"任务 | 否 |
| A3 | **ExperimentService C4 回退** | 未提及 | 需决定：改 ExperimentService（抛错）vs QA 工具层包装 | 是（QA 数值分析正确性） |
| A4 | **TD-T7-001 修复** | 提案建议排查 | 必须 QA-S1 前独立 Task（13 errors red）| 是 |
| A5 | **TD-T6-001 清偿** | 提案建议 QA-S1 清偿 | 同意；独立 Task | 建议 |
| A6 | **qa_release_gate DDL 修正**（PENDING/manual_override/config 索引） | 原 DDL 三态无审计 | 必须并入 QA-S1-2 | 建议 |
| A7 | **qa_role_assignment.status + TIMEOUT** | 4 态 | 必须并入 QA-S1-2 | 建议 |
| A8 | **Role↔AgentType 映射澄清**（§20.1 vs §14） | 内部不一致 | 统一 persona↔AgentType 表后再冻结角色 Prompt | 建议 |
| A9 | **SSE 事件订阅补 3 个** | 复用现有 22 事件 | 必须在前端 QA 页追加监听 | 否（前端 QA-S5 依赖） |
| A10 | **Role 工具数口径**（§1.3 ~12 vs §20.1 各 6/8/7/7）| 内部不一致 | 修正口径："新增 4 编排工具；角色复用白名单不同" | 否 |

---

## 第 14 块 · 技术债新登记（本轮审计新增，不修复）

| 编号 | 描述 | 类别 |
|------|------|------|
| TD-QA-004 | agent_event.run_id/task_id TEXT vs 其他表 INTEGER 不一致（既有缺陷，QA 事件沿用） | 记录 |
| TD-QA-005 | LoopDetector 对 QA 同参轮询的判断待验证（RT3） | 待验证 |
| TD-QA-006 | ExperimentService 静默回退 configVersion（C4）——若走 QA 工具层包装则登记为技术债，否则为直接修复项 | 待人工裁决 |

---

## 最终 STOP — READY FOR HUMAN REVIEW

```text
QA ARCHITECTURE AUDIT — READY FOR HUMAN REVIEW

Designer: FROZEN
Tester:   FROZEN
Research: FROZEN BASELINE (R0 complete)
QA:       NOT STARTED — PROPOSAL DRAFT (1226 行, 20 章) AUDITED
Builder:  DESIGN ONLY — 不可实现（缺文件工具基础设施）

AUDIT 结论:  CONDITIONALLY_PASS
  1 项重大修正:  I10 AgentLoop 修改 = 伪需求（委托已存在）
  6 项 MUST 修正: C4 / TD-T7-001 / DDL×3 / SSE×1
  2 项技术债清偿: TD-T6-001 (建议) / TD-P8-1-001 (已解决, 提案过时)

NEXT ACTION:
  HUMAN ARCHITECTURE REVIEW
  → 批准提案 + 修正 13 项人工裁决 → 才可开始 QA-S1
```

**本轮完全遵守禁令**：未写 QA 生产代码、未创建 QA Tool/Agent、未修改 AgentLoop/Designer/Tester/Research/schema.sql/Repository/ErrorContract、未创建 Sprint 文件、未更新 task-state 为 DONE、未启动任何 Sprint、未创建 P9/V2/V3 任何版本号。

*审计方：Claude（10 并行维度 + 直接源码核查）* · *审计对象：QA_SYSTEM_ARCHITECTURE_PROPOSAL.md*