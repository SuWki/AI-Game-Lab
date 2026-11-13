# R8 DOCUMENTATION & INFRASTRUCTURE HOUSEKEEPING

> **性质**：文档 + 构建环境维护（R8）。只修正文档/注释/Javadoc/构建环境记录；不实现新功能、不修改生产逻辑。
> **状态**：**R8 DOCUMENTATION & INFRASTRUCTURE HOUSEKEEPING — COMPLETE / WAITING FOR HUMAN ACCEPTANCE**。
> **生成时间**：2026-09-17。
> **审计基线**：R7 审计报告已提交（commit f61e0a8，后端 620/0/0，前端 57/0/0）。
> **冻结红线**：全程零 Frozen 文件改动（AgentLoop/RunStateMachine/BudgetGuard/LoopDetector/ToolExecutor/ToolResult/ErrorCodes/AgentEventType）。

---

## §17.1 Scope（本轮范围）

R8 是纯文档 + 构建环境改善，包含 8 个 Task（A1-A8）：

| 编号 | 任务 | 类型 |
|---|---|---|
| A1 | R6 ErrorCode doc 勘误（20001/50001/50301 → 实际 6 码） | 文档勘误 |
| A2 | task-state.md TD-T6-001/TD-T7-001 SSOT 统一 | 文档状态同步 |
| A3 | FindingRepository/TestPlanRepository stale Javadoc 修正 | Javadoc 勘误 |
| A4 | AgentEventType "6 枚举追加至 22" → 当前共 26 | 文档计数修正 |
| A5 | R7 报告执行摘要统计口径区分（12 维度 / 9 返回 / 35 Findings 互不混淆） | 文档措辞修正 |
| A6 | R7 §15 roadmap P2→P3 依赖措辞修正（P2/P3/P5 独立轨道） | 文档措辞修正 |
| A7 | JDK 21 构建可复现（.java-version + BUILD_ENVIRONMENT.md + check-env 脚本） | 构建环境文档 |
| A8 | 全局一致性 re-grep（ErrorCode / AgentEventType / T-DB1 / TD-T6/T7 / 基线） | 一致性审计 |

**严禁**（本轮未做，STOP 于红线）：生产代码改动、新增功能、Schema 改动、AgentType/Tool/Prompt/ErrorCode 变更、R7-P2/P3/P4/P5 实施、QA Phase 2 编排改动。

---

## §17.2 Task Results（A1-A8）

### A1 — R6 ErrorCode doc 勘误
**完成。** `docs/reports/R6_QA_PHASE2_CLOSURE_AUDIT.md` §13 原含码值错误（20001/50001/50301 为凭空猜测）。追加 R8-A1 勘误注释，修正后契约为准确 6 码（40001/40401/40901/42901/50201/50000）；保留原文（历史审计事实）。

### A2 — TD-T6-001/TD-T7-001 SSOT 统一
**完成。** `docs/state/task-state.md` 技术债表（原 1098-1099 行）：TD-T6-001/TD-T7-001 从 `OPEN / DEFERRED` 改为 `RESOLVED`（已清偿），标注 PF-003/AbstractRepositoryTest FK-safe 修复（2026-09-04 基线 GREEN），与 `docs/state/tech-debt.md` 已清偿节一致。同时 R7 发现记录追加 R8 勘误（码值列表纠正为 6 码）。

### A3 — FindingRepository/TestPlanRepository Javadoc 修正
**完成。** `backend/.../repository/FindingRepository.java` 与 `TestPlanRepository.java` 原 Javadoc 仍声称 "T-DB1 人工闸门未批准——schema 无此表，Impl 待批准"（T2 时代过渡描述）。替换为：T-DB1 已批准、DDL 已落地、Impl 已在树（R8-A3 勘误注释保留原描述记录）。

### A4 — AgentEventType 计数修正
**完成。** `docs/state/task-state.md` "Tester V1 能力边界" 节：原文 "TEST_* Event 枚举（6 枚举追加至 22）" → "T4-3 追加 6 枚至 16 基础；当前含 RESEARCH_* 共 **26** 枚"。验证来源：AgentEventType.java 实际 26 值（16 基础 + 6 TEST_* + 4 RESEARCH_*）。

### A5 — R7 执行摘要统计口径区分
**完成。** `docs/reports/R7_POST_QA_PHASE2_ARCHITECTURE_ROADMAP_AUDIT.md` §1 执行摘要：原文 "16 项审计 PASS/CONDITIONALLY PASS" 混淆审计维度数与条目数。重写为分列表格：审计维度总数 12 / 返回 9 / Findings 35 / 技术债 24 / 架构风险 11 / 人工决策 8，附说明段落。同时修正 Findings #2 的 ErrorCode 列表为准确 6 码（原列 4 码不完整）。

### A6 — R7 §15 roadmap 依赖措辞修正
**完成。** `docs/reports/R7_POST_QA_PHASE2_ARCHITECTURE_ROADMAP_AUDIT.md` §15：原文以串行箭头把 P2 → P3/5 画成关键路径（暗示 P3 依赖 P2）。重写为并列轨道图，明确 P2/P3/P5 为独立轨道（P3 后端 API 已在树，可独立进行）。同步修正 H-1 影响描述（原称 "阻塞后续 Phase 3+"，改为 "不影响 Phase 3/5"）。

### A7 — JDK 21 构建环境文档
**完成。** 交付 4 件：

| 文件 | 说明 |
|---|---|
| `.java-version`（仓库根） | 内容 `21`，jenv/sdkman 兼容 |
| `docs/BUILD_ENVIRONMENT.md` | 项目级构建环境文档：Required JDK 21+、Maven 3.9.x、JAVA_HOME 设置指引、本机陷阱（系统 JAVA_HOME 指向 JDK 8）、验证命令、已验证事实表 |
| `scripts/check-env.sh` | 跨平台 env 自检脚本（JDK / JAVA_HOME / Maven / Node / npm），退出码 0=PASS |
| `scripts/check-env.bat` | Windows cmd 版本 |

同时更新 `README.md`（新增 "构建与开发环境" 节，引用 BUILD_ENVIRONMENT.md + check-env 脚本）；同步更新 `docs/state/tech-debt.md` TD-001 当前解决方式描述（VS Code 插件 JRE → ~/.jdks/openjdk-24.0.1 + BUILD_ENVIRONMENT.md）。

### A8 — 全局一致性 re-grep
**完成。** 全库 grep 20001/50001/50301 / 22 AgentEventType / T-DB1 / TD-T6-001 / TD-T7-001 / R1/R2/R6/R7 基线值，结论：

- **20001/50001/50301**：R6 §128（已由 A1 勘误纠正）；R7 行 36（已由 A5 勘误纠正为 6 码）；task-state R7 条目（已由 A2 勘误纠正）；R7 §379 / TS-03 / TD-R7-004（均为历史发现记录，保留原描述）→ **已收敛，无残留当前契约错误**
- **"22 AgentEventType"**：task-state 行 710（T4-3 历史任务记录 "16→22"，准确的历史事实）；行 1061 已由 A4 修正；R7 报告 TS-03/TD-R7-004（历史审计记录，保留）；R4 架构文档（26，准确）→ **已收敛**
- **T-DB1 "未批准" / "无此表"**：FindingRepository/TestPlanRepository.java（已由 A3 勘误）；task-state 行 543（T2 Sprint 历史事实，保留）；R7 报告 TS-01（历史发现记录，保留）→ **已收敛**
- **TD-T6-001 / TD-T7-001 状态**：task-state 表（已由 A2 修正为 RESOLVED）；tech-debt.md 已清偿节（已 RESOLVED）；R7 报告 TS-02/TD-R7-002（历史发现记录，描述"task-state 仍为 OPEN"——这是当时的事实描述，保留）；R7 行 1468（同上）→ **已收敛，当前状态一致**
- **历史基线值**（R1=413 / R2=441 / Preflight=443 / R6=620）：均为各 Sprint 当时的准确记录，保留不改

---

## §17.3 Baseline（R8 前基线）

| 维度 | 值 | 来源 |
|---|---|---|
| 后端测试 | 620/0/0 BUILD SUCCESS | R6 §15 实测（2026-09-16） |
| 前端测试 | 57/0/0 + tsc + build | R6 §15 实测（2026-09-16） |
| 数据库表 | 16 张 | schema.sql |
| ErrorCode | 6 码 | ErrorCodes.java |
| AgentType | 8 值 | AgentType.java |
| AgentEventType | 26 值 | AgentEventType.java |
| Prompt | 13 个（1 active = designer_p001） | PromptService |
| Git HEAD | `f61e0a8` | git rev-parse |

---

## §17.4 Final Regression（R8 实测，2026-09-17）

### 后端

```
JAVA_HOME=~/.jdks/openjdk-24.0.1 (JDK 24.0.1, --release 21)
mvn clean test  →  Tests run: 620, Failures: 0, Errors: 0, Skipped: 0
                   BUILD SUCCESS
```

（surefire-reports 87 测试类，聚合 620/0/0）

### 前端

```
npm test -- --run  →  Test Files: 9 passed (9), Tests: 57 passed (57)
npm run build      →  tsc -b && vite build → ✓ built in 2.08s
npx tsc --noEmit   →  (无输出 = 0 TS errors)
```

### 结论

回归全绿。R8 全部修改仅涉及文档/注释/Javadoc/构建环境文件，**后端 620/0/0、前端 57/0/0 与 R6 基线一致**，无回归。

---

## §17.5 Frozen Audit（8 文件零 diff）

| 文件 | Git diff vs HEAD |
|---|---|
| runtime/AgentLoop.java | **0 行** |
| runtime/RunStateMachine.java | **0 行** |
| runtime/BudgetGuard.java | **0 行** |
| runtime/LoopDetector.java | **0 行** |
| runtime/ToolExecutor.java | **0 行** |
| tool/ToolResult.java | **0 行** |
| common/ErrorCodes.java | **0 行** |
| event/AgentEventType.java | **0 行** |

**`git diff HEAD -- <8 frozen files>` → 0 行输出（零改动）。** 冻结边界完全尊重。

---

## §17.6 Schema Audit（16 表未变）

`backend/src/main/resources/schema.sql` 中 `CREATE TABLE` 语句数 = **16**。与 R6 基线一致，零 DDL 改动。

---

## §17.7 Out-of-Scope / 已知 WIP / 已知遗留

### 保留不动（Out-of-scope WIP）
- `frontend/src/pages/TestPlanPage.test.tsx` — 存在、未修改、未包含在 R8 提交
- `backend/src/test/java/com/aigamelab/qa/QaP1_5Test.java` — 存在、未修改、未包含在 R8 提交
- 两文件保持 "OUT-OF-SCOPE / HUMAN DISPOSITION REQUIRED"

### 工作树中未提交变更（R8 外产生，非 R8 产生）

> ⚠️ **R8 报告勘误（提交后核查）**：初稿曾将下述 QA 文件描述为"工作树未提交变更"。经 `git reflog` / `git show` 核验，该两文件的修改**已于 R8 会话进行期间由独立提交 `813e33b`（QA Phase 2 Implementation 收敛硬化轮：gate 域闭合 + cross-lead 隔离 + deadline 治理测试）纳入仓库**，R8 主 commit `8746217` 的 parent 为 `388503a`（813e33b 报告回填）。R8 **未触碰、未包含**此两文件；最终工作树干净。

| 文件 | 变更内容 | 归属 |
|---|---|---|
| `backend/.../domain/QaReleaseGate.java` | STATUSES 列表追加 `"INCONCLUSIVE"` | **已提交于 `813e33b`**（R8 外，QA Phase 2 收敛硬化轮） |
| `backend/.../qa/QaOrchestratorService.java` | checkSubrunStatus 归属校验 + findByLeadTask fallback + setDeadlineForTest() | **已提交于 `813e33b`**（R8 外，QA Phase 2 收敛硬化轮） |

**处置**：两文件内容为独立提交的工程事实，R8 未修改未提交未回滚，其验收归属 QA Phase 2 收敛硬化轮（等人类验收），与 R8 无关。

---

## §17.8 Remaining R7 Findings（仍为 HOLD / ROADMAP）

R8 为文档/构建环境改善，不解决 R7 的任何架构/生产代码发现。以下 R7 发现 **仍为 HOLD / 路线图项目**，不被 R8 完成或推进：

| 编号 | 内容 | 状态 | 备注 |
|---|---|---|---|
| R-01 / FB-02 | AgentLoop.observe() 零历史回放（HIGH） | **HOLD** — 需 H-1 裁决解冻 | R7-P2；冻结文件，须人工授权 |
| R-11 / SP-01 / TD-02 / TD-R7-018 | ToolExecutor dispatch 无白名单校验（HIGH） | **HOLD** — H-9bis，需独立冻结授权 | ToolExecutor.java 冻结 |
| QA-01 | dominantBuildShare 死代码 | **HOLD** — H-5 | 需接线 wiring |
| QA-02 | Wall-clock 300s deadline 零测试覆盖 | **HOLD** — 需人工授权 | 不可自动修复 |
| QA-03 | checkSubrunStatus fallback task/run id 混淆 | **HOLD** — H-6 | 独立轨道 |
| QA-04 | INCONCLUSIVE 域不闭合 | **HOLD** — H-6 | 独立轨道 |
| QA-05 | 顺序编排仅 prompt 约定 | **HOLD** — 已知 |
| QA-06 | normalizeLeadDecision substring 匹配 | **HOLD** — 低优先级 |
| FB-01 | RUN_TIMEOUT 事件缺口（不可达当前） | **HOLD** — 待 timeoutMs 启用 |
| SP-02 | 非 QA 工具线无 L3 guard | **HOLD** — 独立轨道 |
| SP-04 | QaToolIsolationTest 隔离覆盖不全 | **HOLD** — 独立轨道 |
| R7-P3 | QA Dashboard 前端 | **HOLD** — 独立轨道，可独立排期 |
| R7-P4 | Project Isolation（project_id） | **HOLD** — 独立轨道，可延迟 |
| R7-P5 | 持久化 Deadline | **HOLD** — 独立轨道 |
| Research TK | 信任知识库 | **HOLD** — H-7 |

**不得因 R8 完成而自动推进任何 R7-P2/P3/P4/P5 / QA Phase 3 / Project Isolation / Persistent Deadline。**

---

## 14 项最终核验清单

| # | 核验项 | 结果 |
|---|---|---|
| 1 | A1 完成（R6 ErrorCode 勘误） | ✅ R6 §13 已修正（R8-A1 勘误注释 + 准确 6 码） |
| 2 | A2 完成（TD-T6/T7 SSOT） | ✅ task-state.md:1098-1099 已改 RESOLVED，与 tech-debt.md 一致 |
| 3 | A3 完成（Repository Javadoc） | ✅ FindingRepository + TestPlanRepository Javadoc 已修正 |
| 4 | A4 完成（AgentEventType 26） | ✅ task-state.md 行 1061 已修正 |
| 5 | A5 完成（R7 统计口径） | ✅ R7 §1 执行摘要重写为分列表格 |
| 6 | A6 完成（R7 §15 roadmap） | ✅ P2/P3/P5 独立轨，H-1 影响描述同步修正 |
| 7 | A7 完成（JDK21 构建文档） | ✅ .java-version + BUILD_ENVIRONMENT.md + check-env.sh/.bat + README 增强 + tech-debt TD-001 同步 |
| 8 | A8 完成（全局一致性） | ✅ grep 20001/50001/50301 / 22 AgentEventType / T-DB1 / TD-T6/T7 / 基线 — 全部收敛，无残留当前契约错误 |
| 9 | 后端回归 | ✅ **620/0/0 BUILD SUCCESS**（surefire-reports 87 类聚合） |
| 10 | 前端回归 | ✅ **57/0/0**（9 测试文件）+ `tsc --noEmit` PASS + `npm run build` PASS |
| 11 | Frozen 8 文件零 diff | ✅ `git diff HEAD -- <8 frozen>` = 0 行 |
| 12 | Schema 16 表未变 | ✅ CREATE TABLE = 16，零 DDL |
| 13 | Commit | ✅ 单一 commit `8746217` `"R8: documentation and infrastructure housekeeping"`（11 文件，+328/-41）；工作树残留 QaReleaseGate/QaOrchestratorService 未提交 |
| 14 | Git status 准确披露 | ✅ R8 提交后：R8 文件已提交；QA 硬化文件已由独立提交 `813e33b` 纳入（R8 外）；2 个 WIP 文件（TestPlanPage.test.tsx / QaP1_5Test.java）仍在工作树未提交 |

---

**R8 DOCUMENTATION & INFRASTRUCTURE HOUSEKEEPING — COMPLETE / WAITING FOR HUMAN ACCEPTANCE**
