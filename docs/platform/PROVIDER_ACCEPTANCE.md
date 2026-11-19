# 真实模型验收

本流程在本机暂存 Designer 和 Builder 两个 Run 的验收输出。启动前检查网关凭据是否配置、单次响应上限、Run token 与时间限制，以及 Gateway 重试开关。检查在本机完成，供应商账户是否可用由实际模型响应确认。

## 本机配置

在启动后端的同一个 PowerShell 会话设置供应商凭据，凭据通过环境传入。以下示例限制一次 Designer Run 和一个 Builder 会话。没有凭据时，预检退出并列出缺项。

```powershell
$env:LLM_GATEWAY_RETRY_ENABLED = 'false'
$env:LLM_GATEWAY_TIMEOUT_SECONDS = '30'
$env:AGENT_RUNTIME_TOKEN_BUDGET = '12000'
$env:AGENT_RUNTIME_MAX_TOKENS = '2400'
$env:AGENT_RUNTIME_TIMEOUT_MS = '180000'
```

凭据变量为 `DEEPSEEK_API_KEY`，兼容端点可另设 `LLM_GATEWAY_BASE_URL` 和 `LLM_GATEWAY_MODEL`。启动前请使用有工具调用能力的模型。可选 Builder 估算单价与费用上限沿用[会话预算配置](BUILDER_SESSIONS.md)。token 预算在调用前和记录响应后检查，单次请求仍可能消耗超过剩余 token；实际费用以供应商账单为准。

## 分阶段执行

1. 在仓库根目录执行 `node scripts/production/acceptance/provider-acceptance.mjs preflight`。日常后端可继续使用原重试和预算默认值。本流程要求明确使用小预算配置。
2. 在 UTF-8 文本文件写入原创的短局 survival-web 设计目标，包括机制、范围和可验收预期。运行 `node scripts/production/acceptance/provider-acceptance.mjs design NEW_OUTPUT_DIRECTORY goal.txt`。脚本只启动一次 Designer，输出 Run ID。
3. 在生产页选择本次任务，将模型草稿导入规格，补齐字段，审核并确认精确制造范围。规格的来源 Run 必须与第二步一致。
4. 执行 `node scripts/production/acceptance/provider-acceptance.mjs build OUTPUT_DIRECTORY SPEC_UUID SPEC_REVISION`。脚本启动一个最多八步的 Builder Run。若挂起等待源码提案审批，到修改提案页审阅差异。审批后平台按照已有流程恢复。
5. 执行 `node scripts/production/acceptance/provider-acceptance.mjs status OUTPUT_DIRECTORY` 追加报告。针对生成的固定候选跑浏览器探针，再由用户实际试玩并保存接受或拒绝。

脚本默认访问 `http://127.0.0.1:8080`。隔离实例可通过 `AGL_ACCEPTANCE_ORIGIN` 指定同类回环地址。同一次验收始终使用相同实例和数据库。启动目录保存已确认的任务和 Run ID，Builder 的提交意图只写一次。如果命令在启动请求中断，先到 Workflow 或 Run Trace 核对是否已创建执行。

## 证据和通过口径

报告保存每个模型轮次的请求 ID、请求模型、响应模型、是否报告 usage、时延以及实际记录的输入与输出 token。发送事件标记平台开始调用，响应事件标记平台收到并记录响应；两者的差记为未结算轮次。报告同时保存 Builder 的预算快照、生产与固定候选引用、修订清单、浏览器执行 ID 和当前人工裁决。估算用量费用可与供应商账单交叉核对。

最终验收需要一次真实供应商响应、可追溯的规格、Builder 产物和候选的用户试玩裁决。实际执行失败、模型停止但未产出、用户拒绝或无法核对成本，应按各自的实际结果记录。可用本地兼容协议夹具核对接线、状态与证据结构。
