# 五模块设计复核与补全

层级：State / Decision。2026-11-12。范围为各模块产品/技术/架构/任务文档及相关 QA 阶段设计、Builder 概念稿，并抽查当前 Resolver、本地证据/提案、Research 和 QA 接口实现。属于设计一致性复核，不是重新完成所有代码的独立安全审计。历史测试、完成报告和审批原结论不重写。

## 方向结论

Lab 包含两条正式产品线：已有游戏的分析改进、同类型新游戏的设计制造。前轮把新作排除在近期产品之外的表述过窄，已修正为单模板、有限范围的生产阶段。五个模块的价值在于交接清楚且能完成产物，不能靠模块名称齐全就认为已有制造能力。

当前最明显的缺口：Designer 规格不能直接交给制造；Research 缺统一技术入口且 confidence 容易误读；Tester 把模拟状态泛化为所有游戏；QA 多个时期“现状”相互矛盾；Builder 只有归档概念工具，缺新建工作区、版本化验收、恢复和交付设计。

## 逐文档处理

| 原文档 | 判断 / 问题 | 本轮处理 |
|---|---|---|
| Designer/产品定义 | 模拟器和固定 Build 绑定过深；产品中混工具 schema | 重写为改进/新作输入输出、规格与决策；技术移交演进设计 |
| Designer/技术设计 | 基础设施复用合理；Runtime 伪代码、预算成功、零丢失承诺过期 | 替换 Runtime 章节为当前共享契约；修正挂起工具与单连接保证，标明历史基线 |
| Designer/开发任务树 | P0–P8 有验收，但容易误作下一轮队列 | 保留历史；新增 D-N1..3 规格、研究消费和制造交接 |
| Tester/产品定义 | 默认像玩家操作、通用 HP 规则和固定复跑次数过强 | 重写能力边界、适用 invariant、证据与新作首次验收 |
| Tester/架构设计 | Fake 模型边界合理；Build=ConfigVersion 与 COMPLETED=场景齐全不通用 | 顶部明确适用阶段，链接真实/新作演进契约 |
| Tester/技术设计 | Plan/Finding 可复用；预算成功、零漏报和事件计数有误导 | 修正对应条目；增真实版本与独立验收引用 |
| Tester/开发任务树 | T1–T7 历史足够，缺真实/生产验收任务 | 标注历史；T-N1..4 覆盖独立检查、实际目标、新作、对照 |
| Tester/T-DB1_DDL_Review | 历史数据库决策合理，不应改写当时记录 | 增适用说明；当前版本与新增迁移另走契约 |
| Research/产品定义 | 来源数量/评分被误作真实性，列未实现工具 | 重写为有界研究、独立来源、事实/推断/建议和新作研究 |
| Research/开发任务树 | R0 仍写 Stub/无 UI/7 工具，不能说明当前 R0–R2 | 标注历史及当前 6 工具，新增技术设计和 R-N1..3 |
| archive/03_AI游戏测试团队 | 角色框架合理，固定阈值不适用于所有游戏 | 保留归档，指向正式 QA 目录 |
| archive/05_AI自动做游戏Agent | 首模板方向合理，任意文件工具、自动全回滚和自评置信度缺约束 | 保留归档，补完整 Builder 目录接替实施入口 |
| architecture/07_QA_Phase2_Architecture | 早期无编排/Runtime 缺口现状已过期 | 加历史说明与当前模块/Runtime 入口 |
| QA_PHASE2_ARCHITECTURE | 编排职责可复用；历史 frozen 缺口不应成为当前事实 | 保留阶段正文，指向新版模块；QA 自身恢复债仍保留 |
| QA_PHASE2_ARCHITECTURE_DECISIONS | 历史决策需保留 | 明确阶段适用性与 Runtime 后续 ADR |
| QA_PHASE3_ARCHITECTURE_DESIGN | games 占比契约已有历史；非真实平衡证据 | 保留旧指标，正式 QA 设计明确新生产评估分开版本化 |
| QA_SYSTEM_ARCHITECTURE_PROPOSAL | 提案阶段与后续实现混读 | 加历史范围与当前设计入口 |
| QA_ARCHITECTURE_FREEZE / QA_ARCHITECTURE_DECISION_REVIEW | 阶段冻结/裁决不能被静默覆盖 | 保留原决定，明确后续 Runtime ADR 与新生产设计 |
| QA_PREFLIGHT_PLAN / QA_S1_PLAN | 历史阶段准入与任务，非当前启动指令 | 保留并标明；后续 Q-N1..3 集中在模块任务树 |

Designer/Tester 仍较长的技术基线明确保留早期 API/模拟设计语境；本轮没有声称逐一证实其每条旧字段与当前源码完全相同。新增模块 README 给出当前读取顺序，执行 Runtime 只有一个权威入口。

## 新增的实质设计

| 模块/层 | 文档 | 补足内容 |
|---|---|---|
| 总产品 | [总设计](../architecture/GAME_LAB_PRODUCT_DESIGN.md) | 两条产品线、五模块分工、同类型生产示例与价值验收 |
| Platform | [生产契约](../platform/GAME_PRODUCTION_CONTRACT.md) | Brief→Research→GameSpec→实现计划→候选→证据→裁决；版本与输入引用、新作无旧基线规则 |
| Designer | [演进设计](../projects/01_AI_Game_Designer/演进设计.md) | GameSpec、需求验收映射、工具/Prompt、D-N1..3 |
| Tester | [演进设计](../projects/02_AI_Game_Tester/演进设计.md) | 真实适配能力、新作首次验收、独立检查与 T-N1..4 |
| Research | [技术设计](../projects/03_AI_Research_Agent/技术设计.md) | 当前数据/API/工具、来源独立性、ReferenceStudy、Prompt 与 R-N1..3 |
| QA | [模块入口](../projects/04_AI_Game_Agent_Team/README.md) | 产品、技术/Agent/工具、固定候选证据、Q-N1..3 与验收 |
| Builder | [模块入口](../projects/05_AI_Auto_Game_Developer/README.md) | 产品、技术、Agent/工具/Prompt、模板/工作区/恢复/打包、B-01..07 与验收 |

每模块有职责、输入输出、实现/待实施边界、共享设施引用、失败/停止条件、后续任务和行为验收。Agent/Tool/Prompt 内容按需要合入演进/执行设计，未机械创建八份重复空文件。

## 实施入口与校验

第一步交错推进独立验收/构建来源、Designer 的首规格和 Builder 的首模板；然后串联制造执行与新作验收，最后交付两个核心规则不同的同类作品。避免全部精力再次停留在一个 demo 或纯平台基础设施。

本轮只修改 Markdown，未增加 Builder 代码、数据库表、API 或模型调用；新增 API/工具明确标注拟议。原 695/65 测试结果属于之前实现，不能证明本轮设计功能已可用。校验采用模块导航/本地链接、文档空白与关键状态口径检查，不重复跑无关代码测试。交付记录不等于全量历史文档无任何陈旧描述。

本轮校验结果：35 份模块/平台/主要入口文档，212 个本地链接均存在，无空文档；git diff --check 通过。任务依赖补充 ART-01 作为 D-N1/B-02 的共同前置，避免规格保存与生产元数据互相等待；规格内验收条目先定义，再由 Tester 冻结独立契约，避免交付物循环引用。

下一步详细顺序以 [开发路线](../design/DEVELOPMENT_ROADMAP.md) 顶部及模块任务树为准；原 PRE_BUILDER_FULL_CODE_AUDIT.md 未修改。
