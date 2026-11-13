# QA Phase 2 Architecture — Decisions Log（决策与约束台账）

> 2026-11-12 范围与状态说明：本文件保留对应阶段的设计/决策与准入条件，不能以其中历史表数、无编排、无回放或预算成功描述判断当前实现。当前模块入口见 [QA 设计](projects/04_AI_Game_Agent_Team/README.md)，共享 Runtime 的已授权变更见 [ADR](decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)。新生产评估以模块演进设计为准，旧 Gate 指标不因此重解释。

> **性质：** `docs/QA_PHASE2_ARCHITECTURE.md` 的伴随文档（可选交付物）。记录本次 Architecture Design 过程中**逐条遵循的约束红线**、审计得出的现状事实、以及在设计中**被规避/保留/上升人类裁决**的决策点。本文件不含代码、DDL、实现。
> **日期：** 2026-09-16 ｜ **审计基线：** HEAD `2b42a75`（后端 619/0/0，前端 57/0/0）。

---

## 1. 本轮约束红线遵循记录（§0-§18 用户要求 ↔ 本设计落实）

| # | 用户约束（verbatim 核心） | 本设计落实 |
|---|---|---|
| §0 | 本轮只做 Architecture Design，只设计、不实现 Phase 2 | docs/QA_PHASE2_ARCHITECTURE.md 全文 0 代码 0 DDL 0 建表；§20 准入条件全未满足 |
| §2 | 把 R5 当现有工程事实审计，不能假设它等于 Phase 2 已授权实现 | §1 专列 "R5 交付物作为工程事实（非 Phase 2 已授权）"；§1.3 列出 R5 已存在局限为设计输入 |
| §3 | 设计 QA_LEAD（agent/module 编排，非新后端服务集群）| §4：QA_LEAD = AgentType + 工具 + 服务组合语义，非新 JVM/DB/进程；复用一个 cached pool + 一个 agl.db |
| §4/§13 | 使用 project_id isolation | §16 诚实披露全系统无 project_id（单项目事实）；默认 lead_task_id 隔离根；project 改造单独立项（H-8）。**未虚构承诺** |
| §5 | agents 是 modules 非 services；无 per-agent SQLite；数据统一 agl.db | §5.1 拓扑图示 + 1.2：R5 已天然满足（单库 + 模块语义）|
| §6 | SubRun 设计问题：新域实体？minimal 字段？add-only 迁移？ | §6：S1（ordinary run + assignment）vs S2（新实体/视图）；MVP 主张 S1；迁移全 add-only |
| §7 | 复用既有 LoopDetector，不得重新实现第二套 | §9.2 明文承诺三连：沿用 poll_seq、不改探测算法、不做白名单特判豁免 |
| §8 | 不复制 QaEvidence 平行 Domain | §10：Evidence 仍是 finding.evidence_json 内嵌；Gate 证据 = 既有表统计 |
| §9 | 不要设计 QaFinding/QaEvidence/QaLifecycle | §11：共享域禁平行；source/枚举/迁移矩阵全部不动 |
| §10 | Release Gate 是 QA 编排决策结果，不是 HTTP ErrorCode | §12.1 红线明文；6 码不动；Gate 值在信封 data |
| §11 | 不要修改 Frozen Event infrastructure | §13.2：MVP 26==26；QA_* 3 事件标 FROZEN 需授权，默认延后 |
| §11bis | API 仅讨论、不实际创建；保持 /api/qa/findings；不无限扩张 /api/qa/* | §14 为"讨论层"；现状 5 端点全照实；新端点映射需求挂 H-10/11/12 |
| §12 | Schema 提案（Option A minimal vs Option B complete，add-only），不实际修改 schema.sql | §15：ZERO DDL；A/B 两候选 + 状态闭合问题；迁移建议 add-only |
| §13 | Isolation/security：project_id；Parent Run A 不读 Project B 的 SubRun | §16：lead_task_id 隔离根 = 现状可验证保证；无 project 概念下明示等价语义 |
| §14 | 不要为了架构完整性假设 Frozen Boundary 可解除 | §17.3 立场 + 默认不解除；凡触碰均标 "FROZEN CONTRACT CHANGE REQUIRED" 挂人类决策 |
| §16 | 不直接宣布任一方案为最终；由 Human Acceptance 决定 | §18：A/B 并列 + 对比 + 倾向参考，不代选 |
| §17 | 风险清单 ≥16 项（概率/严重度/缓解/阻塞）| §19：20 项（R1-R20），5 项标阻塞并注明前置裁决 |
| §18 | 禁止为验证架构写 production code | 本会话零生产代码改动（§验证）；仅审计 + 文档 |
| §19 | 22 节交付 | docs/QA_PHASE2_ARCHITECTURE.md 22 节齐备 |
| §20 | mvn -o test 仍 619/0/0；前端 57/0/0 | §验证：回归复跑确认 |

## 2. 审计重要事实（架构设计必须正确面对）

- **observe() 不回放工具结果**（AgentLoop.java:228）——真实 LLM 多轮编排闭环缺口；P1/P2/P3 三路径皆未选（H-9）。
- **执行期白名单无校验**（ToolExecutor 不校验白名单；仅 AbstractQaTool.leadContext 对 5 个 qa_* 工具二检）——P0 安全硬化点（H-9bis）。
- **deadline 进程内**（ConcurrentHashMap），重启丢失（A 接受 / B 持久化）。
- **dominantBuildShare 恒 null**——0.75 阈值短路（未接线的工程事实）。
- **INCONCLUSIVE 写到 gate.status 但不在 STATUSES 常量域**——域不闭合（需 H-7 裁决）。
- **无 @Transactional** —— 多语句操作非原子（孤儿行风险）。
- **project_id 全系统不存在** —— 单项目架构（H-8）。
- **TD-QA-R-001 / TD-QA-P11-001** 两笔技术债已登记，不在 Phase 2 隐形范围。

## 3. 设计中已做的小决策（无需人类裁决的边界内决策）

| 决策 | 依据 |
|---|---|
| QA 树子任务预算维持 50 step / 100k token（编排层固化）| RunControlService 既有先例；Q4 保留 per-role 定制为待决 |
| 子 run 取消不级联（MVP）| 避免跨 run 竞态 + cancelChecker 单槽事实；B 项 |
| 重试仅人工触发（复用既有 retry 端点）| 确定性优先 + 不改契约 |
| Gate 决策幂等（decided_at COALESCE）| R5 现有 decide() 语义 |
| LeadContext 二次校验作为既定安全层保留 | R5 既有 AbstractQaTool 防御 |

## 4. 本次会话未触碰清单（git-verifiable）

- 后端生产文件零改动；前端零改动；schema.sql 零改动；frozen 8 文件零改动。
- 唯一的文件变更 = `docs/QA_PHASE2_ARCHITECTURE.md`（新）+ 本文档（新）+ `docs/state/task-state.md`（状态落档，见 §验证）；R6 收口 commit `0545b7a`（gate 优先级修复 + 表数断言）为审计所见的既有工程事实（R4 基线的一部分），非 R4 产出。

---

*End of Decisions Log.*