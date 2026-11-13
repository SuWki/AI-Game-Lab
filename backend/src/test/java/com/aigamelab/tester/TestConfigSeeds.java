package com.aigamelab.tester;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PF-003 测试辅助：真实播种一个 v002 配置版本。
 *
 * <p>背景：回归 diff（NEW/FIXED/PERSISTED/CHANGED）语义要求两个真实存在的版本。
 * 既有测试直接 {@code findingService.report(...,"v002")} 但从未创建 v002 —— 在
 * PF-003 的 configVersion 存在性校验下会被拒绝。本工具复制 v001 快照为 v002
 * （内容无需区分，仅需存在），使回归测试在新契约下语义不变。幂等：已存在则跳过。
 */
final class TestConfigSeeds {

    private TestConfigSeeds() {
    }

    /** 幂等播种 v002（复制 v001 的 config_json）。若 v001 也不存在则抛 IllegalStateException。 */
    static void seedV002FromV001(JdbcTemplate jdbc) {
        Integer has = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_config_version WHERE version='v002'", Integer.class);
        if (has != null && has > 0) {
            return;
        }
        String v001Json = jdbc.queryForObject(
                "SELECT config_json FROM game_config_version WHERE version='v001'", String.class);
        jdbc.update("INSERT INTO game_config_version"
                + "(version, config_json, parent_version, change_proposal_id, comment)"
                + " VALUES('v002', ?, 'v001', NULL, 'PF-003 regression seed')", v001Json);
    }
}