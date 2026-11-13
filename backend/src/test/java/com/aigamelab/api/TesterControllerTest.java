package com.aigamelab.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.aigamelab.tester.TestPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T4-5 验收：Tester REST API（技术设计 §9 七端点 + 包络/错误码契约）。
 * MockMvc 覆盖正常/异常/边界分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("T4-5 Tester REST API（MockMvc）")
class TesterControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired TaskRepository taskRepo;
    @Autowired TestPlanService planService;
    @Autowired JdbcTemplate jdbc;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t45-", ".db");
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

    private long newTask() {
        Task t = taskRepo.insert(new Task(null, "t", "g",
                "{\"configVersion\":\"v001\"}", 50, false, "ACTIVE", null));
        return t.id();
    }

    private String body(String s) {
        return s;
    }

    @Test
    void createTestPlanDraft() throws Exception {
        long taskId = newTask();
        mockMvc.perform(post("/api/tasks/{id}/test-plan", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"configVersion\":\"v001\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.configVersion").value("v001"));
    }

    @Test
    void createTestPlanMissingConfigVersionFails() throws Exception {
        long taskId = newTask();
        mockMvc.perform(post("/api/tasks/{id}/test-plan", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void activateThenGetPlan() throws Exception {
        long taskId = newTask();
        String create = mockMvc.perform(post("/api/tasks/{id}/test-plan", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"configVersion\":\"v001\"}")))
                .andReturn().getResponse().getContentAsString();
        long planId = M.readTree(create).at("/data/id").asLong();

        mockMvc.perform(post("/api/test-plans/{id}/activate", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(get("/api/test-plans/{id}", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void activateUnknownPlanNotFound() throws Exception {
        mockMvc.perform(post("/api/test-plans/{id}/activate", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void updateScenariosOnDraft() throws Exception {
        long taskId = newTask();
        String create = mockMvc.perform(post("/api/tasks/{id}/test-plan", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"configVersion\":\"v001\"}")))
                .andReturn().getResponse().getContentAsString();
        long planId = M.readTree(create).at("/data/id").asLong();

        String scenarios = "[{\"id\":\"s2\",\"type\":\"BOUNDARY\",\"goal\":\"g\","
                + "\"seed\":7,\"repeat\":1,\"playerProfile\":\"SCRIPTED\","
                + "\"invariants\":[\"HP>=0\"],\"overrides\":{}}]";
        mockMvc.perform(put("/api/test-plans/{id}", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"scenarios\":" + scenarios + "}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void findingLifecycleThroughApi() throws Exception {
        long taskId = newTask();
        // 先经 Service 落一条 Finding（API 仅提供查询/状态流转，落库由 Tool 负责）
        var plan = planService.createDraft(taskId, "v001", null);
        planService.activate(plan.id());
        // 直接落库一条 Finding 用于查询/状态流转测试
        jdbc.update("INSERT INTO finding(run_id,test_plan_id,task_id,scenario_id,"
                + "config_version,severity,category,source,title,expected,actual,"
                + "repro_steps,evidence_json,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (Long) null, plan.id(), taskId, "sc1", "v001", "HIGH", "BALANCE_ANOMALY", "LLM",
                "hp 越界", "hp>0", "hp=-3", "seed=42",
                "{\"sessionId\":\"s1\"}", "OPEN");

        // 列表过滤
        mockMvc.perform(get("/api/findings").param("configVersion", "v001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].severity").value("HIGH"));

        // 详情
        long fid = jdbc.queryForObject("SELECT id FROM finding LIMIT 1", Long.class);
        mockMvc.perform(get("/api/findings/{id}", fid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("hp 越界"));

        // 状态流转
        mockMvc.perform(post("/api/findings/{id}/status", fid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"status\":\"CONFIRMED\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // 非法 status
        mockMvc.perform(post("/api/findings/{id}/status", fid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"status\":\"NONSENSE\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void getUnknownFindingNotFound() throws Exception {
        mockMvc.perform(get("/api/findings/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }
}
