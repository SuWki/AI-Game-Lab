# 生产修复链与预算 v1

后续实现：[Builder 跨 Run 会话与预算](BUILDER_SESSIONS.md) 补齐了同任务生产接管和累计步骤/token/可选估算费用，新增会话表共 24 表。本页“五次提案”策略继续适用，以下首批边界为该批交付时的状态。

2026-11-17，B-05 首个增量。复用 production_job、local_change_proposal、local_test_context 与 local_test_run，不增加表或另一个 AgentLoop。

每个生产的 verify-<productionId> 目标绑定原始验收及其隔离回归；从持久提案按创建顺序恢复修复链。最多五个成功创建的提案，拒绝及失败仍计数，非法请求不计数。相同生产只允许一个未决提案；后续提案必须引用当前候选的执行，禁止退回旧基线重新计数。完整 FAIL 回归可作为下一次修复基线，PASS 候选可进入 REVIEW；证据不足不自动成为新基线。

生产查询新增 iteration 对象：phase、reason、repairCount、repairLimit、currentRunId、attempts。阶段为 IMPLEMENTING、VERIFYING、WAITING_INPUT、REVIEW、FAILED、INTERRUPTED、CANCELLED，和旧 build status 分开。第五次回归仍失败则 FAILED；通过仍仅为引擎 REVIEW，不代表人类已接受或已交付。

通用源码提案入口同样执行生产策略，不能从本地测试页面绕过次数和候选限制。创建提案与预算检查在事务中执行；单实例同步序列化争用。批准前再次核对生产取消状态；注册回归和记录执行在同一生产策略锁中完成，取消在同一锁中标记并停止已登记执行。重启沿用原提案/执行状态，不重放不确定外部操作。

新 Builder 工具读取 iteration.currentRunId 对应的候选源码并提出后续修改；工具个数和 schema 不变。旧 Prompt 的冻结内容不改写，当前工具观察会提供链状态。人工也可在证据页继续同一候选。

边界：本轮预算为持久的提案次数，不代表 token/费用累计预算已完成；新建其他生产不是续修复。跨 Agent Retry 的生产接管、累计 token/费用、完整阶段调度、候选打包和真实供应商验收仍待后续 B-05/B-06。旧生产若已有超过五次历史提案如实显示，不删减历史以满足新上限。
