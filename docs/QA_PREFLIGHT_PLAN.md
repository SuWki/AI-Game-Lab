# QA Preflight Plan（输出 C：PF-001 ~ PF-004）

> 2026-11-12 范围与状态说明：本文件保留对应阶段的设计/决策与准入条件，不能以其中历史表数、无编排、无回放或预算成功描述判断当前实现。当前模块入口见 [QA 设计](projects/04_AI_Game_Agent_Team/README.md)，共享 Runtime 的已授权变更见 [ADR](decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)。新生产评估以模块演进设计为准，旧 Gate 指标不因此重解释。

> 状态：**DEFINED（计划）— 不执行**。执行须待「ARCHITECTURE FREEZE 人工批准」→「PRE-FLIGHT CLEARANCE」两级放行。
> 归属：QA-S1 **前置**独立 Task 集合（不在 QA-S1 Sprint 内）；独立人工授权、独立验收、独立 Checkpoint。
> 依据：`docs/QA_ARCHITECTURE_FREEZE.md`（AF-001）E 节 TD Transition + `docs/QA_ARCHITECTURE_DECISION_REVIEW.md` A3/A4/A5 + `docs/QA_ARCHITECTURE_AUDIT_REPORT.md` 六维审计。
> 代码锚点：主线程复核于 2026-09-03（含行号）。

## 0. 前置关系

```
[ARCHITECTURE FREEZE APPROVAL]  （人工）
        ↓
┌───────────────────────────────┐
│  PREFLIGHT 集合（本轮仅计划）  │
│  PF-001 (C4 fallback)         │
│  PF-002 (TD-T7-001 fixture)   │
│  PF-003 (TD-T6-001 configVer) │
│  PF-004 (QA baseline 验证)    │
└───────────────────────────────┘
        ↓  全部通过 = PRE-FLIGHT CLEARANCE
[QA-S1 IMPLEMENTATION]  （另立 Sprint，须另次人工授权）
```

## 1. Preflight 概览（依赖 DAG）

| Task | 目标 | 前置 | 触碰面 | 是否阻塞 QA-S1 |
|---|---|---|---|---|
| PF-001 | ExperimentService C4 双重 fallback → NOT_FOUND | 无 | `experiment/ExperimentService.java` + 其测试 | **是（BLOCKING）** |
| PF-002 | TD-T7-001：QA 测试自包含 fixture 策略 + 基线验证 | PF-004 前 | `test/` 新增 QA fixture 辅助（不改 AbstractRepositoryTest） | **是（BLOCKING）** |
| PF-003 | TD-T6-001：FindingService.report() 校验 configVersion 真实存在 | 无 | `tester/FindingService.java` + 其测试 | **是（BLOCKING）** |
| PF-004 | QA baseline：full-suite Designer/Tester/Research 健康基线 | PF-001/002/003 全过 | 无（仅跑验证） | **是（QA-S1 准入门）** |

> 并行度：PF-001 与 PF-003 互不依赖可并行；PF-002 独立；PF-004 必须在三者全部 CLEAN 后执行。
> 依赖原则：PF-003 依赖 `ConfigVersionService.findByVersion`（既有，P6）—— 无新依赖。
> PF-001 修复是 QA 数值/实验角色正确性的**硬前提**（C4 会让实验静默跑在 latest 上）。

---

## 2. PF-001 — ExperimentService C4 fallback 修正

### Task ID
`PF-001`

### Purpose
消除 `ExperimentService.create()` 对 `configVersion` 的**静默 latest 回退**（C4）。QA 的 Numerical/Combat Analyst 依赖 `analyze_experiment`/`simulate_game` 指向**被测的精确 ConfigVersion**；当前实现会让 null/blank 或不存在版本静默落入 `getCurrent()`，导致实验结果不可追溯、幂等键（config_version 入 UNIQUE）污染回归对比。修复为显式存在性校验，与 `GameSessionService.start()` 既有先例（40401）一致。

### Dependencies
- 无（独立）；`ConfigVersionService.findByVersion` 既有。
- 正确性参照：`GameSessionService.start()` L70-73（`findByVersion(...).orElseThrow(BusinessException.notFound)` → 40401）。

### Files / Modules
- `backend/src/main/java/com/aigamelab/experiment/ExperimentService.java`（唯一 main 改动）
- `backend/src/test/java/com/aigamelab/experiment/ExperimentServiceTest.java`（新增用例）

### Allowed changes
- `ExperimentService.create()`：
  - L62-64：`configVersion` 为 null/blank 时**不再** `getCurrent()` 兜底 → 抛 `BusinessException(NOT_FOUND)`（或显式契约参数错，按 Error Contract 现有语义二选一，实现期对照 `ErrorCodes`）。
  - L87-88：`configService.findByVersion(cfgVersion).orElseGet(configService::getCurrent)` → `.orElseThrow(() -> BusinessException.notFound("ConfigVersion 不存在: " + cfgVersion))`。
- 仅此两处 fallback 的消除；不改 Experiment/Repository/schema 语义、不改幂等 UNIQUE 复用逻辑。

### Forbidden changes
- 禁止改动 `GameConfig`/`BatchSimulator`/`GameSimulator`/`Aggregator`（P4 冻结）。
- 禁止修改 `ConfigVersionService`、`ExperimentRepository` 契约、schema.sql。
- 禁止扩大范围到 P5-3 异步化/Controller。
- 禁止本轮执行 —— 计划授权后由 Preflight 独立 Task 实施。

### Acceptance Criteria
1. `create("v001", ...)` 正常路径不受影响（幂等复用、ALL_BUILDS、单 build 全部保持）。
2. `create(null, ...)` / `create("", ...)` → `BusinessException`（NOT_FOUND 或显式契约码），**不再静默取 latest**。
3. `create("v999", ...)`（不存在版本）→ `BusinessException`（40401 语义），不再 `orElseGet(getCurrent)`。
4. `game_session` 链路先例对照：`GameSessionService.start()` 行为不变（回归）。

### Tests
- `ExperimentServiceTest` 新增 ≥3 用例：
  1. `create_nullConfigVersion_rejects`（断言抛 BusinessException，code=40401/NOT_FOUND）
  2. `create_unknownVersion_rejects`
  3. `create_validVersion_unchangedAndIdempotent`（回归：reused=true 幂等键仍生效）
- 全量 `mvn -o test` 该包回归。

### Risk
- **低**：改动局部、有既有 40401 先例。潜在影响：既有调用方（P3-4 simulate_game 工具/Designer 流程）若依赖"null→latest"将开始失败 —— 实现期须 grep 所有 `experimentService.create(` 调用方并逐一确认（此为范围内显式行为变更，需在 Preflight 报告列出）。

---

## 3. PF-002 — QA 测试自包含 fixture 策略（TD-T7-001）

### Task ID
`PF-002`

### Purpose
阻断 TD-T7-001（full-suite 13 FK errors：`ConfigVersion 不存在: v001` ×12 + 外键约束 ×1）对 QA 测试的传染。指令约束：**QA tests 不得继承 `AbstractRepositoryTest`，不碰其共享 static 临时 DB**。本 Task 为 QA-S1 定义自包含的 fixture/DB reset/事务隔离策略，并跑通"新策略在既有 full-suite 下零污染"的验证。

### Dependencies
- 无（独立设计任务）；阅读 `AbstractRepositoryTest`（L29-30、L59-82、L84-96）作为反面基准。

### Files / Modules
- **新增** `backend/src/test/java/com/aigamelab/qa/support/QaTestFixture.java`（或等价，QA 测试专用基类/工具，**不继承 AbstractRepositoryTest**）
- **新增** `backend/src/test/java/com/aigamelab/qa/support/QaTestFixtureTest.java`（验证 fixture 自身隔离）
- **不改** `AbstractRepositoryTest` / 既有 tester 测试类。

### Allowed changes
- 设计并实现 QA 专用测试隔离方案（三选一，实施期选型并记录）：
  - **A. 每测试类独立临时 DB**（`@DirtiesContext` 或 per-class `@DynamicPropertySource` 指向唯一临时文件/内存 SQLite），杜绝跨类静态共享。
  - **B. 事务回滚**：`@Transactional` 测试 + 回滚（仅适用于无跨方法提交需求的服务层测试；对编排/多线程测试不适用）。
  - **C. 显式 FK-safe 全表清理**（对齐 AbstractRepositoryTest L59-82 拓扑，但**每类独立 DB**，且不依赖 `@PostConstruct` 全局播种 —— 用 fixture 内显式播种 v001）。
- 提供 QA 测试内**显式 v001 播种**（不依赖生产 `ConfigVersionService@PostConstruct`），规避"测试上下文 @PostConstruct 未触发"的根因。

### Forbidden changes
- **禁止 QA 测试继承 `AbstractRepositoryTest`**（指令）。
- **禁止**修改/共享 `AbstractRepositoryTest` 的 static 临时 DB 文件。
- **禁止**修改生产代码（本 Task 纯测试基建）。
- **禁止**触碰 tester/designer/research 既有测试类的播种方式（不顺手修 TD-P8-1-001 残留）。

### Acceptance Criteria
1. QA fixture 在**独立临时 DB**上完成全量 FK-safe 清理 + v001 播种，且不依赖 `@PostConstruct`。
2. 隔离运行 fixture 示例测试：无 FK 违反、无 `ConfigVersion 不存在: v001`。
3. 与既有 tester 测试同容器/同 maven 运行时不互相污染（QA 示例测试 + 既有 TesterRepositoryTest 同跑全绿）。
4. 验证矩阵：QA fixture 独立 DB 方案在 full-suite 运行顺序变化下仍稳定（随机顺序复跑 2 次）。

### Tests
- `QaTestFixtureTest`：清理拓扑正确性（插入含 FK 链数据后清理 0 残留）、播种幂等、独立 DB 互不可见。
- 冒烟：一个继承 QA fixture 的示例 QA 测试在 `mvn -o test` 全量中与既有 371+ 用例共存通过。

### Risk
- **中**：方案选型错误会再造 TD-T7-001 变体。缓解：PF-002 内先做 3 方案对比记录；选 A（per-class 独立 DB）为默认推荐（最贴近"不继承 AbstractRepositoryTest"且与并行类无交集）。全量复跑 2 次验证随机顺序稳定性。

---

## 4. PF-003 — FindingService configVersion 真实性校验（TD-T6-001）

### Task ID
`PF-003`

### Purpose
清偿 TD-T6-001：`FindingService.validate()` 目前仅校验 `configVersion` 非空（L82-85），QA 的 `finding_report` 可凭任意非空串（如 `"v999"`）落库，破坏"被测对象必须是 ConfigVersion（契约 1）"。补存在性校验 → 非真实版本抛错，经 `AbstractTesterTool` 翻译为非 critical `ToolException`（tool_call=FAILED、Run 继续）。

### Dependencies
- `ConfigVersionService.findByVersion`（既有，P6）。

### Files / Modules
- `backend/src/main/java/com/aigamelab/tester/FindingService.java`（`report()` 或 `validate()`）
- `backend/src/test/java/com/aigamelab/tester/TesterServicesTest.java` 或新增 `tester/FindingServiceConfigVersionTest.java`

### Allowed changes
- `FindingService` 注入 `ConfigVersionService`（构造器扩参），`validate()` 在 non-blank 校验后追加存在性校验：`configService.findByVersion(f.configVersion()).orElseThrow(() -> BusinessException.notFound(...))`。
- 保持 severity/category/source/title/scenarioId/reproSteps/Evidence 全部既有校验顺序与语义。
- 同步更新/新增测试。

### Forbidden changes
- **禁止**修改 `Finding` domain record、`FindingRepository`、schema.sql、ErrorCodes。
- **禁止**改动 `TestPlanService` 或 `GameSessionService`（其 40401 校验是既有正确面，不重复）。
- **禁止**改变既有 T6-2 §2 记录的冻结行为预期之外的语义（该测试断言 v999 被接受 —— 本 Task 实施后该断言**必须**更新为"拒绝"，并在 Preflight 报告中明示行为变更）。

### Acceptance Criteria
1. `report(validFinding(v001))` 通过，落库不变。
2. `report(Finding(configVersion="v999"))` → `BusinessException`（NOT_FOUND）。
3. 经 `AbstractTesterTool` 全链：`finding_report` 工具调用未知版本 → tool_call=FAILED、Run 继续（非 critical、不中断）。
4. 既有 T6-2 §2 断言更新为预期拒绝，并注明行为变更。

### Tests
- 新增/更新 ≥3 用例：`report_unknownConfigVersion_rejected`、`report_validConfigVersion_stillPasses`、`toolChain_unknownVersion_nonCriticalToolFailure`。
- 回归：TesterServicesTest 全量、T6-2 ConfigVersionTestPlanEvidenceIntegrationTest（§2 断言同步）。

### Risk
- **中**：这是对冻结 Tester 契约**契约行为的显式增强**（T6 记录为"冻结行为"）。须按工作流 §41 在 Preflight 报告明确"业务规范变更 + 相应测试断言更新"，并获人工确认后方算 CLEARANCE。其余 Finding 字段校验不变，回归面小。

---

## 5. PF-004 — QA Baseline 验证（full-suite 健康基线）

### Task ID
`PF-004`

### Purpose
在 PF-001/002/003 全部 CLEAN 后，对 Designer/Tester/Research 全链路跑**一次干净的 full-suite 基线**，作为 QA-S1 的"起点快照"：此后 QA-S1 的任何失败都可明确归因（QA 引入 vs 基线残渣），防止重复 TD-P8-1-001/TD-T7-001 归因争议。

### Dependencies
- PF-001、PF-002、PF-003 全部 ACCEPTED。

### Files / Modules
- 无改动；仅执行验证。

### Allowed changes
- 无（只读执行 `mvn -o clean test`，JDK21+，`JAVA_HOME` 指向 `~/.jdks/openjdk-24.0.1`）。

### Forbidden changes
- **禁止**任何生产/测试代码修改。
- **禁止**在基线 DIRTY 时以"带病放行"方式进入 QA-S1 —— 失败必须归类并指定责任方。

### Acceptance Criteria
1. full-suite `mvn -o test` 达到 **0 failures / 0 errors**（或所有失败逐条归因为已知 pre-existing 并形成基线清单 —— 但 Preflight 目标是把它们清零或显式豁免）。
2. 前端 `npx tsc --noEmit` + `npx vitest run` 全绿（QA-S1 不动前端，作为跨层基线）。
3. 产出 `docs/reports/QA_PREFLIGHT_BASELINE.md`：基线版本号（git HEAD）、测试总数、失败清单（应为空或全部豁免）、环境信息。

### Tests
- full-suite 后端 + 前端 typecheck/test；随机顺序复跑一次后端（验证 fixture 稳定性）。

### Risk
- **低**：纯验证。若出现未豁免失败 → 退回对应 PF 修复或作为新 Preflight Task 提报（不塞进 QA-S1）。

---

## 6. Preflight 门槛定义

- **PRE-FLIGHT CLEARANCE = PF-001 + PF-002 + PF-003 各自独立 Checkpoint ACCEPTED + PF-004 基线全绿。**
- 任一 PF FAIL → 不进入 QA-S1；失败处理与冻结边界无关（PF 触碰面受限如各 Task 声明）。
- QA-S1 启动条件（Gate）：① 架构冻结已人工批准；② 本 Preflight 集合全部 CLEARED；③ 无任何残留 blocking 技术债（TD-T6-001/TD-T7-001 由 PF-003/PF-002 清偿，C4 由 PF-001 清偿）。

## 7. 风险总览（Preflight 专属）

| 风险 | 级别 | 缓解 |
|---|---|---|
| PF-001 破坏既有 Designer 调用方（null→latest 行为变更） | 中 | 实现期 grep 全部调用方；Preflight 报告明示行为变更 |
| PF-002 方案选型再造污染 | 中 | 3 方案对比记录 + 默认选 per-class 独立 DB + 全量复跑 2 次 |
| PF-003 修改冻结契约行为（v999 拒绝） | 中 | 工作流 §41 规范修正 + 测试断言同步 + 人工确认 |
| 三个 PF 合并后 full-suite 仍 DIRTY（历史残渣） | 中 | PF-004 要求归因+豁免清单，不核准"带病放行" |

---

**STOP：本计划定义完毕。等待「ARCHITECTURE FREEZE 人工批准」后再行 Preflight 独立授权与执行。不得自动实施 PF-001~PF-004。**