# 两条业务线本地验收

2026-11-19。已有游戏的配对回归和两款模板新作的工程验收通过。真实模型请求和用户试玩接受仍待完成。证据包含版本指纹、Finding、修订、候选归档指纹和浏览器执行 ID。[结构化结果](evidence/business-2026-11-19/results.json)

## 已有游戏改进

两个样本通过同一平台 API 运行。操作者注册命令和版本清单，平台固定基线、导入 Finding、审批单文件提案，再在隔离工作区以同一命令回归。验证结束比对原文件 SHA256，原始项目未被写回。两个样本分别使用匹配其产品目标的检查程序。

| 样本 | 相同测试条件下的改进 | 同轮回归 |
| --- | --- | --- |
| game-demo，`bf3e44b` | 结算把已成型构筑描述为未成型，从 16/18 局降到 0/18 局 | 相同的 18 个种子、路线和策略组合，前后均 2 胜、18 局结算、零不变量失败，记录中的玩法字段一致 |
| 2048，`478b6ec` | Edge 按住 R，重开事件从 2 次降到 1 次 | 同时发送 Shift+R、方向键和 D，修订前后均维持原修饰键处理和左右方向事件 |

game-demo 使用已有结算修复，2048 仅在隔离方案中加键盘重复事件过滤。[game-demo 原始项目](https://github.com/SuWki/game-demo/tree/bf3e44bf2c32fd3a585cc6b982531f16ef807d13) · [2048 源码和许可](https://github.com/gabrielecirulli/2048/tree/478b6ec346e3787f589e4af751378d06ded4cbbc)

### 复跑入口

- game-demo 从上述提交创建干净工作区，复制 [引擎验收程序](../../scripts/game-demo/agl-engine-baseline.ts) 至游戏的 `tools/agl-engine-baseline.ts`。目标命令参数依次为 Node、[平台注册适配器](../../scripts/game-demo/agl-platform-check.mjs)、tsx 4.23.15 的 CLI 路径、npm CLI 路径和完整上游提交号。注册 `src`、`public`、入口 HTML、Vite 和 TypeScript 配置、package.json、lockfile 和验收程序为版本输入，保护配置、锁和验收程序。产物可登记 `dist` 与 `output/qa/agl-engine/summary.json`。应用隔离提案时使用 [结算修订](../../scripts/game-demo/result-summary.patch) 中的源码差异。
- 2048 从上述提交创建干净工作区。目标命令参数为 Node、[键盘验收程序](../../scripts/local-tests/2048-keyboard-check.mjs)、Playwright 的 index.mjs、Chromium 安装路径。注册 `index.html`、`js`、`style`、`LICENSE.txt`，保护许可文件。修订文件为 `js/keyboard_input_manager.js`，在 R 的现有条件加入 `!event.repeat`。

两个目标均需设置相对 `resultPath`。回归开始前再次固定源码范围，适配器负责按相同的游戏脚本执行。配置格式和审批流程见[本地游戏测试协议](../platform/LOCAL_GAME_TEST_EXECUTION.md)。

## 同类型新作

使用同一份 `survival-web 1.0.1` 模板、两个独立规格和两个隔离生产目录。上一阶段在隔离候选上验证的首帧修复已纳入模板。重开先画出初始状态，之后的非零时间步才推进逻辑。两作的首帧资源、得分、生命、时间共八项浏览器检查通过。

| 作品 | 浏览器试玩记录 | 生产和复查用时 |
| --- | --- | --- |
| 余热回廊 | 按空格后热量 6 → 50，存活到自然结算 | 22.1 秒 |
| 屏障穿梭 | 等能量恢复，按方向和空格后能量 65 → 5，存活到自然结算 | 21.3 秒 |

用时包含规格登记、构建、引擎验收、两次浏览器操作及打包下载。两份 ZIP 的 SHA256 与平台记录匹配。解压后分别重新构建，五个构建文件的指纹均与候选清单一致，并通过各自的便携服务逐一读取校验。两作都是自动试玩观察，平台评审仍等待用户接受。

从仓库根目录用一个新输出目录复跑。需要本机运行 AI Game Lab 并提供 Playwright 模块与 Chromium 路径：

```text
node scripts/production/acceptance/two-works.mjs NEW_OUTPUT_DIRECTORY "PLAYWRIGHT_INDEX" "BROWSER_EXECUTABLE"
```

输出包含两个 ZIP、规格、浏览器回执、机制截图和汇总。固定候选中的浏览器探针记录重开四项，玩法激活由同批次的独立浏览器观察记录。实验使用操作者确认规格、模板直接生产和既有审批流程，无真实供应商调用或可报告的账单。付费模型、有界 Builder 执行、模型修订成本和用户试玩接受继续列为最终验收。
