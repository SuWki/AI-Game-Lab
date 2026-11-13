# QA Preflight 基线（PF-004 交付）— QA_PREFLIGHT_BASELINE

> 归属：QA-S1 前置 Preflight 集合 `PF-004`（`docs/QA_PREFLIGHT_PLAN.md` §5）。
> 状态：**GREEN（基线全绿，已豁免 1 例既有顺序依赖偶发失败）**。
> 生成时间：2026-09-04。

## 1. 基线版本与环境

| 项 | 值 |
|---|---|
| git HEAD | `33b6b15`（`33b6b15 Initial commit`，分支 `main`） |
| JDK | `C:\Users\Administrator\.jdks\openjdk-24.0.1` |
| 构建 | `mvn -o test`（backend，surefire 顺序/随机两种 ordering） |
| SQLite | per-class 临时文件，PRAGMA `journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON` |
| 前端 | `npx tsc -b --noEmit` + `npx vitest run`（frontend，QA-S1 不动前端，作为跨层基线） |

## 2. 后端基线结果

| 运行方式 | 结果 |
|---|---|
| 顺序 full-suite `mvn -o test` | **443 tests / 0 failures / 0 errors — BUILD SUCCESS** |
| JUnit 方法级随机（`-Djunit.jupiter.execution.order.strategy=random -Djunit.jupiter.execution.order.random.seed=7`） | **443 tests / 0 failures / 0 errors — BUILD SUCCESS** |
| surefire 类级随机（`-Dsurefire.runOrder=random`） | **BUILD SUCCESS（无失败/错误行）** |
| `RepositoryTest` 类单独顺序跑 | **16 / 16 PASS** |
| surefire-report 汇总（末次全量） | Total=443 Failures=0 Errors=0 |

> 说明：顺序 full-suite 为指令要求的准入基线（0 failures / 0 errors）；两种随机 ordering 复跑均全绿，验证了 PF-002 自包含 fixture（QaTestFixture 独立 DB）在运行顺序变化下稳定。

## 3. 前端基线结果（跨层，QA-S1 不动前端）

| 项 | 结果 |
|---|---|
| `npx tsc -b --noEmit` | exit 0（通过） |
| `npx vitest run` | 3 files / **16 tests passed** |

## 4. 失败清单与豁免

### 4.1 豁免项 R-001：`RepositoryTest.eventReplayAfterIdAndRecent`（既有顺序依赖，非 PF 引入）

- **观测**：资料记录一次随机顺序 full-suite 出现该用例失败（Expected size 3 but was 5，返回 5 条 AgentEvent，id 区间 16-20）。
- **根因（已定位，非猜测）**：
  1. `schema.sql` `agent_event.id INTEGER PRIMARY KEY AUTOINCREMENT`；`DELETE FROM agent_event` **不重置 `sqlite_sequence`**。
  2. `AbstractRepositoryTest` 用共享 `static final Path DB`（L32），全测试树共 **7 个子类**共享同一临时 DB 文件：`RepositoryTest`、`ResearchRepositoryTest`、`TesterRepositoryTest`、`TesterServicesTest`、`TesterReadToolsTest`、`TesterWriteToolsTest`、`InvariantIntegrationTest`。
  3. 其中工具/集成类经 `ToolExecutor`→`AgentEventListener` 发布领域事件写入 `agent_event`，把共享 DB 的 `sqlite_sequence` 推进到 15+。
  4. 随机类顺序下 `RepositoryTest.eventReplayAfterIdAndRecent` 可能在它们之后执行：新插 5 条拿到 id 16-20，全部 `> afterId=2` → `findByRunIdAfter(1, 2, 1000)` 返回 5 条而非期望 3 条。
  5. 该用例硬编码"agent_event id 从 1 开始"的隐含假设（`afterId=2` → 期望 evt-3/4/5），在共享 DB + AUTOINCREMENT 下随顺序漂移。
- **与 PF 改动无交集**：失败位于未触碰文件（`RepositoryTest`、`AbstractRepositoryTest`、`schema.sql`、`AgentEventRepository`）；PF-001/002-A/003 只改 `ExperimentService`、新增 QA fixture（独立 DB）、`FindingService`。单类顺序跑 16/16 通过，证明与 PF 无关。
- **本次复跑**：JUnit 方法级随机（seed=7）与 surefire 类级随机均 **BUILD SUCCESS**，该失败未复现——判定为随机组合下的**偶发/非确定**既有缺陷。
- **豁免理由**：PF-004 验收标准允许"所有失败逐条归因为已知 pre-existing 并形成基线清单"（`QA_PREFLIGHT_PLAN.md` §5）。该例归因为 pre-existing 测试基建顺序依赖，已记录为技术债（见 `docs/state/tech-debt.md` TD-QA-R-001），不阻塞 QA-S1 准入门。
- **后续建议**：QA-S1（或独立测试基建 Task）将 `RepositoryTest` 该用例改为不依赖绝对 id（如先用 `select max(id)` 或按 `runId` 隔离断言），或为每类改用独立临时 DB（对齐 PF-002 选型 A）。

### 4.2 无未豁免失败

除 R-001 外无任何持久/偶发失败；顺序 full-suite 为 0/0 全绿。

## 5. 与既有技术债的对账

| 技术债 | PF 处理 | 状态 |
|---|---|---|
| TD-T6-001（FindingService 未校验 configVersion 存在） | PF-003 清偿（existence 校验 + 测试） | **RESOLVED** |
| TD-T7-001（full-suite FK 污染 / 共享 DB 传染） | PF-002-B：根因 = 共享 static DB + partial DELETE，已由 FK-safe 清理 + ensureSeedV001 修复；PF-002-A 另供 QA 专用独立 DB fixture | **基线 GREEN** |
| TD-QA-R-001（上述随机顺序偶发失败） | PF-004 归因 + 豁免 | **登记技术债（不阻塞）** |

## 6. QA-S1 准入门自检

- [x] ① PF-001/002/003 各自 Checkpoint ACCEPTED（本轮 Preflight）。
- [x] ② 无残留 blocking 技术债：TD-T6-001 / TD-T7-001 已清偿，C4 (PF-001) 已修复。
- [x] ③ 基线全绿（本节 §2/§3）。
- [x] ④ `QA-S1` **未启动**（`NOT STARTED`）：无 QA Orchestrator/Agent/Tool/Release Gate/QASSE/QA 前端实现。