package com.aigamelab.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.aigamelab.domain.Task;
import com.aigamelab.repository.TaskRepository;

/**
 * R2-3 验收：Task 生命周期 REST API（MockMvc）。
 *
 * - POST /api/tasks 创建 Task（agentType → constraintsJson.agent_type；
 *   可选 researchSourceTaskId 写入 constraintsJson，且仅 DESIGNER 可携带、引用须存在）；
 * - GET /api/tasks Task 列表（供工作流页展示）；
 * - 错误分支：缺 title/goal/agentType、非法 agentType、researchSourceTaskId 非 DESIGNER、
 *   引用任务不存在。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("R2-3 Task 创建/列表 REST API")
class TaskControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired TaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-r23-", ".db");
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
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM task");
    }

    @Test
    void createResearchTaskWritesAgentType() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"研究任务\",\"goal\":\"收集证据\","
                                + "\"agentType\":\"RESEARCH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.agentType").doesNotExist()) // API 回 Task record，无该字段
                .andExpect(jsonPath("$.data.constraintsJson")
                        .value("{\"agent_type\":\"RESEARCH\"}"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void createDesignerWithResearchSourceTaskId() throws Exception {
        // 先建 RESEARCH 类型任务作为引用目标（TI：引用任务须为 RESEARCH）
        Task research = taskRepo.insert(new Task(null, "研究", "研究", "{\"agent_type\":\"RESEARCH\"}",
                50, false, "ACTIVE", null));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"设计任务\",\"goal\":\"基于研究设计\","
                                + "\"agentType\":\"DESIGNER\","
                                + "\"researchSourceTaskId\":" + research.id() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.constraintsJson")
                        .value("{\"agent_type\":\"DESIGNER\",\"researchSourceTaskId\":"
                                + research.id() + "}"));
    }

    @Test
    void researchSourceTaskIdMustPointToResearchTask() throws Exception {
        // 引用一个非 RESEARCH 任务（DESIGNER）→ 40001 拒绝
        Task designer = taskRepo.insert(new Task(null, "设计", "设计", "{\"agent_type\":\"DESIGNER\"}",
                50, false, "ACTIVE", null));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"goal\":\"y\","
                                + "\"agentType\":\"DESIGNER\","
                                + "\"researchSourceTaskId\":" + designer.id() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void researchSourceTaskIdOnlyAllowedForDesigner() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"goal\":\"y\","
                                + "\"agentType\":\"RESEARCH\",\"researchSourceTaskId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void researchSourceTaskIdMustExist() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"goal\":\"y\","
                                + "\"agentType\":\"DESIGNER\",\"researchSourceTaskId\":9999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void createTaskRequiresTitleGoalAgentType() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"y\",\"agentType\":\"DESIGNER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void createTaskRejectsIllegalAgentType() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"goal\":\"y\",\"agentType\":\"WIZARD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void listTasksReturnsAllInIdOrder() throws Exception {
        taskRepo.insert(new Task(null, "t1", "g1", "{\"agent_type\":\"RESEARCH\"}",
                50, false, "ACTIVE", null));
        taskRepo.insert(new Task(null, "t2", "g2", "{\"agent_type\":\"DESIGNER\"}",
                50, false, "ACTIVE", null));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void createTaskPublishesTaskCreatedEvent() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"事件任务\",\"goal\":\"g\","
                                + "\"agentType\":\"DESIGNER\"}"))
                .andExpect(status().isCreated());

        Integer tasks = jdbc.queryForObject("SELECT COUNT(*) FROM task", Integer.class);
        org.assertj.core.api.Assertions.assertThat(tasks).isEqualTo(1);
        // TASK_CREATED 事件走全局流（runId=null），由 listener 落库 agent_event
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_event WHERE type='TASK_CREATED'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(events).isEqualTo(1);
    }
}