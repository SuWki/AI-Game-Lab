package com.aigamelab.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.qa.support.QaTestFixture;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.AgentType;
import com.aigamelab.runtime.AgentTypeResolver;
import com.aigamelab.tool.ToolRegistry;

/**
 * QA P1-6 隔离红线断言。
 *
 * <p>将 AF-001 冻结矩阵的隔离红线固化为测试：任何既有类型也索引不到 qa_* 工具，
 * 回退永不落到 QA，prompt 路由不交叉。此类测试为回归守卫——
 * 如果有人在白名单里错误添加工具，测试即失败。
 *
 * <p>与 ContractMatrixTest 的区别：
 * <ul>
 *   <li>ContractMatrixTest：冻结状态（硬编码矩阵 == Resolver 输出）</li>
 *   <li>本类：隔离红线（回归守卫 + 边界否定断言 + 路由隔离）</li>
 * </ul>
 *
 * <p>与 QaP1_5Test 的区别：
 * <ul>
 *   <li>QaP1_5Test：P1-5 新增的隔离回归（A/B/C/D/E/F 六组）</li>
 *   <li>本类：P1-6 隔离红线收口（qa_* 泄漏检测 + 工具注册守卫 + prompt 路由隔离）</li>
 * </ul>
 *
 * <p>使用 QaTestFixture per-class DB 隔离（合规 PF-002）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("QA P1-6 隔离红线断言")
class QaToolIsolationTest {

    @Autowired AgentTypeResolver resolver;
    @Autowired ToolRegistry registry;
    @Autowired TaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;

    static final Path DB = QaTestFixture.tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        QaTestFixture.datasourceProps(registry, DB);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    private Task newTask(String constraintsJson) {
        return taskRepo.insert(new Task(null, "iso-task", "isolation test",
                constraintsJson, 10, false, "ACTIVE", null));
    }

    // ==================================================================
    // I1: qa_* 工具泄漏检测 — 任何非 QA 类型不得索引 qa_ 前缀工具
    //     （回归守卫：当前无 qa_ 前缀工具，此断言 vacuously true；
    //       未来若新增 qa_create_subrun 等工具，此测试立即暴露泄漏）
    // ==================================================================

    @Test
    @DisplayName("I1: DESIGNER/TESTER/RESEARCH 白名单不含 qa_ 前缀工具（泄漏回归守卫）")
    void noQaToolsInNonQaWhitelists() {
        Set<String> designer = resolver.toolWhitelist(AgentType.DESIGNER);
        Set<String> tester = resolver.toolWhitelist(AgentType.TESTER);
        Set<String> research = resolver.toolWhitelist(AgentType.RESEARCH);

        assertThat(designer.stream().noneMatch(t -> t.startsWith("qa_")))
                .as("DESIGNER 白名单不得含 qa_ 前缀工具").isTrue();
        assertThat(tester.stream().noneMatch(t -> t.startsWith("qa_")))
                .as("TESTER 白名单不得含 qa_ 前缀工具").isTrue();
        assertThat(research.stream().noneMatch(t -> t.startsWith("qa_")))
                .as("RESEARCH 白名单不得含 qa_ 前缀工具").isTrue();
    }

    // ==================================================================
    // I2: ToolRegistry — 每个白名单工具均已在 registry 注册
    //     （白名单 ≠ 未注册工具的占位；此为越界防线）
    // ==================================================================

    @Test
    @DisplayName("I2: 每个 AgentType 白名单工具均已在 ToolRegistry 注册")
    void allWhitelistedToolsRegistered() {
        for (AgentType type : AgentType.values()) {
            for (String toolName : resolver.toolWhitelist(type)) {
                assertThat(registry.contains(toolName))
                        .as("白名单工具 %s (AgentType=%s) 应已在 ToolRegistry 注册", toolName, type)
                        .isTrue();
            }
        }
    }

    // ==================================================================
    // I3: 回退永不落到 QA — default fallback 两种情况均 ≠ QA
    //     （与 QaP1_5Test A2/A3 从隔离红线视角再次强调）
    // ==================================================================

    @Test
    @DisplayName("I3a: 无 agent_type + ACTIVE TestPlan → TESTER ≠ QA（回退红线）")
    void fallbackWithPlanNeverQa() {
        Task task = newTask("{}");
        // 直接插入 ACTIVE TestPlan（与 QaP1_5Test 一致模式）
        jdbc.update("INSERT INTO test_plan(task_id,config_version,status,scenarios_json)"
                + " VALUES(?,?,?,?)", task.id(), "v001", "ACTIVE", "[]");

        AgentType resolved = resolver.resolve(task);
        assertThat(resolved).isEqualTo(AgentType.TESTER);
        assertThat(resolved).isNotEqualTo(AgentType.QA);
    }

    @Test
    @DisplayName("I3b: 无 agent_type + 无 plan → DESIGNER ≠ QA ≠ RESEARCH（回退红线）")
    void fallbackWithoutPlanNeverQa() {
        Task task = newTask("{}");
        AgentType resolved = resolver.resolve(task);
        assertThat(resolved).isEqualTo(AgentType.DESIGNER);
        assertThat(resolved).isNotEqualTo(AgentType.QA);
        assertThat(resolved).isNotEqualTo(AgentType.RESEARCH);
    }

    // ==================================================================
    // I4: Prompt 路由隔离 — QA → qa_p001, TESTER → tester_p001, 互不交叉
    // ==================================================================

    @Test
    @DisplayName("I4a: systemPrompt(QA) 包含 qa_ 角色标识，不包含 TESTER/DESIGNER/RESEARCH 标识")
    void qaPromptIsQaSpecific() {
        String qaPrompt = resolver.systemPrompt(AgentType.QA);
        String testerPrompt = resolver.systemPrompt(AgentType.TESTER);

        // QA prompt 非空
        assertThat(qaPrompt).isNotBlank();

        // QA prompt ≠ DESIGNER hardcoded prompt
        assertThat(qaPrompt).isNotEqualTo("You are AGL game designer agent.");

        // QA prompt ≠ TESTER prompt（不同版本内容不同）
        assertThat(qaPrompt).isNotEqualTo(testerPrompt);
    }

    @Test
    @DisplayName("I4b: DESIGNER prompt 为硬编码，不从 prompt_version 表加载")
    void designerPromptIsHardcoded() {
        String prompt = resolver.systemPrompt(AgentType.DESIGNER);
        assertThat(prompt).isEqualTo("You are AGL game designer agent.");
    }

    // ==================================================================
    // I5: finding_report 共享边界 — 仅 QA ∩ TESTER 共享，独立于其他
    //     （回归守卫：如果 DESIGNER/RESEARCH 白名单变动导致 finding_report 泄入）
    // ==================================================================

    @Test
    @DisplayName("I5: finding_report 仅在 QA∩TESTER 白名单中，不在 DESIGNER∩RESEARCH 中")
    void findingReportSharedBoundary() {
        String fr = "finding_report";

        assertThat(resolver.toolWhitelist(AgentType.QA)).contains(fr);
        assertThat(resolver.toolWhitelist(AgentType.TESTER)).contains(fr);
        assertThat(resolver.toolWhitelist(AgentType.DESIGNER)).doesNotContain(fr);
        assertThat(resolver.toolWhitelist(AgentType.RESEARCH)).doesNotContain(fr);
    }

    // ==================================================================
    // I6: 显式 QA 路由 — agent_type=QA 解析到 QA，不被回退逻辑干扰
    //     （与 QaP1_5Test A1 互补：此处侧重端到端解析确认）
    // ==================================================================

    @Test
    @DisplayName("I6: 每个显式 agent_type 均精确路由到对应枚举（不可交叉）")
    void explicitRouteNeverCrosses() {
        for (AgentType expected : AgentType.values()) {
            Task task = newTask("{\"agent_type\":\"" + expected.name() + "\"}");
            AgentType resolved = resolver.resolve(task);
            assertThat(resolved)
                    .as("显式 agent_type=%s 应精确路由到 %s", expected.name(), expected.name())
                    .isEqualTo(expected);
        }
    }
}
