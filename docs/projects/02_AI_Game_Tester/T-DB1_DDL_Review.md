# T-DB1 最终 DDL Review Report（提交人工批准）

> 2026-11-12 文档复核：本文为历史 DDL 审批记录，不重写当时结论。现有 test_plan/finding 已实现，当前真实版本验证见 [本地协议](../../platform/LOCAL_GAME_TEST_EXECUTION.md)；新增生产元数据需独立迁移，不直接重新执行本文提案。

> 状态：**等待批准。本文档不修改 schema.sql，不创建任何表。**
> 前置：Checkpoint B = PASS；T-DB1 两表（test_plan / finding）已获批准进入 DDL Review。
> 依据：已冻结的 TestPlan/Finding 领域模型、TestPlanRepository/FindingRepository 接口契约、技术设计 §6、裁决 §三~§六要求。

---

## 1. test_plan 表

```sql
-- 测试计划（一次测试任务的计划实体；DRAFT → ACTIVE → COMPLETED）
CREATE TABLE IF NOT EXISTS test_plan (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id INTEGER NOT NULL REFERENCES task(id),
    config_version TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT',   -- DRAFT/ACTIVE/COMPLETED
    scenarios_json TEXT NOT NULL,           -- 场景数组 JSON（技术设计 §5 结构）
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_tp_task ON test_plan(task_id);
```

### 列明细

| column | type | nullable | default | Domain 字段 | 类型理由 |
|---|---|---|---|---|---|
| id | INTEGER | NO | AUTOINCREMENT | `Long id` | 与全部现有表一致的自增主键；SQLite 下 INTEGER PRIMARY KEY 即 rowid 别名 |
| task_id | INTEGER | NO | — | `Long taskId` | Plan 挂在用户目标下；NOT NULL 因无 task 的计划无归属、无 UI 入口 |
| config_version | TEXT | NO | — | `String configVersion` | 被测对象 = ConfigVersion（设计核心契约）；TEXT 对应 game_config_version.version 的 'v001' 形态 |
| status | TEXT | NO | 'DRAFT' | `String status` + STATUS_* 常量 | 现有表均用 TEXT 存状态枚举（run.status/experiment.status 同款），不做 CHECK 约束以与现状一致、由 Service 层白名单校验 |
| scenarios_json | TEXT | NO | — | `String scenariosJson` | 场景是嵌套数组结构，SQLite 无 JSON 类型；整体存 TEXT 由 Jackson 解析为 Scenario[]（单测已验证 round-trip）。MVP 不拆子表——场景查询总是随 Plan 整体读取 |
| created_at | TEXT | NO | datetime('now','localtime') | `LocalDateTime createdAt` | 逐字复用现有 10 表的时间列风格 |

### 索引

| index | 列 | 为什么存在 |
|---|---|---|
| idx_tp_task | (task_id) | 主查询路径：任务详情页列出该 task 的全部计划（`findByTaskId`）；一个 task 计划数通常 0~N |

### 主键 / 外键

- 主键：`id` 自增。
- 外键：`task_id REFERENCES task(id)` —— **有必要**。task 是强归属关系，删 task 应级联拒绝孤儿 Plan；且 PRAGMA foreign_keys=ON 已在 JdbcConfig/URL 层启用并有测试守护（journalModeIsWalAndForeignKeysOn）。

---

## 2. finding 表

```sql
-- 缺陷/发现（Tester 的一等产出，供 Designer/Builder 消费）
CREATE TABLE IF NOT EXISTS finding (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER REFERENCES run(id),
    test_plan_id INTEGER REFERENCES test_plan(id),
    task_id INTEGER REFERENCES task(id),
    scenario_id TEXT NOT NULL,
    config_version TEXT NOT NULL,           -- 冗余列：回归对比核心查询路径
    severity TEXT NOT NULL,                 -- BLOCKER/HIGH/MEDIUM/LOW/INFO
    category TEXT NOT NULL,                 -- CRASH/SOFT_LOCK/INVARIANT_VIOLATION/UNEXPECTED_STATE/BALANCE_ANOMALY/OTHER
    source TEXT NOT NULL DEFAULT 'LLM',     -- INVARIANT/LLM/MANUAL
    title TEXT NOT NULL,
    expected TEXT,
    actual TEXT,
    repro_steps TEXT NOT NULL,              -- Markdown 步骤：必含 seed + scenarioId + actionSequence(toolCallId 区间)
    evidence_json TEXT NOT NULL DEFAULT '{}',
    reproduction_rate REAL,
    status TEXT NOT NULL DEFAULT 'OPEN',    -- OPEN/CONFIRMED/DISMISSED/FIXED
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_finding_ver_sev ON finding(config_version, severity);
CREATE INDEX IF NOT EXISTS idx_finding_run ON finding(run_id);
```

### 列明细

| column | type | nullable | default | Domain 字段 | 类型/NULL 理由 |
|---|---|---|---|---|---|
| id | INTEGER | NO | AUTOINCREMENT | `Long id` | 同上 |
| run_id | INTEGER | YES | — | `Long runId` | 可空：环境级验证 Finding（如纯 FakeGameEnvironment 冒烟）可无 Agent Run；Evidence.sessionId 才是必填锚点 |
| test_plan_id | INTEGER | YES | — | `Long testPlanId` | 可空：MANUAL 来源的临时发现可不属于任何 Plan |
| task_id | INTEGER | YES | — | `Long taskId` | 可空：同上，冗余反范式便于按目标聚合 |
| scenario_id | TEXT | NO | — | `String scenarioId` | 场景内定位必填（Plan 的 Scenario.id 或 manual-* 合成 id）；TEXT 与 JSON 内字符串 id 对齐 |
| config_version | TEXT | NO | — | `String configVersion` | 回归对比核心查询路径，冗余存储避免 join test_plan；缺陷必然属于某被测版本 |
| severity | TEXT | NO | — | `String severity` + SEVERITIES 白名单 | TEXT 枚举，Service 层校验，与全库风格一致 |
| category | TEXT | NO | — | `String category` + CATEGORIES 白名单 | 同上 |
| source | TEXT | NO | DEFAULT 'LLM' | `String source` + SOURCES=[INVARIANT,LLM,MANUAL] | **DDL Review 新增列**（裁决 §四.1）。区分确定性引擎基线（零漏报）、Agent 判断（可误报）、人工登记；默认 LLM 兼容存量调用路径 |
| title | TEXT | NO | — | `String title` | 人读摘要必填 |
| expected | TEXT | YES | — | `String expected` | INVARIANT 类可从谓词生成，但 BALANCE_ANOMALY 类可能只有现象描述；允许 NULL |
| actual | TEXT | YES | — | `String actual` | 同上 |
| repro_steps | TEXT | NO | — | `String reproSteps` | 需求硬要求"完整 reproduction sequence"；Markdown 必含 seed+scenarioId+动作序列（见 §4 Reproduction） |
| evidence_json | TEXT | NO | '{}' | `String evidenceJson` ↔ Evidence record | 证据引用整体 JSON（sessionId/runId/toolCallIds/observationSnapshot/tick/seed/overrides）；证据原文不复制只引用 tool_call 行，避免双写漂移。DEFAULT '{}' 保证列非空语义 |
| reproduction_rate REAL | REAL | YES | — | `Double reproductionRate` | MVP 单次判定留空；二期"假设→N 重放→统计"回填。REAL 对应 Double |
| status | TEXT | NO | 'OPEN' | `String status` + STATUSES 白名单 | 生命周期流转 OPEN→CONFIRMED/DISMISSED/FIXED |
| created_at | TEXT | NO | datetime('now','localtime') | `LocalDateTime createdAt` | 全库统一时间列风格 |

### 索引

| index | 列 | 为什么存在 |
|---|---|---|
| idx_finding_ver_sev | (config_version, severity) | 核心查询："v002 相比 v001 新增了哪些 HIGH 以上缺陷"——版本升级回归对比的第一入口（findByFilter 前两参数） |
| idx_finding_run | (run_id) | Run 详情页列出该次执行产出的全部 Finding；Run 结束后报告聚合路径 |

### 主键 / 外键

- 主键：`id` 自增。
- 外键三条（run/test_plan/task）：**均为可空外键，有必要**——它们表达的是"弱关联溯源链"而非强归属（Finding 的主锚点是 Evidence.sessionId 指向的会话，不是数据库行），因此 nullable + REFERENCES；PRAGMA foreign_keys=ON 下仅在有值时校验存在性，阻止指向已删除对象的悬空行。

---

## 3. 字段映射总表（Domain ↔ DDL）

| TestPlan record | 列 | Finding record | 列 |
|---|---|---|---|
| Long id | id | Long id | id |
| Long taskId | task_id NOT NULL | Long runId | run_id NULL |
| String configVersion | config_version NOT NULL | Long testPlanId | test_plan_id NULL |
| String status | status DEF 'DRAFT' | Long taskId | task_id NULL |
| String scenariosJson | scenarios_json NOT NULL | String scenarioId | scenario_id NOT NULL |
| LocalDateTime createdAt | created_at DEF now | String configVersion | config_version NOT NULL |
| （Scenario 为 scenarios_json 内嵌结构，不落独立表） | — | String severity | severity NOT NULL |
| | | String category | category NOT NULL |
| | | String source | source DEF 'LLM' |
| | | String title | title NOT NULL |
| | | String expected / actual | 可空 TEXT ×2 |
| | | String reproSteps | NOT NULL |
| | | String evidenceJson ↔ Evidence | NOT NULL DEF '{}' |
| | | Double reproductionRate | 可空 REAL |
| | | String status | DEF 'OPEN' |
| | | LocalDateTime createdAt | DEF now |

映射完整性：TestPlan 6/6 字段、Finding 17/17 字段一一对应，无遗漏、无多余列。

## 4. 重点审查项回答（裁决 §四）

### 1) Finding 来源

**能区分。** 本次 Review 新增 `source TEXT NOT NULL DEFAULT 'LLM'` 列 + `Finding.SOURCES = [INVARIANT, LLM, MANUAL]`：

- **INVARIANT**：InvariantEngine 确定性引擎自动产出（零漏报基线，不可信度最低）
- **LLM**：Agent 观察判断后主动上报（可能有误报，需 CONFIRMED 流转核实）
- **MANUAL**：人工在 UI 登记

category（缺陷类型）与 source（检测来源）正交：一条 SOFT_LOCK 可以来自 INVARIANT 引擎也可以来自 LLM 判断。

### 2) Evidence 追溯链

```
Finding.evidence_json ──▶ sessionId（GameSessionHandle，必填主锚点）
                       ├─▶ run_id        → run 表 → step 表（seq 区间）→ tool_call 表
                       ├─▶ toolCallIds[] → tool_call 行：arguments_json/result_json
                       │                    （game_observe 的 Observation 原文、
                       │                     game_collect_events 的 Event 原文都在 result_json）
                       └─▶ observationSnapshot（完整状态快照副本，防 tool_call 清理后失据）
Metric 经 game_collect_metrics 的 tool_call 行同理可达。
```

**tick 不是定位键**：Evidence.tick 仅辅助展示（Javadoc 明示 "RESTART 后不保证全局单调"）；单测 `evidence_anchorsOnSessionNotTick` 断言 sessionId/runId/toolCallIds 承担唯一定位。同一会话内 tick 配合 sessionId 仍可用于人读定位。

### 3) Reproduction 完整恢复路径

保存内容：`seed`（evidence_json.seed）+ `overrides`（evidence_json.overrides，含故障注入键）+ **动作序列经 toolCallIds 间接获得**——每个 game_act 调用的 arguments_json 里就是 GameAction(actionType, params, tickBudget)，按 toolCallIds 升序重放即得完整动作序列。

完整恢复步骤（repro_steps 中固化为 Markdown）：
1. `FakeGameEnvironment.start(GameLaunchSpec(seed, configVersion, overrides, playerProfile))`
2. 按 toolCallIds 顺序取出各 game_act 的 arguments_json → 反序列化 GameAction → `act()`
3. 断言重放轨迹中 evidence_json.observationSnapshot 出现在对应位置

**结论：满足"seed + overrides + action sequence"三级复现要求**；action sequence 不落独立列的原因是它已经以权威形态存在于 tool_call 表（单一事实源，复制反而引入漂移风险）。若未来 tool_call 有清理策略，则 observationSnapshot 快照 + repro_steps 文本兜底。

## 5. Evidence Snapshot 技术债登记（裁决 §五）

- ✅ MVP 保持 `evidence_json` 整体存储，**不拆** Evidence 子表
- ⚠️ 技术债（登记入 task-state.md）：observationSnapshot 是完整 Observation 副本（14 分量 + violations 列表），高频 Finding 场景下 finding 表体积可能被快照主导；二期需评估——① 快照裁剪（仅保留违规相关分量）② 独立 evidence_snapshot 表 + 引用 ③ SQLite jsonb 类压缩。当前不阻塞。

## 6. SQLite 兼容性

- 全部语法为 SQLite 支持子集：INTEGER PRIMARY KEY AUTOINCREMENT / TEXT / REAL / CREATE INDEX IF NOT EXISTS / `datetime('now','localtime')` 默认值——后者逐字复用现有表写法
- 无 CHECK/FULLTEXT/json1 依赖；枚举校验走 Service 层白名单（与 run.status 等现状一致）
- WAL + foreign_keys=ON 已由现有 JdbcConfig/URL 参数覆盖，新表自动受益
- JdbcConfig 自定义翻译器把 UNIQUE 冲突转 DuplicateKeyException——本两表**无 UNIQUE 约束**（Finding 允许同场景多次上报同类缺陷，去重是 Service/人工职责），不受影响

## 7. 与现有 schema.sql 风格一致性

逐项对照现有 10 表：自增主键 ✅ / TEXT 状态列+注释枚举值 ✅ / `_json` 后缀 JSON 列 ✅ / `datetime('now','localtime')` ✅ / IF NOT EXISTS 幂等 ✅ / 索引命名 `idx_表_列` 缩写风格 ✅ / 中文注释头 ✅。追加位置：文件末尾，加 Tester 分节注释，不动任何现有行。

## 8. Migration / 初始化方式

**无 Migration 需要**：schema.sql 走 Spring `sql.init.mode=always` + IF NOT EXISTS 幂等初始化（ApplicationTests.journalModeIsWalAndForeignKeysOn 守护该机制），追加两表后重启即建。存量开发库无需数据迁移（新表为空启动）。

## 9. 冲突检查

1. **是否需要修改现有表？否。** run/step/tool_call/task/game_config_version 均原样保留——Finding 通过外键与 evidence_json.toolCallIds **只引用不改造**。
2. **是否需要新增第三张表？不需要。明确写出：不新增其他 Tester 表**——tester_run/tester_step/tester_event/tester_project 一律不加（工作流禁止项 + 设计 §6 已论证替代方案）。
3. **对现有测试的影响预告（供批准时知情）**：ApplicationTests.EXPECTED_TABLES 是精确集合断言（当前 10 表），追加两表后该测试必须同步扩到 12 表——这是唯一需要顺带修改的既有测试文件，届时随实施一并提交说明。
4. Repository 接口已冻结且与 DDL 对齐：findByFilter 的 (configVersion,severity) 命中 idx_finding_ver_sev；findByTaskId 命中 idx_tp_task；接口注释中的索引承诺兑现。

---

## 批准请求

请批准以下任一选项：
- **APPROVED**：按本 DDL 修改 schema.sql（追加两表）+ 更新 EXPECTED_TABLES 测试 + 实现 TestPlanRepositoryImpl/FindingRepositoryImpl（JdbcTemplate，风格对照 ExperimentRepositoryImpl）
- **REJECTED / MODIFIED**：指出需调整项，我方修订后重新提交

**本报告提交后停止，等待人工批准。未获批准前不触碰 schema.sql、不建表、不写实现。**

## 附：技术债登记（同步 task-state.md）

- Evidence Snapshot 体积与规范化策略 → 二期评估（§5）
- ApplicationTests.EXPECTED_TABLES 精确断言将在实施阶段扩展至 12 表（§9.3）
