# 项目整体评估（2026-11-11）

性质：State / Decision 层的当次评估记录。代码基线 `724f01a`；工作区包含上一轮文档更新与原有未跟踪审计报告。本文基于重新读取源码、依赖、测试和历史资料；不覆盖历史验收。后续执行任务见 [开发路线](../design/DEVELOPMENT_ROADMAP.md)，技术债统一登记在 [tech-debt](../state/tech-debt.md)。

## 1. 判断

**总体方向合理，工程骨架值得保留；当前适合作为实验平台继续收敛，尚不能按可靠的自主开发平台使用。**

合理的部分：共享 Agent Runtime / Tool / LLM / 事件体系；单体后端和 SQLite 匹配本地单用户规模；模拟器、固定 seed、实验幂等键、配置快照及人工审批提供了可验证的基础。无需重写后端、拆微服务或更换数据库。

主要失衡：业务类型、QA 角色和审计文档增长快于核心执行链的可靠性。应把投入从新增 Agent 转向真实模型协议、多轮上下文、恢复和业务验收。已有测试通过说明已覆盖的行为稳定，不能证明真实工具调用闭环成立。

默认规划产品定位：**单用户、本地、单游戏项目的 AI 策划与验证工作台**。首个产品结果是“给出平衡目标 → 实验比较 → 审阅提案 → 应用配置 → 回归并交付有证据的结论”。Research 为可选输入；QA 团队在闭环稳定后增加价值；Builder 为后续受控扩展。

## 2. 代码核实的问题

优先级：P0 阻断真实 Agent 闭环或工具边界；P1 影响正确性、恢复、证据或日常使用；P2 为条件性扩展。下表静态竞态属于源码推导，本轮未注入故障复现，不能解释为已观测到线上事故。

| 编号 / 级别 | 源码证据 | 影响与处置 |
|---|---|---|
| REV-01 / P0 | `llm/dto/LlmToolSpec.java` 为裸 record；`LlmRequest.tools` 直接携带它；`DeepSeekClient` 直接 `.body(request)`；`LlmDtoSnapshotTest` 断言 `tools[0].name` | 实际形状为 name/description/jsonSchema/requiresApproval，缺少供应商要求的 type/function 层。先修外部请求映射和真实 HTTP 请求契约测试，测试不能继续把内部 DTO 当外部协议 |
| REV-02 / P0 | `runtime/AgentLoop.observe()` 仅构造 system + task.goal | 下一轮看不到工具结果，无法据实验自主迭代。实现统一会话构建与持久化回放 |
| REV-03 / P0 | `runtime/ToolExecutor.execute()` 按名字 dispatch，未校验当前 AgentType 白名单 | 暴露给模型的工具列表不是执行授权。执行前强制校验；资源归属检查仍由服务层负责 |
| REV-04 / P1，REV-02 前置 | `schema.sql` 的 tool_call 无供应商调用 ID / 批次内序号；`AgentLoop` 遇审批即 break；`ApprovalService` 更新 proposal 后 resume，未同步 tool_call 的审批结果 | 无法完整还原原始 assistant/tool 配对；批次剩余调用可丢失，审批后模型也缺最终观察。必须先持久化整批调用和执行状态，不能仅在 observe 中 SELECT 现有行 |
| REV-05 / P1 | `RunRepositoryImpl.finish()`、`updateStatusAndStep()` 仅 WHERE id；`resume()` 读后无条件写；`retry()` 只插入 PENDING；恢复扫描仅 RUNNING | 取消后迟到的 LLM stop 可能覆盖 CANCELLED；并发 resume 可启动多个执行者；重试创建后不执行。需要条件状态更新、单 Run 执行权和明确重试语义 |
| REV-06 / P1 | `AgentLoop.timeoutMs=-1`；`DeepSeekClient` 未把 timeoutSeconds 设到 requestFactory；`BudgetGuard` 和截断/循环分支使用 COMPLETED；事件枚举缺 RUN_TIMEOUT | 超时配置不等于实际网络超时；预算退出会被误认为业务成功。补实际超时、结束原因、事件及 Gate 消费语义 |
| REV-07 / P1 | `RunControlService.start()` 固定 50 步和 deepseek-chat，未使用 task.maxSteps；promptVersionId 为空；`AgentLoop.request()` 固定 temperature/maxTokens | UI/配置与实际执行参数不一致，难以追溯。启动时解析并冻结配置、模型及 Prompt 版本 |
| REV-08 / P1 | `ApprovalService` 在事务外读 PENDING；`ChangeProposalRepositoryImpl.decide()` 无 PENDING 条件；未见 current/baseVersion 冲突检查 | 并发审批、过期提案应用存在重复或覆盖新配置的风险。以事务内状态抢占、版本前置条件和幂等操作验收 |
| REV-09 / P1 | `QaBuildShareDeriver` 算 games 占比，`EvaluationService` 算 wins 占比；DPS 方差配置实际判断胜率方差 | 同类名称对应不同指标。先制定指标口径，QA games share 作为样本分配信息，不能展示为数值平衡通过证据 |
| REV-10 / P1 | `EvaluationService.evaluate(version,...)` 对显式未知版本回退 current；实验 API 异步响应无 experimentId；无 runId 的失败仅日志可见 | 可能使用错误配置目标，提交失败也难追踪。未知版本显式失败；提交前校验并返回可查询的持久化任务/实验标识 |
| REV-11 / P1 | `WebFetchService` 跟随重定向，未校验内网地址，`BodyHandlers.ofString()` 先完整下载再截文本 | 开启网页抓取前需限制协议、解析后的地址、每跳重定向、响应大小与时间。网页正文作为不可信工具数据，不能取得指令权限 |
| REV-12 / P1 | QA deadline 为内存 Map；Agent 使用 cached thread pool；异步模拟单线程无限排队 | 重启会丢时间边界，提交压力无上限。用持久化截止时间、受限执行器/队列和显式拒绝即可，暂不需要消息队列 |
| REV-13 / P1 产品 | `frontend/src/api.ts` 未消费 Gate/assignment 和 Run cancel/resume/retry；现有多页缺统一任务入口 | 优先补 Workflow/Run Trace 内的控制与证据链；不需要先做大型 QA Dashboard |
| REV-14 / P2 条件 | 全库无 project_id；后端未建立身份和资源授权；本地启动未显式限定监听地址 | 当前按本地单用户交付并核验仅回环监听；远程开放前补身份/请求保护，多项目上线前补隔离，按需求触发而非现在全库迁移 |

源码位置均相对 `backend/src/main/java/com/aigamelab/`（另有显式路径者除外）。

REV-01 协议对照来源：[DeepSeek Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)，2026-11-11 查阅。其工具项要求 `type=function` 与 `function` 对象。官方当前列出的模型名称也已与仓库硬编码的 deepseek-chat 不同：实施时通过账户可用模型验证并配置化，不在未实测时断言旧别名一定失效。

## 3. 对旧结论的校正

1. 历史审计建议“已有 step/tool_call 全量数据，补回放即可”过于乐观：原始调用 ID、未执行批次、审批观察均存在缺口，需持久化与迁移设计。
2. `fetch_page` 直接使用 WebFetchService，不依赖 SerpApi；缺搜索凭据阻断 web_search，不自动阻断所有页面抓取。
3. Workflow 只允许创建 5 类任务，子角色由 QA Lead 创建可以是合理产品边界；不把“UI 5 类、后端 8 类”直接当缺陷，更不直接开放所有内部角色入口。
4. QA games share 恒 1/3 是当前均分实验的数学结果。代码符合旧冻结定义，不代表指标具有产品所需的平衡判断能力；调整应作为显式指标契约变更。
5. Fake Gateway 仍适合大部分确定性回归；缺的是基于请求内容的模拟、HTTP 协议断言和少量真实模型验收，不是把所有测试换成付费模型调用。

## 4. 验证范围

本轮执行后端 `mvn -B test`，使用独立 JDK 24.0.1、现有 Maven 依赖及临时测试数据库，子进程清空模型/搜索凭据。2026-11-11 实测结果：

| 检查 | 结果 |
|---|---|
| 后端默认回归 | 653 tests，0 failures / 0 errors / 0 skipped，BUILD SUCCESS；perf 标签默认排除，不计入该数字 |
| 前端类型检查 | `npm run typecheck` 通过 |
| 前端单测 | 9 文件 / 57 tests 通过 |
| 前端构建 | `npm run build` 通过，88 modules |

前端测试仍输出 React Router 迁移提示、未包裹 act 的更新警告及未完整模拟的相对 URL 请求错误，已纳入 TEST-01；测试通过不等于测试输出干净。后端测试输出位于本机临时目录 `agl-review-backend.log`，结构化结果为 `backend/target/surefire-reports/`。

未执行真实付费模型调用、真实游戏引擎接入、性能测试或完整浏览器端到端验收。静态确认的问题须在对应任务先补行为回归；本文不是全文件安全认证。

## 5. 优化取舍

- 保留 Java/React/SQLite 与现有仓库；先修共享 Runtime，避免新建 QaAgentLoop / BuilderRuntime 绕过公共问题。
- 首次新增持久化字段就建立版本化迁移、旧库升级与恢复演练；`CREATE TABLE IF NOT EXISTS` 本身不能升级既有表。
- 按业务收益补 UI，优先运行控制、审批与“为什么通过/失败”；大屏、皮肤和新 Agent 数量不是近期成果指标。
- 真实游戏适配器先接一个可测试的小样例；FakeGameEnvironment 的通过不能代替目标游戏验收。
- 项目隔离、多用户、向量记忆、自动上网研究规模化、分布式设施均延后到具体消费需求出现。
