# 三批平台开发交付与审查入口

层级：State / Decision。日期：2026-11-12。基于 `724f01a` 的未提交工作区继续开发；包含前轮本地执行首版。目标是已有游戏的分析、测试、优化与扩展，不以单个 demo 的玩法修改作为平台进度。

## 交付结果

| 批次 | 当前可用行为 | 主要实现 |
|---|---|---|
| 共享 Runtime | 合法函数协议、执行层授权、冻结 Prompt/工具/参数、整批调用持久化、工具结果回放、批准/拒绝恢复、取消终态保护、实际重试、有界调度 | RuntimeConversation、AgentLoop、ToolExecutor、RunControlService、RunRepository、ApprovalService、DeepSeekClient |
| 真实测试证据 | 预注册任意命令；声明源码与产物 SHA256；本地版本固定；结构化 QA 判定；幂等导入既有 TestPlan/Finding；受限分析/测试 Agent 任务 | localtest/LocalTestService、LocalEvidenceService、LocalTestTools；LocalTestController、LocalQaController；LocalEvidencePanel |
| 受控修改回归 | 保存文件前后内容和基线哈希，人工批准/拒绝；批准后复制声明源码到独立工作区应用修改并回归；漏测不能算修复；结果回到原 Agent 工具观察 | LocalChangeService、LocalChangeController；LocalChangesPage；/local-tests/changes |

继续复用一个后端、一个 AgentLoop 和 SQLite。schema.sql 幂等增加 runtime_context、runtime_turn、local_test_context、local_change_proposal；与前轮 local_test_run 合计 21 张业务表，既有列未更改。契约变更已明确登记于 [ADR](../decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)，历史预算耗尽=COMPLETED 的测试与消费方同步更新。

工具调用 ID 与完整批次在首次分发前落库；审批不丢失同批后续调用。STARTED 但结果未知的副作用不自动重放。角色权限与目标/基线资源范围均在执行端约束。Agent 不拥有本地提案批准工具。

## 实际验证

| 验证 | 结果与范围 |
|---|---|
| 后端 `mvn -q test` | 最新全量 92 suites，695 tests，0 failures / errors / skipped；JDK 24.0.1，release 21 |
| 前端 | typecheck、production build 通过；Vitest 11 files / 65 tests 通过 |
| HTTP 协议 | WireMock 只有收到正确 function envelope 才返回调用；实际读取超时断言通过，无供应商凭据 |
| Runtime 集成 | 11 项：请求读取真实前轮结果、执行白名单、批准/拒绝恢复批次、迟到响应取消保护、未知副作用不重放、同 Run 并发入口、排队取消、过期审批、持久审批及决定后中断恢复 |
| 独立本地协议集成 | 13 项：失败→Finding→批准→隔离 PASS、漏测、基线漂移、拒绝、越界、缺证据、运行中源变动、目录新增、Designer 挂起恢复、Tester 执行、跨执行拒绝、缺产物、父 Run 取消子执行 |
| 本地服务 + 页面 | API 提交独立 Node 样例；浏览器实际表单导入 Finding、生成差异、批准；隔离验证 VALIDATED，原始 maxHp=0、工作区 maxHp=100 |
| 真实 demo | 通过平台注册目标执行现有浏览器适配，12 项结构化检查 PASS，固定源码/构建产物指纹并导入 TestPlan；0 Finding |
| 后端重启 | 受控停服/重启后，FAIL 基线、Finding、VALIDATED 提案、PASS 回归及 demo 12 项证据均可由 API 查询 |

页面自动化使用 Edge headless 的 DOM 事件与表单交互。demo 检查也是 DOM/QA 夹具验收；不表示自然键鼠通关、玩家胜率或性能达标。运行过程中曾因自动化对文本框的精确 label 定位失败而退出，改用已观测文本框定位后完整重跑通过；不是业务接口的通过证据替代。

本次运行 ID：

- 独立基线：`0be08566-36d3-455a-bf66-ae552cf6d81b`，2 检查 / 1 失败；TestPlan #2、Finding #2（独立验收数据库）。
- 提案：`c9f1d288-6f8b-4919-bc8a-d551d125f7e8`，VALIDATED。
- 隔离回归：`0a020b40-681c-4e77-81fb-ab648db8ba1f`，2 检查全部通过，源码版本变化。
- demo：`7562ac3e-fbee-43f3-88a4-9a0724037381`，12 检查全部通过。

证据：[完整基线/差异/回归](evidence/three-batches-2026-11-12/live-workflow.json)、[页面截图](evidence/three-batches-2026-11-12/isolated-review.png)、[demo 版本证据](evidence/three-batches-2026-11-12/demo-platform-run.json)、[重启留存](evidence/three-batches-2026-11-12/restart-persistence.json)、[测试清单与源码指纹](evidence/three-batches-2026-11-12/validation.json)。验收使用 `data/local-platform-review.db`，未替换用户原业务库。

## 使用与复跑

本地前端地址 `http://127.0.0.1:5173/local-tests`，提案页 `/local-tests/changes`。本轮验收服务使用 8080 后端与 Vite 代理，注册目录位于忽略的 `data/local-test-targets.json`；绝对路径仅是本机配置。

其他机器从 [目标配置示例](../../backend/local-test-targets.example.json) 开始，修改解释器与工作目录路径，再通过 `local-tests.catalog` 指定文件。仓库内 `scripts/local-tests/example-game` 无第三方依赖，默认包含一个可修复失败；在页面读取 config.json，把 maxHp 从 0 改为正数，生成提案并批准即可重复验收。不是让测试自身无条件改为成功。

详细协议：[本地测试与提案 API](../platform/LOCAL_GAME_TEST_EXECUTION.md)、[Runtime 执行恢复](../platform/AGENT_RUNTIME_RECOVERY.md)。测试命令与 JDK 设置见 [构建环境](../BUILD_ENVIRONMENT.md)。

## 审查重点与剩余工作

当前代码可以进入独立审查，但不宣称整个 Lab 已生产就绪：

1. 当前终端未设置 DEEPSEEK_API_KEY，因此未做真实付费模型多轮调用。请求驱动 Gateway 与真实 HTTP 模拟均已验证，真实供应商响应、成本与长任务效果仍需补验。
2. 同 Run 锁与资源隔离面向单后端实例。独立源码目录不是操作系统沙箱，可信注册命令仍以当前用户执行；依赖、工具链、网络和后台脱离进程不被完全隔离。
3. 隔离回归要求命令可移植，源码清单包含必要测试输入；不自动复制 node_modules、安装任何游戏引擎或合并原目录。demo 当前注册命令含原目录绝对参数，已验证版本化测试/导入，尚不能直接用于隔离修改；独立协议样例验证通用修改链。
4. 本地 QA 独立于旧模拟 ReleaseGate 的指标契约，不把本地版本伪造为 GameConfig。通过回归不自动关闭 Finding，不自动合并或部署。
5. HTTP 超时真实生效；总时限在调用边界核对，不等于任意正在执行的外部操作可瞬间终止。旧运行若缺完整协议或结果未知会拒绝续写，需要核对后新建重试。
6. 本轮未清偿全部历史审计问题：原生 GameConfig 的完整 shape 验证、Research 抓取边界、旧模拟 QA 的证据固定/内存 deadline，以及多项目/多用户授权仍见 [技术债](../state/tech-debt.md)。增表沿用现有 schema.sql，不是通用版本化列迁移框架。

建议审查顺序：RuntimeConversation/ToolExecutor/AgentLoop 的事务与授权 → ApprovalService/RunRepository 的竞态 → LocalEvidenceService 的证据准入与路径 → LocalChangeService 的工作区与漏测判定 → LocalTestTools 的资源绑定 → 页面及 API 错误处理。对照 RuntimeIntegrityTest 与 LocalWorkflowIntegrationTest 逐项复跑。部分旧生命周期夹具增加显式测试工具授权，业务集成夹具不再对已完成 Run 发起工具调用；生产权限测试仍用真实 Resolver，没有给 stub 生产豁免。

工作区包含未跟踪新源码，审查时同时查看 `git status --short` 与新增文件，不能只看 tracked diff。用户预置 `PRE_BUILDER_FULL_CODE_AUDIT.md` 未修改。本轮未执行提交、推送、自动合并或发布。后续顺序见 [开发路线](../design/DEVELOPMENT_ROADMAP.md) 顶部最新状态。
