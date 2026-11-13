package com.aigamelab.sim;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 蓝图 §12.4：指标聚合器。从 GameOutcome 序列聚合出 SimulationResult。
 * 设计为线程安全：可并行累加（每线程局部累加后 merge），也可串行直入。
 *
 * 指标：winRate/deathRate/avg/median/p90 存活时间（直方图反推）/
 * avgDps/avgTtk/damageHistogram[24]/buildDistribution(含95%CI)/upgradePickRate(+InWins)。
 */
public final class Aggregator {

    private static final int TIME_BUCKETS = 601;   // 0..600s
    private static final int DMG_BUCKETS = 24;

    private final int totalGames;
    private final AtomicInteger winCount = new AtomicInteger();
    private final AtomicInteger deathCount = new AtomicInteger();
    private final AtomicLong survivalTimeSum = new AtomicLong(0);
    private final AtomicLong dpsSum = new AtomicLong(0);
    private final AtomicLong ttkSum = new AtomicLong(0);
    private final AtomicLong dpsSamples = new AtomicLong(0);
    private final AtomicLong ttkSamples = new AtomicLong(0);

    private final int[] gameTimeHistogram = new int[TIME_BUCKETS];
    private final int[] damageHistogram = new int[DMG_BUCKETS];
    private final Map<String, AtomicInteger> buildGames = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> buildWins = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> upgradePicks = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> upgradePicksInWins = new LinkedHashMap<>();

    public Aggregator(int totalGames) {
        this.totalGames = totalGames;
    }

    /** 累加单局结果（线程安全）。 */
    public void add(GameOutcome o) {
        if (o.win()) {
            winCount.incrementAndGet();
        } else {
            deathCount.incrementAndGet();
        }
        synchronized (gameTimeHistogram) {
            gameTimeHistogram[Math.min(TIME_BUCKETS - 1, (int) o.survivalTimeSec())]++;
        }
        survivalTimeSum.addAndGet((long) (o.survivalTimeSec() * 1000.0));

        double dps = o.survivalTimeSec() > 0 ? o.totalDamage() / o.survivalTimeSec() : 0.0;
        dpsSum.addAndGet((long) (dps * 1000.0));
        dpsSamples.incrementAndGet();
        if (o.timeToKillSec() >= 0) {
            ttkSum.addAndGet((long) (o.timeToKillSec() * 1000.0));
            ttkSamples.incrementAndGet();
        }
        // 伤害直方图（按 totalDamage 分桶，每桶 500 伤害，封顶 24 桶）
        int dmgBucket = Math.min(DMG_BUCKETS - 1, (int) (o.totalDamage() / 500.0));
        synchronized (damageHistogram) {
            damageHistogram[dmgBucket]++;
        }
        // 按 build 分布
        buildGames.computeIfAbsent(o.buildType(), k -> new AtomicInteger()).incrementAndGet();
        if (o.win()) {
            buildWins.computeIfAbsent(o.buildType(), k -> new AtomicInteger()).incrementAndGet();
        }
        for (String up : o.pickedUpgrades()) {
            upgradePicks.computeIfAbsent(up, k -> new AtomicInteger()).incrementAndGet();
            if (o.win()) {
                upgradePicksInWins.computeIfAbsent(up, k -> new AtomicInteger()).incrementAndGet();
            }
        }
    }

    /** 合并另一 Aggregator 的局部累加（并行分治用）。 */
    public void merge(Aggregator other) {
        winCount.addAndGet(other.winCount.get());
        deathCount.addAndGet(other.deathCount.get());
        survivalTimeSum.addAndGet(other.survivalTimeSum.get());
        dpsSum.addAndGet(other.dpsSum.get());
        dpsSamples.addAndGet(other.dpsSamples.get());
        ttkSum.addAndGet(other.ttkSum.get());
        ttkSamples.addAndGet(other.ttkSamples.get());
        synchronized (gameTimeHistogram) {
            for (int i = 0; i < TIME_BUCKETS; i++) {
                gameTimeHistogram[i] += other.gameTimeHistogram[i];
            }
        }
        synchronized (damageHistogram) {
            for (int i = 0; i < DMG_BUCKETS; i++) {
                damageHistogram[i] += other.damageHistogram[i];
            }
        }
        mergeMap(buildGames, other.buildGames);
        mergeMap(buildWins, other.buildWins);
        mergeMap(upgradePicks, other.upgradePicks);
        mergeMap(upgradePicksInWins, other.upgradePicksInWins);
    }

    private static void mergeMap(Map<String, AtomicInteger> a, Map<String, AtomicInteger> b) {
        for (var e : b.entrySet()) {
            a.computeIfAbsent(e.getKey(), k -> new AtomicInteger()).addAndGet(e.getValue().get());
        }
    }

    private double avg(long sum, long samples) {
        return samples == 0 ? 0.0 : sum / (double) samples / 1000.0;
    }

    /** 生成最终结果（直方图反推 median/p90）。 */
    public SimulationResult result() {
        int n = Math.max(1, totalGames);
        double winRate = winCount.get() / (double) n;
        double deathRate = deathCount.get() / (double) n;

        double median = percentileFromHistogram(gameTimeHistogram, 0.5);
        double p90 = percentileFromHistogram(gameTimeHistogram, 0.9);

        Map<String, SimulationResult.BuildDistribution> builds = new LinkedHashMap<>();
        for (var e : buildGames.entrySet()) {
            String b = e.getKey();
            int g = e.getValue().get();
            int w = buildWins.getOrDefault(b, new AtomicInteger()).get();
            double wr = g == 0 ? 0.0 : w / (double) g;
            double[] ci = wilsonCI(w, g, 0.95);
            builds.put(b, new SimulationResult.BuildDistribution(g, w, wr, ci[0], ci[1]));
        }

        Map<String, Double> pickRate = new LinkedHashMap<>();
        for (var e : upgradePicks.entrySet()) {
            pickRate.put(e.getKey(), e.getValue().get() / (double) n);
        }
        Map<String, Double> pickRateWins = new LinkedHashMap<>();
        for (var e : upgradePicksInWins.entrySet()) {
            pickRateWins.put(e.getKey(),
                    winCount.get() == 0 ? 0.0 : e.getValue().get() / (double) winCount.get());
        }

        return new SimulationResult(n, winCount.get(), deathCount.get(),
                winRate, deathRate, avg(survivalTimeSum.get(), n),
                median, p90, avg(dpsSum.get(), dpsSamples.get()),
                avg(ttkSum.get(), ttkSamples.get()),
                gameTimeHistogram, damageHistogram, builds, pickRate, pickRateWins);
    }

    /**
     * 从累计直方图反推分位数（线性插值）。
     * hist 为存活时间分布（桶宽 1s，[0,600] 共 601 桶）。
     */
    private static double percentileFromHistogram(int[] hist, double p) {
        long total = 0;
        for (int v : hist) {
            total += v;
        }
        if (total == 0) {
            return 0.0;
        }
        long target = (long) Math.ceil(p * total);
        long cum = 0;
        for (int i = 0; i < hist.length; i++) {
            cum += hist[i];
            if (cum >= target) {
                return i; // 桶索引即秒数
            }
        }
        return hist.length - 1;
    }

    /** Wilson 区间（95% 置信区间），返回 [low, high]。 */
    private static double[] wilsonCI(int wins, int n, double confidence) {
        if (n == 0) {
            return new double[] {0.0, 0.0};
        }
        double z = 1.96; // 95% → 1.96
        double phat = wins / (double) n;
        double denom = 1 + z * z / n;
        double center = (phat + z * z / (2.0 * n)) / denom;
        double margin = z * Math.sqrt(phat * (1 - phat) / n + z * z / (4.0 * n * n)) / denom;
        return new double[] {Math.max(0.0, center - margin), Math.min(1.0, center + margin)};
    }
}
