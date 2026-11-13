package com.aigamelab.sim.model;

/**
 * 蓝图 §12.1：升级项。effect 描述对某项 stat 的 ADD/MUL 修正。
 */
public record Upgrade(
        String id,
        String name,
        String buildType,
        double weight,
        Effect effect) {

    /**
     * 该升级在同一局内可被拾取的最大次数（防止 MUL 类升级无限复利导致数值爆炸）。
     * 蓝图 §12.1 未规定上限，此处作为平衡护栏（默认 8）。v001 显式写出以钉死行为。
     */
    public int maxStacks() {
        return 8; // 默认护栏；如需按升级差异化可改为 record 字段并在 JSON 中提供
    }

    /**
     * 升级效果：对 stat 执行 op（ADD 加法 / MUL 乘法）叠加 value。
     */
    public record Effect(String stat, String op, double value) {
    }
}
