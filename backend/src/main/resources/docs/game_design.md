# PROJECT ORBITAL — 游戏设计文档

> 本文为 AI 游戏策划 Agent 的静态背景设定与目标说明，供 `get_game_design` 工具读取。
> 数值模型细节见技术设计 §12；工具契约见 §11；配置版本/提案流程见 §14/§15。

## 1. 游戏定位

PROJECT ORBITAL 是一款**数值驱动的 Roguelite 弹幕生存**游戏（纯数值模拟，不渲染）。
玩家在轨道空间站中抵御成波入侵的敌群，存活满 600 秒即胜利，HP 归零即失败。

## 2. 核心循环

- 玩家固定持有一个 **Build**（buildType ∈ {crit, attack_speed, penetration}）
- 每 30 秒推进一波，共 20 波；敌人 HP / 伤害随波次成长
- 玩家持续射击（含暴击），击杀敌人并承受接触伤害
- 每隔固定时间升级一次，从该 Build 的升级池按权重随机选取强化

## 3. 三 Build 设计意图（刻意失衡种子）

| Build | 武器 | 失衡方向 | 设计目标 |
|---|---|---|---|
| `crit` (nova_sniper) | 高单发 + 暴击 | **明显偏强** | 预期胜率最高、DPS 最高 |
| `penetration` (railgun) | 穿透 + 破甲 | **明显偏弱** | 后期护甲成长使其几乎无感 |
| `attack_speed` (gauss_smg) | 高射速低单发 | **居中参照** | 手感与强度均居中 |

## 4. 策划目标

AI 游戏策划 Agent 的核心任务：**在保持三 Build 可玩的前提下，缩小强度差距，使胜率贴近目标值（targetWinRate≈0.5）且各 Build DPS 方差可控（acceptableBuildDpsVariance）。**

修改配置**只能**通过 `modify_game_config` 工具提交 JSON Merge Patch，禁止在文本中"声称"改了数值。
每次修改被批准后必须再次模拟复测，并以前后对比验证平衡改善。

## 5. 工具使用铁律（摘要）

1. 重大修改先用 `create_change_proposal` 提出 ≥2 套候选方案并比较优缺点/风险/玩家体验影响。
2. 实际改数动作一律走 `modify_game_config`，提交后会落 PENDING 提案并挂起 Run 等待人工批准。
3. `modify_game_config` 的 reason 必须回答六问：问题是什么？证据是什么？改什么？为什么？预期结果？如何验证？
4. 工具可用时优先调用工具获取数据，不要臆测配置数值。
