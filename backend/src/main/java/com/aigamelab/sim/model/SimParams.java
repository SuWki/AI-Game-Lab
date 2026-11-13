package com.aigamelab.sim.model;

/**
 * 蓝图 §12.1：模拟参数（tick 步长、总时长、随机种子等）。
 * dt 固定 0.02s（50Hz）；totalTimeSec = 600 涵盖全部波次。
 */
public record SimParams(
        double dt,
        double totalTimeSec,
        long baseSeed) {
}
