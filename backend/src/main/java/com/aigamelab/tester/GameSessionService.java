package com.aigamelab.tester;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.aigamelab.common.BusinessException;
import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.gameenv.GameAdapter;
import com.aigamelab.gameenv.GameEvent;
import com.aigamelab.gameenv.GameLaunchSpec;
import com.aigamelab.gameenv.GameObservation;
import com.aigamelab.gameenv.UnknownSessionException;
import com.aigamelab.sim.GameConfig;
import com.aigamelab.sim.GameConfigLoader;

/**
 * T3-2：游戏会话生命周期协调（技术设计 §4 设计要点）。
 *
 * 职责：sessionId ↔ runId 归属登记与校验（防跨 run 操作他方会话）、
 * 场景启动参数组装（seed/overrides/playerProfile 来自 Plan 的 Scenario）。
 * 环境交互全部委托冻结的 {@link GameAdapter}，不复制游戏状态机。
 *
 * <p>T7-1 显式配置解析：start() 接收调用方明确指定的 {@code configVersion}，
 * 经 {@link ConfigVersionService#findByVersion(String)} 解析为真实的 {@link GameConfig}
 * 快照（绝不使用 {@code findLatest()}，避免自动漂移到最新版本）。
 * 解析结果记入 sessionConfigVersion 便于会话→版本追踪与后续契约校验。
 *
 * 会话不持久化（技术设计 §14 技术债 4）：进程重启后内存登记丢失，
 * game_observe 报 UnknownSession → Agent 重启场景恢复（挑战矩阵 §12 末行取舍）。
 */
@Service
public class GameSessionService {

    /** sessionId → 归属 runId（NO_RUN = 无 Run 的环境级验证会话；CHM 不收 null） */
    private static final Long NO_RUN = -1L;

    private final Map<String, Long> sessionOwner = new ConcurrentHashMap<>();
    /** sessionId → 启动该会话时显式解析的 configVersion（T7-1 配置追踪） */
    private final Map<String, String> sessionConfigVersion = new ConcurrentHashMap<>();

    private final GameAdapter adapter;
    private final ConfigVersionService configService;

    public GameSessionService(GameAdapter adapter, ConfigVersionService configService) {
        this.adapter = adapter;
        this.configService = configService;
    }

    /**
     * 按场景启动（或 RESTART 重启）会话并登记归属。
     *
     * @param seed           确定性随机种子
     * @param configVersion  被测配置版本（显式指定，T7-1：解析该版本而非 findLatest）
     * @param overrides      Boundary/故障注入覆盖
     * @param playerProfile  玩家人格（SCRIPTED/RANDOM）
     * @param runId          归属 Run（无 Run 验证会话传 null）
     * @return 会话句柄视图（不含时间元数据）
     * @throws BusinessException configVersion 不存在时抛 40401（T7-001：显式校验真实性）
     */
    public GameSessionHandleLike start(long seed, String configVersion,
            Map<String, Object> overrides, String playerProfile, Long runId) {
        if (configVersion == null || configVersion.isBlank()) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "configVersion 必填（契约 1：被测对象是 ConfigVersion）");
        }
        // T7-1：显式解析指定版本（非 findLatest），版本不存在即失败——禁止自动漂移。
        String json = configService.findByVersion(configVersion)
                .orElseThrow(() -> BusinessException.notFound(
                        "ConfigVersion 不存在: " + configVersion))
                .configJson();
        GameConfig resolved = GameConfigLoader.fromJson(json);
        var handle = adapter.start(new GameLaunchSpec(seed, configVersion,
                overrides == null ? Map.of() : Map.copyOf(overrides),
                playerProfile == null ? GameLaunchSpec.PROFILE_SCRIPTED : playerProfile,
                resolved));
        sessionOwner.put(handle.sessionId(), runId == null ? NO_RUN : runId);
        sessionConfigVersion.put(handle.sessionId(), configVersion);
        return new GameSessionHandleLike(handle.sessionId(), adapter.id(), configVersion);
    }

    /**
     * 查询会话启动时显式绑定的 configVersion（T7-1 配置追踪；会话不存在返回 null）。
     * 供上层校验 findingReport.configVersion 与 game_start 配置一致时复用。
     */
    public String configVersionOf(String sessionId) {
        return sessionConfigVersion.get(sessionId);
    }

    /** 归属校验后观察；未知 sessionId 或他方会话一律抛 IllegalArgumentException。 */
    public GameObservation observe(String sessionId, Long runId) {
        requireOwned(sessionId, runId);
        return adapter.observe(sessionId);
    }

    /** 归属校验后执行动作。 */
    public GameObservation act(String sessionId, Long runId,
            com.aigamelab.gameenv.GameAction action) {
        requireOwned(sessionId, runId);
        return adapter.act(sessionId, action);
    }

    /** 归属校验后 drain 增量事件（取后即清）。 */
    public List<GameEvent> drainEvents(String sessionId, Long runId) {
        requireOwned(sessionId, runId);
        return adapter.drainEvents(sessionId);
    }

    /** 归属校验后取累计指标。 */
    public com.aigamelab.gameenv.GameMetrics metrics(String sessionId, Long runId) {
        requireOwned(sessionId, runId);
        return adapter.metrics(sessionId);
    }

    /** 结束会话并解除登记（stop 幂等）。 */
    public void stop(String sessionId) {
        sessionOwner.remove(sessionId);
        sessionConfigVersion.remove(sessionId);
        adapter.stop(sessionId);
    }

    // ------------------------------------------------------------------

    private void requireOwned(String sessionId, Long runId) {
        Long owner = sessionOwner.get(sessionId);
        if (owner == null) {
            throw new UnknownSessionException(sessionId);
        }
        if (owner != NO_RUN && runId != null && !owner.equals(runId)) {
            throw new IllegalArgumentException(
                    "会话 " + sessionId + " 不属于当前 Run（防跨 run 操作）");
        }
    }

    /** Tool 层返回给 LLM 的句柄视图（不含时间元数据，含显式绑定的 configVersion）。 */
    public record GameSessionHandleLike(String sessionId, String adapterId, String configVersion) {
    }
}
