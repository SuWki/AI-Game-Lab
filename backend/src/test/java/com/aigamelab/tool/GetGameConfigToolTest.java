package com.aigamelab.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.config.ConfigVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-2 验收测试（03 文档 P3-2 / 02 文档 §11 #1,#2）。
 *
 * 验证 get_game_design / get_game_config 两个只读工具的真实行为：
 *  - get_game_design：从 classpath 读取静态设计文档，payload 含非空 content；
 *  - get_game_config：缺省返回"当前生效版本"（=latest）的 config_json 并带 version 字段；
 *    指定 version 时返回该版本；缺版本号抛 ToolException。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P3-2 get_game_design / get_game_config 只读工具")
class GetGameConfigToolTest {

    @Autowired ConfigVersionService configService;
    @Autowired ToolRegistry registryBean;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p32-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Path DB = tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"
                + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM game_config_version");
        String seed = readSeed();
        jdbc.update("INSERT INTO game_config_version(version, config_json, parent_version, change_proposal_id, comment) "
                + "VALUES(?,?,?,?,?)", "v001", seed, null, null, "initial seed");
    }

    private String readSeed() {
        try (var in = new org.springframework.core.io.ClassPathResource("config/v001.json").getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void getGameDesignReturnsNonEmptyContent() {
        ToolResult r = registryBean.dispatch("get_game_design", mapper.createObjectNode(),
                ToolContext.of(1L, 1L));
        assertThat(r.status()).isEqualTo("COMPLETED");
        JsonNode payload = parse(r.payloadJson());
        assertThat(payload.get("content").asText()).contains("PROJECT ORBITAL");
    }

    @Test
    void getGameConfigReturnsCurrentVersionWithVersionField() {
        ToolResult r = registryBean.dispatch("get_game_config", mapper.createObjectNode(),
                ToolContext.of(1L, 1L));
        assertThat(r.status()).isEqualTo("COMPLETED");
        JsonNode payload = parse(r.payloadJson());
        assertThat(payload.get("version").asText()).isEqualTo("v001");
        assertThat(payload.get("config").asText()).isNotEmpty();
        // 返回的 config 与 ConfigVersionService.getCurrent() 一致
        assertThat(payload.get("config").asText())
                .isEqualTo(configService.getCurrent().configJson());
    }

    @Test
    void getGameConfigByExplicitVersion() {
        ObjectNode args = mapper.createObjectNode().put("version", "v001");
        ToolResult r = registryBean.dispatch("get_game_config", args, ToolContext.of(1L, 1L));
        assertThat(r.status()).isEqualTo("COMPLETED");
        JsonNode payload = parse(r.payloadJson());
        assertThat(payload.get("version").asText()).isEqualTo("v001");
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readValue(json, JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void getGameConfigUnknownVersionThrows() {
        ObjectNode args = mapper.createObjectNode().put("version", "v999");
        assertThatThrownBy(() -> registryBean.dispatch("get_game_config", args, ToolContext.of(1L, 1L)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("配置版本不存在");
    }
}
