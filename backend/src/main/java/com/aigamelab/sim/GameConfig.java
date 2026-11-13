package com.aigamelab.sim;

import java.util.List;

import com.aigamelab.sim.model.Difficulty;
import com.aigamelab.sim.model.Enemy;
import com.aigamelab.sim.model.Player;
import com.aigamelab.sim.model.SimParams;
import com.aigamelab.sim.model.Targets;
import com.aigamelab.sim.model.Wave;
import com.aigamelab.sim.model.Weapon;
import com.aigamelab.sim.model.Upgrade;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 蓝图 §12.1：游戏配置根模型（PROJECT ORBITAL 数值模型）。
 * config JSON ↔ Java 模型双向可用（P4-0 验收：v001.json 反序列化 + targets 三项齐全）。
 * 纯 POJO + Jackson，无任何 Spring/DB 依赖，可独立运行与测试（03 文档专项检查结论）。
 */
public record GameConfig(
        String version,
        Player player,
        List<Weapon> weapons,
        List<Upgrade> upgrades,
        Enemy enemy,
        Wave wave,
        Difficulty difficulty,
        Targets targets,
        SimParams sim) {

    @JsonCreator
    public GameConfig(
            @JsonProperty("version") String version,
            @JsonProperty("player") Player player,
            @JsonProperty("weapons") List<Weapon> weapons,
            @JsonProperty("upgrades") List<Upgrade> upgrades,
            @JsonProperty("enemy") Enemy enemy,
            @JsonProperty("wave") Wave wave,
            @JsonProperty("difficulty") Difficulty difficulty,
            @JsonProperty("targets") Targets targets,
            @JsonProperty("sim") SimParams sim) {
        this.version = version;
        this.player = player;
        this.weapons = weapons;
        this.upgrades = upgrades;
        this.enemy = enemy;
        this.wave = wave;
        this.difficulty = difficulty;
        this.targets = targets;
        this.sim = sim;
    }

    /** 按 buildType 过滤出该局使用的武器（蓝图 §12.1：同局玩家固定 1 个 build）。 */
    public List<Weapon> weaponsByBuild(String buildType) {
        return weapons.stream()
                .filter(w -> buildType.equals(w.buildType()))
                .toList();
    }

    /** 按 buildType 过滤升级池（蓝图 §12.1：升级池按 build 过滤）。 */
    public List<Upgrade> upgradesByBuild(String buildType) {
        return upgrades.stream()
                .filter(u -> buildType.equals(u.buildType()))
                .toList();
    }
}
