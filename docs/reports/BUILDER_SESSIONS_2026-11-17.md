# Builder 会话与累计预算交付

2026-11-17，基于 main `7b79273`。设计、配置和迁移见 [会话协议](../platform/BUILDER_SESSIONS.md)。

## 已交付

同一 Builder Task 的多个 Run 共享生产 ID、固定规格/模型、累计步骤/token、绝对 deadline，以及可选单价下的费用估算和上限。重试复用原项目、验收和候选链；接管记录 ownerHistory。旧 Run/其他任务不能继续访问新的拥有者范围，取消生产标记不被重试清空。

首次 admission/会话初始化在事务中完成，活跃 Run 禁止重复提交；接管要求旧提案及本地执行已明确结束。同任务主循环串行，取消的旧模型响应先落账，再允许新 Run 调用模型。预算耗尽后的响应不能执行其中的工具。最后一个允许步骤可以完成；未配置价格显示未知，配置错误不留下半条 Run/会话。

审批前再次检查等待中 Builder 的会话预算；超过 deadline 时拒绝创建验证工作区。对应负例纳入最后全量回归。

新增 builder_session（24 张业务表），工具仍 40、Prompt 仍 15，不增加 AgentLoop。GET Run 详情返回可选 builderBudget；生产和 Run Trace 展示累计消耗、当前 Run、截止时间和费用口径。普通角色保留原执行语义。

## 验证

- 后端全量 736 项通过。首次全量发现 ApplicationTests 的精确表集合缺新增表，补全后重跑全量通过。
- 前端 74 项测试和生产构建通过；费用面板验证“未知不等于免费”以及估算不等于账单。
- 新会话测试覆盖累计/新服务实例恢复、并发 admission 拒绝、耗尽重试事务回滚、配置费用上限、单价冻结、无单价、错误价格回滚、Step 已落账但 Run 未累加、deadline、未知用量和变更绑定/多份旧生产拒绝。
- Runtime 集成测试真实执行 Node，覆盖重试保留原生产及已验证候选、超限响应不得 dispatch、最后允许步骤、未决提案阻止接管、取消后晚返回用量先结算。使用请求驱动 CheckingGateway，不宣称真实供应商效果。
- 独立端口/数据库中，以本地确定性 OpenAI HTTP 夹具通过真实 DeepSeekClient、Runtime 和 Node 生产，实际 Edge 页面点击重试：原 Run 4 消耗 100 token，新 Run 5 消耗 80 token，合计 180 token/9 步；同一任务只有一份生产，验收 ID 和 deadline 不变。未调用付费模型。
- 重启独立后端后，整个 builderBudget 和既有执行 ID 不变，没有重放构建/验收。夹具进程验证后关闭，用户原本地平台配置不指向夹具。

证据：[运行记录](evidence/builder-session-2026-11-17/workflow.json)、[重启记录](evidence/builder-session-2026-11-17/restart.json)、[Run 预算截图](evidence/builder-session-2026-11-17/run-budget.png)、[生产会话截图](evidence/builder-session-2026-11-17/production-session.png)。

## 边界与下一步

预算是模型调用边界上的停止规则，单次响应仍可能超出剩余 token/估算费用。未返回 usage 的失败调用仅标记未知；缓存折扣、税和真实账单不在本轮估算内。相同任务冻结配置，变更规格、模型或明确开启新预算需新建任务。

接管不重放不确定文件应用，不自动恢复中断构建，也不改变人工审批策略。B-05 的完整阶段协调仍未全部关闭。下一批推进 B-06：将固定候选源码、实际构建、规格/素材/测试引用及已知问题打包，预览和下载都绑定同一份验收候选。浏览器规格覆盖、第二个真实目标与供应商实测继续按原路线推进。
