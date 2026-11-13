package com.aigamelab.config;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.repository.ConfigVersionRepository;

/**
 * P6-1：配置版本服务（蓝图 §14 / 03 文档 P6-1）。
 *
 * <p>ConfigVersion = append-only 全量快照。当前生效版本 = {@link #findLatest()}。
 * 本服务不实现任何"激活/回滚"语义——最新版本即当前生效版本，历史快照只读不可变。
 *
 * <p>启动时空表则导入 classpath {@code config/v001.json} 为 v001（幂等，重复启动不重复导入）。
 * 版本号格式 {@code v%03d}（v001…）；{@link #createVersion} 在事务内取最大序号 +1，保证严格递增且不重号。
 */
@Service
public class ConfigVersionService {

    private static final Logger log = LoggerFactory.getLogger(ConfigVersionService.class);

    static final String SEED_VERSION = "v001";
    private static final String SEED_RESOURCE = "config/v001.json";
    /** 版本号模式：v 后接 1~多位数字。 */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^v(\\d+)$");

    private final ConfigVersionRepository repo;
    private final TransactionTemplate tx;

    public ConfigVersionService(ConfigVersionRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        importSeedIfEmpty();
    }

    /**
     * 当前生效版本（= latest）。无版本时抛异常（Agent 无法无配置运行）。
     */
    public ConfigVersion getCurrent() {
        return repo.findLatest().orElseThrow(() ->
                new IllegalStateException("无当前生效 Config 版本（game_config_version 表空且种子导入失败）"));
    }

    public Optional<ConfigVersion> findByVersion(String version) {
        return repo.findByVersion(version);
    }

    /** 最新版本（= 当前生效版本；append-only 下即字典序最大版本）。 */
    public Optional<ConfigVersion> findLatest() {
        return repo.findLatest();
    }

    public List<ConfigVersion> findAll() {
        return repo.findAll();
    }

    /**
     * 创建一个新版本（append-only 全量快照）。
     *
     * <p>在事务内定位当前最大序号、计算 {@code maxSeq+1}、格式化版本号并插入完整快照。
     * 因 Hikari {@code maximum-pool-size=1} 串行化所有写，且本方法整体处于事务内，
     * 并发调用不会重号：两线程各自取到的最大序号 +1 不会重复（写串行）。
     * 若极端并发下仍撞主键（如外部直写），{@link DuplicateKeyException} 向上抛出，
     * 由调用方（Controller 经 GlobalExceptionHandler）转为 40901。
     *
     * @param configJson          完整配置 JSON 快照
     * @param parentVersion       父版本号（可空，首版或脱离父链时传 null）
     * @param changeProposalId    关联的变更提案 id（可空，仅 approve 流程回填）
     * @param comment             版本说明
     * @return 新插入的 ConfigVersion（含已回填的 createdAt）
     */
    public ConfigVersion createVersion(String configJson, String parentVersion,
            Long changeProposalId, String comment) {
        if (configJson == null || configJson.isBlank()) {
            throw new IllegalArgumentException("configJson 不能为空");
        }
        return tx.execute(status -> {
            String newVersion = nextVersionLocked();
            ConfigVersion created = repo.insert(new ConfigVersion(
                    newVersion, configJson, parentVersion, changeProposalId, comment, null));
            log.info("创建 Config 版本 {}（parent={}, changeProposalId={}）",
                    newVersion, parentVersion, changeProposalId);
            return created;
        });
    }

    /**
     * 事务内分配下一个版本号：取现有最大序号 +1，格式化 v%03d。
     * 仅本类内部于事务上下文中调用。
     */
    private String nextVersionLocked() {
        int maxSeq = repo.findAll().stream()
                .map(ConfigVersion::version)
                .mapToInt(ConfigVersionService::seqOf)
                .max()
                .orElse(0);
        return String.format("v%03d", maxSeq + 1);
    }

    /** 从版本号提取序号；非法格式视为 0（不计入递增起点）。 */
    static int seqOf(String version) {
        if (version == null) {
            return 0;
        }
        Matcher m = VERSION_PATTERN.matcher(version);
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 幂等导入：仅表空时执行，重复启动不重复插入。Spring @PostConstruct 确保在 DataSource+schema 初始化后调用。 */
    @PostConstruct
    private void importSeedIfEmpty() {
        if (repo.findLatest().isPresent()) {
            return; // 已有版本——不重复导入
        }
        if (!repo.findAll().isEmpty()) {
            // 表非空但无 latest：理论上不会（version 是主键），兜底不插入
            log.warn("game_config_version 表非空但不含可解析最新版本，跳过种子导入");
            return;
        }
        String content = readSeed();
        repo.insert(new ConfigVersion(SEED_VERSION, content, null, null,
                "初始种子配置（蓝图 §12.1 失衡种子）", LocalDateTime.now()));
        log.info("已导入初始 Config 版本 {}", SEED_VERSION);
    }

    private String readSeed() {
        try (var in = new ClassPathResource(SEED_RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取内置种子配置失败: " + SEED_RESOURCE, e);
        }
    }
}
