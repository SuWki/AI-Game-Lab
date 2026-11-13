# game-demo 首批实施记录

日期：2026-11-12；State / Report。承接 [开发路线](../design/DEVELOPMENT_ROADMAP.md) 与 [原版基线](GAME_DEMO_BASELINE_2026-11-12.md)。本记录补充后续修复，不改写原版测试事实。

## 已实现

- 主仓库增加 `scripts/game-demo/Invoke-GameDemoTest.ps1`：校验固定游戏 commit，复制测试入口，按需安装锁定依赖，构建并执行真实引擎测试、结算检查，保存执行结果和源码/锁文件/测试脚本/配置指纹；失败停止，不依赖 LLM。
- `result-summary.patch` 保存可审阅的游戏源码修改；`-ApplyResultFix` 检查后应用，已应用时核对反向补丁，冲突则停止。实际修改已在 `D:\game-demo-test` 应用。
- 结算构筑描述复用现有 RouteProgression 的阶段叙述，移除按胜负推断成型的逻辑。胜负、结束原因继续独立记录。修改范围为 RunEngine 的调用与格式化方法，未调整战斗数值。
- 新增 26 项实际引擎格式化检查，覆盖三路线、四阶段、两种结局及无路线的两种结局。原版检查失败，修复版通过。
- 引擎报告记录实际源码哈希，避免仅以相同 Git HEAD 混淆原版与未提交修复版。

运行方式（PowerShell 7，Node/npm/git 可用，demo 已检出固定 commit）：

```powershell
Set-Location D:\AI-Game-Designer
./scripts/game-demo/Invoke-GameDemoTest.ps1 -GamePath D:\game-demo-test -ApplyResultFix
```

新环境可加 `-InstallDependencies`。未传修复参数时测试目录当前源码；原版预期以 GD-F01 Finding 停止。运行会覆盖该 demo 目录中本工具命名的测试脚本及 `output/qa/agl-engine/` 结果，比较不同版本前应保留原版结果。

## 验证结果

实际通过主仓库入口执行：构建 PASS、阶段/胜负检查 26/26、真实引擎 18/18 到达结算、受检不变量零失败、固定的一组重放一致；结算一致性错误从 16 降到 0。18 组的种子、路线偏好、移动策略、模拟时长、Boss 到达、选项序列、胜负、阶段、结束原因和终点名称与原版相同；这是限定字段与样本的回归证据，不是全状态等价证明。

证据：[修复后检查](evidence/game-demo-2026-11-12/result-consistency-fixed.json)、[执行记录](evidence/game-demo-2026-11-12/execution-fixed.json)、[玩法字段对比](evidence/game-demo-2026-11-12/gameplay-comparison.json)。执行记录来自配置哈希列表加入前的实际运行；后续入口会额外保存全部 src/data 文件哈希。

## 尚未完成与下一步

本轮是主仓库可调用的命令入口，未注册 Java GameAdapter、未新增 UI/API、未改动线上游戏。GD-01 部分完成：仍使用隔离进程中的全局 RNG/clock 替换，只复跑一组，不承诺完整输入回放。GD-03 已完成源码与引擎验证，浏览器结算面板回归仍待完成。

GD-02 的下一处实现边界已核实：`GameSessionService.start()` 强制解析平台 ConfigVersion 为 Java GameConfig；该配置不能代表 TS 源码版本。应先在 Tester 技术设计中定义真实游戏版本/配置指纹绑定，保持旧 Fake 契约兼容，再实现可启动、逐步操作、观测、停止的 Node 桥接。不能直接切换 Bean 并沿用错误的配置归属。共享 AgentLoop 与 Tool Registry 保持复用。

## 本地启动并直接调用验证

已启动构建版 `http://127.0.0.1:4173/game-demo/`，通过 `scripts/game-demo/agl-local-results.mjs` 直接调用游戏已有 QA 接口。crit/pierce/dash × victory/defeat 共六个结算页面场景通过，未捕获 pageerror；证据见 [本地浏览器结果](evidence/game-demo-2026-11-12/local-browser-results.json)。这是强制场景检查，不是自然通关；启动和调用不依赖 Java GameAdapter。

补充核实：OverlayController 的 showResultDetails 目前只有方法定义，没有调用入口。原报告中两段矛盾文案的详情模板属于未接通代码，不能据此认定玩家在当前可达页面看到了矛盾。已确认的问题存在于 RunResult 与 MetricsTracker 数据；本次浏览器检查验证可达结算页面，未声称覆盖隐藏详情模板。

## 本地统一测试流程（第二批）

新增 `scripts/game-demo/local-suite.mjs`：独立启动 Vite 预览，等待本进程就绪后再调用浏览器；串行执行基础交互与结算检查；每次生成独立的时间目录，保存 JSON、截图、日志和 report.html；结束时清理本次启动的服务。端口被占用时明确失败，不复用身份未知的服务。每个浏览器子测试有 180 秒上限，启动等待上限 20 秒。

统一命令：

```powershell
./scripts/game-demo/Invoke-GameDemoTest.ps1 -GamePath D:\game-demo-test -ApplyResultFix -BrowserChecks
```

默认测试端口 4174，可通过 `-PreviewPort` 指定。用户自己启动的 4173 预览不受影响。结果在 demo 的 `output/qa/local-suite/<UTC时间>/`；打开 report.html 查看通过项与证据链接。浏览器测试仍是 DOM 事件和 QA 场景验证，不等同于真实键鼠或自然通关。

已单独验证：两组各六项通过；使用被占用的 4173 时生成 FAIL 报告并说明端口冲突；成功与失败均有独立结果目录。未改动 Java/React 生产代码，因此本批不重跑无关后端/前端全量测试。

统一命令最终验收通过：构建、26 项结算检查、18 局引擎回归、GD-F01 零失败，以及两组共 12 项浏览器检查。成功证据见 [suite PASS](evidence/game-demo-2026-11-12/local-suite-pass.json)，故障路径见 [端口占用 FAIL](evidence/game-demo-2026-11-12/local-suite-port-conflict.json)。本次结果页位于 `D:\game-demo-test\output\qa\local-suite\2026-11-12T02-25-42-097Z\report.html`。已核对 4174 测试服务退出。

## GD-05：单局复盘导出功能

已在 demo 结算页新增“导出本局复盘”。玩家可下载 schemaVersion=1 的 JSON，包含路线节点、强化列表、事件选择、构筑阶段、结束原因和局内统计。事实字段与游戏生成的解释文案分开保存；未记录的伤害时间线、输入回放、种子和源码版本明确标记不可用。路线记录沿用当前引擎最后最多六个节点，不能声称全程记录。

功能源码位于独立 demo：`src/systems/RunReview.ts`、`src/scenes/ResultScene.ts`、`src/ui/OverlayController.ts`。可审阅、复用的差异保存为主仓库 `scripts/game-demo/run-review.patch`。命令：

```powershell
./scripts/game-demo/Invoke-GameDemoTest.ps1 -GamePath D:\game-demo-test -ApplyReviewFeature -BrowserChecks
```

该参数会同时核对结算修复，应用复盘功能，构建并运行相关回归。新增 `run-review.test.ts` 用全部 18 个真实引擎结果检查导出与原始数据一致、事实/解释分离，以及修改导出副本不会污染原对局。浏览器结算测试实际点击按钮、接收下载，再读取 JSON 核对路线、胜负、列表和可用性标记。启用功能时缺少导出按钮必须失败。

当前功能是可下载的结果复盘，不是可重演的操作录像；关键受伤事件与全量输入采集仍是后续开发项。本批推进了已有游戏的新功能，未调整战斗平衡。
