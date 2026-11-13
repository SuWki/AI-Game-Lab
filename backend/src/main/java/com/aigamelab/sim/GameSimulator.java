package com.aigamelab.sim;

import java.util.SplittableRandom;

import com.aigamelab.sim.model.Player;
import com.aigamelab.sim.model.Upgrade;
import com.aigamelab.sim.model.Weapon;

/**
 * 蓝图 §12.2 / §12.5：单局确定性模拟器。
 *
 * 主循环（tick 定步长 dt=0.02s，共 30000 tick）：
 *   波次生成 → 玩家射击 → 弹丸推进+命中(含穿透) → 敌人推进+接触伤害 → 升级(加权随机) → 死亡判定
 *
 * 确定性保证（蓝图 §12.3）：所有随机经注入 SplittableRandom；
 * 同一 (config, buildType, seed) 必得逐字段相同结果。
 * 本类为无状态纯计算（不依赖 Spring/DB），可直接独立测试（03 文档专项检查结论）。
 */
public final class GameSimulator {

    private final GameConfig config;
    private final String buildType;
    private final SplittableRandom rng;

    public GameSimulator(GameConfig config, String buildType, long seed) {
        this(config, buildType, new SplittableRandom(seed));
    }

    /** 注入 RNG（批量模拟预分裂时使用，保证可复现）。 */
    public GameSimulator(GameConfig config, String buildType, SplittableRandom rng) {
        this.config = config;
        this.buildType = buildType;
        this.rng = rng;
    }

    public GameOutcome simulate() {
        Player player = config.player();
        BuildState build = BuildFactory.create(config, buildType);
        WaveDirector waves = new WaveDirector(config.wave(), config.enemy());
        double dt = config.sim().dt();
        double totalTime = config.sim().totalTimeSec();

        double hp = player.maxHp();
        double elapsed = 0.0;
        double totalDamage = 0.0;
        double timeToKill = -1.0;
        double fireCooldown = 0.0;
        double levelUpTimer = 0.0;
        double spawnTimer = 0.0;
        int nextEnemyWave = 1;
        // 单敌模型：敌人有 alive 状态；仅 alive 时造成接触伤害。
        // kill 后进入 spawnGap 空窗（无接触伤害、玩家回血），由 spawnRate 决定刷新节奏。
        double enemyHp = waves.enemyHpAt(1);
        double enemyDmg = waves.enemyDamageAt(1);
        boolean enemyAlive = true;
        double spawnInterval = 1.0 / Math.max(0.01, config.wave().spawnRate()
                * config.difficulty().spawnRateMultiplier());

        while (elapsed < totalTime) {
            int waveIdx = waves.currentWave(elapsed);
            // ---- 波次推进：波次变化时刷新敌人成长值 ----
            if (waveIdx > nextEnemyWave) {
                nextEnemyWave = waveIdx;
                enemyHp = waves.enemyHpAt(waveIdx);
                enemyDmg = waves.enemyDamageAt(waveIdx);
                enemyAlive = true; // 新波次重新刷出
            }

            // ---- 玩家射击（主武器，含暴击）；仅对 alive 敌人有效 ----
            fireCooldown -= dt;
            Weapon w = build.weapons().get(0);
            if (fireCooldown <= 0.0) {
                fireCooldown = w.fireIntervalMs() / 1000.0;
                boolean crit = DamageCalculator.rollCrit(w.critChance(), rng);
                double dmg = DamageCalculator.compute(w, enemyAlive ? config.enemy().defense() : 0.0, crit);
                if (enemyAlive) {
                    enemyHp -= dmg;
                    totalDamage += dmg;
                    if (enemyHp <= 0.0) {
                        if (timeToKill < 0.0) {
                            timeToKill = elapsed;
                        }
                        enemyAlive = false;          // 敌人死亡 → 进入空窗
                        spawnTimer = 0.0;
                    }
                }
            }

            // ---- 敌人推进 + 接触伤害（仅 alive 时；空窗期玩家不受击、回血） ----
            if (enemyAlive) {
                double incoming = enemyDmg * dt;
                double mitigated = incoming * (1.0 - player.armor());
                hp -= Math.max(0.0, mitigated);
            } else {
                // 空窗：玩家回血（蓝图 §12.1 hpRegenPerSec）
                hp = Math.min(player.maxHp(), hp + player.hpRegenPerSec() * dt);
                spawnTimer += dt;
                if (spawnTimer >= spawnInterval) {
                    // 刷新下一个敌人（按当前波次成长）
                    enemyHp = waves.enemyHpAt(waveIdx);
                    enemyDmg = waves.enemyDamageAt(waveIdx);
                    enemyAlive = true;
                }
            }

            // ---- 升级（按 levelUpInterval 触发，加权随机） ----
            levelUpTimer += dt;
            if (levelUpTimer >= player.levelUpIntervalSec()) {
                levelUpTimer = 0.0;
                Upgrade u = UpgradePool.pickWeighted(build.upgradePool(), rng);
                if (u != null) {
                    build.applyUpgrade(u);
                }
            }

            // ---- 死亡判定 ----
            if (hp <= 0.0) {
                return GameOutcome.death(elapsed, totalDamage,
                        timeToKill < 0 ? elapsed : timeToKill, buildType, build.pickedUpgrades());
            }

            elapsed += dt;
        }

        // 存活到时间结束 → 胜（蓝图 §12.1：600s 存活判胜）
        boolean win = hp > 0.0;
        return win
                ? GameOutcome.win(totalTime, totalDamage,
                        timeToKill < 0 ? totalTime : timeToKill, buildType, build.pickedUpgrades())
                : GameOutcome.death(totalTime, totalDamage,
                        timeToKill < 0 ? totalTime : timeToKill, buildType, build.pickedUpgrades());
    }
}
