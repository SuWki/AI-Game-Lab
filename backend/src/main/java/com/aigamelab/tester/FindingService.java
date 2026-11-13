package com.aigamelab.tester;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingFilter;
import com.aigamelab.repository.FindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T3-1：Finding 业务规则（技术设计 §7）。
 *
 * 职责：severity/category/source/status 白名单校验、Evidence 结构校验
 * （sessionId 必填主锚点，tick 不得为唯一定位键——契约由单测守护）、
 * 过滤查询入口。TestPlan=测试什么 / Finding=发现什么，职责不混淆。
 *
 * <p>PF-003（TD-T6-001 清偿）：report() 追加 configVersion 存在性校验——
 * 被测对象必须是真实存在的 ConfigVersion（契约 1），杜绝凭任意非空串
 * （如 "v999"）落库指向不存在版本的 Finding。
 *
 * <p>QA P1-2 追加两项数据质量/Lifecycle 校验（纯应用层，schema 冻结不动）：
 * <ol>
 *   <li><b>Evidence 数据质量</b>：只要 Finding 携带 Evidence（evidenceJson
 *       非空/非 "{}"），其 expected / actual 必须非 null 且非 blank——杜绝
 *       "Expected 无/Autual 空白"这类无意义的对比内容进入 QA/Tester 核心工件。</li>
 *   <li><b>最小生命周期迁移矩阵</b>：updateStatus() 从"白名单直写"升级为
 *       "合法边白名单校验"。矩阵锚定 QA P1 Audit §线上证据（docs/QA_P1_IMPLEMENTATION_AUDIT.md）：
 *       合法边 OPEN→CONFIRMED/DISMISSED、CONFIRMED→FIXED/DISMISSED；
 *       终态 DISMISSED/FIXED 不可再迁移；OPEN 未确认不可直跳 FIXED；CONFIRMED 不可回退 OPEN。
 *       越权迁移抛 40901（与现有 TestPlanService/ErrorCodes"状态机违规"约定一致）。</li>
 * </ol>
 */
@Service
public class FindingService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Finding 最小合法状态迁移矩阵（QA P1-2，纯 Java 校验，无迁移表）。
     * key = 当前状态，value = 允许迁往的目标状态集合。
     *
     * <p>事实来源（docs/QA_P1_IMPLEMENTATION_AUDIT.md 权威矩阵）：
     * - OPEN → CONFIRMED / DISMISSED（人工确认或驳回）；
     * - CONFIRMED → FIXED / DISMISSED（确认后标记已修复，或驳回已确认项）；
     * - DISMISSED / FIXED 为终态：不可再迁移（含回退 OPEN）；
     * - OPEN → FIXED 非法（未确认不得直接修复）；CONFIRMED → OPEN 非法（已确认不可回退）。
     */
    static final Map<String, Set<String>> LEGAL_TRANSITIONS = Map.of(
            "OPEN", Set.of("CONFIRMED", "DISMISSED"),
            "CONFIRMED", Set.of("FIXED", "DISMISSED"),
            "DISMISSED", Set.of(),
            "FIXED", Set.of());

    private final FindingRepository findingRepo;
    private final ConfigVersionService configService;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public FindingService(FindingRepository findingRepo, ConfigVersionService configService) {
        this.findingRepo = findingRepo;
        this.configService = configService;
    }

    /** 校验并落库一条 Finding（source 为空默认 LLM 由 Repository 兜底）。 */
    public Finding report(Finding finding) {
        validate(finding);
        return findingRepo.insert(finding);
    }

    public Finding require(long id) {
        return findingRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Finding 不存在: " + id));
    }

    /** 回归对比/列表过滤入口（null 参数忽略该条件）。 */
    public List<Finding> findByFilter(String configVersion, String severity,
            Long runId, Long testPlanId) {
        return findingRepo.findByFilter(configVersion, severity, runId, testPlanId);
    }

    /**
     * QA P1-3 多维过滤入口（R3-B1）。维度语义见 {@link FindingFilter}：
     * 全维度 AND 组合，null/空集合忽略该维度，空过滤返回全部。
     *
     * <p>枚举维度（severity/category/source/status）先按 {@link Finding} 白名单校验，
     * 非法值抛 40001（与 report/updateStatus 既有校验契约一致）——过滤参数非法
     * 属于调用方错误，不做静默忽略（避免"过滤条件打错却返回全量"掩盖查询意图）。
     *
     * <p>configVersion 不为空时仅要求非 blank，不校验存在性——过滤已存在版本的
     * Finding 属查询语义，与 report()（PF-003 落库防伪）职责不同。
     */
    public List<Finding> findByQaFilter(FindingFilter filter) {
        validateFilter(filter);
        return findingRepo.findByQaFilter(filter);
    }

    /**
     * 人工确认/驳回状态流转（QA P1-2 起强制最小合法迁移矩阵）。
     * 合法边见 {@link #LEGAL_TRANSITIONS}；越权迁移抛 40901（状态机违规约定，
     * 与 TestPlanService.activate 一致），不产生任何 DB 副作用。
     * 状态值不在白名单视为参数非法 40001（既有行为不变）。
     */
    public Finding updateStatus(long id, String status) {
        Finding current = require(id); // 先读当前状态：不存在 → 40401，且迁移校验以真实状态为源
        if (status == null || !Finding.STATUSES.contains(status)) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "status 非法: " + status + "，允许值 " + Finding.STATUSES);
        }
        String from = current.status() == null ? "OPEN" : current.status();
        Set<String> legalTargets = LEGAL_TRANSITIONS.getOrDefault(from, Set.of());
        if (!legalTargets.contains(status)) {
            throw BusinessException.conflict(
                    "Finding 状态迁移非法: " + from + " → " + status
                            + "（合法迁移边: " + LEGAL_TRANSITIONS.get(from) + "）");
        }
        findingRepo.updateStatus(id, status);
        return require(id);
    }

    // ------------------------------------------------------------------
    // 内部校验
    // ------------------------------------------------------------------

    /**
     * QA P1-3 过滤参数校验：枚举维度按 Finding 白名单校验（40001），
     * configVersion 仅防 blank（查询语义，不校验存在性——查不存在的版本
     * 合法语义是空结果，不是 404）。
     */
    private void validateFilter(FindingFilter f) {
        if (f.severity() != null && !Finding.SEVERITIES.contains(f.severity())) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "severity 非法: " + f.severity() + "，允许值 " + Finding.SEVERITIES);
        }
        if (f.category() != null && !Finding.CATEGORIES.contains(f.category())) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "category 非法: " + f.category() + "，允许值 " + Finding.CATEGORIES);
        }
        if (f.source() != null && !Finding.SOURCES.contains(f.source())) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "source 非法: " + f.source() + "，允许值 " + Finding.SOURCES);
        }
        if (f.status() != null && !Finding.STATUSES.contains(f.status())) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "status 非法: " + f.status() + "，允许值 " + Finding.STATUSES);
        }
        if (f.configVersion() != null && f.configVersion().isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "configVersion 非空条件不得为 blank");
        }
    }

    private void validate(Finding f) {
        if (f.severity() == null || !Finding.SEVERITIES.contains(f.severity())) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "severity 非法: " + f.severity() + "，允许值 " + Finding.SEVERITIES);
        }
        if (f.category() == null || !Finding.CATEGORIES.contains(f.category())) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "category 非法: " + f.category() + "，允许值 " + Finding.CATEGORIES);
        }
        if (f.source() != null && !Finding.SOURCES.contains(f.source())) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "source 非法: " + f.source() + "，允许值 " + Finding.SOURCES);
        }
        if (f.title() == null || f.title().isBlank()) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "title 必填");
        }
        if (f.scenarioId() == null || f.scenarioId().isBlank()) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "scenarioId 必填");
        }
        if (f.configVersion() == null || f.configVersion().isBlank()) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "configVersion 必填（契约 1：被测对象是 ConfigVersion）");
        }
        // PF-003（TD-T6-001）：configVersion 必须真实存在于 game_config_version，
        // 防止凭任意非空串（如 "v999"）落库指向不存在版本的 Finding。
        boolean local=f.configVersion().startsWith("local-") && jdbc!=null && jdbc.queryForObject("SELECT COUNT(*) FROM local_test_context WHERE json_extract(binding_json,'$.version')=?",Integer.class,f.configVersion())>0;
        if(!local)configService.findByVersion(f.configVersion()).orElseThrow(() ->BusinessException.notFound("被测版本不存在: " + f.configVersion()));
        if(local) {
            try {
                Finding.Evidence ev=MAPPER.readValue(f.evidenceJson(),Finding.Evidence.class);
                String localRun=String.valueOf(ev.observationSnapshot().get("localTestRunId"));
                Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM local_test_context WHERE run_id=? AND json_extract(binding_json,'$.version')=?",Integer.class,localRun,f.configVersion());
                if(count==null||count!=1||!("local:"+localRun).equals(ev.sessionId()))throw new IllegalArgumentException("Local evidence binding mismatch");
            }catch(Exception e){throw new IllegalArgumentException("Local Finding must reference its bound test execution",e);}
        }
        if (f.reproSteps() == null || f.reproSteps().isBlank()) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "reproSteps 必填（完整 reproduction sequence 硬要求）");
        }
        validateEvidenceJson(f.evidenceJson());
        validateExpectedActual(f);
    }

    /**
     * Evidence 数据质量校验（QA P1-2）：只要 Finding 携带 Evidence
     * （evidenceJson 可反序列化且非空/非 "{}"），其 expected 和 actual
     * 必须非 null 且非 blank——杜绝 QA/Tester 核心工件中出现无意义对比行。
     *
     * <p>无 Evidence 的 MANUAL 登记（evidenceJson 为空/"{}"）不受影响——
     * 这是既有 MVP 允许路径（见 TesterServicesTest.manualFinding_withoutEvidence_allowed）。
     */
    private void validateExpectedActual(Finding f) {
        if (!hasEvidence(f.evidenceJson())) {
            return; // 无 Evidence 的路径（MANUAL 等）跳过
        }
        if (f.expected() == null || f.expected().isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "expected 必填（Finding 携带 Evidence 时，expected 不得为 null/blank）");
        }
        if (f.actual() == null || f.actual().isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "actual 必填（Finding 携带 Evidence 时，actual 不得为 null/blank）");
        }
    }

    /** 判断 evidenceJson 是否承载了真实 Evidence（非空、结构合法、非 "{}"）。 */
    private boolean hasEvidence(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()
                || "{}".equals(evidenceJson.trim())) {
            return false;
        }
        try {
            Finding.Evidence ev = MAPPER.readValue(evidenceJson, Finding.Evidence.class);
            return ev.sessionId() != null && !ev.sessionId().isBlank();
        } catch (Exception e) {
            return false; // 非法 JSON 不被视为有效 Evidence
        }
    }

    /**
     * Evidence 结构校验：evidenceJson 非空时必须能反序列化为 Evidence 且
     * sessionId（必填主锚点）非空——RESTART 后 tick 重置也不影响唯一定位。
     */
    private void validateEvidenceJson(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()
                || "{}".equals(evidenceJson.trim())) {
            return; // MVP 允许无 Evidence 的 MANUAL 登记
        }
        Finding.Evidence ev;
        try {
            ev = MAPPER.readValue(evidenceJson, Finding.Evidence.class);
        } catch (Exception e) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "evidenceJson 非法（须符合 Evidence 结构）: " + e.getMessage());
        }
        if (ev.sessionId() == null || ev.sessionId().isBlank()) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "Evidence.sessionId 必填主锚点（tick 不得作为唯一定位键）");
        }
    }
}
