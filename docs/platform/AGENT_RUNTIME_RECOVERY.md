# 共享 Agent Runtime 的执行与恢复

层级：Platform，2026-11-12。变更依据：[ADR](../decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)。实现入口为 `RuntimeConversation`、`AgentLoop`、`ToolExecutor`、`RunControlService`。

## 固定执行契约

每个新 Run 在 runtime_context 保存目标、Prompt 内容与哈希、允许工具名、供应商函数 schema 和审批元数据、模型与采样参数。task.maxSteps 参与预算；`agent.runtime.temperature` 默认 0.4、`max-tokens` 默认 4096、`timeout-ms` 默认 900000。总时限含审批等待。模型由既有配置解析并固定，不在恢复时重新选择。

工具定义按 OpenAI 兼容 function envelope 发送，内部 requiresApproval 不出现在供应商请求。ToolExecutor 复核冻结白名单与当前工具契约；改变工具权限/审批语义的运行不能静默续写。工具调用前后查询数据库状态，终态条件更新避免取消被迟到响应覆盖。

## 对话与副作用恢复

整批 assistant 调用（原始 ID、参数、顺序和对应 tool_call 行）在执行第一项前事务写入 runtime_turn。下一轮包含可见 assistant 文本及配对的工具观察；不保存隐藏推理。每批最多 16 调用、参数合计 32K 字符。模型上下文最多最近 12 个完整回合，每条工具观察为有界摘要，整体字符预算 128K；原始结果保留数据库。

| 恢复状态 | 处理 |
|---|---|
| QUEUED，Run 活跃 | 继续执行同批剩余调用 |
| 审批 WAITING | 人工决定更新原 tool 观察；无待决审批后恢复 |
| 审批已决定、进程在恢复前退出 | 启动时核对持久化观察后继续，不重新应用决定 |
| STARTED 且无结果 | 明确失败，要求核对副作用后重试 |
| 旧 Run 缺完整协议批次 | 保留可读历史，拒绝冒险续写 |
| CANCELLED / FAILED / TIMEOUT / COMPLETED | 不再执行下一调用或覆盖状态 |

原生配置审批会校验提案基线仍为当前版本；本地文件审批检查源码基线，并在隔离回归后回传结果。决策发生在挂起之前的竞态通过挂起后核对持久化决定解决。

## 调度与结束语义

Run 调度为 4 工作线程、64 等待位。满载明确失败并记录事件；同 Run 使用进程内锁和数据库状态迁移避免重复恢复，非多实例分布式执行权。启动时恢复可安全处理的 PENDING / RUNNING，核对 WAITING 的审批观察。retry 创建新 ID 并实际调度，旧终态保留。中台 Run Trace 可取消、恢复和重试。

模型正常 stop 为 COMPLETED；token/步数预算耗尽、截断或循环检测为 FAILED；时间预算为 TIMEOUT 并发送 RUN_TIMEOUT。COMPLETED 不是 QA PASS。HTTP 连接/读取超时实际应用；总预算在模型和工具边界核对，不能瞬间强杀任意工具内部的副作用。Run 取消/失败/超时会取消其受管的本地测试子执行。

目前沿用 schema.sql 幂等增表，runtime_context/runtime_turn 不修改旧列；这不是通用复杂列迁移系统。当前恢复契约只承诺单后端实例，不承诺任意外部副作用 exactly-once。真实供应商多轮验收与成本评估仍需单独记录，不能用请求驱动替身测试代替。
