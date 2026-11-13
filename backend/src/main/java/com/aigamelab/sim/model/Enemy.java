package com.aigamelab.sim.model;

/**
 * 蓝图 §12.1：敌人基础属性（按波次成长，见 Wave）。
 */
public record Enemy(
        double hp,
        double damage,
        double speed,
        double defense,
        double radius) {
}
