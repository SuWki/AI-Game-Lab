# QA Architecture Freeze Candidate（AF-001）

> 2026-11-12 范围与状态说明：本文件保留对应阶段的设计/决策与准入条件，不能以其中历史表数、无编排、无回放或预算成功描述判断当前实现。当前模块入口见 [QA 设计](projects/04_AI_Game_Agent_Team/README.md)，共享 Runtime 的已授权变更见 [ADR](decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)。新生产评估以模块演进设计为准，旧 Gate 指标不因此重解释。

> 状态：**ARCHITECTURE READY FOR FREEZE**（候选，等待人工批准）
> 依据：`docs/QA_SYSTEM_ARCHITECTURE_DECISION_REVIEW.md`（17 节 Human Review，A1-A10 全部 APPROVE）＋
> `docs/QA_ARCHITECTURE_AUDIT_REPORT.md`（六维审计，总体 CONDITIONALLY_PASS）
> 日期：2026-09-03 · 冻结基线快照：Designer P0-P8 = FROZEN / Tester T1-T7 = FROZEN / Research R0 = FROZEN / QA = NOT STARTED / Builder = DESIGN ONLY
> 本文档是 **QA 架构冻结的唯一裁决事实源**；与提案/审计不一致处以本文档为准（工作流 §41：具体规范 > 摘要，回写修正方须在报告中注明）。

---

## 0. 冻结边界（重申）

**绝对冻结（不得触碰，除非人工另行解除）**：Designer、Tester、Research、AgentLoop、Repository、Error Contract。

尤其：
- `AgentLoop.java` —— 零修改（`AgentLoop.java:242-250` 委托 `agentTypeResolver.resolve` → `selectSystemPrompt`/`selectToolSpecs`；QA AgentType 接入只扩展 `DefaultAgentTypeResolver`）。
- `DefaultAgentTypeResolver.java` —— QA 的唯一扩展着陆点（`systemPrompt()` 穷举 switch L120-131、`toolWhitelist()` 穷举 switch L141-147、`resolveFromConstraints` 错误消息 L179 需含 QA 允许值）。
- `schema.sql`（14 表）—— **不修改**；两张 QA 新表（qa_role_assignment / qa_release_gate）作为 schema migration 由 **QA-S1-2** 提报人工批准后执行（本轮禁止执行）。
- 测试夹具约定 —— QA 测试**不得继承** `AbstractRepositoryTest`，**不得**触碰其共享 static 临时 DB（TD-T7-001，见 PF-002）。

**本轮不做（NO PRODUCTION CODE / NO IMPLEMENTATION）**：新建/修改任何生产 Java、AgentLoop、Designer、Tester、Research、Builder、schema migration 执行、Sprint 执行、task-state 完成度更新。仅允许架构文档 / Task Tree / Decision records / 依赖分析 / 验收标准 / Risk register。

---

# A. QA Architecture Freeze Candidate（20 节）

## 1. Scope

QA 系统第一里程碑（QA-S1）范围：
1. AgentType 扩展 4 值（QA_LEAD / QA_TESTER / QA_ANALYST / QA_RESEARCHER）—— 仅 `DefaultAgentTypeResolver`。
2. 8 个角色 Prompt 种子导入（p001 起）。
3. 4 个 QA 编排工具（qa_create_subtask / qa_start_subrun / qa_check_subrun_status / qa_collect_findings）。
4. 两张新表（qa_role_assignment 6 态 / qa_release_gate）+ 索引 —— QA-S1-2 提请人工批准后落地。
5. Sequential Orchestration 运行时（QaOrchestratorService + QaReleaseGateService）。
6. 复用 existing Finding / finding 表、TestPlan、Experiment、configVersion 链路；不新建 QA-Finding 表。
7. QA-S1 验收闭环：8 角色 Contract Matrix 五处一致（§14/§20.1/Prompt/Resolver/Task Tree）经测试断言。

## 2. Non-goals（明确排除，防 scope creep）

- 不做 Runner / Run 级超时（不改 AgentLoop.timeoutMs，保持 -1）。MVP 采用**编排层 deadline/poll**。
- 不做 PlayerProfile 枚举扩展 —— 3 个人格是**纯 Prompt 驱动**（NOVICE/AGGRESSIVE/BUILD_SPECIALIST），不触碰 `GameLaunchSpec`/`FakeGameEnvironment`；V2 的 CONSERVATIVE/EXPLOIT 人格不在本里程碑。
- 不加新 SSE 事件（useGlobalSse=26 与 `AgentEventType.java`=26 已 1:1 对齐）；`QA_*` 3 事件延后 QA-S5。
- 不做截图/CV 类 UX 评测 —— UX Reviewer 基于 Finding/Experiment/Claim 纯文本评估。
- 不做 Release Manager 独立 Agent —— Merge 进 QA Lead 职责（audit B 缺口的裁决）。
- 不做路由权限体系 —— 沿用既有 permissions 列（EXECUTE/READ + requiresApproval）与 Error Contract。
- 不复制 Finding 去重 —— 复用 `FindingRegressionService` / `FindingFingerprintService`（T7）与 finding 表 `source` 分类。
- 不做前端 QA 页 —— 前端归属 QA-S5（MVP 复用现有 /findings、/test-plans 页 + REST refetch 驱动）。

## 3. Agent Roles（冻结：8 角色 / 8 Prompt）

| # | Role | AgentType | Prompt 文件 | 定位 |
|---|---|---|---|---|
| 1 | QA Lead | QA_LEAD | `qa_lead_p001.md` | 编排（create subtask → start subrun → poll → collect）、Gate 判定（lead_decision）、发布 Release Report |
| 2 | Player Agent · NOVICE | QA_TESTER | `qa_player_novice_p001.md` | 保守操作，找常规功能缺陷 |
| 3 | Player Agent · AGGRESSIVE | QA_TESTER | `qa_player_aggressive_p001.md` | 激进/压力操作 |
| 4 | Player Agent · BUILD_SPECIALIST | QA_TESTER | `qa_player_build_specialist_p001.md` | 专精单项 Build 深度挖掘 |
| 5 | Combat Analyst | QA_ANALYST | `qa_combat_analyst_p001.md` | 战斗数值/平衡分析（ROLE_FOCUS=COMBAT） |
| 6 | Numerical Analyst | QA_ANALYST | `qa_numerical_analyst_p001.md` | 数值/统计/实验分析（ROLE_FOCUS=NUMERICAL） |
| 7 | Exploit Hunter | QA_TESTER | `qa_exploit_hunter_p001.md` | 漏洞/伪造/负值/NaN/卡死挖掘 |
| 8 | UX Reviewer | QA_RESEARCHER | `qa_ux_reviewer_p001.md` | 基于 Finding+Experiment 评估 UX 问题 |

> **Persona↔AgentType 定稿（A8 方案 A）**：Exploit Hunter → **QA_TESTER**；UX Reviewer → **QA_RESEARCHER**。
> 全部 8 个角色签名了 Contract Matrix（见 B）。人格差异只经 Prompt，`FakeGameEnvironment` 不感知。

## 4. AgentType Mapping（冻结）

- `AgentType` 枚举：新增 4 值 → `AGENTTYPE_COUNT = 7`（DESIGNER/TESTER/RESEARCH + QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER）。
- 唯一改动文件：`runtime/AgentType.java` ＋ `runtime/DefaultAgentTypeResolver.java`（systemPrompt switch、toolWhitelist switch、resolveFromConstraints 允许值消息）。
- 保证：穷举 switch（无 default）在实现期编译失败即暴露遗漏的工具/提示词着陆点 —— QA 不会静默漏配。
- `resolveFromConstraints` 的 `AgentType.valueOf`（L174）自动接受新枚举值；L179 错误串"允许值: DESIGNER, TESTER, RESEARCH"扩展为含 4 个 QA 值。

## 5. Prompt Architecture（冻结）

- 8 个 Prompt 种子（p001 前缀，`backend/src/main/resources/prompts/qa_*.md`），文件名清单见 §3。
- 每个 Prompt 结构对齐既有种子约定（角色定义 + 工作循环 + 铁律 + ROLE_FOCUS），**参考但不复制** tester_p001 / designer_p001 冻结文本。
- 导入：新增 `PromptService` 导入方法（`importQaSeedsIfAbsent()` 幂等模式，仿 `importTesterSeedIfAbsent`）；保持 qa_* 版本 **inactive**，唯一 active 仍为 designer_p001。
- QA_TESTER 复用 tester_p001 骨架改写（人格指令）；QA_RESEARCHER 融合 research 能力 + UX 评估指令。
- 验收计数口径：**Prompt 总数 8**（qa_lead + 3 player + combat + numerical + exploit + ux）。旧文档 7/6 均为 stale（见 §19）。

## 6. Tool Architecture（冻结）

**已注册工具 23 个**（工具清单证据：`ToolRegistry` 构造期收集全部 GameTool Bean）：
- DESIGNER 8、TESTER 8、RESEARCH 6，另 `get_research_context` **REGISTERED_ONLY**（不在任何 AgentType 白名单；提案 §2.3 的"22"少算 1 个 —— 保持注册不删，仅登记口径修正）。

**4 个新编排工具（NEW/QA-specific）**，全部仅入 QA_LEAD 白名单：

| Tool | 权限 | 输入 | 输出 | Side effects | 错误语义 | 证据语义 |
|---|---|---|---|---|---|---|
| `qa_create_subtask` | EXECUTE·无审批 | {role, title, constraints} | subtask id(Long) | 建子 Task 行 + qa_role_assignment(status=PENDING) | role 非法/重复/缺 configVersion → 抛错 | assignment 行 + 事件落库 |
| `qa_start_subrun` | EXECUTE·无审批 | {subTaskId, playerProfile?} | subrun id + 初始状态 | 建子 Run（注入角色 Prompt+Tools）、回填 sub_run_id、PENDING→RUNNING | subTaskId 不存在/已启动 → 抛错；AgentType 映射失败 → 抛错 | run 行 + assignment.sub_run_id |
| `qa_check_subrun_status` | READ·无审批 | {subRunId} | status + currentStep + tokensUsed | 纯只读 | subRunId 不存在 → 抛错；子 Run FAILED 正常返回 status | 只读证据，供 Lead 轮询 |
| `qa_collect_findings` | READ·无审批 | {subTaskIds[]} | 汇总 Finding + Experiment + Research Claim | 纯只读 | 失败角色无 Finding 不抛错；Lead 汇总并在报告中注明失败角色 | 跨角色汇总作为 Gate 判定依据 |

**AgentType → Tool 白名单（A8 后冻结，处五处一致之一）**：
| AgentType | 工具 | 数 |
|---|---|---|
| QA_LEAD | qa_create_subtask, qa_start_subrun, qa_check_subrun_status, qa_collect_findings, get_game_config, create_design_document | **6** |
| QA_TESTER | test_plan_get, game_start, game_observe, game_act, game_collect_events, game_collect_metrics, finding_report, test_report_create, web_search, fetch_page | **10** |
| QA_ANALYST | simulate_game, analyze_experiment, compare_experiments, game_collect_metrics, game_collect_events, finding_report, create_design_document | **7** |
| QA_RESEARCHER | web_search, fetch_page, save_source, extract_claims, compare_sources, generate_report, finding_report, game_observe, game_collect_events, analyze_experiment | **10** |

> 证据：QA_TESTER 的 10 工具覆盖 §14.5 Exploit Hunter 全部 8 工具；QA_RESEARCHER 的 10 工具覆盖 §14.6 UX Reviewer 全部 6 工具 —— 方案 A 代码级可行。
> 注：白名单 = AgentType 级并集；角色级经 Prompt ROLE_FOCUS/子集说明子集化（写入角色 Prompt 章节），避免 §8.3 解释成本。

## 7. Orchestration（冻结：MVP = Sequential）

**执行顺序（只此一种）**：`Lead → Player1(NOVICE) → Player2(AGGRESSIVE) → Player3(BUILD_SPECIALIST) → Combat → Numerical → Exploit → UX → Aggregate → Release Gate`。

**QaOrchestratorService 角色**：
1. Lead 调 `qa_create_subtask` 为 7 个子任务（Player×3/Combat/Numerical/Exploit/UX）逐一建 assignment（PENDING）。
2. 依序对每角色 `qa_start_subrun` 启动子 Run（RUNNING），注入该角色 Prompt+Tools。
3. Lead 对该子 Run 轮询 `qa_check_subrun_status`，直至子 Run 终态（COMPLETED/FAILED/TIMEOUT/CANCELLED）或到 deadline；轮询参数带递增 poll_seq 防 LoopDetector 假阳性（WINDOW=3 精确匹配，见 §9）。
4. 记录 assignment status；进入下一角色。任一角色失败**不中断编排**，标记后继续（部分结果语义）。
5. 全部完成（或含失败角色）后 `qa_collect_findings` 聚合跨角色 Finding/Experiment/Claim。
6. `QaReleaseGateService` 独立计算 `automatic_result`（读 finding 统计 + 子 Run 终态，与 Lead Run 状态正交）。
7. Lead 提交 `lead_decision` 写入 gate 行；人工审阅后 `final_result` + override（见 §13）。

**不并行**：MVP 不做并发子 Run（Run 无父 Run 关联、Hikari 单连接、LoopDetector 干扰面最小化、deadline 单一来源）。线程级并发已存在于既有 cached pool，但编排级**无 join/通知**（audit §2.5），sequential 是唯一可行且可判定的形态。

## 8. Run/SubRun Lifecycle（冻结）

- **子 Run 是普通 run 行**（7 态：PENDING/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED/TIMEOUT），由 `qa_start_subrun` 创建。
- **父子关联不放在 run 表**（run 无 parent_run_id 字段，不扩建 run 表）→ 关联在 `qa_role_assignment`（parent_run_id + sub_run_id）。
- assignment.status 6 态（PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED，A7 裁决）。
- **同步语义（A7 关键）**：Gate 与聚合**一律以 run 行最新 status 为准**；assignment.status 仅是编排视图（防"assignment 永远 RUNNING"假挂起）。
- 子 Run 完成后回写 assignment（COMPLETED/FAILED/TIMEOUT）与 result_summary。回写由 QaOrchestratorService 在 poll 观察终态时执行（不依赖被动通知）。
- Lead 子任务：Parent Run 是 Lead run；subtask 复用现有 task 表。

## 9. Timeout（冻结：编排层 deadline，禁止 Run 级）

**约束**：`AgentLoop.timeoutMs = -1`（L52 一旦启用即触碰 AgentLoop，本轮禁止）→ MVP 不假设 AgentLoop 提供超时。

**QaOrchestratorService 编排超时（MVP 自带保护）**：

| 要素 | 定稿 |
|---|---|
| WHO 设置 deadline | QaOrchestratorService 在 `qa_start_subrun` 时配置（每角色 wallClockBudget，默认建议 300s，可覆盖） |
| WHO 检测 timeout | QaOrchestratorService poll 循环：`now - startedAt > deadline` ⇒ 判定超时 |
| WHAT 更新 assignment | `qa_role_assignment.status → TIMEOUT`（DB3 6 态未变） |
| 子 Run 自身状态 | **不强制改**（AgentLoop 冻结）：run 行保持其最后观察到的状态；编排视图以 assignment=TIMEOUT 表达 |
| Parent Run | 不因子超时自动 PASS/FAIL —— Lead 继续编排，产生 FAIL 或部分结果（见 §10） |
| Release Gate | timeout 角色视为失败角色参与聚合；**绝不**因单一子超时给出 PASS（见 §13 "Child timeout ≠ Parent PASS"） |

**LoopDetector 防误杀**：`qa_check_subrun_status` 同参连续调用可能触发假阳性（WINDOW=3 exact-string）。MVP 采用 **poll_seq 参数递增**（每次轮询携带不同 pollSeq）天然规避；同时 sequential 编排逐角色参数不同。

## 10. Failure semantics（冻结）

| 事件 | 语义 |
|---|---|
| 子 Run FAILED | 该角色无决定权 Finding；assignment=FAILED；编排继续（不中断） |
| 子 Run TIMEOUT | 视作失败角色；assignment=TIMEOUT；**禁止自动 PASS 传导** |
| 子 Run CANCELLED | assignment=CANCELLED；聚合按既有 finding 统计，标记该角色 cancelled |
| 部分结果（部分角色失败） | crowd_findings 仅汇总有产出的角色；Lead 报告注明失败角色清单 |
| 工具层校验失败 | 复用 AbstractTesterTool 异常翻译模式（非 critical → tool_call=FAILED、Run 继续），QA 编排工具不新建平行契约（§6 错误语义列） |
| 致命错误（编排本体异常） | 编排状态以 gate 行 PENDING + 人工介入为准；不伪造 PASS |

## 11. Evidence semantics（冻结）

- **每条可审计事实落在证据源**：子 Run 的 run 行、tool_call、step、finding、experiment、research_source/claim、qa_role_assignment、qa_release_gate。
- assignment.result_summary 收子 Run 完成摘要；report_doc_id 指向 Release Report（复用 design_document）。
- findings 溯源：`finding.source` 不扩展角色枚举（审计 DB5）→ 角色溯源走 `finding.run_id → run`（run 行 + assignment.sub_run_id 双向解析）。
- Gate 判定基于 evidence（自动阈值）＋ Lead 文字判定（lead_decision）双保险；二者一致性记录于 is_consistent。

## 12. Finding（冻结）

- 复用 `finding` 表 + `FindingService` + `finding_report` 工具，**不新建 QA-Finding 表**。
- QA 产出 Finding 的 source：INVARIANT/LLM/MANUAL 既有白名单（不扩展，DB5）。
- 回归对比：复用 `FindingRegressionService.diff`（T7），不对 QA 数据做特殊路径。
- Finding.configVersion 必填（既有契约）；真实性校验由 **PF-003/TD-T6-001** 补（Preflight，前置独立 Task）。

## 13. Release Gate（冻结 + 字段定稿）

### 13.1 语义
- 状态机：`PENDING → PASS / CONDITIONAL_PASS / FAIL`（起始 PENDING，可含 automatic_result 已算）。
- **automatic_result**：`QaReleaseGateService` 服务端自动判定（阈值驱动），可 ∈ {PASS, CONDITIONAL_PASS, FAIL, **INCONCLUSIVE**}。缺少足够 evidence/关键角色失败 → INCONCLUSIVE，不进 PASS。
- **final_result**：人工审阅/确认后的权威最终值；未审阅保持 PENDING。
- **manual_override** 及其 reason/by/at：人工推翻自动判定时置 1 并留痕（DB2）。
- **解耦**：Gate 判定与 Lead Run 的 RUNNING/COMPLETED **正交** —— Lead Run 即便 COMPLETED，Gate 仍可为 FAIL/PENDING（防"Run 完成=过 Gate"假等价）。
- **红线（Refrain 特别禁用）**：`Child timeout → Parent PASS` 任何路径均不被实现；子超时/失败接入门控判定的只有"失败/缺失 evidence"一侧。

### 13.2 最终字段（指令字段 × 审计字段 合并定稿 —— 权威）

> 指令要求含 automatic_result / final_result / status / manual_override / override_reason / override_by / override_at / config_version；
> 审计 §6.2 用 decision / is_manual_override / manual_override_reason / manual_override_by；提案 §11.1 用 decision(PASS/CONDITIONAL_PASS/FAIL) 3 态。
> **最终以本节为准重新整理**（DB1/DB2/DB4 并入），映射如下并落 QA-S1-2 DDL：

| 最终列 | 语义 | 来源映射 |
|---|---|---|
| `id` | PK | — |
| `task_id` | 被测 Task | proposal `task_id` |
| `run_id` | Lead Run | proposal `run_id` |
| `config_version` | 被测版本（**带索引**，DB4） | proposal `config_version` |
| `status` | 门控总状态（PENDING/PASS/CONDITIONAL_PASS/FAIL）｜DB1 +PENDING | ← audit `decision`（4 态） |
| `automatic_result` | 服务端自动判定（PASS/CONDITIONAL_PASS/FAIL/INCONCLUSIVE） | ← audit `server_decision` 扩展 INCONCLUSIVE |
| `lead_decision` | QA Lead LLM 判定 | 保留 audit `lead_decision`（证据） |
| `is_consistent` | auto vs lead 一致（审计） | 保留 audit `is_consistent` |
| `final_result` | 人工确认后的权威结果（PENDING/PASS/CONDITIONAL_PASS/FAIL）｜**新增** | —（指令字段） |
| `manual_override` | 1=人工覆盖自动判定 | ← audit `is_manual_override` |
| `override_reason` | 覆盖理由 | ← audit `manual_override_reason` |
| `override_by` | 覆盖人 | ← audit `manual_override_by` |
| `override_at` | 覆盖时间｜**新增**（审计原表无） | —（指令字段） |
| `critical_bugs / high_bugs / medium_bugs / soft_lock_count / crash_rate / dominant_build_share` | 自动阈值统计 | 保留 proposal/audit |
| `report_doc_id` | Release Report→design_document | proposal `report_doc_id` |
| `created_at` | 记录时间 | proposal `created_at` |

### 13.3 判定公式（MVP 阈值草案，QA-S3 可校准）
- FAIL：任一 `critical_bugs>0` 或 `high_bugs>threshold`（建议 ≥3）或 `soft_lock_count>0` 或 dominant_build_share 失衡；
- CONDITIONAL_PASS：仅 medium 以下问题且回填 lead_decision 建议；
- PASS：全部阈值通过且关键角色全部完成（Exploit/UX 至少注册并产出或明确 N-A 说明）；
- INCONCLUSIVE：关键 evidence 缺失（如 Numerical 角色 TIMEOUT）→ 不进 PASS。

## 14. Persistence（冻结：2 张新表，QA-S1-2 提报）

> 均以 **QA-S1-2（独立 DB Task）提请人工批准后执行**，本轮不落库。DDL 以审计 §6.2 修正稿为基底，按 §13.2/§8 字段定稿：

```sql
-- QA 角色分配（编排事务实体，6 态）
CREATE TABLE IF NOT EXISTS qa_role_assignment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_run_id INTEGER NOT NULL REFERENCES run(id),
    sub_task_id INTEGER NOT NULL REFERENCES task(id),
    sub_run_id INTEGER REFERENCES run(id),
    role TEXT NOT NULL,                -- PLAYER_AGENT/COMBAT_ANALYST/NUMERICAL_ANALYST/EXPLOIT_HUNTER/UX_REVIEWER
    player_profile TEXT,               -- NOVICE/AGGRESSIVE/BUILD_SPECIALIST
    status TEXT NOT NULL DEFAULT 'PENDING',
        -- PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED   （DB3 6 态）
    result_summary TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_qra_parent ON qa_role_assignment(parent_run_id);
CREATE INDEX IF NOT EXISTS idx_qra_subtask ON qa_role_assignment(sub_task_id);

-- Release Gate（字段按 §13.2 定稿）
CREATE TABLE IF NOT EXISTS qa_release_gate (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id INTEGER NOT NULL REFERENCES task(id),
    run_id INTEGER NOT NULL REFERENCES run(id),
    config_version TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',      -- PENDING/PASS/CONDITIONAL_PASS/FAIL
    automatic_result TEXT,                        -- PASS/CONDITIONAL_PASS/FAIL/INCONCLUSIVE
    lead_decision TEXT,
    is_consistent INTEGER NOT NULL DEFAULT 1,
    final_result TEXT,                            -- PENDING/PASS/CONDITIONAL_PASS/FAIL（人工）
    manual_override INTEGER NOT NULL DEFAULT 0,
    override_reason TEXT,
    override_by TEXT,
    override_at TEXT,
    critical_bugs INTEGER NOT NULL DEFAULT 0,
    high_bugs INTEGER NOT NULL DEFAULT 0,
    medium_bugs INTEGER NOT NULL DEFAULT 0,
    soft_lock_count INTEGER NOT NULL DEFAULT 0,
    crash_rate REAL,
    dominant_build_share REAL,
    report_doc_id INTEGER REFERENCES design_document(id),
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_qrg_task ON qa_release_gate(task_id);
CREATE INDEX IF NOT EXISTS idx_qrg_config ON qa_release_gate(config_version);  -- DB4
```

## 15. SSE（冻结：MVP 零新增事件）

- 现状验证：前端 `useGlobalSse.ts` = **26** 事件，后端 `AgentEventType.java` = **26** 值，1:1 对齐（含 4 design/6 tester/4 research…）。此前审计标 22 为 **stale**。
- MVP 复用现有 RUN_*/TOOL_*/FINDING_CREATED/TEST_REPORT_CREATED 事件驱动 QA 相关 REST refetch（仿 `useTesterEventSignal`，可加 `useQaEventSignal` 前端局部，不改后端 SSE）。
- `QA_*` 3 事件（QA_SUBTASK_CREATED 等）：**QA-S5** 前后端同加（随后 26→29）。
- Event 码序列：26→（QA-S5）29，不插入旧值中间。

## 16. Frontend（冻结）

- MVP **不新增前端页面**。复用现有 `/findings`、`/test-plans`、`/regression`（T7）与全局 SSE；QA 页属 QA-S5。
- 前端零后端 SSE 改动；如加 `useQaEventSignal` 仅前端局部（jsdom 防御性 early-return 沿用 T5 模式）。
- 不修改 `main.tsx` / `SseController`（审计 FE 修正：路由改动落在 `App.tsx`，且已存在，无需 QA 新增）。

## 17. Security / permission boundaries（冻结）

- 4 个编排工具权限：create/start = EXECUTE（无审批）；check/collect = READ（无审批）。对齐既有 orchestration 工具权限模型，无新权限体系。
- **仅 QA_LEAD 白名单持有 4 个编排工具**（Resolver toolWhitelist switch 保证）—— 非 Lead 角色绝不接触编排面。
- 子 Run 亦是普通 run 行 → 受既有 Run/Step/Tool 审计流覆盖。
- 错误契约统一 BusinessException(new ErrorCodes) + 包络；不扩展 ErrorCodes 枚举。

## 18. Test strategy（冻结）

- **QA 测试不得继承 AbstractRepositoryTest**（TD-T7-001；不共享 static temp DB）；采用**自包含 fixture/DB reset**（per-task 或 @DirtiesContext，避免 aplicar 13 FK 污染）。
- 断言面：`AgentTypeResolverTest` 穷举 switch 覆盖 7 AgentType（Prompt/Tools 非空）；`PromptService` 导入幂等；白名单 5 处一致（Contract Matrix 断言）；编排 sequential 顺序与 timeout 语义。
- 运行要求：JDK21+（系统 PATH 默认 JDK1.8，JAVA_HOME 指向 `~/.jdks/openjdk-24.0.1`）；`mvn -o`。
- 覆盖红线：不触碰 Designer/Tester/Research/现有 tester 测试夹具；full-suite 验证仅在 preflight 基线 CLEAN 后执行（PF-004）。

## 19. Technical debt boundary（转移，见 E）

## 20. Builder dependency（仅设计分析，见 F）

---

# B. QA Contract Matrix（五处一致 · 最终）

> 五处一致性 = §14 / §20.1 / Prompt 文件 / Resolver / Task Tree。以下为唯一裁决矩阵。

| Role | AgentType | Prompt 文件 | Whitelist Tools | 权限注释 |
|---|---|---|---|---|
| QA Lead | **QA_LEAD** | qa_lead_p001.md | `qa_create_subtask, qa_start_subrun, qa_check_subrun_status, qa_collect_findings, get_game_config, create_design_document`（6） | orchestration EXECUTE + READ；Gate 判定+Report 职责并入；isolated via Resolver QA_LEAD case；AgentLoop 零修改 |
| Player · NOVICE | **QA_TESTER** | qa_player_novice_p001.md | **10**（Tester 8 + web_search + fetch_page） | A8 方案 A；人格仅 Prompt |
| Player · AGGRESSIVE | **QA_TESTER** | qa_player_aggressive_p001.md | **10** 同上 | ROLE_FOCUS=AGGRESSIVE |
| Player · BUILD_SPECIALIST | **QA_TESTER** | qa_player_build_specialist_p001.md | **10** 同上 | ROLE_FOCUS=BUILD_SPECIALIST |
| Combat Analyst | **QA_ANALYST** | qa_combat_analyst_p001.md | `simulate_game, analyze_experiment, compare_experiments, game_collect_metrics, game_collect_events, finding_report, create_design_document`（7） | Designer/Tester 混合复用，ROLE_FOCUS=COMBAT |
| Numerical Analyst | **QA_ANALYST** | qa_numerical_analyst_p001.md | **7** 同上 | ROLE_FOCUS=NUMERICAL；配合 PF-001/C4 兜底 |
| Exploit Hunter | **QA_TESTER** | qa_exploit_hunter_p001.md | **10**（Tester 8 + web_search + fetch_page） | **A8 终裁：归 QA_TESTER**（非提案 §14.5/§9.2）；5 game 工具支撑激进操作 |
| UX Reviewer | **QA_RESEARCHER** | qa_ux_reviewer_p001.md | web_search, fetch_page, save_source, extract_claims, compare_sources, generate_report, finding_report, game_observe, game_collect_events, analyze_experiment（10） | Research 6 + finding_report + A8 补 3；纯文本评估 |

汇总：`AgentType 7` · `Prompt 8` · `New Orchestration Tool 4` · 白名单总量 6/10/7/10。

**proposal 五处不一致（须修正后冻结，见 §19 与 `QA_ARCHITECTURE_DECISION_REVIEW.md` §七）**：
1. §14.5 Exploit Hunter → QA_RESEARCHER（改为 QA_TESTER）
2. §9.2 Exploit 行 → QA_RESEARCHER（改为 QA_TESTER）
3. §8.3 白名单代码块：QA_TESTER 8→10（+web_search/fetch_page）、QA_RESEARCHER 7→10（+finding_report/game_observe/game_collect_events/analyze_experiment）
4. §20.1 命名模式 `qa_*_researcher_p001.md` → 按角色命名（exploit=qa_exploit_hunter_p001.md，归 QA_TESTER）；Tool List 6/8/7/7 → 6/10/7/10
5. §16.4 "角色 Prompt ×6" → ×7（非 Lead）；QA-S4-1 验收改 8 个 Prompt 均过 AgentTypeResolverTest

---

# E. Technical Debt Transition（阶段化）

> 规则：严格区分 OPEN / RESOLVED / CLEARANCE REQUIRED。QA 实施绝不因"清零"而改冻结代码。

| TD 编号 | 描述 | 状态 | 对 QA 的意义 | 处置 |
|---|---|---|---|---|
| TD-T6-001 | FindingService.report() 未校验 configVersion 真实存在 | **OPEN** | QA finding 可能指向不存在版本（契约 1 弱化） | 独立 Task（Preflight **PF-003**），QA-S1 **前置**；修复不得改冻结 Finding 契约（注入 ConfigVersionService 存在性校验，新增校验不破坏既有字段） |
| TD-T7-001 | Full-suite Spring 上下文/DB 状态污染（13 FK errors） | **OPEN** | QA 复用 TestPlanService/finding 链路会继承污染 | Preflight **PF-002**：QA 测试自包含 fixture，**不继承 AbstractRepositoryTest**；full-suite 健康在 PF-004 基线验证 |
| TD-P8-1-001 | Tester v001 fixture 未初始化（@PostConstruct） | **RESOLVED**（P8-1-3 修复） | v001 已可被 QA 链路引用 | 维持 RESOLVED，不重新打开 |
| TD-P8-2-001 | advanceStepIfRunning 时序 | **RESOLVED**（P8-2） | — | 维持 RESOLVED |
| TD-QA-PROP-001 | QA 提案对既有技术债影响分析 | OPEN（登记） | — | 保持登记；清偿动作分散至 PF-001/002/003 |
| TD-QA-004 | agent_event.run_id 是 TEXT（非 INTEGER） | **OPEN**（既有设计缺陷） | QA 不改 agent_event（EE 冻结） | 记录；QA-S5 前维持 |
| TD-004（gate）| qa_release_gate 原 DDL 无 PENDING/override/索引 | — | — | 由 QA-S1-2 DDL 修正（§14）清偿该文档口径 |
| TD-003 | ToolRegistry 测试隔离无清理机制 | OPEN | QA 编排工具增加注册面 | QA-S1 实施期评估（非前置 blocker） |

转移裁决：**QA-S1 BLOCKING PREFLIGHT** = PF-001（C4）/ PF-002（TD-T7-001）/ PF-003（TD-T6-001）；PF-004（QA 基线验证）为 QA-S1 准入门。

---

# F. Builder Prerequisites（仅设计分析 · DESIGN ONLY）

> 本轮**不实施** Builder。以下为设计前瞻，供冻结后人工评估（不构成承诺）。

- Builder 依赖：① Release Gate 的 `final_result` / `manual_override` 链路稳定；② `qa_release_gate.report_doc_id` → design_document（Release Report）成为 Builder 消费的结构化事实源；③ ConfigVersion append-only 语义（既有）作为 Builder 输入版本锚。
- Builder 复用面：不再新建编排工具（承接 4 编排工具输出）；错误契约/权限模型/前端路由（App.tsx 已存）直接复用。
- 不满足条件：Gate/Evidence 语义未冻结前 Builder 无可靠输入 —— 因此 Builder 严格排在本里程碑之外。
- 前瞻风险：（F-风险）automatic_result INCONCLUSIVE 会传导到 Builder"无可用判定"；需在 Builder 需求文档显式定义"门外观望"语义。

---

# G. Final Risk Register（P0/P1/P2）

> P0 = 冻结前必须已关闭或已有明确缓解且入 Preflight；P1 = 高风险，Sprint 内必须主动管理；P2 = 可观察、记录。

| 优先 | 风险 | 影响 | 缓解 |
|---|---|---|---|
| P0 | Child run TIMEOUT → 自动 PASS 传导 | 假放行生产缺陷 | §10/§13 红线：timeout 角色=失败角色，聚合仅 evidence 侧；**显式禁止实现**（QA-S1-2 验收含负例测试） |
| P0 | C4 ExperimentService 静默回退 latest（PF-001） | QA 数值分析基于错误版本；幂等键污染 | Preflight PF-001 独立 Task（Fallback→NOT_FOUND）；QA-S1 数值角色依赖此修复 |
| P0 | TD-T7-001 共享 fixture 污染进入 QA 测试（PF-002） | full-suite 假红，QA 测试不可信 | PF-002 自包含 fixture；QA 测试不继承 AbstractRepositoryTest；PF-004 基线验证 |
| P1 | LoopDetector 假阳性杀父 Lead（poll 同参×3） | sequential 编排中断 | poll_seq 参数递增规避（§9）；QA-S1-3/4 轮询负例测试 |
| P1 | 编排超时 wallClockBudget 如何设 | 长 CPI LLM 调用超时误判 | deadline 可配置（默认 300s，按步骤放大）；INCONCLUSIVE 而非 FAIL 兜底 |
| P1 | Gate 阈值草案未经验证 | 误 PASS/误 FAIL | QA-S3 校准回合；QA-S1-10 用已知失衡/健康版本对抗验证 |
| P1 | 前端 SSE 26 vs 22 stale 口径再扩散 | 文档/实现分叉 | 本文档 pin 26；QA-S5 前端改动前复查 |
| P2 | get_research_context 已注册未入白名单（23 vs 22） | 注册面与文档口径漂移 | 保留注册不删；文档归正（§6）；后续评估是否入 QA_RESEARCHER |
| P2 | TD-QA-004 agent_event.run_id TEXT | QA 事件溯源类型不一致 | 记录不修（EE 冻结）；QA-S5 评估 |
| P2 | assignment 状态漂移假挂起 | Dashboard 展示误导 | A7 同步语义：以 run 行为准（§8）已收口 |
| P2 | PlayerProfile V2 人格（CONSERVATIVE/EXPLOIT）远期需求 | 若误解为 MVP 风险 | 明确 MVP=3 人格 Prompt 驱动；V2 不属本里程碑 |

---

## 冻结裁决（待人工批准）

- 本候选在 **不触碰任何冻结边界** 的前提下成立：AgentType 扩展仅 Resolver；无生产代码改动；无 schema 落地。
- **条件**：Proposal 五处不一致（B 节）须修正文档（先修文档再冻结）；PF-001~PF-003 作为 QA-S1 前置独立 Task 提报。

**NEXT ACTION：HUMAN APPROVAL OF ARCHITECTURE FREEZE（禁止自动进入 QA-S1/任何实现）。**