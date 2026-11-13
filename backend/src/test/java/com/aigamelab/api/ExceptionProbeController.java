package com.aigamelab.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aigamelab.common.BusinessException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 仅存在于测试源码的异常探针端点：让 MockMvc/HTTP 测试能真实触发每一类异常，
 * 验证 GlobalExceptionHandler 的完整 HTTP 出口行为。
 * 不属于生产代码，不引入任何业务功能（P0-2 范围约束）。
 */
@RestController
@RequestMapping("/test/probe")
@Validated
public class ExceptionProbeController {

    private final JdbcTemplate jdbc;

    public ExceptionProbeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Body(@NotBlank String name) {
    }

    /** 200 正常包络 */
    @GetMapping("/ok")
    public String ok() {
        return "fine";
    }

    /** 参数校验异常 → 40001 */
    @PostMapping("/validate")
    public String validate(@Valid @RequestBody Body body) {
        return body.name();
    }

    /** 业务异常（资源不存在）→ 自带 code */
    @GetMapping("/not-found")
    public String notFound() {
        throw BusinessException.notFound("任务 999 不存在");
    }

    /** 业务异常（状态冲突）→ 40901 */
    @GetMapping("/conflict")
    public String conflict() {
        throw BusinessException.conflict("状态机违规");
    }

    /**
     * UNIQUE 端到端：真实插入 experiment 撞 UNIQUE(config_version,seed,count,scenario)，
     * SQLite 错误经 JdbcConfig 翻译为 DuplicateKeyException 再由全局处理器出口 → 409/40901
     */
    @PostMapping("/unique-violation")
    public String uniqueViolation() {
        jdbc.update("""
                INSERT INTO experiment(config_version, seed, simulation_count, scenario)
                VALUES('probe-v', 42, 100, 'ALL_BUILDS')
                """);
        // 第二次同键插入必撞 UNIQUE
        jdbc.update("""
                INSERT INTO experiment(config_version, seed, simulation_count, scenario)
                VALUES('probe-v', 42, 100, 'ALL_BUILDS')
                """);
        return "unreachable";
    }

    /** 非 UNIQUE 数据完整性违规 → 40901 */
    @PostMapping("/integrity-violation")
    public String integrityViolation() {
        // task_id=999999 无对应行且 FK 开启 → NOT NULL/FK 类约束冲突（非 UNIQUE）
        jdbc.update("INSERT INTO run(task_id) VALUES(?)", 999999L);
        return "unreachable";
    }

    /** 未知运行时异常 → 50000 且零泄漏 */
    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException(
                "SECRET leak check: SELECT * FROM sqlite_master; [SQLITE_CONSTRAINT_UNIQUE] java.lang.FakeInternal");
    }
}
