# AI Game Lab 本地测试平台实施与审查入口

> 历史首版记录。后续三批开发及当前审查入口见 [最新交付报告](THREE_BATCH_DELIVERY_2026-11-12.md)；本文的 17 表、无 Agent 工具/版本绑定等边界仅代表首版时点。

日期：2026-11-12。本轮主交付为 AI Game Lab 的共享执行能力与中台页面；不是继续定制游戏玩法。用户指定 demo 仅作为其中一个被测目标。

## 改动与使用

平台现在可从服务端目录配置注册任意本地测试命令，通过中台 `/local-tests` 提交、取消、查看历史状态、日志和 JSON 证据。命令参数由操作者预先注册，浏览器只发送目标 ID。两种实际接入已验证：平台前端类型检查记为 COMPLETED；demo 浏览器测试凭本次 runId 对应的 PASS 证据记为 PASSED。

启动和报告协议见 [平台文档](../platform/LOCAL_GAME_TEST_EXECUTION.md)，示例配置见 [目标目录](../../backend/local-test-targets.example.json)。本机运行配置位于忽略入库的 `data/local-test-targets.json`，验证数据库为独立的 `data/local-platform-review.db`，没有替换用户既有业务数据库。

## 审查代码入口

| 范围 | 文件 |
|---|---|
| 共享执行与恢复 | `backend/src/main/java/com/aigamelab/localtest/LocalTestService.java` |
| HTTP / 本机访问约束 | `backend/src/main/java/com/aigamelab/api/LocalTestController.java` |
| 数据持久化 | `backend/src/main/resources/schema.sql` 追加 local_test_run；现有 16 张表不变 |
| 中台页面 | `frontend/src/pages/LocalTestsPage.tsx`、`frontend/src/App.tsx` |
| 前端 API 与类型 | `frontend/src/api.ts` |
| 执行行为测试 | `backend/src/test/java/com/aigamelab/localtest/LocalTestServiceTest.java` |
| HTTP 边界测试 | `backend/src/test/java/com/aigamelab/api/LocalTestControllerTest.java` |
| 页面行为测试 | `frontend/src/pages/LocalTestsPage.test.tsx` |
| Schema 集合兼容测试 | ApplicationTests、ResearchRepositoryTest、TesterRepositoryTest |
| 样例报告适配 | `scripts/game-demo/local-suite.mjs` 增加环境指定的结果文件输出，带 runId；无新增游戏玩法修改 |

工作区保留此前未提交文档、demo 补丁与报告；`PRE_BUILDER_FULL_CODE_AUDIT.md` 是此前已有文件，本轮未修改。审查时先 `git status --short`，再查看上述文件，注意新文件未被 `git diff` 单独列入。没有自动提交或推送。

## 关键行为与证据

- 同一规范目录不能同时跑两个任务，避免共享输出交叉污染。
- 两个执行线程、八个等待位；过载显式 REJECTED，不生成无限线程。
- 日志持续读取但仅保留末尾 64K 字符，JSON 结果最多读取 1 MiB。
- PASS 必须同时满足退出码 0、新结果文件、合法 JSON、本次 runId 与 status=PASS；旧文件、缺失证据、未知结果状态均不会通过。
- 取消不覆盖已终结状态；超时终止进程；关闭服务有界等待退出；重启保留历史并把未完成状态改为 INTERRUPTED。
- 修改端点只接受预注册目标 ID，并检查本机来源、Host 和非简单请求头。没有向 LLM 开放任意命令参数。
- 列表只返回摘要；详情按需获取证据；终态停止轮询详情。

验证记录：

1. 后端一次完整 `mvn clean test`：667 项通过，0 failures / 0 errors。
2. 后续新增结果大小硬边界、错误 runId 与队列满载验证后，相关服务与 Controller 定向回归：17 项通过；最新用例总库存为 670，未把定向回归描述为第二次全量。
3. 前端 typecheck、build、61 项测试通过。新增四项页面测试覆盖提交/证据、提交失败、取消与空目录。
4. 实际平台 API：前端类型检查执行退出 0，状态 COMPLETED；demo 浏览器回归两组各六项、状态 PASSED，结果 runId 与任务一致。
5. 实际中台浏览器：从页面点击运行、看到终态和真实日志，未捕获页面脚本错误；截图已人工检查。

审查证据位于 `docs/reports/evidence/platform-local-tests-2026-11-12/`。本轮中途的表集合断言、进程退出等待及并发 Maven 构建造成的验证失败已处理，最终验证采用串行后端构建，避免运行中覆盖 target/classes。

## 尚未解决的范围

这是本机单用户的命令型测试接口，不是源码执行沙箱。无法保证清理脱离进程树的后台进程；后端重启不尝试重新附着或清理旧进程，也不自动重跑。读取路径检查不是针对恶意本机文件系统写入者的隔离边界。

尚无源码/构建指纹绑定、project_id、多用户授权、结果文件浏览器下载、日志实时 SSE、Finding/Release Gate 接线、Agent 工具调用。记录的目标命令快照不能替代游戏版本。证据中的 PASS 是测试程序声明，平台验证其归属与格式，不替代对测试质量的审查。

建议下一批：先给通用目标增加版本/构建清单和产物哈希；再把结构化检查映射到现有 TestPlan/Finding；完成既有 Runtime 的工具协议、授权、回放修正后，才允许 Agent 自动编排这类执行能力。GD 试点脚本降为适配样例，不作为平台后续研发主线。

## 可交给另一个模型的审查任务

请以本文件列出的平台代码为范围，重点检查：任务状态与取消竞争、进程超时/关闭/重启、执行目录与证据路径、结果归属与旧证据误用、队列上限、SQLite 记录一致性、API 本机限制，以及前端失败/终态展示。请给出可复现的问题与位置，区分本首版明确未承诺的能力和实现缺陷；不要只根据测试数量判定正确。
