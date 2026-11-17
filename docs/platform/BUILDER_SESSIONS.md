# Builder 跨 Run 生产会话与预算 v1

2026-11-17。B-05 第二个增量。新增 builder_session(task_id 主键/外键, record_json)，共 24 表。record_json 的 schemaVersion=1，固定规格引用、模型、首次 Run 的步骤/token 上限、绝对 deadline，可选美元单价/费用上限；保存生产 ID 和当前 Run。旧库 CREATE TABLE IF NOT EXISTS 升级，旧 Builder 任务按最早 Run 初始化，历史消耗仍计入。

同一 Builder Task 的 start/retry 只允许一个 PENDING/RUNNING/WAITING_APPROVAL Run。新 Run 的主循环与旧 Run 按任务串行，避免旧请求取消后仍返回用量时，新 Run 抢先花费。接管要求旧 Run 终结、没有未决源码提案或在途本地执行；不重放未知外部操作。任务规格/模型变更须新建任务，不能静默换绑。

production_start 使用任务级幂等键并绑定会话。重试从会话取回原生产，核对同任务后转移 ownerRunId；原始构建、验收及五次提案链都保留。旧 Run 不再拥有后续候选。显式取消生产的标记不因重试清除；遗留任务存在多份生产时要求人工检查，不任意选一份。

预算从所有同任务 Run/Step 汇总，而不是复制剩余量到新的零计数 Run。每轮模型调用前检查累计步骤/token/费用/deadline；响应后先记用量，再检查 token/费用/时间，超限不得继续执行工具。最后一个允许步骤仍可正常完成。模型单次响应仍可能超过 token/估算费用上限：本版是调用边界限制，不保证供应商账单硬封顶。失败且未报告 usage 的请求显示未确定用量次数。

人工批准 WAITING_APPROVAL 提案时也会重新检查会话预算，不能在等待期间过期后仍启动回归。原 Run 的冻结时限继续保留，会话绝对时限是额外的累计约束。

可选配置 production.budget.prompt-usd-per-million、completion-usd-per-million、max-usd：非负十进制单价、正费用上限，单价必须成对提供，有费用上限时必须有单价。首次会话冻结，修改配置不重置旧会话。金额为按已报告 prompt/completion tokens 估算 USD，不处理缓存折扣/税/账单；缺单价时 estimatedCostUsd=null、pricingStatus=UNCONFIGURED，不能显示为免费。

复用唯一 AgentLoop 与六项生产工具，工具 schema 不变；production_context/result 和生产页返回 budget，供模型/人类查看累计消耗及会话关联。人工源文件提案仍需批准，未知结果、取消或验收不足仍不等于质量通过。候选打包及完整自动阶段编排继续后续开发。

Run 详情 GET /api/runs/{id} 增加可选 builderBudget，普通角色保持原字段。工具观察压缩历史和长检查，控制在 Runtime 的结果预算内，完整记录仍在生产/API 证据中。累计 token 取每个 Run 已记总数与 Step 已知 prompt+completion 的较大值，防止响应落 Step 后、Run 计数提交前中断造成少计。旧任务按接入时冻结单价估算所有已知用量，不回填真实历史账单。
