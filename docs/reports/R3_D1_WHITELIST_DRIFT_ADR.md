# R3-D1: Whitelist Drift Decision Record (ADR)

> **归属**：R3 Sprint Phase 2，Task R3-D1（C-08 扩展）。
> **状态**：**R3-D1 COMPLETE — WAITING FOR HUMAN ACCEPTANCE**。
> **生成时间**：2026-09-14。
> **前置**：R3-A1 Git Baseline 已建立（commit `e7e18cd`）；QA P1 closure 已完成。

---

## 1. Decision（决策）

**接受当前白名单同步模型，不做任何代码或 schema 改动。**

本轮审计确认 AgentType / Prompt / Tool / Backend / Frontend 五维白名单均处于已知、受控、有测试守卫的状态。已知受控差异（X-8：Designer 白名单 10 vs 产品定义 8）为 R2 授权的 additive 扩张，非违约漂移，正式记录终止争议。

---

## 2. Source of Truth（各维白名单的唯一事实来源）

### 2.1 AgentType 枚举

| 层 | 事实来源 | 当前值 | 文件 |
|---|---|---|---|
| **Backend 枚举**（唯一权威） | `AgentType.java` enum | `DESIGNER`, `TESTER`, `RESEARCH`, `QA` | `runtime/AgentType.java:16-21` |
| 前端同步 | `WorkflowPage.tsx` `AGENT_TYPES` | `["RESEARCH","DESIGNER","TESTER","QA"]` | `pages/WorkflowPage.tsx:8` |
| 约束解析 | `DefaultAgentTypeResolver.resolveFromConstraints` → `AgentType.valueOf` | 大小写敏感，Java enum 自动扩展 | `runtime/DefaultAgentTypeResolver.java:182-211` |

**同步机制**：前端 `AGENT_TYPES` 必须与后端 `AgentType.values()` 保持相同集合（顺序可不同）。同步守卫：`WorkflowPage.test.tsx` 断言 options 精确等于 `["RESEARCH","DESIGNER","TESTER","QA"]`，后端新增 enum 值时若前端不同步，CI 即红。

### 2.2 Prompt 种子

| 层 | 事实来源 | 当前值 | 文件 |
|---|---|---|---|
| **资源目录**（唯一权威） | `backend/src/main/resources/prompts/` | 5 个 .md 文件 | — |
| DB 种子 | `PromptService` 启动幂等导入 | p001 (active) + 4 inactive | `llm/PromptService.java:86-160` |
| 设计文档预期 | R3 审计 §1.1 | 5 个种子 | `R3_Architecture_Scope_Audit.md:22` |

| 种子版本 | 对应 AgentType | 状态 | 资源文件 |
|---|---|---|---|
| `p001` | DESIGNER（硬编码 systemPrompt，p001 为 DB fallback） | **active** | `designer_p001.md` |
| `tester_p001` | TESTER | inactive | `tester_p001.md` |
| `research_p001` | RESEARCH | inactive | `research_p001.md` |
| `designer_p002` | DESIGNER（候选，未激活） | inactive | `designer_p002.md` |
| `qa_p001` | QA | inactive | `qa_p001.md` |

**同步机制**：`PromptService.importTesterSeedIfAbsent` 幂等模式（每个 AgentType 各一个 import 方法）；新增种子须在 `PromptService` 添加 import 方法 + 对应 .md 文件 + `AgentTypeResolver.systemPrompt` switch 分支。同步守卫：`PromptServiceTest` / `TesterPromptImportTest` 断言 `prompt_version` 总行数 = 5，CI 即红。

### 2.3 Tool 白名单

| AgentType | 工具数 | 工具集合 | 授权来源 |
|---|---|---|---|
| **DESIGNER** | **10** | `get_game_config`, `modify_game_config`, `simulate_game`, `analyze_experiment`, `compare_experiments`, `get_game_design`, `create_design_document`, `create_change_proposal`, `get_research_context`, `get_research_claims` | R0-8（8 基础）+ R2-1（2 只读研究消费，additive） |
| **TESTER** | **8** | `test_plan_get`, `game_start`, `game_observe`, `game_act`, `game_collect_events`, `game_collect_metrics`, `finding_report`, `test_report_create` | T1-T7 冻结契约 |
| **RESEARCH** | **6** | `web_search`, `fetch_page`, `save_source`, `extract_claims`, `compare_sources`, `generate_report` | R0-8 |
| **QA** | **1** | `finding_report`（与 Tester 共享） | QA P1-1 |

**事实来源**：`DefaultAgentTypeResolver` 四组 `static final Set<String>`（`:64-95`）。

**同步机制**：编译器穷尽 switch（`toolWhitelist(AgentType)` / `systemPrompt(AgentType)` / `resolve(Task)`）强制无漏分支——新增 AgentType 枚举值时，Java 编译器在所有三处 switch 均报错，不添加新分支无法编译通过。

**同步守卫**：
- `AgentTypeResolverTest` 对 Designer/Tester/Research 三型执行精确集合断言（`hasSize` + `containsExactlyInAnyOrder`）：Designer=10, Tester=8, Research=6。
- `QaP1_1Test`（`:191,219,226`）对 QA 白名单执行精确断言：仅 `finding_report` 1 个，且与 Tester 共享、不新增 QA-only 工具。
- `AgentTypeResolverTest.whitelistsAreMutuallyExclusive()`：Designer/Tester/Research 三集互不重叠（QA 与 Tester 共享 `finding_report`，属预期受控共享，非漂移）。
- 后端全量 551+ 测试通过 `mvn -o clean test` 级联守护。

### 2.4 Backend 冻结集

| 冻结对象 | 文件 | 冻结级别 |
|---|---|---|
| **AgentLoop** | `AgentLoop.java` | 零修改（prompt/tool 选择委托 AgentTypeResolver） |
| **RunStateMachine** | run 状态语义（schema.sql PENDING→RUNNING→...） | 零修改 |
| **BudgetGuard** | run 表 `token_budget/tokens_used` | 零修改 |
| **LoopDetector** | Agent 循环检测 | 零修改 |
| **ToolExecutor** | dispatch + critical/non-critical 错误语义 | 零修改 |
| **ToolResult** | LLM 面工具结果契约 | 零修改 |
| **ErrorCodes** | 6 码注册表（ErrorCodes.java:9-26） | 零修改 |
| **schema.sql** | 14 张表 | 零 DDL |
| **AgentEventType** | 26 枚举值 | 零修改 |

**事实来源**：`docs/QA_P1_IMPLEMENTATION_AUDIT.md` §14 + `docs/state/task-state.md` FROZEN 标记。
**同步守卫**：R3-A1 Git Baseline（commit `e7e18cd`）建立后，`git diff <baseline>` 可检测任何冻结文件变更；未来 `scripts/check_frozen.sh`（Gate-2 计划，尚未实现）将自动比对内容哈希。

### 2.5 Frontend 冻结契约

| 冻结对象 | 文件 | 说明 |
|---|---|---|
| **AGENT_TYPES 枚举** | `WorkflowPage.tsx:8` | 必须与 `AgentType.java` 集合一致 |
| **SSE 协议** | `useGlobalSse.ts` / `SsePayload` type | 不修改后端 SSE 协议（§17 冻结） |
| **API 客户端契约** | `api.ts` | 只追加，不改既有 endpoint 签名 |
| **REST 端点** | 30 个（28→30，QA P1-3/4 追加 2） | 冻结既有端点语义 |

**同步守卫**：
- `tsc --noEmit` 保证类型安全（CI 必跑）。
- `vitest run` 当前 48 项（含 WorkflowPage/FindingsPage/ResearchPage 冒烟），CI 即红。
- `npm run build` 保证编译打包通过。

---

## 3. Synchronization（同步机制总结）

五维白名单各自有独立的"事实来源 → 代码落地 → 测试守卫"链条：

```
事实来源（设计文档/枚举/资源目录）
  → 代码落地（DefaultAgentTypeResolver / PromptService / WorkflowPage.tsx）
    → 测试守卫（AgentTypeResolverTest / PromptServiceTest / WorkflowPage.test.tsx）
      → CI 级联（mvn test / vitest / tsc）
```

**关键同步保证**：
1. **编译器强制**：Java 穷尽 switch 保证新增 AgentType 不遗漏 prompt/tool 分支。
2. **前端枚举镜像**：`AGENT_TYPES` 必须等于 `AgentType.values()` 集合，WorkflowPage.test.tsx 精确断言。
3. **集合精确断言**：`AgentTypeResolverTest` 对每个 AgentType 的工具集做精确匹配（多一个少一个都红）。
4. **行数断言**：`PromptServiceTest` 断言 prompt_version 总行数 = 5，防止增删种子漏记录。

---

## 4. Drift Detection（漂移检测现状）

### 4.1 已有检测能力

| 检测点 | 机制 | 覆盖范围 |
|---|---|---|
| AgentType 增减 | 编译器穷尽 switch + `WorkflowPage.test.tsx` | Backend + Frontend 枚举同步 |
| Tool 白名单变更 | `AgentTypeResolverTest` 精确集合断言 + 互斥断言 | 四维工具集 |
| Prompt 种子增删 | `PromptServiceTest` 行数断言 = 5 | 种子注册 |
| Frozen 文件变更 | R3-A1 Git Baseline + `git diff`（手动） | 8 核心文件 + schema + 26 枚举 |
| 前端类型安全 | `tsc --noEmit` | API 类型契约 |
| 前端回归 | `vitest run` 48 项 | 核心页冒烟 |
| 后端回归 | `mvn -o clean test` 551+ | 全量后端测试 |

### 4.2 已知缺口（Deferred，非 R3 P2 范围）

| 缺口 | 说明 | 计划 |
|---|---|---|
| `scripts/check_frozen.sh` 未实现 | Gate-2 设计中的自动哈希比对脚本尚未落地 | 独立 Task 或下个 Sprint |
| Frozen 验证依赖手动 `git diff` | R3-A1 后已可操作但无自动化 | 随 check_frozen.sh 一并实现 |
| Designer 工具集 10 vs 产品定义 8 | 已知受控差异（X-8），见 §5 | 不需修复，本 ADR 终止争议 |

---

## 5. Current Decision（当前已知受控差异详解）

### X-8：Designer 白名单 8 → 10

| 维度 | 事实 |
|---|---|
| **产品定义/任务树** | 8 个 Designer 核心工具 |
| **代码实际** | 10 个（8 基础 + 2 Research 消费工具） |
| **差异来源** | R2 Sprint — `get_research_context` / `get_research_claims` 为 Research→Designer 产物消费的只读工具 |
| **授权依据** | R2-1 授权（additive-only：不删不改既有工具，无研究关联的 Designer 行为不受影响） |
| **是否违约** | **否** — additive 扩张经 R2 人工授权，非 drift |
| **审计建议** | 本 ADR 正式记录，防止未来审计误判为漂移 |

### QA 与 Tester 共享 `finding_report` 工具

| 维度 | 事实 |
|---|---|
| **是否重叠** | `finding_report` 同时出现在 TESTER_TOOL_NAMES（8 个之一）和 QA_TOOL_NAMES（唯一 1 个） |
| **是否违约** | **否** — QA P1-1 设计决策：复用现有 Finding 域，不创建平行 QA 工具；FindingService 已含 P1-2 数据质量 + Lifecycle 校验；`AgentTypeResolverTest.whitelistsAreMutuallyExclusive` 只断言 Designer/Tester/Research 互斥，QA 与 Tester 的受控共享属预期 |

---

## 6. 合规确认（R3 P2 Scope）

- ✅ **Frozen Boundary 零改动**：AgentLoop / RunStateMachine / BudgetGuard / LoopDetector / ToolExecutor / ToolResult / ErrorCodes
- ✅ **schema.sql 零改动**：14 表不变
- ✅ **前端零新页面**：C1 改 useGlobalSse（SSE 协议层），C2 补冒烟测试，均不新增页面或路由
- ✅ **无 Phase 2 功能泄漏**：无 QA_LEAD / SubRun / Release Gate / QA Dashboard
- ✅ **纯文档 Task**：R3-D1 不改动任何生产代码，仅输出本 ADR

---

## 7. 测试结果（本轮 R3 P2 完整回归）

| 层 | tests | failures | errors | 执行方式 |
|---|---|---|---|---|
| 后端 | **551** | **0** | **0** | `mvn -o clean test` |
| 前端 | **48** | **0** | **0** | `npx vitest run` |
| 前端类型 | **0 errors** | — | — | `npx tsc --noEmit` |

基线对照：后端 551 不变；前端 28 → 48（+20：C1 useGlobalSse 12 + C2 ResearchPage 7 + FindingsPage 6 + App 3 — 其中 FindingsPage 6 + App 3 为既有测试，实际新增 19 项：C1 12 + C2 7）。

---

## 8. 交付物清单

| 类别 | 交付 |
|---|---|
| **ADR 文档** | 本文件 `docs/reports/R3_D1_WHITELIST_DRIFT_ADR.md` |

---

## 9. 附录：人工验收 Checklist

- [x] 五维白名单事实来源已明确列出（AgentType / Prompt / Tool / Backend / Frontend）
- [x] 同步机制（编译器 switch + 测试守卫 + CI）已文档化
- [x] 漂移检测能力盘点（已有 + 缺口）已记录
- [x] X-8 Designer 10 vs 8 正式裁决为"授权 additive，非 drift"
- [x] QA-Tester 共享 finding_report 记录为"受控共享，非漂移"
- [x] Frozen Boundary 零改动确认
- [x] 全量回归 551/0/0 + 48/0/0 + tsc 0 errors
- [x] task-state.md 状态落档
