# QA PHASE 3 ARCHITECTURE DESIGN

> 2026-11-12 范围与状态说明：本文件保留对应阶段的设计/决策与准入条件，不能以其中历史表数、无编排、无回放或预算成功描述判断当前实现。当前模块入口见 [QA 设计](projects/04_AI_Game_Agent_Team/README.md)，共享 Runtime 的已授权变更见 [ADR](decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)。新生产评估以模块演进设计为准，旧 Gate 指标不因此重解释。

> **性质**：Phase 3 — Architecture Design（仅调查/问题定义/方案设计/影响面分析，**禁止直接实现**）。
> **状态**：**DECISIONS FROZEN（HD-1..7）+ IMPLEMENTED（见 docs/reports/QA_PHASE3_IMPLEMENTATION_REPORT.md）**。
> **生成时间**：2026-09-17。
> **基线**：后端 628/0/0，前端 57/0/0 + tsc 0（QA Phase 2 实现后基线，commit `813e33b`/`388503a`）。
> **来源**：QA Phase 2 Deferred — `QA-01 dominantBuildShare 恒为 null`（Phase 2 已标记 "Phase 3"）。
> **冻结红线**：Phase 3 不得修改 Frozen Contract（AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult/ErrorCodes/AgentEventType）。若方案必须改 `AgentLoop.observe()` → 明确标记 `FROZEN CONTRACT CHANGE REQUIRED` 并作为 Human Decision。
> **工程原则**：one repo / one backend / one frontend / one SQLite `data/agl.db` / project_id isolation（当前仓库**尚无** project_id 实现） / Agent 是 module / cross-project only through Platform Interface。不得引入 microservice / second database / per-agent database / 第二套持久化架构。

---

## 1. Phase 3 来源

QA Phase 2 已验收保留的 Deferred 中，QA-01 是最明确候选：

> `dominantBuildShare` 当前恒为 `null`。

Phase 2 报告中已确认（R5 §1.3.3 / §12.3）：

- `QaReleaseGateService.countStats()` [QaReleaseGateService.java:150](backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java#L150) **硬编码 `dominantBuildShare = null`**；
- `isBuildShareImbalanced(null)` 恒返回 `false`（:159-161）→ Gate FAIL 公式的 `build share 失衡` 分支（:90 `|| isBuildShareImbalanced(stats.dominantBuildShare)`）**永不触发**；
- 0.75 阈值形同虚设（freeze §13.3 定义了"dominant_build_share 失衡 → FAIL"，但工程上"定义了但未接线"）。

本阶段调查并设计 build-share 数据管道的三种候选架构，交由 Human 裁决。

---

## 2. dominantBuildShare 完整链路追踪（§5 Q1 回答）

### 逐层追踪

| # | 层 | 文件:行 | 事实 |
|---|---|---|---|
| 1 | Source（build 分布数据） | `experiment.result_json`（schema.sql:83 result_json + ExperimentService:94-110 写入） | **数据源存在**。`SimulationResult.buildDistribution: Map<String, BuildDistribution>`（[SimulationResult.java:24](backend/src/main/java/com/aigamelab/sim/SimulationResult.java#L24)），`BuildDistribution{games, wins, winRate, ciLow, ciHigh}`（:31-36）。`Aggregator` 逐局累加 build 分布（[Aggregator.java:121-128](backend/src/main/java/com/aigamelab/sim/Aggregator.java#L121-L128)）。 |
| 2 | Source 可达性 | `ExperimentRepository.findByFilter(configVersion, scenario)`（[ExperimentRepository.java:29](backend/src/main/java/com/aigamelab/repository/ExperimentRepository.java#L29)），Impl 动态 SQL 支持 `config_version=? AND scenario=?` 过滤（:85-96） | **查询能力存在**，可精确取 `scenario='ALL_BUILDS'` 的聚合实验。 |
| 3 | Computation | [QaReleaseGateService.java:150](backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java#L150) | **computation 缺失**：`countStats(findings)` 只接收 `List<Finding>`，硬编码 null。没有注入 ExperimentRepository，**无任何读取 build 分布数据的路径**。 |
| 4 | Transport | `GateResult.dominantBuildShare` → `completeOrchestration` → `gateRepo.decide()`（[QaOrchestratorService.java:257-262](backend/src/main/java/com/aigamelab/qa/QaOrchestratorService.java#L257-L262)） | **transport 存在**：值如何进来就如何出去（当前恒 null）。 |
| 5 | Persistence | `QaReleaseGateRepositoryImpl.decide` 写 `dominant_build_share REAL` 列（schema.sql:276）；domain 字段存在（[QaReleaseGate.java:37](backend/src/main/java/com/aigamelab/domain/QaReleaseGate.java#L37)） | **persistence 存在**：列/字段已建，只是永远写 null。 |
| 6 | API | `QaController` 直接返回 `QaReleaseGate` domain record（[QaController.java:87-91](backend/src/main/java/com/aigamelab/api/QaController.java#L87-L91)），无 DTO 剥离 | **API 暴露存在**：`GET /api/qa/gates` 响应天然携带 `dominantBuildShare` 字段（Jackson 序列化 record 全字段）。 |
| 7 | Frontend | [api.ts](frontend/src/api.ts) 无 gate 客户端方法（仅 `listQaFindings`）；无页面渲染 gate | **frontend 未消费**：Phase 2 §16 确认"不建新页面"为正确行为，gate 展示本就是 GAP/deferred。 |
| 8 | Gate 消费 | [QaReleaseGateService.java:159-161](backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java#L159-L161) `isBuildShareImbalanced`，:90 接入 FAIL 公式 | **消费逻辑存在**：分支写好但条件永不真。 |

### Q1 结论：为什么恒为 null？

> 既不是"没有 source"，也不是"API/运输/持久化丢失"。**唯一断裂点是 `computation` 层**：
> - **source 存在**：`experiment.result_json.buildDistribution`（SimulationResult 完整结构）；
> - **查询能力存在**：`ExperimentRepository.findByFilter(configVersion, scenario)`；
> - **computation 缺失**：`countStats(findings)` 只拿到 `List<Finding>`，构造签名里没有 ExperimentRepository / 没有 build share 参数，硬编码 null；
> - **下游（transport/persistence/API/consume）全部就绪**，只是上游永远喂 null。

准确归因：**source 存在但未接入（computation 层缺接线）**。

---

## 3. Build Share 语义定义（§6）

### 3.1 Build 是什么实体

**Build = 玩家构筑流派（gameplay build archetype），不是部署产物。**

代码事实：
- 恰 3 种 build：`crit`（暴击流）/ `penetration`（穿透流）/ `attack_speed`（攻速流）——[ExperimentService.java:38](backend/src/main/java/com/aigamelab/experiment/ExperimentService.java#L38) `ALL_BUILDS`；
- GameSimulator 同局玩家固定 1 个 build（[GameConfig.java:55](backend/src/main/java/com/aigamelab/sim/GameConfig.java#L55) `weaponsByBuild`），确定性模拟（:16）；`GameOutcome.buildType` 标记每局（[GameOutcome.java:13](backend/src/main/java/com/aigamelab/sim/GameOutcome.java#L13)）；
- build identity 的权威来源：`game_config_version.config_json` 内的 weapons/upgrades `buildType` 字段（v001.json:10/30/44/58），`BuildFactory.create(config, buildType)` 实例化；
- 与 config_version 的层级：**build ⊂ config**（一个 config 版本内含 3 个 build；config 是快照，build 是流派）。

### 3.2 build share 的分子/分母（可验证定义）

基于现有数据的唯一数学可计算定义：

- **分母** = 某次聚合实验（`scenario='ALL_BUILDS'`）的**总模拟局数** = `Σ buildDistribution[build].games`；
- **分子** = **主导 build 的局数** = `max over builds of buildDistribution[build].games`；
- **dominantBuildShare** = `分子 / 分母`。

代码中的 buildDistribution 恰好给出 `games` 计数（SerializationResult.BuildDistribution.games）。这是 **games-share**，不是 win-share、不是 time-share、不是 bug-share。

> ⚠️ 语义载体必须明确：这个 share 度量的是**实验局在各 build 流派间的分布是否失衡**（某 build 被模拟局数占比 >0.75 → 怀疑配置使单一流派主导），**不是**"哪些 build 上 bug 更多"。若业务意图是后者，则需 findings 携带 buildType —— 现状 Finding **没有** build-related 字段（[Finding.java:19-36](backend/src/main/java/com/aigamelab/domain/Finding.java#L19-L36) 无 build 字段），无法支撑 bug-per-build 统计，除非加列（≈新 schema 变更，超出 Architecture A 的 0 新表约束）。

### 3.3 其余语义问题

| 问题 | 代码事实 |
|---|---|
| 一个 Project 允许多个 build？ | 3 个 build 是配置内的固定集合，与 project 概念无关（当前无 project 表）。 |
| 一个 Run 对应一个 build？ | 模拟 Run（game session）对应单个 build（GameOutcome.buildType）；QA 子 Run（AgentLoop）测试任务级，不绑定 build。 |
| child Run 是否可能不同 build？ | QA 子 Run 是 Agent 编排任务，无 build 维度；模拟 Run 之间可以不同 build（各自 session）。 |
| build share 是 run-level 还是 orchestration-level？ | **orchestration-level**：Gate 是 lead_task × config_version 一条（schema.sql:253 注释 + QaReleaseGate），share 应用于该编排的 config 维度。 |
| 时间窗口？ | 需设计决策：取该 config_version 最近一次 ALL_BUILDS 实验（快照语义）vs n 次/窗口聚合。现状 `findByFilter` 无排序保障（Impl :96 `ORDER BY id`），取最后一行为可行默认。 |
| 数据是否来自当前 QA execution？ | **否**。实验（ExperimentService）与 QA 编排（QaOrchestratorService）是两条独立执行流，仅通过 `config_version` 文本连接（无 FK，schema.sql:83/156/170 均为 TEXT）。**QA 编排不触发实验**（QaCompleteOrchestrationTool 无实验调用）。 |
| 是否允许历史数据参与？ | 现状无实验触发耦合 → 天然"复用已存在的实验行"。可设计为只读复用最新，或要求 QA Lead 显式跑实验后再过 gate。 |

### 3.4 0.75 threshold 的业务含义

代码事实（[QaReleaseGateService.java:43-44](backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java#L43-L44)）：

```java
/** dominant_build_share 失衡阈值（MVP 草案，QA-S3 可校准）。 */
static final double DOMINANT_BUILD_IMBALANCE_THRESHOLD = 0.75;
```

- Freeze §13.3 仅写 "dominant_build_share 失衡" 无量化语义（[QA_ARCHITECTURE_FREEZE.md:207](docs/QA_ARCHITECTURE_FREEZE.md#L207)）；
- 代码注释自称 "MVP 草案，QA-S3 可校准"；
- **语义推断**（INFERENCE，非 FACT）：`share > 0.75` 意味着某个 build 覆盖了实验局的大多数（3 个 build 均匀分布应各 ≈0.333；>0.75 强烈失衡，暗示该 config 使单一流派绝对主导，可能为数值平衡缺陷）；
- **UNKNOWN 声明**：0.75 的具体商业依据（为何 0.75 而非 0.8/0.6）**无法从代码证明**。QA-S3 校准流程本身尚未定义（无对应 RULE_VERSION 迭代记录，`RULE_VERSION="QA-S3-MVP-20260910"` 是唯一版本）。**须 Human 裁决 0.75 语义与是否保留**。

---

## 4. 现有数据源调查（§7）

| 数据源 | 是否存在 | build identity | share 信息 | project_id | 可复用性 |
|---|---|---|---|---|---|
| `project` 表 | ❌ 无（schema.sql 全文无 project 表/列） | — | — | N/A（原则未落地） | — |
| `task` | ✅ (schema.sql:17) | ❌ 无 build 字段；constraints_json 可含 configVersion | ❌ | ❌ 无 | 间接（QA lead task 的 config 锚点） |
| `run` | ✅ (schema.sql:19) | ❌ 无 build 字段 | ❌ | ❌ 无 | 间接（关联实验 run_id） |
| `game_session` | ✅（Tester adapter 会话，非独立表；GameSessionService 是内存句柄） | ❌ 单一 buildType 隐含于 GameLaunchSpec | ❌ | ❌ | 间接（provenance） |
| `test_plan` | ✅ (schema.sql:136) | ❌ 仅 config_version（:156） | ❌ | ❌ | 间接 |
| `experiment` | ✅ (schema.sql:72-86) | ✅ `scenario`（build 或 ALL_BUILDS）+ config_version | ✅ `result_json` = SimulationResult 含 buildDistribution.games/wins | ❌ 无 | **✅ 核心可复用**：`findByFilter(configVersion, scenario)` 直接可得 |
| `finding` | ✅ (schema.sql:163) | ❌ config_version 有（:170），**buildType 无** | ❌ | ❌ | diff 上不能按 build 切 |
| `evidence` | ✅（finding.evidence_json 内嵌，schema.sql:172） | ❌ | ❌ | ❌ | 否 |
| `qa_role_assignment` | ✅ (schema.sql:228) | ❌ | ❌ | ❌ | 否（编排状态，无 build 语义） |
| `qa_release_gate` | ✅ (schema.sql:253) | ✅ `dominant_build_share REAL` 列已存在 | 列存在但恒 null | ❌ | ✅ 目标接收侧 |
| `game_config_version` | ✅ (schema.sql:88) | ✅ `version` PK + `config_json`（内嵌 build 定义） | ∘（可从 config_json 推断 build 集合，非运行时 share） | ❌ | 权威 build identity 源 |

**结论**：无需为新架构创造专属数据模型即可计算 share（实验数据 + gate 列已就位）。唯一缺口是 **computation 接线**（见 §5 Architecture A）。

---

## 5. Architecture A — Derived / Existing Data（0 新表）

```
existing experiment (scenario='ALL_BUILDS', config_version=<gate.config_version>)
    ↓ findByFilter(configVersion, "ALL_BUILDS") → 取最后一行为最新
    ↓ 解析 result_json → SimulationResult
    ↓ buildDistribution → 求 max(games)/Σ(games)
    ↓ dominantBuildShare
    ↓ QaReleaseGateService.evaluate FAIL 公式 (isBuildShareImbalanced > 0.75)
```

### A. 设计要点

| 维度 | 设计 |
|---|---|
| build identity | `experiment.scenario` + `game_config_version.config_json` 内 buildType（无需新增字段） |
| share 公式 | `dominantShare = max(games) / Σ(games)`，源自 `SimulationResult.buildDistribution` |
| computation 插入点 | **A1（推荐）：注入 `ExperimentRepository` 到 `QaReleaseGateService`**，`evaluate()` 内按 `configVersion` 调 `findByFilter(cfg, "ALL_BUILDS")`；或 **A2**：在 `QaOrchestratorService.completeOrchestration` 计算 share 作为参数传入 `evaluate`（改动面更小，保持 `QaReleaseGateService` 纯函数性）。 |
| 数据读取 | `ExperimentRepository.findByFilter(configVersion, "ALL_BUILDS")`，取 `ORDER BY id` 最末行（Impl 已存在，零新 SQL） |
| DTO/API | 无新端点；`QaReleaseGate.dominantBuildShare` 字段已随 record 序列化输出 → **API 零改动** |
| Frontend | 零改动（Phase 2 §16：不建 gate 页面） |
| FAIL 分支 | 真实值接入后 `isBuildShareImbalanced(share)` 可触发，0.75 语义须 Human 裁决 |

### B. 失败模式（必须 degrade）

| 场景 | 行为 |
|---|---|
| 该 configVersion 无 ALL_BUILDS 实验 | `findAll 为空` → `dominantBuildShare=null`（保持现状，不触发失衡分支，Gate 可照常 PASS/FAIL 基于其他判据） |
| 实验 status ≠ DONE | 过滤只考虑 DONE（`ExperimentService` 复用判断同款语义），否则缺省 null |
| result_json 解析失败 | catch → null（不阻塞 Gate 决策） |
| 实验属于历史 config 且被覆盖 | 按最新实验行读取（快照语义），历史不可比问题由"gate 幂等性（同 config 同树一条）"约束 |

### C. 边界与约束

- 0 新表 ✅ / 0 frozen 改动 ✅（QaReleaseGateService、QaOrchestratorService、ExperimentRepository 均非 frozen）/ 0 新端点 ✅；
- **一致性风险**：QA 编排不触发实验，若人类/其他 Track 未先跑 ALL_BUILDS 实验，share 恒缺省 → 失衡判据实际仍不生效。"实验先行"成为隐式前置条件，须文档化或经 Human 决策是否在编排内触发实验（后者会增加编排副作用，须慎重）。

---

## 6. Architecture B — Build Snapshot（新表，add-only）

### B.1 设计：add-only 快照表

> **只设计，不实现。** 表格即使批准，H-2（0 新表）在 Phase 3 阶段不豁免——0 新表是 Phase 2 硬约束；Phase 3 若选 B，需 Human 专门裁决 schema 变更授权。

**表设计草案**：

```sql
-- Build Share 快照（Phase 3 候选；add-only；仅设计）
CREATE TABLE IF NOT EXISTS qa_build_share_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    lead_task_id INTEGER NOT NULL REFERENCES task(id),     -- orchestration 作用域
    run_id INTEGER NOT NULL REFERENCES run(id),            -- lead run（Provenance）
    config_version TEXT NOT NULL,                          -- 被测版本
    scenario TEXT NOT NULL DEFAULT 'ALL_BUILDS',           -- 快照来源实验
    experiment_id INTEGER REFERENCES experiment(id),       -- 溯源：source experiment 行
    dominant_build_share REAL NOT NULL,                    -- 阈值输入（0.75 判据）
    dominant_build_type TEXT,                              -- e.g. 'crit'
    total_games INTEGER NOT NULL,                          -- 分母（Σ games）
    dominant_games INTEGER NOT NULL,                       -- 分子（max games）
    build_distribution_json TEXT NOT NULL DEFAULT '{}',    -- 全量分布留痕
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
-- 索引：按 task × created_at / 按 config × created_at；project_id 当前不存在
CREATE INDEX IF NOT EXISTS idx_qbss_task_time ON qa_build_share_snapshot(lead_task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_qbss_cfg_time  ON qa_build_share_snapshot(config_version, created_at);
```

### B.2 生命周期

| 阶段 | 设计 |
|---|---|
| WRITE（add-only） | `completeOrchestration` 计算 share 时同步插入快照行（先算后写，写失败不阻塞 Gate） |
| READ | `QaReleaseGateService` 读取该 lead_task 最近快照（或写时即作为 gate 参数） |
| RETENTION | 每 lead_task 保留最新一条即可（add-only 板层复用，冗余行由历史审计保留）或 TTL 90 天 |
| MIGRATION | 新表无需迁移既有表；`qa_release_gate.dominant_build_share` 可保留为**冗余镜像列**（快照 id 作为溯源关联） |
| consistency | 快照与 gate 行同事务写入（Spring @Transactional），或 gate 行仅存 share 快照为宽松最终一致 |

### B.3 对比 A 的价值

- **解决 A 的一致性缺陷**：快照在编排时点固化 → 后续实验新增不改变历史 gate 的 share 判定（历史对齐）；
- **溯源**：experiment_id 保留数据血缘；
- **代价**：1 张新表 + schema 变更（H-2 需人工豁免）+ 新 repository + 生命周期/保留策略维护。

---

## 7. Architecture C — External / Build Pipeline Integration

**调查结论（FACT）：本仓库不存在外部 build pipeline / build 元数据来源。**

证据：
- 无 CI/CD 配置（无 .github/、Jenkinsfile、.gitlab-ci.yml、azure-pipelines.yml）——`scripts/` 仅 check-env/bat/sh；
- 无构建产物部署/外部服务集成代码；`pom.xml`/`package.json` 为标准构建，无第三方 build metadata 摄取；
- "build"在系统内是**模拟内部概念**（GameSimulator 的 buildType），由 `game_config_version.config_json` 定义，非外部产物；
- 无外部 HTTP 客户端调用 build 服务（`ToolExecutor`/Service 层无相关调用）。

**因此 Architecture C 为理论设计（Theoretical Design）。不得虚构集成点。**

### C.1 理论集成设计（若未来接入真实 build 管线）

```
Build Pipeline（外部 CI/build 系统）
      ↓ Metadata（build id, artifact hash, 部署状态, 测服映射）
      ↓ 摄取边界：新 API 端点（如 POST /api/qa/builds）或轮询器
      ↓ QA Orchestration（按 configVersion 关联 build 元数据）
      ↓ Gate（share 来自 build 元数据）
```

| 维度 | 设计 |
|---|---|
| integration boundary | 新增只读摄取层（controller + service），不触碰 frozen 文件；或 poller |
| source of truth | 外部 build 系统拥有 build 血缘；AGL 仅消费映射（configVersion ↔ build） |
| failure behavior | 元数据超时/不可用 → share=null（Gate 照常，失衡判据跳过）；**绝不阻塞 gate** |
| stale data | 时间戳新鲜度校验；超过阈值视为缺失 |
| security/auth | 摄取端点鉴权（AGL 现有鉴权表面不足，需随实现补充） |
| project isolation | 当前无 project_id；摄取级须补充 project 作用域（连同全局 project_id 迁移决策一起裁决） |

### C.2 适用性判断（Inférence）

当前 AGL 是纯模拟环境（无真实部署反馈循环）。C 的唯一现实意义是：若未来接入真实编辑器/测服部署流，"单 build 主导"在真实玩家分布上才有意义。**当下 C 的构建成本远高于收益，且依赖不存在的工程面。**

---

## 8. Architecture Comparison（§9）

| Dimension | A (Derived) | B (Snapshot) | C (External) |
|---|---|---|---|
| New tables | **0** | **1**（qa_build_share_snapshot） | 0（纯理论；若轮询需状态表则 +1） |
| Frozen changes | **0** | 0 | 0（若新增摄取端点亦非 frozen） |
| API changes | **0**（gate record 已含字段） | 0（可选暴露快照端点） | +1 摄取端点（理论上） |
| Frontend changes | **0** | 0（可选 snapshot 展示） | 0 |
| Data source | `experiment.result_json`（已有） | 同 A，固化到新表 | 不存在的外部 build 系统（理论） |
| Accuracy | 依赖实验是否先跑（弱于 B 的时点固化） | 高（编排时点快照，历史对齐） | 依赖外部系统假设 |
| Consistency | 最终一致（历史 gate 可能受影响） | 快照时点一致（事务固化） | 未知（外部漂移） |
| Failure mode | 无实验 → null → 判据跳过 | 写失败 → null（不阻塞） | 超时/不可用 → null 降级 |
| Project isolation | 无 project 维度（全仓单 project 现状） | 可含 project_id 列（待全局迁移） | 需显式 project 边界 |
| Implementation complexity | **LOW**（注入 repo + 1 方法） | MEDIUM（新表+repo+生命周期） | **HIGH**（基建不存在） |
| Migration risk | **LOW**（零表零端点） | MEDIUM（H-2 豁免 + 新表治理） | HIGH（依赖未存在的面） |
| Test complexity | LOW（单测注入 mock repo） | MEDIUM（DDL+生命周期+事务） | HIGH（无法集成测试外部系统） |
| Long-term extensibility | 依赖模拟实验持续存在（可演进） | **HIGH**（add-only 血缘清晰，可加 project/retention） | 理论上的未来扩展点 |

> **不输出 best/worst/score/ranking。最终选择权交给 Human。**

---

## 9. Gate Integration 分析（§10）

### 9.1 当前 evaluation 链（QaReleaseGateService.evaluate:77-129）

1. Hard FAIL：`critical>0 ∨ high≥3 ∨ softLock>0 ∨ isBuildShareImbalanced(share)`（:89-93）；
2. TIMEOUT → INCONCLUSIVE（红线，:96-99）；
3. FAILED/CANCELLED → INCONCLUSIVE（:102-105）；
4. missing role → INCONCLUSIVE（:108-110）；
5. key role 未 COMPLETED → INCONCLUSIVE（:113-115）；
6. CONDITIONAL_PASS / PASS（:118-128）。

### 9.2 谁计算 threshold？（§10 Q1）

**当前：无人计算。** `dominantBuildShare` 由 `countStats` 硬编码 null（:150），`isBuildShareImbalanced` 只在 `share != null && share > 0.75` 时触发（:159-161），数值永不产生 → 阈值判据是**死代码**。Rule Formula 版本 `QA-S3-MVP-20260910` 无 build-share 输入。

### 9.3 各架构下的集成

- **Architecture A**：`countStats` → 增加 `ExperimentRepository` 依赖（或从 `completeOrchestration` 传入 share），真实 share 进入 `GateStats` → FAIL 分支的 `isBuildShareImbalanced` 可触发；`0.75` 常量原地保留；`RULE_VERSION` 随公式语义变更 bump（设计为 `QA-S3-20260917` 之类版本化变更）；
- **Architecture B**：share 从快照行读取（或以快照作为 gate 参数注入），集成点相同，但 share 是**编排时点固化值**；
- **Architecture C**：share 来自外部摄取，集成点相同但引入异步/新鲜度语义。

### 9.4 关键 Gate 语义提示（红线安全）

- 无论哪种架构，**share 缺失（null）不得改判**——保持"无失衡判据时不阻断 PASS/FAIL 其他判据"的现状语义，避免引入"未知 share = FAIL"的隐性红线；
- Gate 独立性保持：build share 是**新增 FAIL 触发器**，不替代既有 TIMEOUT/Failed/missing 判据。

---

## 10. Human Decision Items

| # | 决策项 | 选项 | 建议默认（不预置选择） |
|---|---|---|---|
| HD-1 | 选哪条架构（或混合） | A（Derived，0 新表）/ B（Snapshot，+1 表）/ 先 A 后 B 演进 | **已冻结：A（Derived / Existing Data）；实现采用 A2（orchestrator 计算，gate 纯函数）** |
| HD-2 | 0.75 threshold 业务含义 | 保留（需补语义文档）/ 修改数值 / 移除失衡判据 / 暂不启用 | **已冻结：保留 0.75 + 严格 `>`（isBuildShareImbalanced 不变，不可配）** |
| HD-3 | build share 的语义载体 | `games-share`（模拟局分布）/ `win-share` / 要求 findings 携带 buildType（需 schema 变更） | **已冻结：games-share（max(games)/Σ(games)，build 名无关）** |
| HD-4 | share 数据来源触发 | 只读复用已有实验（现状可作）/ QA 编排内显式触发 ALL_BUILDS 实验（副作用增强） | **已冻结：只读复用，evaluate-time 实时 Derived（无缓存/异步/SSE/API）** |
| HD-5 | share 缺失时 Gate 行为 | null=跳过（现状语义）/ null=INCONCLUSIVE（更严格） | **已冻结：null=INCONCLUSIVE，never PASS（Human 选更严格项；实现见 step 4c）** |
| HD-6 | project_id 维度处理 | 本轮不引入（全仓单 project）/ 随 B 表加入 project_id 列并连带全局迁移 | **已冻结：本轮不引入（已验证不破 lead-task 边界，gap 已文档化）** |
| HD-7 | 若选 B：快照保留策略 | 仅最新一条 / TTL 90 天 / 全量永久 | **已冻结：不选 B —— 无快照表，保持 Gate 持久化不变** |

---

## 11. Anti-Scope（§2-4 约束 + §32 禁令）

Phase 3 **不得**（本设计不包含、未来实施时也须守）：

- 不直接实现任何候选架构（本阶段只交付设计文档）；
- 不碰 Frozen 文件（AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult/ErrorCodes/AgentEventType）；
- 不新增 DB 表（除非 Human 明确授权 H-2 豁免，且仅在选 B 时）；
- 不新增 ErrorCode、不扩展 SSE EventType、不改前端架构；
- 不引入 microservice / 第二数据库 / per-agent DB / 独立 QA service；
- 不修改 `AgentLoop.observe()`（若未来需 replay 支持 → 标记 `FROZEN CONTRACT CHANGE REQUIRED` 并单独提交 Human 决策）；
- 不做 project_id 全局迁移（除非单独授权）；
- 不虚构外部 build pipeline 集成（C 为理论设计）。

---

## 12. Risk Register

| Risk | 架构 | 说明 | 缓解 |
|---|---|---|---|
| experiment 未跑 → share 恒缺省 | A | 失衡判据实际失效 | 文档化前置条件；HD-4 决策是否编排内触发 |
| 语义歧义（0.75/win vs games） | 全 | 误判 FAIL | HD-2/HD-3 裁决后再实施 |
| 历史 gate 对齐 | A | 后续实验改变最新行 → 读时不一致 | 选 B 固化时点；或 gate 存快照值（现状列） |
| schema 变更治理 | B | 违反 H-2 | 需显式 human 豁免 + 新表 lifecycle 文档 |
| 外部依赖幻觉 | C | 引入不存在的集成点 | 仅理论设计，实施需真实管线存在验证 |
| 引入隐式红线 | 全 | share 缺失被误判成 FAIL | 明确 null=跳过策略（§9.4） |
| frontend 误改造 | 全 | Phase 2 §16 已冻结"不建 gate 页面" | 本轮零前端变更声明 |

---

## 13. 交付边界声明

本设计文档**不包含实现**。以下为 Human 裁决后的后续工作清单（不自动启动）：

1. HD-1..HD-7 裁决；
2. 选定架构的实施 Task（独立授权）；
3. 0.75 语义补充或移除（RULE_VERSION bump）；
4. （若选 B）schema 变更授权 + 快照生命周期治理；
5. 回归门槛保持 628/0/0 后端 / 57/0/0 前端。