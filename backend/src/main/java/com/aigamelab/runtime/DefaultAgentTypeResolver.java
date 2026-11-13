package com.aigamelab.runtime;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aigamelab.domain.Task;
import com.aigamelab.llm.PromptService;
import com.aigamelab.tester.TestPlanService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AgentTypeResolver 默认实现（R0-1 + R0-8 + QA P1-1 + R5 Phase 2）。
 *
 * <h3>解析规则（保持既有不变）</h3>
 * <ol>
 *   <li>优先检查 task.constraintsJson 中的 "agent_type" 字段</li>
 *   <li>允许值：DESIGNER, TESTER, RESEARCH, QA, QA_LEAD, QA_TESTER, QA_ANALYST, QA_RESEARCHER</li>
 *   <li>非法值时抛出 IllegalArgumentException</li>
 *   <li>缺失 agent_type 字段时，回退到二值逻辑：
 *       ACTIVE TestPlan → TESTER，否则 → DESIGNER
 *       （QA* 任务必须显式指定 agent_type）</li>
 * </ol>
 *
 * <h3>Prompt/Tool 选择（R5 Phase 2）</h3>
 * <p>DESIGNER / TESTER / RESEARCH / QA — 行为不变。</p>
 * <p>QA_LEAD / QA_TESTER / QA_ANALYST / QA_RESEARCHER — 8 Role 4 种 AgentType，
 *    每个 Role 的 Prompt 版本由 constraintsJson.prompt_version 决定
 *    （如 qa_lead_p001 / qa_player_novice_p001 等）。由于 AgentLoop（冻结）的
 *    {@code systemPrompt(AgentType)} 不传递 Task，使用 ThreadLocal 在
 *    {@link #resolve(Task)} 中存储 prompt_version，供 {@link #systemPrompt(AgentType)}
 *    同线程读取（AgentLoop 按固定顺序调用 resolve→systemPrompt）。</p>
 */
@Component
public class DefaultAgentTypeResolver implements AgentTypeResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentTypeResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== AgentLoop 冻结接口：ThreadLocal 桥接 ====================

    /**
     * R5 Phase 2：resolve() 与 systemPrompt() 之间的 prompt_version 传递通道。
     * <p>AgentLoop.selectSystemPrompt(task) 严格按 {@code resolve(task) → systemPrompt(type)}
     * 顺序调用，且全程在同一线程，故 ThreadLocal 安全。</p>
     */
    private static final ThreadLocal<String> QA_PROMPT_VERSION_HOLDER = new ThreadLocal<>();

    // ==================== 既有常量（冻结契约） ====================

    /**
     * Designer 硬编码 System Prompt（既有行为，冻结契约）。
     */
    static final String DESIGNER_SYSTEM_PROMPT = "You are AGL game designer agent.";

    /**
     * Tester Prompt 版本标识（既有行为，冻结契约）。
     */
    static final String TESTER_PROMPT_VERSION = "tester_p001";

    /**
     * Research Prompt 版本标识（R0-2 落地）。
     */
    static final String RESEARCH_PROMPT_VERSION = "research_p001";

    /**
     * QA Prompt 版本标识（QA P1-1 落地）。
     */
    static final String QA_PROMPT_VERSION = "qa_p001";

    // ==================== R5 Phase 2 Prompt 版本标识 ====================

    static final String QA_LEAD_DEFAULT_PROMPT = "qa_lead_p001";
    static final String QA_TESTER_DEFAULT_PROMPT = "qa_player_novice_p001";
    static final String QA_ANALYST_DEFAULT_PROMPT = "qa_combat_analyst_p001";
    static final String QA_RESEARCHER_DEFAULT_PROMPT = "qa_ux_reviewer_p001";

    // ==================== 既有白名单（冻结契约） ====================

    static final Set<String> TESTER_TOOL_NAMES = Set.of(
            "test_plan_get", "game_start", "game_observe", "game_act",
            "game_collect_events", "game_collect_metrics", "finding_report", "test_report_create");

    static final Set<String> DESIGNER_TOOL_NAMES = Set.of(
            "get_game_config", "modify_game_config", "simulate_game",
            "analyze_experiment", "compare_experiments",
            "get_game_design", "create_design_document", "create_change_proposal",
            // R2-1：Research→Designer 产物消费（只读）
            "get_research_context", "get_research_claims");

    static final Set<String> RESEARCH_TOOL_NAMES = Set.of(
            "web_search", "fetch_page", "save_source",
            "extract_claims", "compare_sources", "generate_report");

    static final Set<String> QA_TOOL_NAMES = Set.of(
            "finding_report");

    // ==================== R5 Phase 2 白名单 ====================

    /**
     * R5 §6：QA_LEAD 编排工具（6 个）。
     * 不含 get_game_config / create_design_document（与 freeze §B 不同，R5 §6 优先）。
     */
    static final Set<String> QA_LEAD_TOOL_NAMES = Set.of(
            "qa_create_subtask", "qa_start_subrun", "qa_check_subrun_status",
            "qa_collect_findings", "finding_report", "qa_complete_orchestration");

    /**
     * R5 Phase 2：QA_TESTER 工具白名单（10 个）。
     * Tester 8 + web_search + fetch_page（freeze §B+A8 方案）。
     */
    static final Set<String> QA_TESTER_TOOL_NAMES = Set.of(
            "test_plan_get", "game_start", "game_observe", "game_act",
            "game_collect_events", "game_collect_metrics",
            "finding_report", "test_report_create",
            "web_search", "fetch_page");

    /**
     * R5 Phase 2：QA_ANALYST 工具白名单（7 个）。
     * Designer 分析子集 + finding_report + create_design_document（freeze §B）。
     */
    static final Set<String> QA_ANALYST_TOOL_NAMES = Set.of(
            "simulate_game", "analyze_experiment", "compare_experiments",
            "game_collect_metrics", "game_collect_events",
            "finding_report", "create_design_document");

    /**
     * R5 Phase 2：QA_RESEARCHER 工具白名单（10 个）。
     * Research 6 + finding_report + game_observe + game_collect_events + analyze_experiment（freeze §B）。
     */
    static final Set<String> QA_RESEARCHER_TOOL_NAMES = Set.of(
            "web_search", "fetch_page", "save_source",
            "extract_claims", "compare_sources", "generate_report",
            "finding_report", "game_observe", "game_collect_events", "analyze_experiment");

    private final TestPlanService testPlanService;
    private final PromptService promptService;

    public DefaultAgentTypeResolver(TestPlanService testPlanService, PromptService promptService) {
        this.testPlanService = testPlanService;
        this.promptService = promptService;
    }

    // ==================== AgentTypeResolver 接口 ====================

    @Override
    public AgentType resolve(Task task) {
        // 1. 优先检查 constraintsJson 中的 agent_type
        AgentType explicit = resolveFromConstraints(task.constraintsJson());
        if (explicit != null) {
            log.debug("Task {} 通过 constraintsJson 显式指定 agent_type={}", task.id(), explicit);

            // R5 Phase 2：QA_* 类型 → 提取 prompt_version 存入 ThreadLocal
            if (isQaType(explicit)) {
                String promptVersion = extractPromptVersion(task.constraintsJson());
                if (promptVersion != null) {
                    QA_PROMPT_VERSION_HOLDER.set(promptVersion);
                    log.debug("Task {} QA prompt_version={}", task.id(), promptVersion);
                } else {
                    QA_PROMPT_VERSION_HOLDER.remove();
                    log.debug("Task {} QA 无 prompt_version，将使用默认", task.id());
                }
            } else {
                QA_PROMPT_VERSION_HOLDER.remove();
            }
            return explicit;
        }

        // 2. 回退到二值逻辑
        QA_PROMPT_VERSION_HOLDER.remove();
        boolean hasActiveTestPlan = !testPlanService.findActiveByTask(task.id()).isEmpty();
        AgentType fallback = hasActiveTestPlan ? AgentType.TESTER : AgentType.DESIGNER;
        log.debug("Task {} 无 agent_type 指定，回退到 {}", task.id(), fallback);
        return fallback;
    }

    @Override
    public String systemPrompt(AgentType type) {
        return switch (type) {
            case BUILDER -> promptFor("builder_p001","Builder prompt unavailable");
            case DESIGNER -> DESIGNER_SYSTEM_PROMPT;
            case TESTER -> promptFor(TESTER_PROMPT_VERSION,
                    "Tester Run 但缺少 " + TESTER_PROMPT_VERSION + " Prompt 版本");
            case RESEARCH -> promptFor(RESEARCH_PROMPT_VERSION,
                    "Research Run 但缺少 " + RESEARCH_PROMPT_VERSION + " Prompt 版本");
            case QA -> promptFor(QA_PROMPT_VERSION,
                    "QA Run 但缺少 " + QA_PROMPT_VERSION + " Prompt 版本");

            // R5 Phase 2：从 ThreadLocal 读取 constraintsJson.prompt_version
            case QA_LEAD -> resolveQaPrompt(type, QA_LEAD_DEFAULT_PROMPT);
            case QA_TESTER -> resolveQaPrompt(type, QA_TESTER_DEFAULT_PROMPT);
            case QA_ANALYST -> resolveQaPrompt(type, QA_ANALYST_DEFAULT_PROMPT);
            case QA_RESEARCHER -> resolveQaPrompt(type, QA_RESEARCHER_DEFAULT_PROMPT);
        };
    }

    @Override
    public Set<String> toolWhitelist(AgentType type) {
        return switch (type) {
            case BUILDER -> Set.of("production_context","production_start","production_result");
            case DESIGNER -> DESIGNER_TOOL_NAMES;
            case TESTER -> TESTER_TOOL_NAMES;
            case RESEARCH -> RESEARCH_TOOL_NAMES;
            case QA -> QA_TOOL_NAMES;
            case QA_LEAD -> QA_LEAD_TOOL_NAMES;
            case QA_TESTER -> QA_TESTER_TOOL_NAMES;
            case QA_ANALYST -> QA_ANALYST_TOOL_NAMES;
            case QA_RESEARCHER -> QA_RESEARCHER_TOOL_NAMES;
        };
    }

    // ==================== 内部方法 ====================

    /** QA_* 类型判定。 */
    private static boolean isQaType(AgentType type) {
        return type == AgentType.QA_LEAD || type == AgentType.QA_TESTER
                || type == AgentType.QA_ANALYST || type == AgentType.QA_RESEARCHER;
    }

    /**
     * R5 Phase 2：QA Prompt 解析。
     * 优先使用 ThreadLocal 中的 prompt_version（由 resolve() 写入），
     * 回退到默认版本。
     */
    private String resolveQaPrompt(AgentType type, String defaultVersion) {
        String requestedVersion = QA_PROMPT_VERSION_HOLDER.get();
        String version = (requestedVersion != null && !requestedVersion.isBlank())
                ? requestedVersion : defaultVersion;
        return promptFor(version,
                type + " Run 但缺少 " + version + " Prompt 版本");
    }

    private String promptFor(String version, String errorMsg) {
        return promptService.getByVersion(version)
                .map(com.aigamelab.domain.PromptVersion::systemPrompt)
                .orElseThrow(() -> new IllegalStateException(errorMsg));
    }

    private AgentType resolveFromConstraints(String constraintsJson) {
        if (constraintsJson == null || constraintsJson.isBlank()) {
            return null;
        }

        try {
            JsonNode root = MAPPER.readTree(constraintsJson);
            JsonNode agentTypeNode = root.get("agent_type");
            if (agentTypeNode == null || agentTypeNode.isNull()) {
                return null;
            }

            String agentTypeValue = agentTypeNode.asText();
            if (agentTypeValue == null || agentTypeValue.isBlank()) {
                return null;
            }

            return AgentType.valueOf(agentTypeValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "constraintsJson 中 agent_type 值非法: " + e.getMessage()
                            + "，允许值: " + String.join(", ", allowedAgentTypes()));
        } catch (Exception e) {
            log.warn("constraintsJson 解析失败，回退到二值逻辑: {}", e.getMessage());
            return null;
        }
    }

    /**
     * R5 Phase 2：从 constraintsJson 提取 prompt_version 字段。
     */
    private String extractPromptVersion(String constraintsJson) {
        if (constraintsJson == null || constraintsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(constraintsJson);
            JsonNode node = root.get("prompt_version");
            if (node == null || node.isNull()) {
                return null;
            }
            String v = node.asText();
            return (v == null || v.isBlank()) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static String allowedAgentTypes() {
        return java.util.Arrays.stream(AgentType.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
