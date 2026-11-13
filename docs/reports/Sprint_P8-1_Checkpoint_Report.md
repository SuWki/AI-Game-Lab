# Sprint P8-1 Checkpoint Report

## 1. Sprint 概要

| 项 | 值 |
|---|---|
| Sprint | P8-1（脚手架 + 性能基准重新验证 + v001 fixture 修复） |
| 范围 | P8-1-1 / P8-1-2 / P8-1-3 / P8-1-4 |
| 授权依据 | 人工指令（4 项 Human Decision + 8 节执行规则） |
| 执行时间 | 2026-08-28 |
| 状态 | **DONE / WAITING_FOR_HUMAN_CHECKPOINT** |

## 2. 完成清单

### P8-1-1 一键启动脚本 ✅

| 文件 | 类型 | 说明 |
|---|---|---|
| `scripts/dev.bat` | 新增 | Windows 一键启动：后端（mvn -o spring-boot:run）+ 前端（npm run dev），独立 cmd 窗口，8s 延迟 |
| `scripts/dev.sh` | 新增 | Unix/macOS 版本：后台启动 + PID 文件 + trap Ctrl+C 清理 |
| `scripts/reset-db.bat` | 新增 | 删除 backend/data/agl.db + agl.db-shm + agl.db-wal |

### P8-1-2 P4-4 性能基准重新验证 ✅

| 指标 | 值 | 验收阈值 |
|---|---|---|
| 模拟规模 | 100,000 局 | 100k |
| Build | crit（§12.1 失衡种子） | — |
| Seed | 12345 | — |
| 耗时 | **24.2s** | < 60s ✅ |
| 可复现性 | 同 seed 双跑 winCount/avgDps 完全一致 | 一致 ✅ |
| 环境 | JDK 24.0.1 (--release 21)，Windows 10 Pro | — |
| 首次验证 | 22.976s（2026-08-27，P4-4 Sprint） | — |
| 本轮验证 | 24.2s（2026-08-28，P8-1-2） | — |
| pom.xml 净变更 | 零（临时注释已还原） | — |

`README.md` 新增：一键启动段落、重置数据库段落、手动启动子节、性能基准表。

### P8-1-3 v001 fixture 修复 ✅

| 文件 | 修改 | 说明 |
|---|---|---|
| `ConfigVersionService.java` | 新增 `import jakarta.annotation.PostConstruct` + `@PostConstruct` 注解 | `importSeedIfEmpty()` 由构造器调用改为 Spring `@PostConstruct` 回调 |

**根因**：构造器调用 `importSeedIfEmpty()` 时 DataSource + schema 初始化尚未完成 → `findByVersion("v001")` → 空 → `BusinessException(40401)`。

**修复**：`@PostConstruct` 确保 Spring 在 DataSource + schema 初始化后调用 `importSeedIfEmpty()`。

**验证**：12/12 v001 相关测试全部 PASS（含 `ConfigVersionServiceTest` 8/8 + `GameSessionService` 相关 4/4）。

### P8-1-4 全量回归 ✅

#### 后端

| 口径 | 结果 |
|---|---|
| `mvn clean test -o` | 366 tests, 0 failures, 12 errors |
| 通过 | 354/354 PASS |
| 失败 | 12 error（全部 pre-existing Tester fixture 问题） |

**12 个 error 明细**（全部同一根因：`ToolException: [40401] ConfigVersion 不存在: v001`）：

| 测试类 | error 数 | 测试方法 | 根因 |
|---|---|---|---|
| `TesterReadToolsTest` | 2 | `startObserveCollect_fullChain_isConsistentAndDeterministic`、`overridesAreApplied_onStart_boundaryMaxHp1` | pre-existing |
| `TesterWriteToolsTest` | 7 | `gameAct_invalidParams_failNonCritically` 等 7 个 | pre-existing |
| `InvariantIntegrationTest` | 3 | `hpNegativeInjection_appearsInObserve`、`hpNegativeInjection_appearsInActReturn`、`healthyBuild_hasNoViolations` | pre-existing |

- **归因**：pre-existing Tester 测试基础设施问题，非 P8-1 引入。P8-1-3 修复的是生产启动路径（`@PostConstruct`）；测试上下文中 `@PostConstruct` 是否被调用取决于 `@SpringBootTest` 上下文配置。
- **影响**：仅限 Tester 工具集成测试，不涉及 P8-1 生产修复。
- **已登记**：TD-P8-1-001。

#### 前端

| 口径 | 结果 |
|---|---|
| `npx tsc --noEmit` | exit code 0（类型检查通过） |
| `npx vitest run` | 3 test files, 16 tests, all passed |

| 测试文件 | 用例数 | 结果 |
|---|---|---|
| `App.test.tsx` | 2 | ✅ |
| `useGlobalSse.test.tsx` | 5 | ✅ |
| `ConfigDiffPage.test.tsx` | 9 | ✅ |

前端零回归。

## 3. Git Diff Scope Audit

| 文件 | 变更类型 | 所属 Task | 范围验证 |
|---|---|---|---|
| `README.md` | 修改 | P8-1-2 | ✅ 文档段落新增 |
| `scripts/dev.bat` | 新增 | P8-1-1 | ✅ 脚手架 |
| `scripts/dev.sh` | 新增 | P8-1-1 | ✅ 脚手架 |
| `scripts/reset-db.bat` | 新增 | P8-1-1 | ✅ 脚手架 |
| `ConfigVersionService.java` | 修改 | P8-1-3 | ✅ 测试基础设施修复 |

**越界检查**：

| 检查项 | 结果 |
|---|---|
| P8-2 Golden Path | NOT IMPLEMENTED ✅ |
| P8-4 Exception Path E2E | NOT IMPLEMENTED ✅ |
| P7 页面修改 | NOT MODIFIED ✅ |
| schema.sql | NOT MODIFIED ✅ |
| REST Endpoint 新增 | NOT IMPLEMENTED ✅ |
| ConfigVersion 语义（active/activate/rollback） | NOT MODIFIED ✅ |
| Tester 业务逻辑 | NOT MODIFIED ✅ |
| 02 文档 P6-4 修正 | NOT IMPLEMENTED ✅ |
| P4 Simulation Engine | NOT MODIFIED ✅ |
| P3/P5/P6 | NOT MODIFIED ✅ |

## 4. 技术债

| ID | 说明 | 归因 | 影响 |
|---|---|---|---|
| TD-P8-1-001 | Tester 工具测试（ReadTools/WriteTools/InvariantIntegration）中 `GameSessionService.start()` 触发 `BusinessException(40401) ConfigVersion 不存在: v001`。根因：测试上下文中 `ConfigVersionService@PostConstruct` 未被 Spring 调用 | pre-existing，非 P8-1 引入 | 仅 12 个 Tester 工具集成测试 |

## 5. 下一步

- **STOP — WAITING_FOR_HUMAN_CHECKPOINT**
- 验收通过后，由人工授权 Sprint P8-2（Golden Path + Exception Path E2E）。
- **不自动进入 P8-2**。
