# B-01 首个可玩模板切片

2026-11-13，实际代码与可玩产物交付。平台从版本化 GameSpec 通过确定性模板参数生成独立游戏项目；本轮没有模型编程、生产编排或生成 UI。继续沿用已有 ART-01 API，没有新增后端执行循环。

## 实现与产出

新增 templates/survival-web：Canvas/ES modules 原创几何生存游戏，移动、自动射击、资源技能、胜负、重开。两个实例为 Heat Garden（热量/环形爆发）与 Shield Runner（能量/护盾冲刺），不同资源规则改变技能使用时机；没有把换标题当玩法差异。

新增 scripts/production 的 instantiate/from-artifact/build-project/serve-project 与外部 acceptance/browser-smoke。每实例固定 GameSpec ID/revision/hash、模板版本/hash与文件清单；构建输出进入新目录，旧构建保留，预览只提供清单内且 hash 符合的产物。源码、配置、规格、依赖清单（零依赖）、启动/构建脚本和 README 位于工作区。

实际工作区：

- Heat Garden：`data/generated-games/a375d445-12d4-47ab-8abc-2916d4b8f46d`，build-23ec7afe-9921-4940-abb6-e4f780cabbca。
- Shield Runner：`data/generated-games/585faed1-6521-49ca-ac56-374847de0876`，build-a43fc2c3-03a6-4398-aa9a-752c683170ca。

这些为忽略目录下的本机产物，不是提交到主仓库的两个定制 demo；制造逻辑和测试保留在共享模板/脚本。若工作区丢失，按证据中的 specification.json 可重新生成。

## 验证

`node --test scripts/production/template.test.mjs`：7 项通过。覆盖确定性复现、自然结算/重建、两种机制、移动/资源边界、碰撞死亡/护盾、非法参数/篡改规格拒绝、双实例构建隔离及旧输出保留。最初两个测试未隔离自动射击对场景的影响，明确隔离对应 fixture 后通过，没有删去被测行为。

两实例分别使用 Edge headless 和 Playwright（测试环境的外部依赖），真实点击开始、移动按键、空格、经过 6 秒到自然胜负，再点击重开；每实例 4 项通过、无 pageerror。此为短轮次 smoke，不能证明长局平衡或完整体验质量。独立 runner 不复制到可编辑游戏目录，尚未实现平台级验收程序写保护。

证据：[热量浏览器结果](evidence/template-2026-11-13/heat/browser.json)、[规格](evidence/template-2026-11-13/heat/specification.json)、[模板来源](evidence/template-2026-11-13/heat/production-manifest.json)、[截图](evidence/template-2026-11-13/heat/settlement.png)；[护盾结果](evidence/template-2026-11-13/shield/browser.json)、[规格](evidence/template-2026-11-13/shield/specification.json)、[来源](evidence/template-2026-11-13/shield/production-manifest.json)、[截图](evidence/template-2026-11-13/shield/settlement.png)。

本轮只改模板、Node 工具与文档，未改 Java/React，未重复运行后端/前端测试。操作、参数及复跑见 [平台说明](../platform/PLAYABLE_TEMPLATE.md)。

## 范围与下一步

原设计 Phaser/TypeScript/Vite 改为先验证零依赖 Canvas 切片，正式 Phaser 模板待接入。自然语言 coreLoop/requirements 未自动转为代码，仅 systems.survivalWeb 支持的参数生效，manifest 明示剩余规格未实现。6 秒 smoke 配置不代表默认成品时长；正式任务可配置 1–300 秒。

下一步 B-02/03 将实例与生产记录/API 关联，复用既有注册命令执行与验收基础，再接 Builder 身份、提案/修复和工作台。VAL-01 仍需真正保护验收程序。两种参数实例不等于模型从两份原创设计自主实现，更不是完整 B-07 验收。
