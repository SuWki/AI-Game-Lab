# 真实模型验收

本流程在本机暂存 Designer 和 Builder 两个 Run 的验收输出。预检检查连接模式、单次响应上限、Run token 与时间限制及 Gateway 重试开关。账户额度、工具调用和实际计费需要供应商响应才能核实。

## 本机配置

在启动后端的同一个 PowerShell 会话选择一种连接方式，然后设置预算。

**CC Switch 的 Claude 中转通道。** 在 CC Switch 中选择已配置的 Claude 供应商并开启本地代理。Lab 支持其 3.12.3 版本的非流式 Messages 路由，发送本地占位认证，代理负责添加中转站凭据。Lab 只连接 IPv4 回环地址，不读取 CC Switch 的配置库。CC Switch 的 Codex 和 Gemini 通道使用不同的路由。[对应版本的代理处理器](https://github.com/farion1231/cc-switch/blob/v3.12.3/src-tauri/src/proxy/handlers.rs)

```powershell
$env:SPRING_PROFILES_ACTIVE = 'cc-switch'
$env:CC_SWITCH_MODEL = '<本机 Claude 供应商支持或映射的模型名>'
# 修改过 CC Switch 的监听端口时再设此项，默认 15721
$env:CC_SWITCH_PORT = '15721'
```

该模式要求显式模型名，采样参数沿用该模型默认值，Lab 每轮只发送一次模型请求。验收期间保持 CC Switch 的供应商和映射固定，检查代理自身的重试和故障转移设置。Lab 的一次发送可能对应代理的多次上游尝试。若环境中已有 `LLM_GATEWAY_MODEL`，Spring 会优先使用它，可在预检结果里核对最终模型名。恢复默认直连可在新终端启动后端，切换前先处理等待恢复的 Run。

**直连兼容端点。** 使用默认 Spring 配置并设置 `DEEPSEEK_API_KEY`。自定义服务可另设 `LLM_GATEWAY_BASE_URL` 和 `LLM_GATEWAY_MODEL`。两种模式都需要支持工具调用的模型。

**验收预算。** 以下配置限制一次 Designer Run 和一个 Builder 会话。

```powershell
$env:LLM_GATEWAY_RETRY_ENABLED = 'false'
$env:LLM_GATEWAY_TIMEOUT_SECONDS = '30'
$env:AGENT_RUNTIME_TOKEN_BUDGET = '12000'
$env:AGENT_RUNTIME_MAX_TOKENS = '2400'
$env:AGENT_RUNTIME_TIMEOUT_MS = '180000'
```

可选 Builder 估算单价与费用上限沿用[会话预算配置](BUILDER_SESSIONS.md)。token 预算在调用前和记录响应后检查，单次请求仍可能消耗超过剩余 token。Claude 返还的输入、缓存读取、缓存写入都计入输入 token，估算按统一输入单价计，实际费用以中转站账单为准。

启动后端后运行下面的预检命令。代理模式返回 `gatewayMode=cc-switch-claude`、端口和 `proxyHealthy`。预检只调用本地 `GET /health`。代理关闭或端口填错时，命令退出码为 2，修正后可以直接重试。健康检查通过只说明代理能够应答。实际模型返回 HTTP 401/403 时核对 CC Switch 中的账户授权，400/404 时核对通道、模型名和工具协议。

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
