# 本地游戏测试执行

层级：Platform。更新于 2026-11-12。注册本地游戏的测试命令，绑定版本和证据，复用现有 TestPlan/Finding、共享 Agent Runtime 与 SQLite。契约变更见 [ADR](../decisions/ADR-20261112-RUNTIME-LOCAL-TEST.md)。

当前 PASS/VALIDATED 表示注册检查满足本协议。验收逻辑独立性、源码到运行产物的构建关联、优化目标对照仍待实现，见 [可信改进验证设计](IMPROVEMENT_VALIDATION_DESIGN.md)。当前 coverageComplete 为生产测试报告的程序声明，平台不能据此证明场景覆盖充分。

## 配置与启动

设置 `local-tests.catalog`（Spring 参数或 `LOCAL_TESTS_CATALOG` 环境变量）指向操作者维护的 JSON 数组。默认路径为后端工作目录下 `./data/local-test-targets.json`。文件不存在时目标列表为空；平台不会自动克隆游戏或执行任意浏览器输入命令。

```json
[
  {
    "id": "my-game-smoke",
    "name": "我的游戏基础检查",
    "workingDirectory": "D:/games/my-game",
    "command": ["D:/nodejs/node.exe", "tools/test.mjs"],
    "timeoutSeconds": 120,
    "resultPath": "output/test-result.json",
    "versionFiles": ["src", "package.json", "package-lock.json", "tools"],
    "artifactFiles": ["dist"]
  }
]
```

命令是参数数组，通过 ProcessBuilder 直接启动，不拼接 shell。Windows 的 npm.cmd 不能作为可移植的直接可执行文件；可以使用 node.exe 与 npm-cli.js，或由操作者明确注册 PowerShell 等解释器及脚本。HTTP 仅接收目标 ID，不允许覆盖命令、目录、环境或超时。

目标 ID 唯一。工作目录需存在，结果文件必须位于该目录中；拒绝绝对结果路径、`..` 越界与已存在的越界符号链接。同一规范工作目录一次只允许一个活跃任务，避免共享结果文件的交叉污染。全局两条执行线程、八个等待位，过载返回冲突并保留 REJECTED 记录。

这不是运行不可信代码的沙箱。命令以当前操作系统用户执行；首版仅供单用户本机使用。修改 API 检查本机来源、本机 Host 和 `X-AGL-Local-Test: 1` 非简单请求头。页面通过现有同源 API 代理调用；多人、远程执行与身份授权需另行设计。

## 结果协议

任务启动时注入 `AGL_TEST_RUN_ID`；配置了 resultPath 时另注入其绝对路径 `AGL_TEST_RESULT_PATH`。测试程序在本次执行中写入：

```json
{
  "runId": "本次 AGL_TEST_RUN_ID",
  "status": "PASS",
  "coverageComplete": true,
  "checks": [{"id":"player-health", "status":"PASS", "expected":"maxHp > 0", "actual":"100"}]
}
```

结果文件上限 1 MiB，必须是可解析 JSON、与执行前指纹不同，并且 runId 与本任务相同。`PASS` / `FAIL` 是报告协议，不沿用旧模块含混的 games-share 判据。没有结果文件时退出 0 只记 COMPLETED。日志持续消费，结束后最多保存末尾 65,536 字符。

| 执行情况 | 本地测试状态 |
|---|---|
| 等待 / 已开始 | QUEUED / RUNNING |
| 退出 0，匹配的新证据 status=PASS | PASSED |
| 退出非 0，或匹配证据 status=FAIL | FAILED |
| 退出 0，未配置结果文件 | COMPLETED |
| 退出 0，配置的证据缺失、旧文件、runId 不匹配或未知判据 | INCONCLUSIVE |
| 非法 JSON / 超大结果 / 启动或读取失败 | FAILED（保留原因） |
| 超时 / 用户取消 / 后端重启 | TIMEOUT / CANCELLED / INTERRUPTED |
| 调度拒绝 | REJECTED |

取消和超时尝试终止已观测的子进程树；不承诺对逃逸、后台脱离或其他进程管理器实现操作系统级隔离。重启只把数据库中未终结的记录改为 INTERRUPTED，不自动重放副作用或宣称旧任务仍被控制。关闭服务会取消活跃任务并有界等待线程退出。

## API 与中台

- GET `/api/local-tests/targets`：目标配置。
- POST `/api/local-tests/targets/{id}/runs`：202，提交已注册目标。
- GET `/api/local-tests/runs`：最近 100 条摘要，不带日志与证据大字段。
- GET `/api/local-tests/runs/{id}`：完整快照、尾部日志、证据、错误。
- POST `/api/local-tests/runs/{id}/cancel`：取消；已终结任务保持原状态。

响应复用 ApiResp。平台页面 `/local-tests` 提供目标、运行按钮、任务列表、取消、详情和错误展示。状态轮询，首版没有新增 SSE 类型。

## 被测版本与 QA 判定

`versionFiles` 支持相对文件或目录，递归展开后对排序后的路径及 SHA256 生成 `local-<SHA256>` 版本。执行前后复查同一清单，包括目录中的新增、删除；禁止符号链接、越界路径，最多 5000 文件 / 64 MiB。命令另存指纹；完成时保存声明产物的 SHA256。没有声明源码仍可执行命令，但不能进入版本化 QA、导入或修改回归。工具链与清单以外的依赖不自动被版本覆盖。

执行状态与 QA 判定分开：只有完整版本、有效本次报告、`coverageComplete=true`、非空且 ID 唯一的结构化检查才得到 PASS/FAIL；失败检查必须有 expected/actual。报告与退出状态矛盾、必需产物缺失或证据不足均为 INCONCLUSIVE。单纯退出 0、模型 stop 均不构成质量通过。

导入是事务且幂等的：创建既有 Task / TestPlan，每个 FAIL 创建 Finding，保存 `local:<执行 UUID>` 证据会话、版本和原始检查。PASS 不生成 Finding。版本来自登记的本地命名空间，不伪造内置 GameConfig。QA 本地证据入口与旧模拟 ReleaseGate 指标分别判定。

## 修改提案与 Agent 接线

页面 `/local-tests` 支持选择版本文件、读取内容、编辑修改后文本和生成提案；`/local-tests/changes` 展示前后文本，人工批准或拒绝。批准后校验基线未漂移，复制声明源码到 `local-tests.workspace-root`（默认 `./data/local-workspaces`）下的独立目录，再应用修改并执行原注册命令。最多 20 文件、总计 1 MiB UTF-8 修改；仅允许声明范围内的文件。当前支持文本写入，不提供删除/重命名操作。

命令使用相对参数或 `{workspace}` 引用隔离目录；明确指向原始工作目录的命令拒绝验证。依赖安装、引擎和测试程序需通过注册命令及清单准备，平台不自动复制 node_modules。验证结果必须 PASS 并保留全部基线检查 ID 才为 VALIDATED；漏测为 INCONCLUSIVE，失败为 REGRESSION_FAILED。原目录不写回，Finding 不自动标为 FIXED；人工审查后再决定合并。

可从本次证据创建 DESIGNER / TESTER 任务，在 Workflow 启动。绑定的角色工具：Tester 可启动、查询、导入本目标测试；Designer/QA Analyst 可查结果、读声明源码、提出需审批修改。任务不能跨基线读取其他执行。`local_change_propose` 挂起现有 Run，人工拒绝或隔离验证结束后，把观察结果回传原工具调用并恢复共享循环。没有 Agent 自批工具。

补充 API（响应仍为 ApiResp，修改接口仍需本机来源和请求头）：

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/local-tests/runs/{id}/assessment` | QA 判定、版本、源码/产物指纹、导入关联 |
| GET | `/api/qa/local-tests/{id}` | 同一 QA 判定 |
| GET | `/api/local-tests/runs/{id}/source?path=...` | 校验基线后的源码文本与 SHA256 |
| POST | `/api/local-tests/runs/{id}/import` | 幂等导入 TestPlan / Finding |
| POST | `/api/local-tests/runs/{id}/agent-task` | `{agentType, goal}` 创建受限任务 |
| GET / POST | `/api/local-tests/changes` | 列表 / `{baseRunId,title,edits:[{path,beforeSha256,afterText}]}` |
| GET | `/api/local-tests/changes/{id}` | 提案、工作区、回归执行和错误 |
| POST | `/api/local-tests/changes/{id}/approve` 或 `/reject` | 人工决策 |

## 持久化与复跑

沿用幂等 schema.sql 初始化，新增 local_test_run、local_test_context、local_change_proposal，加上 Runtime 两张表共 21 张业务表；既有列不变。重启可查历史版本/提案；执行中测试转 INTERRUPTED，准备中修改不盲目重放。

[配置示例](../../backend/local-test-targets.example.json) 使用仓库内无依赖 Node 样例：`scripts/local-tests/example-game`。调整 node.exe 和 workingDirectory 后启动，第一次健康值为 0，应得到 FAIL；在页面改成正数，批准后工作区应 PASS，原目录仍为 0。这是通用协议与隔离回归验收样例。真实游戏的命令适配需自行提供；demo 浏览器结果也是该协议的消费者。

仍无 project_id、多用户授权、操作系统沙箱或自动合并发布。完整交付证据和剩余限制见 [三批交付报告](../reports/THREE_BATCH_DELIVERY_2026-11-12.md)。
