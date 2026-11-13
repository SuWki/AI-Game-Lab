package com.aigamelab.sim;

import com.aigamelab.sim.model.Enemy;
import com.aigamelab.sim.model.Wave;

/**
 * 蓝图 §12.1/§12.2：波次导演。按 tick 推进波次，依波次序号对敌人 hp/damage 做成长缩放。
 * 纯计算，无随机依赖（敌人生成节奏由模拟主循环用注入 RNG 决定）。
 */
public final class WaveDirector {

    private final Wave wave;
    private final Enemy baseEnemy;

    public WaveDirector(Wave wave, Enemy baseEnemy) {
        this.wave = wave;
        this.baseEnemy = baseEnemy;
    }

    /** 当前波次（1-based），到达上限后保持最后一波。 */
    public int currentWave(double elapsedSec) {
        if (wave.waveDurationSec() <= 0) {
            return 1;
        }
        int w = (int) (elapsedSec / wave.waveDurationSec()) + 1;
        return Math.min(w, wave.totalWaves());
    }

    /** 该波次敌人 HP（按 enemyHpGrowthPerWave 指数成长）。 */
    public double enemyHpAt(int waveIndex) {
        return baseEnemy.hp() * (1.0 + wave.enemyHpGrowthPerWave() * (waveIndex - 1));
    }

    /** 该波次敌人伤害（按 enemyDamageGrowthPerWave 成长）。 */
    public double enemyDamageAt(int waveIndex) {
        return baseEnemy.damage() * (1.0 + wave.enemyDamageGrowthPerWave() * (waveIndex - 1));
    }

    public int totalWaves() {
        return wave.totalWaves();
    }

    /** 是否 boss 波（每 bossEveryNWaves 波）。 */
    public boolean isBossWave(int waveIndex) {
        return wave.bossEveryNWaves() > 0 && waveIndex % wave.bossEveryNWaves() == 0;
    }
}
