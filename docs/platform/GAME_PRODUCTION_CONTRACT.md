# 游戏生产与迭代的共享交付契约

层级：Platform。2026-11-12。**新增设计，尚未完整实现**。适用五个模块，复用 [Runtime](AGENT_RUNTIME_RECOVERY.md)、[本地执行](LOCAL_GAME_TEST_EXECUTION.md)、[可信验证](IMPROVEMENT_VALIDATION_DESIGN.md)。现有 API 不因本文自动增加字段或路由。

2026-11-13 实现进展：ART-01 首版已提供三种交付物保存、版本/hash/同任务输入链与数据库增量初始化，实际接口和精确字段见 [生产交付物 API](PRODUCTION_ARTIFACTS.md)。其余交付物、生产接受状态与制造执行仍是后续设计。

## 两条工作流

- 改进已有游戏：选定版本 → 问题/目标 → 复现与假设 → 候选改动 → 独立验收 → 前后对照 → 接受。
- 制造同类型新游戏：创作简报 → 可选参考研究 → 原创设计规格 → 技术与验收计划 → 模板实例 → 可玩切片 → 实现迭代 → QA → 交付候选包。

新游戏首轮没有失败基线，先按规格验收第一个可玩切片，之后每个接受版本成为回归基线。不人为制造 Finding 作为生产前置条件。研究已有游戏和复用已有游戏源码是不同输入；生产简报明确模式为新建、授权派生或修改现有项目。

## 交付物与消费规则

以下是逻辑对象，实施前决定其存储与迁移，不预先新建一整套数据库。

| 交付物 | 生产者 → 消费者 | 必填内容与拒绝条件 |
|---|---|---|
| ProductionBrief | 用户/Designer → Research/Designer | 目标玩家、类型、平台、时长、输入方式、范围、预算、素材约束、模式；矛盾未解决时不能直接制造 |
| ReferenceStudy | Research → Designer | 来源、观察机制、未知项、可借鉴原则、差异化方向；不能把推测当既定需求 |
| GameSpec | Designer → Builder/Tester | 核心循环、状态与规则、数值/内容结构、界面流程、胜负/重开、需求 ID、验收条目、排除项；缺核心闭环不能进入完整实现 |
| ImplementationPlan | Builder → Tester/用户 | 规格版本、模板/引擎与依赖、文件边界、切片、构建方式、风险；关键能力不支持须缩范围或选另一模板 |
| AcceptanceContract | Tester/人类 → QA/执行器 | 冻结检查与语义版本、场景、运行环境、指标与必需证据；实现者不能自行降低验收 |
| Candidate | Builder → Tester/QA | 源码差异、规格/模板/素材版本、实际构建、已知限制、追溯引用；只交代码片段不算可玩产物 |
| TestEvidence | Tester → Designer/QA/Builder | 运行与构建 ID、检查结果、复现/动作证据、失败与缺项；缺观察不得用模型自评补齐 |
| ReviewDecision | QA/人类 → 后续迭代 | 固定候选与证据、自动建议、人类接受及理由；与公开发布分开 |

所有新交付物采用 envelope：`schemaVersion, kind, artifactId, revision, taskId, producerRunId, inputRefs[{id,revision,hash}], contentHash, content`。这是待实现数据契约；现有 Markdown 报告不是自动兼容的结构化对象。先复用 design_document 存可读内容，结构化版本元数据通过独立迁移添加，禁止仅依赖标题前缀识别业务类型。

变更规则：已引用 revision 不覆盖；修改规格后生成新 revision，并标记旧候选需重新核对受影响需求。消费方核对输入 hash 和任务范围，不能自行取 latest 替换；产物失效/缺失要显示并停止依赖阶段。

最小 GameSpec.content 形状：`briefRef, title, genre, targetPlatform, coreLoop, controls, states, endConditions, restartBehavior, systems, contentScope, assetNeeds, requirements[{id,description,acceptanceIds}], acceptanceCriteria[{id,requirementId,expected,method}], exclusions, openQuestions`。字段类型/schema 版本由 ART-01 校验定义；ID 在规格内唯一，验收引用指向本规格的 acceptanceCriteria，未解决的阻断问题阻止进入制造。Tester 随后依据此规格创建冻结 AcceptanceContract，不要求规格反向依赖尚不存在的测试产物。类型特有数据放 systems，不把武器/敌人字段强制到非战斗游戏。

共享前置任务 **ART-01**：实现 envelope 与 GameSpec/AcceptanceContract 的最小 schema 校验、revision/hash、作用域检查和元数据持久化迁移。内容 hash 使用固定规范化 JSON 编码，字符串文本按 UTF-8 保存，路径/换行归一化规则随 schemaVersion 固定；不能依赖不同语言的默认对象序列化。相同输入重试不重复创建 revision，变更生成新 revision；旧 Markdown 无元数据时按 legacy 展示，不自动伪造绑定。

ART-01 先于 Designer D-N1 的结构化保存和 Builder B-02 的消费，实现不依赖完整 Builder。需求/模板的设计和无模型模板验证可先开展，避免互相等待形成环。验收包括缺字段/重复需求 ID/越范围引用/输入 hash 不符/重复请求/旧库升级，不另建平行文档系统。

## 原创性、素材与复用

“同类型”指可复用的机制范式与技术能力，如俯视角生存、回合策略或消除；简报必须列出本作品的主题、规则/成长差异和内容范围。第三方美术、音乐、字体、文本及源码分别记录来源和可用范围；允许占位素材，必须在交付清单明确。来源不明不作为已具备的正式资产。

模板是可启动、带依赖锁和测试适配的工程基线，不是不可变的换皮游戏。模板清单记录版本/hash、支持能力、参数 schema、可扩展位置、构建/启动/测试命令、资产清单。生成结果引用模板版本，不反向修改共享模板；模板升级单独回归并审核。

## 执行、预算与状态

跨阶段协调复用共享服务与普通 Task/Run；不为 Builder 建第二 AgentLoop，也不把开发机上的外部编码助手当成产品已有能力。逻辑阶段可为 BRIEF、DESIGN、PLAN、IMPLEMENT、VERIFY、REVIEW、DELIVER，但不扩充 Run 的状态枚举。阶段状态与产物关联的持久化需先设计迁移，当前无通用生产编排服务。

每次生产设置文件/差异规模、模型和进程预算、修复次数、墙钟时限、允许写入范围。第一版默认最多 5 次修复，次数不因重启或另建子 Run 清零。审批预算耗时遵守 Runtime 契约。预算耗尽交付未完成报告及已有证据，不伪装为候选完成。

无效规格返回 Designer；可复现功能失败返回 Builder；验收不足返回 Tester；证据冲突交给 QA 补实验。只回退受影响阶段。失败保存工作区与日志；回滚仅在本次受管工作区恢复到已知检查点，不删除用户原目录或以全仓 hard reset 代替恢复。

## 交付门槛

可玩切片：启动 → 核心操作 → 一个有意义的选择或挑战 → 胜负/完成反馈 → 再开始，配有可复跑检查。完整候选另需满足接受的范围、独立验收、构建来源、阻断问题清单和交付文档。视觉质量与乐趣需用户评审，不能由成功启动替代。

交付包包括源码/锁文件、构建产物、启动说明、GameSpec、素材来源、测试报告、已知问题、版本清单。发布/商店上传/自动提交不是交付默认动作。

首轮生产验收：同一模板产生两个规格不同且可玩的作品，至少一项核心规则或成长系统差异，不只改名字/颜色；不得为第二件作品在平台服务添加特判。同时验证范围过大、缺素材、测试失败、取消和修复次数耗尽。此为生产能力验收，不能由现有协议样例替代。
