# Sprint P6-4 Checkpoint Report

**状态：✅ COMPLETE → WAITING_ACCEPTANCE**
**TD-005 状态：已人工解除（本 Sprint 新授权），P6-4 执行完毕。**
**范围纪律：仅实现 P6-4 ConfigController 4 个 REST Endpoint；P7-5 未实现，P8 未进入。**

---

## 1. 人工裁决采纳

- 先前裁决：P6-4 ConfigController NOT IMPLEMENTED，登记为 TD-005。
- 本 Sprint 人工重新裁决：**允许执行 P6-4**（新授权，非执行线程自行扩范围）。
- 严格执行边界：**只解决 P6-4 ConfigController REST Endpoint，不顺手实现 P7-5。**

---

## 2. 实际修改文件

| 文件 | 类型 | 说明 |
|------|------|------|
| `backend/src/main/java/com/aigamelab/api/ConfigController.java` | **新增** | P6-4 REST Adapter，4 个 endpoint |
| `backend/src/test/java/com/aigamelab/api/ConfigControllerTest.java` | **新增** | 9 个专项集成测试 |

**未修改**（红线核查）：
- `schema.sql` — 未改动（无 DDL）
- `ConfigVersionRepository` / `ChangeProposalRepository` 接口与 Impl — 未改动
- `BusinessException` / `ApiResp` / `GlobalExceptionHandler` — 未改动（Error Contract 原样透传）
- 所有前端文件（含 P7-1~P7-4 页面）— 未改动
- 所有 Tester lane 代码 — 未改动

---

## 3. 四个 Endpoint

| Method & Path | 行为 | 复用既有 |
|---------------|------|----------|
| `GET /api/config/versions` | 历史版本列表（最新优先） | `ConfigVersionService.findAll()`（Repository 升序 → Controller 层倒序，不改 Repository） |
| `GET /api/config/proposals?status=PENDING` | 提案列表（status 可选过滤；无 status 返回全部） | `ChangeProposalRepository.findByStatus()`；无 status 时按 closed 状态集（PENDING/APPROVED/REJECTED）逐一并集，**不新增 Repository 方法** |
| `POST /api/config/proposals/{id}/approve` | 批准提案 | `ApprovalService.approve(id, comment)` |
| `POST /api/config/proposals/{id}/reject` | 拒绝提案 | `ApprovalService.reject(id, comment)` |

---

## 4. Service 复用关系（纯 HTTP Adapter，零业务重实现）

```
HTTP 请求
   ↓
ConfigController（仅参数解析 + 调用 + 转 ApiResp）
   ↓
既有 Service / Repository（未触碰）：
  - ConfigVersionService.findAll()         （P6-1，append-only 查询）
  - ChangeProposalRepository.findByStatus() （既有，无扩展）
  - ApprovalService.approve() / reject()    （P6-3，已验收语义）
   ↓
ApiResp  / GlobalExceptionHandler（Error Contract 原样透传）
```

Controller 内**未实现**：diff / merge / 状态转换 / 版本创建 / 审批逻辑 / Run resume / 事件发布——全部委托既有 Service。

---

## 5. 测试结果

### 专项测试 `ConfigControllerTest`（真实 Spring 集成，WebEnvironment.NONE）
```
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  →  PASS
```
覆盖：
- **versions**：返回历史版本（含种子 v001）、最新优先、append-only 历史未被修改
- **proposals?status=PENDING**：仅返回 PENDING，已 APPROVED 不混入
- **proposals(无 status)**：返回全部
- **approve**：新 ConfigVersion 创建、成为 latest=current、版本数 +1、提案→APPROVED + targetVersion 回填（Run resume 为异步不在此断言，属 P1-4）
- **reject**：不创建新版本、提案→REJECTED、targetVersion=null、latest 不变
- **Error Contract**：缺失提案 → 40401；非 PENDING approve/reject → 40901

### 全量回归 `mvn clean test`（JDK 24 → --release 21）
```
Tests run: 342, Failures: 0, Errors: 0, Skipped: 0  →  BUILD SUCCESS
```
（较 P7 完成时的 327 + 本次新增 9 ConfigControllerTest + 6 RunControllerReadTest = 342）
含 P3-4 工具测试、P6-4、P7 全部后端测试、既有 Tester WIP 测试。

### 前端（回归，未改动）
```
typecheck (tsc -b --noEmit): EXIT 0
build     (vite build)        : 235.93 kB / gzip 73.04 kB, EXIT 0
test      (vitest run)        : 7 passed, EXIT 0
```

---

## 6. Scope Check（红线逐项）

| 红线 | 结论 |
|------|------|
| 不改 schema（无 DDL） | ✅ schema.sql 未改动 |
| 不改 Repository 接口契约 | ✅ 接口与 Impl 均未改；仅复用既有 `findAll` / `findByStatus` / `findById`；无 status 查询用 closed 状态并集，未新增方法 |
| 不新建多余 Controller | ✅ 仅 `ConfigController` |
| 不清偿/扩展 P6-3 审批逻辑 | ✅ approve/reject 仅转发 `ApprovalService` |
| 不改 Tester lane | ✅ 全部 Tester 代码未触碰 |
| 不改 Error Contract | ✅ `BusinessException` / `ApiResp` / `GlobalExceptionHandler` 未改；40401→404、40901→409、50000→500 由既有 Handler 透传 |
| 不改 ConfigVersion 语义 | ✅ 仍 append-only 全量快照；current = findLatest() |
| 无 active / activateVersion / rollbackVersion | ✅ 未引入任何此类字段或方法 |
| 不进入 P7-5 | ✅ 未实现 Config & Diff 前端页面 / React 页面 / 任何 P7-5 frontend |
| 不修改 P7-1~P7-4 | ✅ 前端与后端 P7 代码均未改 |
| 不进入 P8 | ✅ 未触碰 P8 相关 |

---

## 7. 是否修改 Repository / schema

- **Repository 接口**：**未修改**（契约零扩展）。
- **Repository Impl**：**未修改**。
- **schema**：**未修改**（无 DDL / 无新增字段 / 无新增表）。
- 无 status 查询的实现方式为按既有 closed 状态集（`ChangeProposalRepository.findByStatus`）并集，**未向 Repository 新增 `findAll()`**（遵守 §六 / §十三红线）。

---

## 8. ConfigVersion 语义核查

- ✅ append-only 全量快照：approve 经 `ApprovalService.approve()` → `ConfigVersionService.createVersion()`，事务内取最大序号 +1 插入新快照，`decide` 回填提案；历史版本只读不可变。
- ✅ current = latest：`findLatest()` 字典序最大版本即当前生效版本；测试中 `approve` 后 `SELECT version ... ORDER BY version DESC LIMIT 1` 等于新创建版本，`reject` 后 latest 仍为 v001（不变）。
- ✅ 无 active 字段、无 activateVersion()、无 rollbackVersion()、无历史版本状态修改、无指定历史版本重新激活。
- ✅ 未修改 ConfigVersion Repository 数据层契约。

---

## 9. P7-5 明确 NOT IMPLEMENTED

本 Sprint **仅实现 P6-4 ConfigController 4 个 REST Endpoint**，为 P7-5 提供 REST 能力。
**未实现** P7-5 的 Config & Diff 前端页面、任何 React 页面、或任何 P7-5 frontend 逻辑。
P7-5 仍按先前裁决保持未启动状态，等待后续独立授权。

---

## 10. P8 明确 NOT IMPLEMENTED

未触碰任何 P8 相关代码、配置或文档。未自动进入 P8。

---

## 11. 裁决结论

- ✅ P6-4 ConfigController 4 个 Endpoint 全部完成并通过验收测试。
- ✅ 全部 P6-4 红线达标，纯 HTTP Adapter，零业务重实现。
- ✅ ConfigVersion 语义冻结维持（append-only / current=latest / 无 active·activate·rollback）。
- ✅ Repository / schema / Error Contract 均未改。
- ✅ 全量回归 342 测试通过；前端回归通过。
- 🚫 P7-5 NOT IMPLEMENTED（未启动）。
- 🚫 P8 NOT IMPLEMENTED（未进入）。
- **进入 WAITING_ACCEPTANCE**：等待用户裁决是否接受 P6-4，或指示后续动作（如授权启动 P7-5）。
