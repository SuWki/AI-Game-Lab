# Sprint P3-4 完成报告 — 实验/文档工具 ×4（P5 解锁后增量）

> 依据 `docs/state/工作流.md` §8 输出。本 Sprint 为增量 Sprint：仅执行此前因 P5-1/P5-2 阻塞、现已被 P5 解锁的 **P3-4 四工具**。
> 严格执行"只执行 P3-4"红线：未执行 P6-4 / P7 / P8；未重做 P3-1/P3-2/P3-3/P3-5；未修改 Repository 数据层 / ConfigVersion 语义 / Tester 泳道。

---

## 1. 本次执行范围

- **P3-4 #1 simulate_game**：EXECUTE 权限、无审批；参数校验（count ≤ 100000）+ 调 ExperimentService.create()（同步、幂等）。
- **P3-4 #2 analyze_experiment**：READ 权限、无审批；加载 experiment → 解析 result_json → 复用 EvaluationService → 紧凑指标文本表。
- **P3-4 #3 compare_experiments**：READ 权限、无审批；对给定 id 序列两两 Δ 胜率 + z 显著性检验。
- **P3-4 #4 create_design_document**：WRITE 权限、无审批；落 design_document 表（复用 T4-3 已建立的 Repository）。
- 依赖确认（03 文档 + P5 状态）：P3-4 依赖 P5-1（ExperimentService，DONE）+ P5-2（EvaluationService，DONE），均已解除阻塞。

---

## 2. P3-4 #1 simulate_game

| 产物 | 类型 | 说明 |
|---|---|---|
| `tool/SimulateGameTool.java` | 新增 `@Component` | name=`simulate_game`，requiresApproval=false；seed/count 必填，configVersion/scenario 可选；count ≤ 0 抛错、count > 100000 抛错（防超时/资源爆炸）；调 `experimentService.create(configVersion, seed, count, scenario, ctx.runId())`（同步、幂等，严格复用 P5-1 冻结语义）；scenario 语义严格复用 P5-1（crit/penetration/attack_speed 单 build；null/空 → ALL_BUILDS）；返回 payload 含 experimentId/configVersion/seed/simulationCount/scenario/status/winRate/reused + 截断 summaryText |

**关键修复**：SimulateGameToolTest 暴露两处问题并修复——① `@BeforeEach` 的 run 插入缺 task FK（`run.task_id NOT NULL`），修正为先插 task 再插 run；② nameAndSchema 断言误将 count 的 Map 当 List；③ JsonProcessingException 未处理，抽 parse() 辅助消化 checked exception。修复后 7/7 PASS。

验收（03 文档）：dispatch 正常落库且 scenario 语义正确 → ✅ 由 SimulateGameToolTest 验证。

---

## 3. P3-4 #2 analyze_experiment

| 产物 | 类型 | 说明 |
|---|---|---|
| `tool/AnalyzeExperimentTool.java` | 新增 `@Component` | name=`analyze_experiment`，requiresApproval=false；experimentId 必填；加载 experiment（不存在抛 ToolException）→ 解析 result_json（轻量 SimulationResultLike，仅取 buildDistribution winRate/ciLow/ciHigh）→ 复用 `evaluationService.evaluate(configVersion, toSimResult(like))`（失败则"评估跳过"）→ 紧凑文本表（整体/各 build 胜率±CI + 评估结论）→ 截断；不重新实现评估逻辑 |

**设计说明**：result_json 解析为轻量结构（SimulationResultLike），仅提取评估所需字段；toSimResult 将其适配为 EvaluationService 所需的 SimulationResult（buildDistribution 喂 winRate/CI，其余字段填 0/null）。EvaluationService 的 targets 来自 experiment.configVersion 对应的 config，非工具自带。

验收（03 文档）：dispatch 已有实验返回指标表 + 评估结论 → ✅ 由 AnalyzeExperimentToolTest 验证。

---

## 4. P3-4 #3 compare_experiments

| 产物 | 类型 | 说明 |
|---|---|---|
| `tool/CompareExperimentsTool.java` | 新增 `@Component` | name=`compare_experiments`，requiresApproval=false；ids 数组必填（≥2）；加载 experiments（不足 2 抛 ToolException）→ 相邻两两 comparePair（复用 02 §13 公式：z=Δ/√(pA(1−pA)/nA+pB(1−pB)/nB)，\|z\|>1.96 显著；se=0 时 z=0 防除零）→ 返回 payload.pairs 数组 + 截断 summaryText |

**统计说明**：与 P5-3 ExperimentController 的 compare 逻辑同源公式，但 Tool 独立实现（不依赖 REST），便于 Agent 直接调用。

验收（03 文档）：dispatch 两实验返回 Δ+z 显著性 → ✅ 由 CompareExperimentsToolTest 验证（显著/不显著双向）。

---

## 5. P3-4 #4 create_design_document

| 产物 | 类型 | 说明 |
|---|---|---|
| `tool/CreateDesignDocumentTool.java` | 新增 `@Component` | name=`create_design_document`，requiresApproval=false；title/content 必填（缺任一抛 ToolException），configVersion 可选；调 `docRepo.insert(new DesignDocument(null, ctx.runId(), null, title, content, null))` 落 design_document 表（复用 T4-3 已建立的 DesignDocumentRepository + 表，不新增数据层）；返回 payload 含 documentId/title + 截断 summaryText |

**数据层说明**：复用既有 `DesignDocumentRepository.insert` + `design_document` 表（T4-3 已建立），本 Sprint 未新增表、未修改 Repository 契约、未新建 DB。

验收（03 文档）：dispatch 落库且可查询 → ✅ 由 CreateDesignDocumentToolTest 验证。

---

## 6. 修改文件清单

| 文件 | 修改类型 | 所属 Task |
|---|---|---|
| `backend/src/main/java/com/aigamelab/tool/SimulateGameTool.java` | 新增 `@Component` | P3-4 #1 |
| `backend/src/main/java/com/aigamelab/tool/AnalyzeExperimentTool.java` | 新增 `@Component` | P3-4 #2 |
| `backend/src/main/java/com/aigamelab/tool/CompareExperimentsTool.java` | 新增 `@Component` | P3-4 #3 |
| `backend/src/main/java/com/aigamelab/tool/CreateDesignDocumentTool.java` | 新增 `@Component` | P3-4 #4 |
| `backend/src/test/java/com/aigamelab/tool/SimulateGameToolTest.java` | 新增 | P3-4 #1 验收（7 用例） |
| `backend/src/test/java/com/aigamelab/tool/AnalyzeExperimentToolTest.java` | 新增 | P3-4 #2 验收（4 用例） |
| `backend/src/test/java/com/aigamelab/tool/CompareExperimentsToolTest.java` | 新增 | P3-4 #3 验收（5 用例） |
| `backend/src/test/java/com/aigamelab/tool/CreateDesignDocumentToolTest.java` | 新增 | P3-4 #4 验收（5 用例） |
| `backend/src/test/java/com/aigamelab/tool/ToolRegistryIntegrationTest.java` | 新增 | P3-4 Registry 集成（5 用例） |

**零触碰**：Repository 数据层契约、ConfigVersion append-only 语义、active/rollback/activateVersion、REST Controller、Tester 并行泳道、P3-1/P3-2/P3-3/P3-5/P4/P5/P6 已完成代码。

---

## 7. 测试

- 编译：PASS（JDK 21，redhat JRE `21.0.11`；系统 PATH 默认 JDK 1.8 须 JAVA_HOME 指向 JDK 21）。
- **P3-4 专项（本次新增）26/26 PASS**：
  - `SimulateGameToolTest` 7/7：nameAndSchema / 正常落库 / null scenario→ALL_BUILDS / 幂等复用 / 缺 count 抛错 / 超上限 100000 抛错 / count=0 抛错
  - `AnalyzeExperimentToolTest` 4/4：nameAndSchema / 正常解析含 build 指标 / 不存在实验抛 ToolException / 缺 experimentId 抛错
  - `CompareExperimentsToolTest` 5/5：nameAndSchema / 显著差异显著（z>1.96）/ 不显著差异（z<1.96）/ id<2 抛错 / 缺 ids 抛错
  - `CreateDesignDocumentToolTest` 5/5：nameAndSchema / 正常落库且 DB 可查 / 缺 title 抛错 / 缺 content 抛错 / null args 抛错
  - `ToolRegistryIntegrationTest` 5/5：4 工具已注册 / 无重名（names.size==registry.size）/ 总数 16（8 Designer + 8 Tester）/ 4 工具均无审批 / 既有工具仍在（get_game_config 等）
- **全量 `mvn clean test`：315 run / 315 PASS / 0 FAIL**（BUILD SUCCESS，CLEAN，零回归；含 Tester/Designer 全量）。

---

## 8. Scope Check（红线遵守）

| 红线（用户指令） | 是否遵守 |
|---|---|
| 1. 不新增独立 Simulation/Backend 服务 | ✅ 复用 P4/P5 既有能力 |
| 2. 不新增独立数据库 | ✅ 复用既有表 |
| 3. 不新增第二套 Runtime | ✅ |
| 4. 不修改 Repository 数据层契约 | ✅ 仅消费既有 insert/findById/findByIds |
| 5. 不修改 ConfigVersion append-only 语义 | ✅ |
| 6. 不新增 active 字段 | ✅ |
| 7. 不新增 activateVersion() | ✅ |
| 8. 不新增 rollbackVersion() | ✅ |
| 9. 不新增 ConfigController / REST Endpoint（除非 P5 明确） | ✅ 无新增 REST，本 Sprint 纯 Tool 层 |
| 10. 不实现 P6-4 | ✅ |
| 11. 不实现 P7/P8 | ✅ |
| 12. 不修改 Tester 泳道代码 | ✅ |
| 13. 不顺手修复其他线程失败测试 | ✅ |
| 14. 不为了架构更漂亮大规模重构 | ✅ |
| 15. 不重做 P3-1/P3-2/P3-3/P3-5 | ✅ |
| 每 Task 专项自动化测试、真实验证行为 | ✅ 26/26 |
| >10s 操作按 §6.1 广播 | ✅ ToolResult payload + summaryText 截断（≤4000） |

---

## 9. 技术债新增 / 更新

- **无新增技术债**。本 Sprint 未暴露新的设计歧义或临时方案。
- 既有 TD-005（P6，02 vs 03 文档不一致 P6-4）：NOT IMPLEMENTED，未受影响。
- 既有 TD-006（P5-2，acceptableBuildDpsVariance 胜率方差代理）：继续登记，通过 AnalyzeExperimentTool 间接复用，未受影响。

---

## 10. 下一步解锁情况

- **P3 全组现已 DONE**：P3-1 / P3-2 / P3-3 / P3-5（Sprint P3 增量）+ P3-4（本次）。P3 整组收口，无剩余 BLOCKED Task。
- **P6-4**：仍 NOT IMPLEMENTED（TD-005），需独立评估。
- 本报告提交后 **STOP 等待人工 CHECKPOINT**，不自动进入 P7 / P8。

> 按工作流：报告已提交，**STOP 等待人工 CHECKPOINT**。
