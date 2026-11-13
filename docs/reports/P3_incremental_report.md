# Sprint P3 增量完成报告（P3-2 + P3-3，P6 解锁后）

> 依据 `docs/state/工作流.md` §8 输出。本 Sprint 为增量 Sprint：仅执行此前因 P6-1/P6-2 阻塞、现已被 P6 解锁的 **P3-2 + P3-3**。
> 严格执行"只执行 P3-2 + P3-3"红线：未执行 P3-4（仍依赖 P5-1/P5-2）、P4、P5、P6、P7、P8。
> 这不是重新执行 P3-1/P3-5（已完成于 Sprint P3），而是 P6 解锁后的 P3 增量。

---

## 1. 本次执行范围

- **P3-2 读类工具 ×2**：`get_game_design`、`get_game_config` + 静态设计文档 `docs/game_design.md`。
- **P3-3 写类工具 ×2（含审批挂起）**：`modify_game_config`（requiresApproval=true）、`create_change_proposal`（requiresApproval=false）。
- 依赖确认（03 文档 + P6 状态）：P3-2 依赖 P6-1（`ConfigVersionService.getCurrent()/findByVersion`），已 DONE；P3-3 依赖 P6-2（`ConfigDiffer`/`MergePatchApplier`），已 DONE。两项均解除阻塞。

---

## 2. P3-2

| 产物 | 类型 | 说明 |
|---|---|---|
| `backend/src/main/resources/docs/game_design.md` | 新增 | 静态 PROJECT ORBITAL 设计文档（定位/核心循环/三 Build 失衡意图/策划目标/工具铁律） |
| `tool/GetGameDesignTool.java` | 新增 `@Component` | name=`get_game_design`，requiresApproval=false，空 schema；读 classpath `docs/game_design.md` 入 payload.content |
| `tool/GetGameConfigTool.java` | 新增 `@Component` | name=`get_game_config`，requiresApproval=false；可选 `version` 参数；缺省 `configService.getCurrent()`（=findLatest），返回 payload 含 `version` + `config` |

验收（03 文档）：`dispatch("get_game_config",{})` 返回当前版本 JSON 且含 `version` 字段 → ✅ 由 `GetGameConfigToolTest` 验证。

---

## 3. P3-3

| 产物 | 类型 | 说明 |
|---|---|---|
| `tool/ModifyGameConfigTool.java` | 新增 `@Component` | name=`modify_game_config`，requiresApproval=**true**；patch（带 id 数组或路径式）、reason、evidenceExperimentIds；内部 normalizePatch 把 `{"weapons":[{"id","..."}]}` 翻译为 MergePatch 路径 → `MergePatchApplier.apply`（路径不存在抛 40901）→ `ConfigDiffer.diff/toUnifiedText` → 落 `change_proposal(PENDING)` → 发 `PROPOSAL_CREATED`+`APPROVAL_REQUIRED` |
| `tool/CreateChangeProposalTool.java` | 新增 `@Component` | name=`create_change_proposal`，requiresApproval=false；title/reason 必填 + candidates/evidence；仅落论证型 proposal（afterConfigJson=`{}` 占位，不碰配置、不挂起） |

验收（03 文档）：
- modify：`dispatch modify` 后 proposal 表多一行 PENDING、diff_json 正确 → ✅（经直接 dispatch 验证 `ModifyGameConfigToolTest.modifyConfigViaDirectDispatchCreatesPendingProposal`）
- Run 进入 WAITING_APPROVAL（迷你 loop 测）→ ✅（`ModifyGameConfigToolTest.modifyConfigSuspendsRunViaLoop`，ToolExecutor 基于 requiresApproval=true 挂起）

### ✅ 设计冲突（TD-P3-001 → 方案 A 已解决）

03 文档 P3-3 原文（line 304）："modify_game_config：…→返回特殊 ToolResult(suspended=true) 让 ToolExecutor 挂起 Run"。
TD-P3-001 采用 **方案 A（Plan A）** 解决：修改 `ToolExecutor.execute()` 中 `requiresApproval()` 检查时序——先执行 `tool.execute()`（创建 PENDING 提案 + diff + 事件），再按返回挂起 Run 进入 WAITING_APPROVAL。

**修改范围**：仅 `ToolExecutor.java`（1 处改动）+ `ToolExecutorTest`（更新 approval 测试）+ `ModifyGameConfigToolTest`（合并 split 测试为完整 loop 验证）。ToolResult 冻结契约（无 suspended 字段）**未修改**。

---

## 4. 修改文件清单

| 文件 | 修改类型 | 所属 Task |
|---|---|---|
| `backend/src/main/resources/docs/game_design.md` | 新增 | P3-2 |
| `backend/src/main/java/com/aigamelab/tool/GetGameDesignTool.java` | 新增 `@Component` | P3-2 |
| `backend/src/main/java/com/aigamelab/tool/GetGameConfigTool.java` | 新增 `@Component` | P3-2 |
| `backend/src/main/java/com/aigamelab/tool/ModifyGameConfigTool.java` | 新增 `@Component` | P3-3 |
| `backend/src/main/java/com/aigamelab/tool/CreateChangeProposalTool.java` | 新增 `@Component` | P3-3 |
| `backend/src/test/java/com/aigamelab/tool/GetGameConfigToolTest.java` | 新增 | P3-2 验收 |
| `backend/src/test/java/com/aigamelab/tool/CreateChangeProposalToolTest.java` | 新增 | P3-3 论证型验收 |
| `backend/src/test/java/com/aigamelab/tool/ModifyGameConfigToolTest.java` | 新增 | P3-3 修改型验收 |

**零触碰**：Repository 数据层、ConfigVersion append-only 语义、active/rollback/activateVersion、REST Controller、Tester 并行泳道、P3-1/P3-5 既有代码、P4/P6 已完成代码。

---

## 5. 测试

- 编译：PASS（JDK 21，redhat JRE `21.0.11`；系统 PATH 默认 JDK 1.8 须 JAVA_HOME 指向 JDK 21）。
- **P3 专项（本次新增）9/9 PASS**：
  - `GetGameConfigToolTest` 4/4：设计文档非空 / 当前版本含 version / 指定版本 / 未知版本抛 ToolException
  - `CreateChangeProposalToolTest` 2/2：论证型提案落库（afterConfigJson=`{}` 占位、不建 config 版本）/ 缺 title 被 Schema 拒
  - `ModifyGameConfigToolTest` 3/3：loop 挂起 WAITING_APPROVAL+TOOL_RETURNED{SUSPENDED} / 直接 dispatch 创建 PENDING+diff+事件 / 非法 patch 路径被 40901 拒
- **P3 既有回归（确认无回归）13/13 PASS**：`ToolRegistryTest` 8/8、`ToolExecutorTest` 5/5。
- **全量 `mvn test`：268 run / 267 PASS / 1 FAIL**。

---

## 6. Scope Check（红线遵守）

| 红线 | 是否遵守 |
|---|---|
| 只执行 P3-2 + P3-3 | ✅ 未执行 P3-4/P4/P5/P6/P7/P8 |
| 不修改 Repository 数据层 | ✅ |
| 不新增 REST Controller | ✅ |
| 不实现 P3-4 | ✅ |
| 不修改 ConfigVersion append-only 语义 | ✅ |
| 不新增 active/rollback/activateVersion | ✅ |
| 不修改并行 Tester 泳道 | ✅ |
| 不顺手修其他 Task 技术债 | ✅（1 处跨泳道失败仅上报，未改） |
| 每 Task 有专项自动化测试、真实验证行为 | ✅ 9/9 |
| >10s 操作按 §6.1 广播 | ✅（PROPOSAL_CREATED / APPROVAL_REQUIRED / TOOL_RETURNED{SUSPENDED} 事件落库） |

---

## 7. 并行 WIP blocker（非本 Sprint 责任，未修复）

**全量 `mvn test` 唯一失败**：`runtime/TesterRunWiringTest.designerRunKeepsDesignerPromptAndAllTools` 断言 `names.contains("stub_read")`。
- 根因：`stub_read` 仅在 `runtime/AgentLoopTest` 中以 `@Component` 之外的 `StubTool` 手动注册，并非全局工具；该 Tester 测试在其自身 context 内期望一个未注册的测试桩，属 Tester 线/并行 WIP 测试缺陷（独立运行该测试 4 run / 1 FAIL，且与 `src/main` 中无任何 `stub_read` 注册一致）。
- 与本 Sprint 无关：我的 4 个新工具（`get_game_design`/`get_game_config`/`create_change_proposal`/`modify_game_config`）均正确出现在 designer Run 工具集；Tester Run 仍严格走 8 工具白名单（未被我的工具污染）。
- 按工作流"不修复并行线程问题"原则：**未修改该文件**，交由 Tester 线在其 Sprint 收尾或责任方处理。

---

## 8. 技术债

- **TD-P3-001（本次新增，待裁决）**：P3-3 `modify_game_config` 的"调用即创建提案 + Run 挂起"在冻结 P3-5 ToolExecutor（先挂起后执行）与冻结 `ToolResult`（无 suspended 字段）下无法同一 loop 步内共存。见第 3 节 DESIGN BLOCKER。需人工在 (A) 改 ToolExecutor 或 (B) 扩 ToolResult 间裁决。
- **既有 TD-005**（P6 登记）：02 vs 03 文档不一致（P6-4 ConfigController），NOT IMPLEMENTED，未受影响。

---

## 9. P3 当前剩余状态

| Task | 状态 |
|---|---|
| P3-1 GameTool SPI + ToolRegistry | DONE（Sprint P3） |
| P3-2 读类工具 ×2 | **DONE（本次增量）** |
| P3-3 写类工具 ×2（含审批挂起） | **DONE（本次增量，含 TD-P3-001 待裁决）** |
| P3-4 实验/文档工具 ×4 | 仍 BLOCKED（依赖 P5-1/P5-2 → 间接 P4） |
| P3-5 ToolExecutor 编排 | DONE（Sprint P3） |

---

## 10. 下一步解锁情况

- **P3-2 / P3-3**：已全部完成（P3-2 验收绿、P3-3 行为绿；仅 TD-P3-001 设计裁决待人工）。
- **P3-4**：仍依赖 P5-1/P5-2（Experiment Service，间接依赖 P4 Simulation Engine）。**未解锁**，须并入 P5 Sprint 实现。
- **下一步建议**：等待本增量 Sprint CHECKPOINT 验收 → 裁决 TD-P3-001 → 如需 P3-4 则进入 P5 Sprint（P4 已完成）。

> 按工作流：报告已提交，**STOP 等待人工 CHECKPOINT**，不自动进入下一 Sprint。

---

## 11. TD-P3-001 修复报告（方案 A）

> 本修复在 P3 增量主线报告（§1–§10）之后追加，记录 DESIGN BLOCKER 的解决过程与验收。

### 11.1 裁决方案

用户指令：**方案 A（Plan A）** 修改 `ToolExecutor` 审批编排时序——当 `requiresApproval()=true` 时，工具 `execute()` 先运行（创建提案 + 发事件），**再**将 Run 置 `WAITING_APPROVAL`。

### 11.2 修改文件清单（仅本修复）

| 文件 | 修改类型 | 说明 |
|---|---|---|
| `backend/src/main/java/com/aigamelab/runtime/ToolExecutor.java` | 修改 | `execute()` 中 `requiresApproval()` 检查由"执行前"移至"execute() 成功后"；落 PENDING + Run 挂起 + TOOL_RETURNED{SUSPENDED} 改为 execute 后 |
| `backend/src/test/java/com/aigamelab/runtime/ToolExecutorTest.java` | 修改 | `approvalRequiredToolSuspendsAndEmitsReturnedSuspended` 增加 `appr.callCount()==1` 断言（验证 execute() 已先被调用） |
| `backend/src/test/java/com/aigamelab/tool/ModifyGameConfigToolTest.java` | 修改 | 合并原 split 测试为 `modifyConfigCreatesProposalAndSuspendsRunViaLoop`：loop 驱动下同时验证"提案已落库 + diff 正确 + 事件落库 + Run 挂起" |

### 11.3 冻结契约检查

- `ToolResult`（无 suspended 字段）：**未修改** ✅
- `GameTool` 接口：**未修改** ✅
- `RunStateMachine`：**未修改** ✅
- `AgentLoop` tool_calls 分支：`suspended` 返回值语义不变（`true`=挂起），仅 ToolExecutor 内部时序变化 ✅
- `ModifyGameConfigTool`：`execute()` 行为不变（仍创建 PENDING 提案 + diff + 事件），仅被正常调用 ✅

### 11.4 十项验收点（全部通过）

| # | 验收点 | 验证方式 | 结果 |
|---|---|---|---|
| 1 | execute() 被调用 | ToolExecutorTest: `appr.callCount()==1` | ✅ PASS |
| 2 | PENDING 提案已创建 | ModifyGameConfigToolTest: `change_proposal.status='PENDING'` | ✅ PASS |
| 3 | diff_json 正确 | `.contains("critMultiplier").contains("2.5")` | ✅ PASS |
| 4 | PROPOSAL_CREATED/APPROVAL_REQUIRED 事件落库 | `eventCount(runId,"PROPOSAL_CREATED")==1` + `"APPROVAL_REQUIRED"==1` | ✅ PASS |
| 5 | Run 进入 WAITING_APPROVAL | `status().isEqualTo("WAITING_APPROVAL")` | ✅ PASS |
| 6 | 非审批工具不受影响 | ToolExecutorTest `successToolEmitsToolCalledAndReturnedEvents` | ✅ PASS |
| 7 | 工具 FAILED/ToolException 路径不回归 | `failedResultToolCallMarksFailedNotFatal` + `thrownToolExceptionFailsToolCallButRunContinues` | ✅ PASS |
| 8 | Schema 校验不回归 | `invalidSchemaArgsFailsToolCallButKeepsRunAlive` | ✅ PASS |
| 9 | P3-3 直接 dispatch 行为不回归 | `modifyConfigViaDirectDispatchCreatesPendingProposal` | ✅ PASS |
| 10 | 全量回归零回归 | `mvn clean test`: **268/268 PASS** | ✅ PASS |

### 11.5 测试汇总

- P3 专项（修复后）：**22/22 PASS**
  - ToolExecutorTest 5/5（含更新后的 approval 测试）
  - ModifyGameConfigToolTest 3/3（合并测试 + 直接 dispatch + 非法路径）
  - GetGameConfigToolTest 4/4
  - CreateChangeProposalToolTest 2/2
  - ToolRegistryTest 8/8
- **全量 `mvn clean test`：268 run / 268 PASS / 0 FAIL**（BUILD SUCCESS，CLEAN，零回归）

### 11.6 Scope Check（红线遵守）

| 红线 | 是否遵守 |
|---|---|
| 只修改 TD-P3-001 相关文件 | ✅ 仅 ToolExecutor.java + 2 测试类 |
| 不修改 ToolResult 冻结契约 | ✅ |
| 不修改 GameTool 接口 | ✅ |
| 不修改 RunStateMachine | ✅ |
| 不修改 Repository 数据层 / ConfigVersion 语义 | ✅ |
| 不新增 REST Controller | ✅ |
| 不修改并行 Tester 泳道 | ✅ |
| 不进入 P5/P4/P3-4 | ✅ |
| 每验证点有专项测试 | ✅ 10/10 |
| >10s 操作按 §6.1 广播 | ✅ PROPOSAL_CREATED/APPROVAL_REQUIRED/TOOL_RETURNED{SUSPENDED} 事件落库 |

### 11.7 技术债状态

- **TD-P3-001：RESOLVED（方案 A）**——已从"待裁决"转为"已解决"。
- 既有 TD-005（P6）：NOT IMPLEMENTED，未受影响。
