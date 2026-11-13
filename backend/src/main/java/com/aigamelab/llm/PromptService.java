package com.aigamelab.llm;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.aigamelab.domain.PromptVersion;
import com.aigamelab.repository.PromptVersionRepository;

/**
 * 蓝图 §10 / P2-5：Prompt 版本化服务。
 * 启动时若 prompt_version 表空则导入 classpath prompts/designer_p001.md
 * 为 p001 并置 active；再次启动不重复导入。
 * AgentLoop.observe() 经 getActive() 取 System Prompt；Run 记录 promptVersionId 可追溯。
 */
@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);

    static final String SEED_VERSION = "p001";
    private static final String SEED_RESOURCE = "prompts/designer_p001.md";
    private static final String SEED_DESCRIPTION = "v001 初始草稿（蓝图 §18）";

    // T4-1：Tester Prompt 种子（技术设计 §8；version 前缀区分 agent 类型，默认 inactive）
    static final String TESTER_SEED_VERSION = "tester_p001";
    private static final String TESTER_SEED_RESOURCE = "prompts/tester_p001.md";
    private static final String TESTER_SEED_DESCRIPTION = "tester_p001 初始草稿（技术设计 §8）";

    // R0-2：Research Prompt 种子（04_AIResearchAgent.md 设计，默认 inactive）
    static final String RESEARCH_SEED_VERSION = "research_p001";
    private static final String RESEARCH_SEED_RESOURCE = "prompts/research_p001.md";
    private static final String RESEARCH_SEED_DESCRIPTION = "research_p001 初始草稿（04_AIResearchAgent.md）";

    // R2-1：Designer 研究增强 Prompt 种子（当任务 researchSourceTaskId 关联时的工作流；默认 inactive）。
    // 注：运行期 Designer Prompt 由 DefaultAgentTypeResolver 冻结的硬编码文本决定（AgentLoop/Resolver 契约冻结），
    // 此种子作为版本化的研究增强 Designer Prompt 规范随库可见，供追溯/演进。
    static final String DESIGNER_P002_VERSION = "designer_p002";
    private static final String DESIGNER_P002_RESOURCE = "prompts/designer_p002.md";
    private static final String DESIGNER_P002_DESCRIPTION = "designer_p002 研究增强草稿（R2-1）";

    // QA P1-1：QA Prompt 种子（最小闭环；版本前缀区分 agent 类型，默认 inactive）
    static final String QA_SEED_VERSION = "qa_p001";
    private static final String QA_SEED_RESOURCE = "prompts/qa_p001.md";
    private static final String QA_SEED_DESCRIPTION = "qa_p001 初始草稿（QA P1-1）";

    // R5 Phase 2：8 个 QA Role Prompt 种子（inactive、幂等导入；重复启动不重复插入）
    private static final String[][] QA_P2_SEEDS = {
            {"builder_p001", "prompts/builder_p001.md", "Bounded template production Builder"},
            {"qa_lead_p001", "prompts/qa_lead_p001.md", "qa_lead_p001 编排角色（R5 §8.2）"},
            {"qa_player_novice_p001", "prompts/qa_player_novice_p001.md", "qa_player_novice_p001 Player NOVICE（R5 §8.2）"},
            {"qa_player_aggressive_p001", "prompts/qa_player_aggressive_p001.md", "qa_player_aggressive_p001 Player AGGRESSIVE（R5 §8.2）"},
            {"qa_player_build_specialist_p001", "prompts/qa_player_build_specialist_p001.md", "qa_player_build_specialist_p001 Player BUILD（R5 §8.2）"},
            {"qa_combat_analyst_p001", "prompts/qa_combat_analyst_p001.md", "qa_combat_analyst_p001 Combat（R5 §8.2）"},
            {"qa_numerical_analyst_p001", "prompts/qa_numerical_analyst_p001.md", "qa_numerical_analyst_p001 Numerical（R5 §8.2）"},
            {"qa_exploit_hunter_p001", "prompts/qa_exploit_hunter_p001.md", "qa_exploit_hunter_p001 Exploit（R5 §8.2）"},
            {"qa_ux_reviewer_p001", "prompts/qa_ux_reviewer_p001.md", "qa_ux_reviewer_p001 UX Reviewer（R5 §8.2）"},
    };

    private final PromptVersionRepository repo;

    public PromptService(PromptVersionRepository repo) {
        this.repo = repo;
        importSeedIfEmpty();
        importTesterSeedIfAbsent();
        importResearchSeedIfAbsent();
        importDesignerP002SeedIfAbsent();
        importQaSeedIfAbsent();
        importQaP2SeedsIfAbsent();
    }

    /** 当前生效版本；表空且种子导入失败时抛出（Agent 无法无 Prompt 运行）。 */
    public PromptVersion getActive() {
        return repo.findActive().orElseThrow(() ->
                new IllegalStateException("无 active 的 Prompt 版本（prompt_version 表异常）"));
    }

    /** 指定版本号获取（追溯场景）。 */
    public java.util.Optional<PromptVersion> getByVersion(String version) {
        return repo.findAll().stream()
                .filter(p -> p.version().equals(version))
                .findFirst();
    }

    /** 幂等导入：仅表空时执行，重复启动不重复插入。 */
    private synchronized void importSeedIfEmpty() {
        if (repo.findActive().isPresent()) {
            return; // 已有 active 版本——不重复导入
        }
        if (!repo.findAll().isEmpty()) {
            // 表非空但无 active：修复 active 标记而不重复插入
            var first = repo.findAll().get(repo.findAll().size() - 1);
            repo.setActive(first.id());
            log.warn("prompt_version 表无 active 版本，已将 id={} 置为 active", first.id());
            return;
        }
        String content = readSeed(SEED_RESOURCE);
        // insert() 按 P0-3 契约默认 inactive；种子导入后显式置 active
        PromptVersion saved = repo.insert(new PromptVersion(null, SEED_VERSION,
                content, SEED_DESCRIPTION, false, null));
        repo.setActive(saved.id());
        log.info("已导入初始 Prompt {} (id={}, {} 字符)", saved.version(), saved.id(),
                content.length());
    }

    /**
     * T4-1：Tester Prompt 种子导入。
     * 仅当 {@code tester_p001} 版本尚不存在时插入一条 inactive 版本；
     * 绝不触碰 active 标记（设计师 p001 保持生效），且幂等于进程重启。
     */
    private synchronized void importTesterSeedIfAbsent() {
        if (repo.findAll().stream().anyMatch(p -> TESTER_SEED_VERSION.equals(p.version()))) {
            return; // 已存在则跳过，重复启动不重复插入
        }
        String content = readSeed(TESTER_SEED_RESOURCE);
        PromptVersion saved = repo.insert(new PromptVersion(null, TESTER_SEED_VERSION,
                content, TESTER_SEED_DESCRIPTION, false, null));
        log.info("已导入 Tester Prompt {} (id={}, {} 字符, inactive)",
                saved.version(), saved.id(), content.length());
    }

    /**
     * R0-2：Research Prompt 种子导入。
     * 仅当 {@code research_p001} 版本尚不存在时插入一条 inactive 版本；
     * 绝不触碰 active 标记（设计师 p001 保持生效），且幂等于进程重启。
     */
    private synchronized void importResearchSeedIfAbsent() {
        if (repo.findAll().stream().anyMatch(p -> RESEARCH_SEED_VERSION.equals(p.version()))) {
            return; // 已存在则跳过，重复启动不重复插入
        }
        String content = readSeed(RESEARCH_SEED_RESOURCE);
        PromptVersion saved = repo.insert(new PromptVersion(null, RESEARCH_SEED_VERSION,
                content, RESEARCH_SEED_DESCRIPTION, false, null));
        log.info("已导入 Research Prompt {} (id={}, {} 字符, inactive)",
                saved.version(), saved.id(), content.length());
    }

    /**
     * R2-1：Designer 研究增强 Prompt 种子导入。
     * 仅当 {@code designer_p002} 版本尚不存在时插入一条 inactive 版本；
     * 绝不触碰 active 标记（设计师 p001 保持生效），且幂等于进程重启。
     *
     * 注：Designer 运行期 System Prompt 由 DefaultAgentTypeResolver 硬编码返回
     * （AgentLoop / AgentTypeResolver 接口均冻结），因此 p002 是版本化的研究增强
     * Designer Prompt 规范，供人类可读/演进/追溯使用；运行时的研究增强行为由
     * Designer 工具描述 + 工具内关联解析实现（见 GetResearchContextTool / GetResearchClaimsTool）。
     */
    private synchronized void importDesignerP002SeedIfAbsent() {
        if (repo.findAll().stream().anyMatch(p -> DESIGNER_P002_VERSION.equals(p.version()))) {
            return; // 已存在则跳过，重复启动不重复插入
        }
        String content = readSeed(DESIGNER_P002_RESOURCE);
        PromptVersion saved = repo.insert(new PromptVersion(null, DESIGNER_P002_VERSION,
                content, DESIGNER_P002_DESCRIPTION, false, null));
        log.info("已导入 Designer P002 (research-enhanced) Prompt {} (id={}, {} 字符, inactive)",
                saved.version(), saved.id(), content.length());
    }

    /**
     * QA P1-1：QA Prompt 种子导入。
     * 仅当 {@code qa_p001} 版本尚不存在时插入一条 inactive 版本；
     * 绝不触碰 active 标记（设计师 p001 保持生效），且幂等于进程重启。
     * 与 tester/research 种子同一模式（T4-1 / R0-2），无平行机制。
     */
    private synchronized void importQaSeedIfAbsent() {
        if (repo.findAll().stream().anyMatch(p -> QA_SEED_VERSION.equals(p.version()))) {
            return; // 已存在则跳过，重复启动不重复插入
        }
        String content = readSeed(QA_SEED_RESOURCE);
        PromptVersion saved = repo.insert(new PromptVersion(null, QA_SEED_VERSION,
                content, QA_SEED_DESCRIPTION, false, null));
        log.info("已导入 QA Prompt {} (id={}, {} 字符, inactive)",
                saved.version(), saved.id(), content.length());
    }

    /**
     * R5 Phase 2：QA Role Prompt 种子批量导入。
     * 仅当各自版本尚不存在时插入一条 inactive 版本；绝不触碰 active 标记。
     * 幂等：重复启动不重复插入。8 个角色：qa_lead + 3 Player + Combat +
     * Numerical + Exploit + UX（freeze §8.2 计数口径=8）。
     */
    private synchronized void importQaP2SeedsIfAbsent() {
        for (String[] seed : QA_P2_SEEDS) {
            String version = seed[0];
            String resource = seed[1];
            String description = seed[2];
            if (repo.findAll().stream().anyMatch(p -> version.equals(p.version()))) {
                continue; // 已存在则跳过
            }
            String content = readSeed(resource);
            PromptVersion saved = repo.insert(new PromptVersion(null, version,
                    content, description, false, null));
            log.info("已导入 QA Phase 2 Prompt {} (id={}, {} 字符, inactive)",
                    saved.version(), saved.id(), content.length());
        }
    }

    private String readSeed(String resource) {
        try (var in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取内置 Prompt 失败: " + SEED_RESOURCE, e);
        }
    }
}
