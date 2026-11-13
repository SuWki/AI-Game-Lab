package com.aigamelab.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.DesignDocumentRepository;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.RunControlService;
import com.aigamelab.tool.ToolRegistry;

/**
 * R0-9 验收：Research Agent 端到端工作流。
 *
 * Task（agent_type=RESEARCH）→ Run → web_search → fetch_page → save_source
 *   → extract_claims → compare_sources → generate_report → stop。
 *
 * 断言：① Run COMPLETED；② research_source 有记录；③ research_claim 有记录且 confidence>0；
 *       ④ design_document 有 [RESEARCH REPORT] 前缀报告；⑤ RESEARCH prompt 与 6-tool 白名单生效。
 *
 * WebFetchService 通过 @Primary stub bean 替换（避免 @MockBean 的 ByteBuddy/Java24 兼容问题）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("R0-9 Research Agent E2E 验收")
class ResearchAgentEndToEndTest {

    @Autowired RunControlService runControl;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired ResearchSourceRepository sourceRepo;
    @Autowired ResearchClaimRepository claimRepo;
    @Autowired DesignDocumentRepository docRepo;
    @Autowired ToolRegistry registryBean;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;

    static final Set<String> EXPECTED_RESEARCH_TOOLS = Set.of(
            "web_search", "fetch_page", "save_source",
            "extract_claims", "compare_sources", "generate_report");

    /** stub WebFetchService 内容（@Primary 覆盖真实 Bean） */
    static final String STUB_PAGE_CONTENT =
            "AI 在游戏设计中的应用越来越广泛。"
            + "根据官方统计，超过60%的游戏工作室已采用AI工具辅助设计。"
            + "然而，部分开发者对此持谨慎态度，认为AI尚未成熟。";

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-r09-", ".db");
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
        @Bean
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }

        @Bean
        @Primary
        WebFetchService stubWebFetchService() {
            return new WebFetchService() {
                @Override
                public String fetchText(String url) {
                    return STUB_PAGE_CONTENT;
                }

                @Override
                public String fetchHtml(String url) {
                    return "<html><body>" + STUB_PAGE_CONTENT + "</body></html>";
                }
            };
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM research_claim");
        jdbc.update("DELETE FROM research_source");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        llm.reset();
    }

    private Task newResearchTask() {
        return taskRepo.insert(new Task(null, "Research 任务",
                "调查 AI 在游戏设计中的应用现状",
                "{\"agent_type\":\"RESEARCH\"}", 50, false, "ACTIVE", null));
    }

    /** 轮询等待 Run 进入终态 */
    private void awaitTerminal(long runId) {
        for (int i = 0; i < 200; i++) {
            Run r = runRepo.findById(runId).orElseThrow();
            if (RunStatus.COMPLETED.name().equals(r.status())
                    || RunStatus.FAILED.name().equals(r.status())) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Set<String> toolNamesOf(int requestIndex) {
        var req = llm.recordedRequests().get(requestIndex);
        var specs = req.tools();
        return specs == null ? Set.of()
                : Set.copyOf(specs.stream().map(s -> s.name()).toList());
    }

    private static ToolCall tc(String id, String name, String argsJson) {
        return new ToolCall(id, name, argsJson);
    }

    // ==================== E2E：完整 Research 工作流 ====================

    @Test
    @DisplayName("Research Agent 端到端：web_search → fetch_page → save_source → extract → compare → generate_report → stop")
    void fullResearchWorkflow() {
        Task task = newResearchTask();

        // 剧本：6 次工具调用 + 1 次 stop
        llm.thenToolCalls(
                tc("call_1", "web_search", "{\"query\":\"AI游戏设计应用现状\"}"),
                tc("call_2", "fetch_page", "{\"url\":\"https://example.com/ai-game-design\"}"),
                tc("call_3", "save_source",
                        "{\"url\":\"https://example.com/ai-game-design\","
                        + "\"sourceType\":\"Official\","
                        + "\"title\":\"AI在游戏设计中的应用\","
                        + "\"contentSummary\":\"AI在游戏设计中的应用越来越广泛\","
                        + "\"reliability\":0.8}"),
                tc("call_4", "extract_claims", "{\"sourceId\":1}"),
                tc("call_5", "compare_sources",
                        "{\"claimId\":1,\"supportingSourceIds\":[1],\"contradictingSourceIds\":[]}"),
                tc("call_6", "generate_report",
                        "{\"question\":\"AI在游戏设计中的应用现状\","
                        + "\"summary\":\"AI在游戏设计中应用广泛，超过60%的工作室已采用AI工具。\"}")
        );
        llm.thenStop("研究完成");

        Run run = runControl.start(task.id());
        awaitTerminal(run.id());

        // ---- 断言 1：Run 状态 COMPLETED ----
        Run finished = runRepo.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED.name());

        // ---- 断言 2：RESEARCH prompt 被使用 ----
        String prompt = llm.recordedRequests().get(0).messages().get(0).content();
        assertThat(prompt).contains("AI Game Researcher");
        assertThat(prompt).contains("QUESTION");

        // ---- 断言 3：工具白名单为 6 个 Research 工具 ----
        Set<String> names = toolNamesOf(0);
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_RESEARCH_TOOLS);
        // Designer/Tester 工具均不可见
        assertThat(names).doesNotContain("simulate_game", "game_start", "test_plan_get");

        // ---- 断言 4：research_source 有记录 ----
        List<ResearchSource> sources = sourceRepo.findByRunId(run.id());
        assertThat(sources).hasSizeGreaterThanOrEqualTo(1);
        assertThat(sources.get(0).sourceType()).isEqualTo("Official");
        assertThat(sources.get(0).reliability()).isEqualTo(0.8);

        // ---- 断言 5：research_claim 有记录且 confidence > 0 ----
        List<ResearchClaim> claims = claimRepo.findByRunId(run.id());
        assertThat(claims).hasSizeGreaterThanOrEqualTo(1);
        assertThat(claims.get(0).confidence()).isGreaterThan(0.0);

        // ---- 断言 6：design_document 有 [RESEARCH REPORT] 前缀报告 ----
        Integer reportCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM design_document WHERE title LIKE '%[RESEARCH REPORT]%'",
                Integer.class);
        assertThat(reportCount).isGreaterThanOrEqualTo(1);

        // ---- 断言 7：报告内容包含完整结构 ----
        String reportMd = jdbc.queryForObject(
                "SELECT content_md FROM design_document WHERE title LIKE '%[RESEARCH REPORT]%' LIMIT 1",
                String.class);
        assertThat(reportMd).contains("研究问题");
        assertThat(reportMd).contains("结论");
        assertThat(reportMd).contains("支持证据");
        assertThat(reportMd).contains("来源");
    }

    // ==================== 隔离：Designer/Tester Run 不受影响 ====================

    @Test
    @DisplayName("同一 Task 不指定 agent_type 时仍走 Designer Run（向后兼容）")
    void defaultTaskStillStartsDesignerRun() {
        Task task = taskRepo.insert(new Task(null, "普通任务", "设计游戏",
                "{}", 30, false, "ACTIVE", null));
        llm.thenStop("设计完成");

        Run run = runControl.start(task.id());
        awaitTerminal(run.id());

        Run finished = runRepo.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED.name());

        // Designer prompt，非 Research
        String prompt = llm.recordedRequests().get(0).messages().get(0).content();
        assertThat(prompt).isEqualTo("You are AGL game designer agent.");
        assertThat(prompt).doesNotContain("AI Game Researcher");
    }
}
