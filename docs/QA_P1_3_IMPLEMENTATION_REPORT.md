# QA P1-3 实施报告（Finding 多维过滤 + QA 命名空间端点）

> 归属：QA P1 Sprint，Task P1-3（`QA P1-2 — ACCEPTED` 后按 Task 输入实施）。
> 状态：**QA P1-3 COMPLETE — WAITING FOR HUMAN ACCEPTANCE**。
> 生成时间：2026-09-11；验证更新：2026-09-14。
> 前置依赖：P1-1（QA AgentType + 闭环）✅、P1-2（Evidence 质量 + Lifecycle）✅。
> 附注：`/api/qa/*` 端点（由先期 R3-B1 并行会话建立）于 2026-09-14 经人工批准保留。

## 1. Status

```text
QA P1-3 COMPLETE — WAITING FOR HUMAN ACCEPTANCE
```

- 新增测试：**39 项**（QaP1_3Test 26 + QaControllerTest 13）。
- 完整回归（2026-09-14 复验）：顺序 / 方法级随机 seed=7 / 类级随机，全部 **551 tests / 0 Failures / 0 Errors**（`mvn -o clean test`，JDK 24）。
- 未触碰 Frozen Boundary；未新增 DB 表；未修改 schema.sql。
- `/api/qa/*` 已获人工批准（2026-09-14）

## 2. 目标

> 为 QA 提供结构化的 Finding 查询入口，支持按 severity/category/source/status/configVersion/runIds/taskId/testPlanId 多维过滤，AND 语义，空过滤返回全部。

## 3. 实现改动清单（Changed Files）

| 文件 | 改动 | 类型 |
|---|---|---|
| `backend/src/main/java/com/aigamelab/domain/FindingFilter.java` | **新增** record（8 维过滤参数 + isEmpty 辅助） | 主代码 |
| `backend/src/main/java/com/aigamelab/repository/FindingRepository.java` | 接口追加 `findByQaFilter(FindingFilter)` | 主代码（接口扩展） |
| `backend/src/main/java/com/aigamelab/repository/FindingRepositoryImpl.java` | 实现 `findByQaFilter`——动态 SQL 拼装，runIds 非空时 `IN (?)` 多值绑定，ORDER BY id | 主代码 |
| `backend/src/main/java/com/aigamelab/tester/FindingService.java` | ① `findByQaFilter` 服务入口 + `validateFilter` 枚举白名单校验；② configVersion blank 校验（查询语义：不校验存在性——查不存在版本 = 空结果） | 主代码 |
| `backend/src/main/java/com/aigamelab/api/QaController.java` | **新增** `GET /api/qa/findings`——8 个 `@RequestParam(required=false)` + `blankToNull` 归一化 | 主代码（API 端点） |
| `backend/src/test/java/com/aigamelab/qa/QaP1_3Test.java` | **新增** 26 项 Service 层验收测试 | 测试 |
| `backend/src/test/java/com/aigamelab/api/QaControllerTest.java` | **新增** 13 项 MockMvc 控制器验收测试 | 测试 |

**未改动**（全部复用）：Finding / Finding.Evidence / FindingService（既有 report/updateStatus 路径不变）/ FindingRepositoryImpl（既有 findByFilter 四参路径不变）/ TesterController / schema.sql / Frozen Boundary / 前端。

## 4. FindingFilter 域模型

```java
public record FindingFilter(
    String configVersion, String severity, String category,
    String source, String status, List<Long> runIds,
    Long testPlanId, Long taskId) {
    // runIds null → 空 List（不可变副本）
    // isEmpty(): 所有维度 null/空 → 返回全部
}
```

**设计决策**：
- `runIds` 为 `List<Long>`——支持多 Run 聚合查询（`run_id IN (?,?,?)`），null 自动归一化为空 List。
- 全维度 AND 语义——任何非 null 非空维度追加 SQL `AND col=?`。
- 空过滤（全部 null/空）退化为全表扫描 `SELECT * FROM finding WHERE 1=1 ORDER BY id`——与既有 `findByFilter` 全 null 行为同构。

## 5. Repository 实现

`FindingRepositoryImpl.findByQaFilter` 采用与既有 `findByFilter` 一致的动态 SQL 拼装模式（StringBuilder + 参数列表），扩展至 8 维：

```java
if (!filter.runIds().isEmpty()) {
    sql.append(" AND run_id IN (?"
        + String.join(",", nCopies(runIds.size(), "?"))
        + ")");
    args.addAll(filter.runIds());
}
```

- runIds 非空才追加 `IN` 子句——空 List 不产生 SQL 片段。
- ORDER BY id 保证结果顺序稳定（可预期的分页/观测行为）。

## 6. Service 层校验

`FindingService.validateFilter` 对枚举维度按 `Finding` 白名单校验：

| 维度 | 白名单来源 | 非法值 → ErrorCode |
|---|---|---|
| severity | `Finding.SEVERITIES` = [BLOCKER, HIGH, MEDIUM, LOW, INFO] | 40001 INVALID_ARGUMENT |
| category | `Finding.CATEGORIES` = [CRASH, SOFT_LOCK, INVARIANT_VIOLATION, UNEXPECTED_STATE, BALANCE_ANOMALY, OTHER] | 40001 |
| source | `Finding.SOURCES` = [INVARIANT, LLM, MANUAL] | 40001 |
| status | `Finding.STATUSES` = [OPEN, CONFIRMED, DISMISSED, FIXED] | 40001 |
| configVersion | 仅 `isBlank()` 拒绝（查询语义，不校验存在性） | 40001 |

**与 report() 的区别**：report() 的 PF-003 校验 configVersion 必须真实存在（落库防伪）；查询路径 configVersion 不命中 = 空结果（合法查询语义）。

## 7. QaController 端点

```
GET /api/qa/findings?configVersion=&severity=&category=&source=&status=&runIds=&testPlanId=&taskId=
```

- 所有参数 `@RequestParam(required = false)`——Spring 自动绑空为 null。
- 多值绑定：`?runIds=1&runIds=2` → `List<Long> runIds = [1, 2]`。
- `blankToNull` 归一化：空串 / 纯空白 → null（过滤等价"忽略该维度"）。
- 返回 `ApiResp<List<Finding>>`——与既有 API 信封模式一致。

## 8. 测试矩阵

### 8.1 Service 层（QaP1_3Test，26 项）

| 类别 | 测试 | 数量 |
|---|---|---|
| A. 单维过滤 | severity / status / category / source / configVersion / runIds(单值+多值) / taskId / testPlanId | 9 |
| B. 多维组合 AND | severity+status+configVersion / status+source+category / runIds+taskId+status | 3 |
| C. 空过滤 | 空过滤返回全部 / runIds null 归一化 | 2 |
| D. 非法过滤 | severity/status/category/source 越界 → 40001 / configVersion blank → 40001 / 非法过滤无 DB 副作用 | 6 |
| E. 无结果 | configVersion 不命中 / 合法枚举无交集 / 空库空过滤 | 3 |
| F. 既有回归 | findByFilter 四参结果不变 / 全 null = 全量 / 无结果语义保持 | 3 |

### 8.2 控制器层（QaControllerTest，13 项）

| 类别 | 测试 | 数量 |
|---|---|---|
| 正常单条件 | severity / configVersion / status / category+source 多条件 AND | 4 |
| 多值绑定 | runIds 双值 → union | 1 |
| 空串归一化 | severity 空串 → null → 等价空过滤 | 1 |
| 空过滤 | 无参数 → 全部返回 | 1 |
| 非法枚举 | severity/status/category/source 越界 → 40001 | 4 |
| 无结果 | configVersion=v999 → 空列表 | 1 |
| 既有回归 | 原有 `/api/findings` 不受影响 | 1 |

### 8.3 Regression（P1-3 验证期）

| 运行方式 | tests | failures | errors |
|---|---|---|---|
| `mvn -o clean test` 顺序（JDK 24） | **551** | **0** | **0** |
| `mvn -o test` 方法级随机 seed=7（`junit.jupiter.execution.order.random.seed=7`） | **551** | **0** | **0** |
| `mvn -o test -Dsurefire.runOrder=random` 类级随机 | **551** | **0** | **0** |

P1-2 基线 512 → P1-3 后 551（+39 新增），全部通过。类级随机本轮未复现 R-001 既有豁免（TD-QA-R-001 为既有技术债，保持豁免记录不变）。

## 9. Scope Compliance

- ✅ 未触碰 Frozen Boundary（AgentLoop / RunStateMachine / BudgetGuard / LoopDetector / ToolExecutor / ToolResult / ErrorCodes 零改动）
- ✅ 未修改 schema.sql / 未新增 DB 表
- ✅ 未新增 ErrorCode
- ✅ 未破坏既有 findByFilter 四参路径（回归守护）
- ✅ 未创建平行 QA Finding Domain（继续复用 Finding / FindingService / FindingRepository）
- ✅ `/api/qa/findings` 端点——**已获人工批准保留（2026-09-14）**；仅查询路径，不含写操作
- ✅ QA P1-2 的 Lifecycle + Evidence 校验不受影响（QaFilter 不修改 Finding 状态）
- ✅ 无 Multi-Agent / Release Gate / QA_LEAD 等角色端点（QaController javadoc 仅声明 NOT AUTHORIZED）

## 10. 技术债登记

无新增技术债。既有 TD-QA-R-001（共享 static DB）和 TD-QA-P11-001（ToolExecutor dispatch 层无角色校验）状态不变。

## 11. 附录：验收 Checklist

- [x] FindingFilter 8 维 record 可正确构造
- [x] 多维 AND 语义正确（多条件同时收紧结果集）
- [x] runIds 多值 IN 子句正确绑定
- [x] 空过滤返回全部
- [x] 枚举维度越界 → 40001
- [x] configVersion blank → 40001
- [x] 非法过滤不产生 DB 副作用
- [x] 无结果场景返回空列表
- [x] 既有 findByFilter 四参路径回归通过
- [x] QaController GET /api/qa/findings 端点可用
- [x] 多值 runIds 绑定（?runIds=1&runIds=2）
- [x] 空串参数归一化为 null
- [x] 既有多 `/api/findings` 端点不受影响
- [x] 未触碰 Frozen Boundary
- [x] 未修改 schema.sql
- [x] 新增测试全过（39/39）
- [x] 完整回归通过（顺序 / seed=7 / 类级随机，全部 551/0/0）
- [x] `/api/qa/*` 平行 API 已获人工批准（2026-09-14）——Checklist"无 `/api/qa/*` 平行 API，或已获人工批准"满足
