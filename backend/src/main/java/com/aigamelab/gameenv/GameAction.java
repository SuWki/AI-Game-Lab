package com.aigamelab.gameenv;

/**
 * T1-1：一次测试动作（技术设计 §3.1）。
 *
 * actionType 为开放字符串 + 参数 JSON，不枚举封闭（需求文档接口风格）。
 * MVP 约定值：MOVE / ATTACK / CHOOSE_UPGRADE / WAIT / RESTART。
 *
 * @param actionType 动作类型（大写约定）
 * @param params     动作参数（如 {"direction":"UP"} / {"upgradeId":"dmg10"}）
 * @param tickBudget 本次动作允许推进的最大 tick 数；环境按此推进模拟时钟
 */
public record GameAction(String actionType, com.fasterxml.jackson.databind.JsonNode params,
        int tickBudget) {

    public static GameAction of(String actionType, String paramsJson, int tickBudget) {
        try {
            return new GameAction(actionType,
                    paramsJson == null ? null
                            : new com.fasterxml.jackson.databind.ObjectMapper().readTree(paramsJson),
                    tickBudget);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法动作参数 JSON: " + paramsJson, e);
        }
    }
}
