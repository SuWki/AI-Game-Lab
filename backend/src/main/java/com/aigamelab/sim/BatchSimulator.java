package com.aigamelab.sim;

import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * 蓝图 §12.3 / P4-2：批量并行模拟器。
 * 串行预分裂 SplittableRandom[]（保证同 seed+count 可复现），
 * IntStream.parallel() 消费（ForkJoinPool=核数）。
 *
 * 复现保证：master.split() 是确定性分裂，同 master seed 序列得到的 rng[i] 严格一致，
 * 因此同 (config, buildType, seed, count) 必得相同聚合结果（03 验收标准）。
 */
public final class BatchSimulator {

    private final GameConfig config;
    private final String buildType;
    private final long seed;
    private final int count;
    private final Aggregator shared;

    public BatchSimulator(GameConfig config, String buildType, long seed, int count) {
        this.config = config;
        this.buildType = buildType;
        this.seed = seed;
        this.count = count;
        this.shared = new Aggregator(count);
    }

    /**
     * 串行预分裂 + 并行执行。返回聚合结果。
     * 03 验收：10000 局两次运行聚合结果一致；多核下明显快于串行。
     */
    public SimulationResult run() {
        SplittableRandom master = new SplittableRandom(seed);
        // 串行预分裂，保证 rng[i] 确定性（蓝图 §12.3）
        SplittableRandom[] rngs = new SplittableRandom[count];
        for (int i = 0; i < count; i++) {
            rngs[i] = master.split();
        }
        // 并行执行
        IntStream.range(0, count).parallel().forEach(i -> {
            GameOutcome o = new GameSimulator(config, buildType, rngs[i]).simulate();
            synchronized (shared) {
                shared.add(o);
            }
        });
        return shared.result();
    }

    /** 串行执行（性能对比用 / 调试用）。 */
    public SimulationResult runSerial() {
        SplittableRandom master = new SplittableRandom(seed);
        for (int i = 0; i < count; i++) {
            SplittableRandom rng = master.split();
            GameOutcome o = new GameSimulator(config, buildType, rng).simulate();
            shared.add(o);
        }
        return shared.result();
    }
}
