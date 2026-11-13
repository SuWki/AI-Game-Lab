# Builder 的共享 Runtime 接入

2026-11-13。BUILDER 是第 9 个 AgentType，使用 builder_p001 版本化 Prompt 和唯一 AgentLoop，不新增执行服务。当前只支持受控模板生产与证据查询，没有任意代码写入、审批、合并或发布工具。

## 创建与启动

生产页面选择精确规格后，填写 Builder 目标并点击“创建 Builder 任务”。对应 POST `/api/productions/agent-task`：`{taskId,specId,specRevision,goal}`，taskId 指规格所属任务。服务创建独立 BUILDER Task，绑定原任务、规格 ID 与 revision，之后在 Workflow 启动 Run。

创建、启动和控制这些任务均须本机来源及 `X-AGL-Local-Test: 1`。通用 POST /api/tasks 不接受未绑定的 BUILDER 创建请求。初始任务绑定在 RuntimeConversation 中冻结，恢复时不改取其他规格。模型凭据未配置时不要将其他角色或 Fake 剧本当真实供应商。

## 实际工具

| 工具 | 模型参数 | 行为与边界 |
|---|---|---|
| production_context | `{}` | 固定规格摘要、需求与模板能力 |
| production_start | `{}` | 使用固定规格和 `agent-<runId>` 幂等键创建生产；模型不能改命令、参数或路径 |
| production_result | `{productionId}` | 仅可读本 Run 拥有的生产，最长有界等待 10 秒，返回实际状态和构建引用 |

其他 Agent 角色没有这些工具。本角色也没有旧模拟器、文件写入或本地审批工具。结果明确标记 qualityAccepted=false；BUILT 不是玩法通过或完整规格实现。若预算不足/执行仍在进行，报告当前状态与缺项，不能自行宣告完成。

生产记录 ownerRunId 与共享本地子执行绑定 Agent Run。Run 取消/失败/超时通过既有事件处理停止受管子执行；同一 Run 重复 start 复用原生产。另一 Run 即便绑定同规格也不能读其产物；人工生产记录也不是可自动读取的所有权范围。

## 验证与限制

BuilderRuntimeTest 使用读取实际请求/工具观察的 CheckingGateway，并真实运行 Node 模板，覆盖 context→start→result→正常停止、跨执行拒绝、幂等启动、未绑定失败、角色隔离。它验证 Runtime/授权/生产接线，不替代真实模型效果验收。

当前总工具 37、Prompt 种子 14；原角色权限不扩大。原六项 Builder 工具草案中的源码提案、验证/打包能力仍需独立验收保护和后续实现。生产状态与需求接受状态继续分开，未将模型 stop 当成质量结论。
