package com.aigamelab.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.AgentEventRepository;
import com.aigamelab.repository.ChangeProposalRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.AgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-3 修改类工具验收（03 文档 P3-3 / 02 文档 §11 #3）。
 *
 * modify_game_config：提交 JSON Merge Patch → 落 PENDING 提案 + 发事件 + 挂起 Run。
 *  - 通过 AgentLoop + FakeLlmGateway 驱动完整 Run→WAITING_APPROVAL 流程；
 *  - TD-P3-001：execute() 在 Run 挂起前执行——PENDING 提案、diff、事件均在同一 loop 步内完成；
 *  - change_proposal 新增 PENDING 行，diff_json 含预期变更，afterConfigJson 非空；
 *  - PROPOSAL_CREATED + APPROVAL_REQUIRED 事件落 agent_event；
 *  - game_config_version 表无新增行（config 未被落盘，仅存提案）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P3-3 modify_game_config 修改类工具")
class ModifyGameConfigToolTest {

    @Autowired AgentLoop loop;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired ChangeProposalRepository proposalRepo;
    @Autowired AgentEventRepository eventRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;
    @Autowired ToolRegistry registryBean;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p33m-", ".db");
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

    @TestConfiguration
    static class FakeConfig {
        @Bean FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @BeforeEach
    void clean() {
        llm.reset();
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM game_config_version");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        // 种子 v001
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

    private Task newTask(int maxSteps) {
        return taskRepo.insert(new Task(null, "修改配置", "平衡三 Build",
                "{}", maxSteps, false, "ACTIVE", null));
    }

    private Run newRun(Task task) {
        return runRepo.insert(new Run(null, task.id(), "PENDING", null,
                null, 0, 10, 100000, 0, null, null, null, null, null));
    }

    private int eventCount(long runId, String type) {
        String rid = String.valueOf(runId);
        return (int) eventRepo.findRecent(10000).stream()
                .filter(e -> rid.equals(e.runId()) && e.type().equals(type))
                .count();
    }

    /**
     * TD-P3-001：通过 AgentLoop 驱动——LLM 调用 modify_game_config（requiresApproval=true）→
     * execute() 先执行（创建 PENDING 提案 + diff + 事件）→ Run 挂起 WAITING_APPROVAL。
     * 验证：提案已落库、diff 含预期变更、事件落库、Run 状态正确、TOOL_RETURNED{SUSPENDED}。
     */
    @Test
    void modifyConfigCreatesProposalAndSuspendsRunViaLoop() {
        String argsJson = "{\"patch\":{\"weapons\":[{\"id\":\"nova_sniper\",\"critMultiplier\":2.5}]},"
                + "\"reason\":\"降低暴击倍率，缩小 crit build 强度差距\"}";
        llm.thenToolCalls(new ToolCall("c1", "modify_game_config", argsJson));

        Task task = newTask(10);
        Run run = newRun(task);
        loop.run(run.id());

        // TD-P3-001：Run 进入 WAITING_APPROVAL
        assertThat(runRepo.findById(run.id()).orElseThrow().status())
                .isEqualTo("WAITING_APPROVAL");
        assertThat(eventCount(run.id(), "TOOL_CALLED")).isEqualTo(1);
        boolean suspended = eventRepo.findRecent(10000).stream()
                .filter(e -> String.valueOf(run.id()).equals(e.runId())
                        && e.type().equals("TOOL_RETURNED"))
                .anyMatch(e -> e.payloadJson() != null && e.payloadJson().contains("SUSPENDED"));
        assertThat(suspended).isTrue();

        // TD-P3-001：execute() 已执行——PENDING 提案已落库
        String diffJson = jdbc.queryForObject(
                "SELECT diff_json FROM change_proposal WHERE status='PENDING' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(diffJson).contains("critMultiplier").contains("2.5");

        String afterConfig = jdbc.queryForObject(
                "SELECT after_config_json FROM change_proposal WHERE status='PENDING' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(afterConfig).isNotNull().contains("2.5");

        // TD-P3-001：PROPOSAL_CREATED + APPROVAL_REQUIRED 事件在 Run 挂起前已落库
        assertThat(eventCount(run.id(), "PROPOSAL_CREATED")).isEqualTo(1);
        assertThat(eventCount(run.id(), "APPROVAL_REQUIRED")).isEqualTo(1);

        // config_version 无新增（config 仅存提案，不写版本）
        Integer cfgRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_config_version", Integer.class);
        assertThat(cfgRows).isEqualTo(1); // 仅种子 v001
    }

    /**
     * 直接 dispatch（不经 AgentLoop）验证：execute() 真正运行 → 落 PENDING 提案、
     * diff_json 含预期变更、after_config_json 非空、PROPOSAL_CREATED/APPROVAL_REQUIRED 事件落库、
     * game_config_version 无新增（config 仅存提案，不写版本）。
     */
    @Test
    void modifyConfigViaDirectDispatchCreatesPendingProposal() {
        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode()
                .put("reason", "降低暴击倍率，缩小 crit build 强度差距");
        ObjectNode patch = m.createObjectNode();
        ArrayNode weapons = m.createArrayNode();
        weapons.add(m.createObjectNode().put("id", "nova_sniper").put("critMultiplier", 2.5));
        patch.set("weapons", weapons);
        args.set("patch", patch);
        ArrayNode ev = m.createArrayNode().add(1);
        args.set("evidenceExperimentIds", ev);

        // 满足 change_proposal.run_id 外键
        jdbc.update("INSERT INTO task(id, title, goal, constraints_json, max_steps, approval_required, status) "
                + "VALUES(1, 't', 'g', '{}', 10, 0, 'ACTIVE')");
        jdbc.update("INSERT INTO run(id, task_id, status) VALUES(7, 1, 'PENDING')");

        int before = jdbc.queryForObject("SELECT COUNT(*) FROM change_proposal", Integer.class);
        ToolResult r = registryBean.dispatch("modify_game_config", args, ToolContext.of(7L, 1L));

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM change_proposal", Integer.class))
                .isEqualTo(before + 1);

        String diffJson = jdbc.queryForObject(
                "SELECT diff_json FROM change_proposal WHERE status='PENDING' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(diffJson).contains("critMultiplier").contains("2.5");

        String afterConfig = jdbc.queryForObject(
                "SELECT after_config_json FROM change_proposal WHERE status='PENDING' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(afterConfig).isNotNull().contains("2.5");

        // 事件落库（直接 dispatch 下 run_id=7）
        assertThat(eventCount(7L, "PROPOSAL_CREATED")).isEqualTo(1);
        assertThat(eventCount(7L, "APPROVAL_REQUIRED")).isEqualTo(1);

        // config_version 无新增
        Integer cfgRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_config_version", Integer.class);
        assertThat(cfgRows).isEqualTo(1); // 仅 ConfigVersionService 构造时种子 v001，提案不写版本
    }

    /**
     * 直接调用 dispatch 验证：非法 patch（路径不存在）被 MergePatchApplier 拒绝。
     */
    @Test
    void invalidPatchPathThrowsToolException() {
        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode().put("reason", "测试非法路径");
        ObjectNode patch = m.createObjectNode().put("nonExistentField", "x");
        args.set("patch", patch);
        args.set("evidenceExperimentIds", m.createArrayNode());

        assertThatThrownBy(() -> registryBean.dispatch("modify_game_config", args, ToolContext.of(1L, 1L)))
                .isInstanceOf(Exception.class); // MergePatchApplier 抛 BusinessException(40901)
    }
}
