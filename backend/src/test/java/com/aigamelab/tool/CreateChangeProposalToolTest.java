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

import com.aigamelab.repository.ChangeProposalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-3 论证型工具验收（03 文档 P3-3 / 02 文档 §11 #8）。
 *
 * create_change_proposal：仅落"论证型"提案（afterConfigJson=null），不碰配置、不挂起 Run。
 *  - dispatch 返回 COMPLETED（requiresApproval=false）；
 *  - change_proposal 表新增 1 行 PENDING，afterConfigJson 为 null、diffJson 承载候选论证；
 *  - 不创建 ConfigVersion（配置未被改写）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P3-3 create_change_proposal 论证型提案")
class CreateChangeProposalToolTest {

    @Autowired ToolRegistry registryBean;
    @Autowired ChangeProposalRepository proposalRepo;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p33c-", ".db");
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
    void clean() {
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM game_config_version");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        // 论证型提案的 run_id 外键需存在：插一条 run(id=7) + task 满足 FK
        jdbc.update("INSERT INTO task(id, title, goal, constraints_json, max_steps, approval_required, status) "
                + "VALUES(1, 't', 'g', '{}', 10, 0, 'ACTIVE')");
        jdbc.update("INSERT INTO run(id, task_id, status) VALUES(7, 1, 'PENDING')");
    }

    @Test
    void createsRationaleOnlyProposalWithoutConfigTouch() {
        ObjectNode candidates = mapper.createObjectNode();
        ArrayNode list = mapper.createArrayNode();
        list.add(mapper.createObjectNode().put("name", "方案A").put("pro", "x").put("con", "y"));
        list.add(mapper.createObjectNode().put("name", "方案B").put("pro", "u").put("con", "v"));
        candidates.set("candidates", list);

        ObjectNode args = mapper.createObjectNode()
                .put("title", "削弱 crit 偏强")
                .put("reason", "实验 E1/E2 显示 crit 胜率 100%")
                .put("baseVersion", "v001");
        args.set("candidates", list);
        ArrayNode ev = mapper.createArrayNode().add(1).add(2);
        args.set("evidenceExperimentIds", ev);

        int before = proposalCount();
        ToolResult r = registryBean.dispatch("create_change_proposal", args, ToolContext.of(7L, 1L));

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(proposalCount()).isEqualTo(before + 1);

        long id = jdbc.queryForObject(
                "SELECT id FROM change_proposal ORDER BY id DESC LIMIT 1", Long.class);
        var saved = proposalRepo.findById(id).orElseThrow();
        assertThat(saved.status()).isEqualTo("PENDING");
        assertThat(saved.afterConfigJson()).isEqualTo("{}"); // 不碰配置（NOT NULL 占位）
        assertThat(saved.title()).isEqualTo("削弱 crit 偏强");
        assertThat(saved.diffJson()).contains("candidates="); // 论证承载于备注
        assertThat(saved.evidenceExperimentIds()).isEqualTo("[1,2]");

        // 配置版本未被创建
        Integer cfgRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_config_version", Integer.class);
        assertThat(cfgRows).isEqualTo(0);
    }

    @Test
    void missingRequiredTitleRejectedBySchema() {
        // 缺 required 字段 title → Schema 校验拦截，落 FAILED（非 COMPLETED）
        ObjectNode args = mapper.createObjectNode().put("reason", "x");
        // Schema 校验失败：dispatch 抛 IllegalArgumentException（缺 required 字段），
        // ToolExecutor 捕获后该 tool_call 落 FAILED。断言：不落任何 PENDING 提案。
        assertThatThrownBy(() ->
                registryBean.dispatch("create_change_proposal", args, ToolContext.of(7L, 1L)))
                .isInstanceOf(Exception.class);
        assertThat(proposalCount()).isEqualTo(0);
    }

    private int proposalCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM change_proposal", Integer.class);
    }
}
