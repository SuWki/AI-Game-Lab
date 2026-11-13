package com.aigamelab.experiment;

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

import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.domain.Experiment;
import com.aigamelab.repository.ExperimentRepository;

/**
 * P5-1 验收（03 文档 P5-1）：幂等落库 + BatchSimulator 同步执行回填。
 *
 * - 同参两次调用：第二次 reused=true 且同 experimentId；
 * - 不同 configVersion：正常新建；
 * - 单 build（crit）与 ALL_BUILDS 聚合均落 DONE + result_json 非空 + winRate 在 [0,1]。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P5-1 ExperimentService 幂等落库 + 模拟")
class ExperimentServiceTest {

    @Autowired ExperimentService service;
    @Autowired ExperimentRepository repo;
    @Autowired ConfigVersionService configService;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p51-", ".db");
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
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    @Test
    void idempotentSameParamsReturnsExisting() {
        var r1 = service.create("v001", 12345L, 200, "crit", null);
        assertThat(r1.reused()).isFalse();
        assertThat(r1.experiment().status()).isEqualTo("DONE");

        var r2 = service.create("v001", 12345L, 200, "crit", null);
        assertThat(r2.reused()).isTrue();
        assertThat(r2.experiment().id()).isEqualTo(r1.experiment().id());
    }

    @Test
    void singleBuildScenarioRunsAndBackfills() {
        var r = service.create("v001", 777L, 200, "penetration", null);
        assertThat(r.reused()).isFalse();
        Experiment e = r.experiment();
        assertThat(e.status()).isEqualTo("DONE");
        assertThat(e.resultJson()).isNotNull().contains("winRate");
        assertThat(e.winRate()).isBetween(0.0, 1.0);
        assertThat(e.scenario()).isEqualTo("penetration");
    }

    @Test
    void allBuildsScenarioAggregates() {
        var r = service.create("v001", 999L, 150, null, null);
        assertThat(r.reused()).isFalse();
        Experiment e = r.experiment();
        assertThat(e.scenario()).isEqualTo("ALL_BUILDS");
        assertThat(e.status()).isEqualTo("DONE");
        assertThat(e.resultJson()).contains("buildDistribution");
        // 聚合局数 = 3 个 build × 150
        assertThat(e.simulationCount()).isEqualTo(150);
    }

    @Test
    void differentSeedCreatesNewExperiment() {
        // 同版本不同 seed 视为不同实验（幂等键含 seed）
        var r1 = service.create("v001", 111L, 100, "crit", null);
        var r2 = service.create("v001", 222L, 100, "crit", null);
        assertThat(r1.experiment().id()).isNotEqualTo(r2.experiment().id());
        assertThat(r2.reused()).isFalse();
    }

    // ── PF-001：不存在的 configVersion 必须显式失败（40401），绝不静默回退 latest ──

    @Test
    void nonexistentConfigVersionFailsNotFound() {
        // 显式指定但不存在 → NOT_FOUND，且不得产生任何 experiment 行（无悬挂 RUNNING）
        assertThatThrownBy(() -> service.create("v999", 1L, 100, "crit", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCodes.NOT_FOUND))
                .hasMessageContaining("v999");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM experiment", Integer.class))
                .isZero();
    }

    @Test
    void nonexistentConfigVersionNeverSilentlyFallsBackToLatest() {
        // C4 回归：不存在版本绝不静默变成 latest —— 后续 create(latest) 必须视为全新实验
        assertThatThrownBy(() -> service.create("v404", 2L, 100, "crit", null))
                .isInstanceOf(BusinessException.class);
        // 未被 v404 污染：latest 实验正常、幂等键不含 v404
        var r = service.create(configService.getCurrent().version(), 2L, 100, "crit", null);
        assertThat(r.reused()).isFalse();
        assertThat(r.experiment().status()).isEqualTo("DONE");
    }

    @Test
    void nullConfigVersionUsesCurrentVersion() {
        // 合法契约：null → 当前生效版本（不得因 PF-001 修复破坏）
        var r = service.create(null, 321L, 100, "crit", null);
        assertThat(r.reused()).isFalse();
        assertThat(r.experiment().status()).isEqualTo("DONE");
        assertThat(r.experiment().configVersion()).isEqualTo(configService.getCurrent().version());
    }

    @Test
    void blankConfigVersionUsesCurrentVersion() {
        // 合法契约：空 → 当前生效版本
        var r = service.create("", 322L, 100, "crit", null);
        assertThat(r.reused()).isFalse();
        assertThat(r.experiment().status()).isEqualTo("DONE");
        assertThat(r.experiment().configVersion()).isEqualTo(configService.getCurrent().version());
    }
}
