package com.aigamelab.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.domain.Experiment;
import com.aigamelab.repository.ExperimentRepository;

/**
 * QA Phase 3（HD-1 A2 / HD-3 / HD-4）：QaBuildShareDeriver 单元测试（stub repo，无 Spring）。
 *
 * <p>验证：games-share 语义（max/Σ，build 名无关）/ 最新 DONE 选择 / 全部失败模式
 * degrade 为 null（无实验 / 非 DONE / 解析失败 / buildDistribution 缺失 / 零局数 /
 * 查询异常）/ configVersion 空值。Deriver 负责派生，Gate 的 INCONCLUSIVE 语义由
 * {@link QaReleaseGateServiceTest} H 组覆盖。</p>
 */
@DisplayName("QA Phase 3 QaBuildShareDeriver 单元测试")
class QaBuildShareDeriverTest {

    /** 可配置 findByFilter 的 stub；其余方法未使用（单元测试不触达）。 */
    private static class StubExperimentRepository implements ExperimentRepository {
        private List<Experiment> experiments = List.of();

        void setExperiments(List<Experiment> experiments) {
            this.experiments = experiments;
        }

        @Override
        public List<Experiment> findByFilter(String configVersion, String scenario) {
            return experiments.stream()
                    .filter(e -> configVersion == null || configVersion.equals(e.configVersion()))
                    .filter(e -> scenario == null || scenario.equals(e.scenario()))
                    .toList();
        }

        @Override public Experiment insert(Experiment e) { throw new UnsupportedOperationException(); }
        @Override public void complete(long id, double winRate, double avgDps, double avgGameTimeS,
                String resultJson, long durationMs) { throw new UnsupportedOperationException(); }
        @Override public void updateStatus(long id, String status) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<Experiment> findById(long id) {
            return experiments.stream().filter(e -> e.id() != null && e.id() == id).findFirst();
        }
        @Override public List<Experiment> findByIds(List<Long> ids) {
            return experiments.stream().filter(e -> e.id() != null && ids.contains(e.id())).toList();
        }
    }

    private final StubExperimentRepository stubRepo = new StubExperimentRepository();
    private final QaBuildShareDeriver deriver = new QaBuildShareDeriver(stubRepo);

    /** DONE ALL_BUILDS 实验行（result_json 仅含 buildDistribution 子树即足够派生解析）。 */
    private static Experiment doneExperiment(long id, String configVersion, String resultJson) {
        return new Experiment(id, null, configVersion, 42L, 300, "ALL_BUILDS", "DONE",
                0.5, 100.0, 10.0, resultJson, 1000L, null);
    }

    /** 构造 buildDistribution JSON（games 逐 build）。 */
    private static String buildDistJson(int... gamesPerBuild) {
        StringBuilder sb = new StringBuilder("{\"buildDistribution\":{");
        for (int i = 0; i < gamesPerBuild.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"build").append(i).append("\":{\"games\":").append(gamesPerBuild[i])
                    .append(",\"wins\":").append(gamesPerBuild[i] / 2)
                    .append(",\"winRate\":0.5,\"ciLow\":0.4,\"ciHigh\":0.6}");
        }
        return sb.append("}}").toString();
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("D1: games-share 语义直接核验（HD-3：max/Σ，build 名无关）")
    void derive_gamesShareSemantics() {
        // 40/35/25 → max=40, Σ=100 → 0.4（与 build 名无关，仅 games 计数）
        stub(List.of(doneExperiment(66L, "v001", buildDistJson(40, 35, 25))));
        assertThat(deriver.deriveDominantBuildShare("v001")).isEqualTo(0.4);
    }

    @Test
    @DisplayName("D2: 取最新 DONE（id 升序最后一行为准），与 config 精确过滤")
    void derive_takesLatestDone() {
        // 同 config 两行：id=1 旧失衡，id=66 新均衡 → 取 66
        Experiment older = doneExperiment(1L, "v001", buildDistJson(90, 10, 10));
        Experiment newer = doneExperiment(66L, "v001", buildDistJson(40, 35, 25));
        stub(List.of(older, newer));
        assertThat(deriver.deriveDominantBuildShare("v001")).isEqualTo(0.4);
    }

    @Test
    @DisplayName("D3: 不同 config 隔离（HD-6：按 configVersion 作用域查询，不跨版本污染）")
    void derive_scopedByConfigVersion() {
        Experiment v001 = doneExperiment(1L, "v001", buildDistJson(90, 10, 10));
        Experiment v002 = doneExperiment(2L, "v002", buildDistJson(40, 35, 25));
        stub(List.of(v001, v002));
        // v002 只应看到 v002 行 → 0.4；v001 只应看到 v001 行 → 90/110≈0.818
        assertThat(deriver.deriveDominantBuildShare("v001")).isEqualTo(90.0 / 110.0);
        assertThat(deriver.deriveDominantBuildShare("v002")).isEqualTo(0.4);
    }

    // ==================== 失败模式（全部 degrade 为 null） ====================

    @Test
    @DisplayName("E1: 无实验 → null")
    void derive_emptyRepo() {
        stub(List.of());
        assertThat(deriver.deriveDominantBuildShare("v001")).isNull();
    }

    @Test
    @DisplayName("E2: 实验存在但非 DONE（RUNNING）→ null（不取未完成实验）")
    void derive_nonDoneIgnored() {
        Experiment running = new Experiment(44L, null, "v001", 1L, 300, "ALL_BUILDS", "RUNNING",
                0.0, 0.0, 0.0, buildDistJson(100, 100, 100), null, null);
        stub(List.of(running));
        assertThat(deriver.deriveDominantBuildShare("v001")).isNull();
    }

    @Test
    @DisplayName("E3: result_json 解析失败 → null（不抛异常）")
    void derive_malformedJson() {
        stub(List.of(doneExperiment(55L, "v001", "{not-valid-json!!")));
        assertThat(deriver.deriveDominantBuildShare("v001")).isNull();
    }

    @Test
    @DisplayName("E4: buildDistribution 缺失/非对象 → null")
    void derive_missingBuildDistribution() {
        stub(List.of(doneExperiment(56L, "v001", "{\"totalGames\":300}")));
        assertThat(deriver.deriveDominantBuildShare("v001")).isNull();
    }

    @Test
    @DisplayName("E5: 零局数 → null（分母为 0，无意义）")
    void derive_zeroGames() {
        stub(List.of(doneExperiment(57L, "v001", buildDistJson(0, 0, 0))));
        assertThat(deriver.deriveDominantBuildShare("v001")).isNull();
    }

    @Test
    @DisplayName("E6: configVersion 空值 → null（不查询）")
    void derive_blankConfig() {
        stub(List.of(doneExperiment(58L, "v001", buildDistJson(100, 100, 100))));
        assertThat(deriver.deriveDominantBuildShare(null)).isNull();
        assertThat(deriver.deriveDominantBuildShare("  ")).isNull();
    }

    @Test
    @DisplayName("E7: 查询异常 → null（degrade，不阻塞 Gate 决策）")
    void derive_repoThrows() {
        ExperimentRepository throwing = new StubExperimentRepository() {
            @Override
            public List<Experiment> findByFilter(String configVersion, String scenario) {
                throw new IllegalStateException("db down");
            }
        };
        QaBuildShareDeriver d = new QaBuildShareDeriver(throwing);
        assertThat(d.deriveDominantBuildShare("v001")).isNull();
    }

    private void stub(List<Experiment> experiments) {
        stubRepo.setExperiments(experiments);
    }
}