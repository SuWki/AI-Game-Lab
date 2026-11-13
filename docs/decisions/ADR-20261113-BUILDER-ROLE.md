# ADR：受限 Builder 身份与生产所有权

2026-11-13，按用户继续开发与交付授权实施。新增 BUILDER 枚举与 builder_p001、3 项生产工具，复用共享 Runtime 的冻结参数、回放、执行授权和预算。

第一版 Builder 仅制造已授权规格支持的模板切片，不开放通用 shell、文件写入、自批或发布。创建独立模型任务时明确绑定源 task/spec revision；生产 ownerRunId 及子执行所有权持久化在已有 JSON 中，不增加新表。外部 API 调用者仍为可信本机单用户。

原阶段的 AgentType=8 / Tool=34 / Prompt=13 精确集合随新增行为更新到 9/37/14，既有角色工具集合保持不变；生产角色不能通过修改工具参数切换目标。具体实际协议见 [Builder Runtime](../platform/BUILDER_RUNTIME.md)。
