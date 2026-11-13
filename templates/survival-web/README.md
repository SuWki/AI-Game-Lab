# Survival Web 1.0.0

Builder 首模板的可玩切片，使用原生 Canvas / ES modules，无第三方运行或构建依赖。支持 WASD/方向键移动、自动射击、两种不同资源技能、胜负与重开。图形全部是本仓库原创几何占位，无外部美术/音乐。

热量模式：自动射击增加热量，空格释放环形弹幕，过热停止自动射击；护盾模式：能量随时间恢复，空格消耗能量沿当前方向冲刺并短暂无敌。二者共用底层工程，各自改变操作决策。当前没有关卡/成长/声音/存档/联网。

模板不直接启动，需要通过 `scripts/production/instantiate.mjs` 或 `from-artifact.mjs` 生成 config.json 和项目元数据。读取 [操作说明](../../docs/platform/PLAYABLE_TEMPLATE.md)。独立验收工具位于 scripts/production/acceptance，未复制进可编辑项目。

支持 `systems.survivalWeb`：mechanic 为 heat-burst/shield-dash；duration 1–300 秒；enemySpeed 20–180；spawnInterval 0.3–3 秒；seed 为 uint32。title 最多 120 字符。未知机制或指纹不符拒绝实例化。

第一版只解释明确支持的参数。coreLoop/requirements 的自然语言不会自动变成代码；生成项目的 manifest 明示剩余规格须另行实现和验收。不要据此声称完整 Builder 或全部需求达标。
