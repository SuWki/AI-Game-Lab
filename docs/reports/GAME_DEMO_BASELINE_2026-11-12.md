# game-demo 接入评估与首次实测

日期：2026-11-12（工作跨 11–12 日）。层级：State / Report。目标：[SuWki/game-demo](https://github.com/SuWki/game-demo)，固定 commit `bf3e44bf2c32fd3a585cc6b982531f16ef807d13`。本报告记录独立测试，不代表 AI Game Lab 已接入真实游戏。开发任务与产品范围的唯一规划入口是 [开发路线 §6](../design/DEVELOPMENT_ROADMAP.md#6-gdgame-demo-试点任务与验收)。

## 结论

这个 demo 适合作为首个真实游戏试点：有可直接调用的 RunEngine、配置源、MetricsTracker、浏览器调试入口和场景夹具。第一轮已发现一个具体的结算反馈问题，足以验证“测试 → 定位 → 修改 → 回归”的价值。现阶段不应根据少量机器人结果调整三条流派的强弱，也不应先建设完整自动游戏生成平台。

用户明确的产品方向是已有游戏的分析、测试、优化和功能扩展。自有源码游戏先实现深度接入；第三方热门游戏按可获得的画面、操作与接口提供分析和测试。模型用于提出假设、理解证据和生成修改，测试执行器负责判定实际发生了什么。

## 测试范围与结果

独立工作目录：`D:\game-demo-test`。Node `24.13.0`，tsx `4.23.15`，依赖按 demo 的 package-lock 安装。没有修改游戏生产源码、数值或线上部署。测试引擎直接运行 demo 的 TS 代码，没有使用 AGL 的 Java 模拟器替代。

| 检查 | 结果 | 能证明什么 / 限制 |
|---|---|---|
| `npm run build` | PASS | TypeScript 与 Vite 构建成功；有大 chunk 提示，未做性能预算验收 |
| `node tools/validate-configs.ts` | 0 错误、0 警告 | 检查三个 JSON 导出文件；不等于运行时 TS 配置覆盖完整 |
| 既有 `src/tests/balanceTest.ts` | 输出 15/15 PASS | 公式与阈值检查；不代表真实玩家平衡性达标 |
| 真实 RunEngine 自动对局 | 18/18 到达结算 | 3 种子 × 3 流派偏好 × 2 简单移动策略；2 胜、16 负，17 局进入 Boss |
| 采样状态不变量 | 0 次失败 | 检查有限数值、HP 不超过 maxHP、角色不超出场地；不是所有游戏规则的证明 |
| 固定输入条件重放 | PASS | 只复跑 seed=11、crit、orbit 这一组，完整输出一致；未验证跨机器或全部种子 |
| 浏览器 DOM 事件与场景检查 | 6/6 PASS，未捕获 JS/console error | 首页、暂停、恢复、三流派升级夹具；核对各流派特有卡牌文本 |
| 原有浏览器全流程脚本 / 指针复跑 | 未稳定完成 | Chrome 启动、截图、场景等待及一次 Edge 指针点击出现超时；完整自然对局未获通过证据 |
| 新增结算一致性检查 | **FAIL：16/18** | 形成明确 Finding GD-F01；这是游戏输出问题，不是脚本异常 |

浏览器最后通过的版本使用 Edge headless、`--disable-gpu` 和 DOM click 事件触发处理器；QA hook 强制进入升级场景。它没有证明真实键鼠命中、完整渲染性能或自然通关。此前一次指针基础检查通过，但复跑超时，不能称为稳定回归。

引擎脚本按固定 60 Hz 推进，测试进程注入带种子的 Math.random 与虚拟时钟，使用空存档；自动选项偏向目标流派以及强化/事件节点。`orbit` 绕场移动，`seek-xp` 优先追最近经验球。这些策略没有理解弹幕或 Boss 安全窗口。没有无敌、加血、直接触发胜利或 QA 场景注入。由于选择策略与自动决策，模拟时长不能作为真人体验时长。

| 偏好流派 | 胜 / 样本 |
|---|---|
| crit（暴击） | 0 / 6 |
| pierce（穿透） | 0 / 6 |
| dash（穿梭） | 2 / 6 |

上述数据只描述这两个策略在三个种子上的表现。相同种子也不保证跨流派随机调用轨迹相同，不能由此断言穿梭最强、其他流派不可通关或游戏太难。后续应先校准策略，再做同流派、同策略、同种子的前后版本配对实验，并补充人工试玩。

## GD-F01：失败被误写为“流派未成型”

分类：已复现的输出语义缺陷，建议优先级 P2。复现参数：seed=11、preferredRoute=crit、policy=orbit。

实际结算同时包含：

```json
{
  "outcome": "defeat",
  "buildStage": "committed",
  "summary": "流派已成型，但伤害输出不足。",
  "buildSummary": "暴击流派未成型"
}
```

源码定位（基线版本）：`src/systems/RunEngine.ts:1509` 的 `getBuildSummary()` 接收 buildStage，但只依据 outcome 和 routeId 返回“已就绪”或“未成型”；`getRouteFailureReason()` 则使用 buildStage。`src/ui/OverlayController.ts:745` 与 `:749` 在战斗回顾中输出两个字段，`finishRun()` 也把 buildSummary 交给 MetricsTracker。

本次 16 个失败样本均为 committed 或 matured，全部收到“未成型”。对玩家而言，这会误导下一局的选择；对 AI 分析而言，直接消费文案会把生存失败误判为构筑失败。生命值耗尽是已观测的结束原因，“伤害不足”仍是游戏规则生成的归因，未经反事实实验不能直接当作根因。

修复建议：统一流派阶段映射，分别表达成型状态、胜负和结束原因；缺乏充分证据时只报告观测结果。先保留当前失败回归，再改源码并核对 hinted/committed/matured × 胜负组合及浏览器结算面板。本次已添加检查脚本，尚未改动游戏实现。

## 接入前的必要调整

1. **真实数据源**：ConfigLoader 静态导入 `src/data/*.ts`；`public/data/*.json` 仅为导出。只修改 JSON 不会改变当前游戏。AGL 的现有配置提案要映射到实际 TS 源或新增受控覆写入口。
2. **复现入口**：当前随机与时间通过全局对象读取，试验脚本只能在隔离进程替换。正式接入需显式 RNG/clock，并记录 commit、配置与策略哈希、种子、输入序列和存档。
3. **两种执行模式**：复用真实 RunEngine 做逻辑回归；浏览器验证渲染与交互。测试宿主补了真实 Phaser 包的 Clamp 函数，这是引擎对渲染环境的依赖边界，不能记作游戏玩法错误。
4. **小步解耦**：RunEngine 共 9,413 行。先提取上述测试依赖，随后围绕具体功能拆出结算、战斗与选择流程；不要为了接入先全面重写。
5. **指标与归因**：保留流派成型阶段、结束原因、节点选择与动作策略；补齐伤害来源和 Boss 剩余状态。AGL 的 games-share、Java 仿真流派和公式不能直接套到此 demo。

以上对应 [GD-01～GD-05](../design/DEVELOPMENT_ROADMAP.md)，不是本次已经完成的平台能力。

## 证据与复跑

仓库保存有界证据，不纳入完整临时日志、视频或依赖目录：

- [18 局摘要与选择序列](evidence/game-demo-2026-11-12/engine-summary.json)
- [GD-F01 检查结果](evidence/game-demo-2026-11-12/result-consistency.json)
- [浏览器最终检查](evidence/game-demo-2026-11-12/browser-summary.json) / [指针复跑失败](evidence/game-demo-2026-11-12/browser-pointer-failure.json)
- [版本与文件哈希](evidence/game-demo-2026-11-12/manifest.json)
- 引擎与浏览器脚本位于 `scripts/game-demo/`；复制到 demo 的 `tools/` 后运行。它们是针对该 commit 的试点脚本，尚不是通用适配器。

在 PowerShell 中复跑（以下使用已存在的独立 clone；新环境先克隆并固定到报告 commit）：

```powershell
Copy-Item D:\AI-Game-Designer\scripts\game-demo\agl-engine-baseline.ts D:\game-demo-test\tools\
Copy-Item D:\AI-Game-Designer\scripts\game-demo\agl-browser-check.mjs D:\game-demo-test\tools\
Set-Location D:\game-demo-test
npm ci --ignore-scripts
npm run build
node tools/validate-configs.ts
npm exec --yes --package=tsx@4.23.15 -- tsx src/tests/balanceTest.ts
npm exec --yes --package=tsx@4.23.15 -- tsx tools/agl-engine-baseline.ts
node D:\AI-Game-Designer\scripts\game-demo\check-result-consistency.mjs output/qa/agl-engine/summary.json
```

最后一条在当前基线上应退出 1，输出 `GD-F01 FAIL count=16`。引擎完整 JSON 留在 `D:\game-demo-test\output\qa\agl-engine\summary.json`，有界摘要保留全部 18 局的关键结果。

另开终端运行 `npm run preview -- --host 127.0.0.1 --port 4173 --strictPort`，在 demo 目录执行 `node tools/agl-browser-check.mjs`。脚本当前使用 Windows Edge 固定路径与 1440×960 视口，浏览器最终 PNG 和 JSON 在 `output/qa/agl-browser/`。机器或路径变化需调整启动参数；超时必须报告为未完成，不能当作游戏失败或测试通过。

本轮交付：真实游戏基线、一个已定位的 Finding、可复跑脚本与修订后的开发计划。未交付：Java GameAdapter 接入、Agent 自动修改、数值优化结论、完整浏览器自然对局验收及游戏源码修复。
