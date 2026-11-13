package com.aigamelab.tester;

import java.util.ArrayList;
import java.util.List;

import com.aigamelab.gameenv.GameObservation;

/**
 * T1-3 / T3-4：不变量（Invariant）白名单谓词引擎。
 *
 * 设计定位（技术设计 §5）：GameObservation 的确定性字段检查器，替代 T1 FakeEnv
 * 内置的硬编码检查（注释见 FakeGameEnvironment.checkInvariants）。引擎只认识
 * 固定词表，任何不在白名单内的表达式一律视为非法并抛 {@link IllegalArgumentException}，
 * 杜绝 LLM 注入任意表达式执行（安全边界：无脚本引擎、无反射调用）。
 *
 * 词表（左操作数 字段别名 → Observation 取值；右操作数 常量或已知常量别名）：
 *   HP          → obs.hp()
 *   MaxHP       → obs.maxHp()
 *   ENEMY_COUNT → obs.enemyCount()
 *   CAP         → 200（ENEMY_CAP 常量，与 FakeGameEnvironment 同值）
 *   PROJECTILE  → obs.projectileCount()
 *   MAX_PROJECT → 500（MAX_PROJECTILE 常量）
 *   TICK        → obs.tick()
 *   SCORE       → obs.score()
 *
 * 运算符（固定集合）：>= <= > < == !=
 *
 * 特殊谓词（无运算符，独立语义）：
 *   PHASE_LEGAL → obs.phase() 必须属于 GamePhase 合法集合
 *
 * 验收（T1-3）：每条谓词正/反例各一通过；非法表达式报错明确。
 * 验收（T3-4）：evaluate 返回违反的表达式列表，由 observe/act 工具附回 Observation。
 */
public final class InvariantEngine {

    /** 环境内置上限（与 FakeGameEnvironment.ENEMY_CAP / MAX_PROJECTILE 保持一致） */
    static final int ENEMY_CAP = 200;
    static final int MAX_PROJECTILE = 500;

    private InvariantEngine() {
    }

    /**
     * 对给定 Observation 逐项求值 invariants，返回所有违反项的可读描述。
     * 空列表表示全部通过（零漏报基线）。非法表达式立即抛错（不静默跳过）。
     */
    public static List<String> evaluate(List<String> invariants, GameObservation obs) {
        List<String> violations = new ArrayList<>();
        if (invariants == null) {
            return violations;
        }
        for (String raw : invariants) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String expr = raw.trim();
            try {
                if (!check(expr, obs)) {
                    violations.add(expr + " violated: " + snapshotOf(expr, obs));
                }
            } catch (IllegalArgumentException e) {
                // 非法谓词：明确报错，交由调用方（Service/Tool）转 BusinessException
                throw new IllegalArgumentException(
                        "非法 Invariant 谓词: [" + expr + "] — " + e.getMessage());
            }
        }
        return violations;
    }

    /**
     * 单条谓词判定。右操作数若为已知常量别名（CAP/MAX_PROJECT）先解析，
     * 否则按 long/double 解析字面量。
     */
    private static boolean check(String expr, GameObservation obs) {
        if ("PHASE_LEGAL".equals(expr)) {
            return com.aigamelab.gameenv.GamePhase.isLegal(obs.phase());
        }
        String op = findOperator(expr);
        if (op == null) {
            throw new IllegalArgumentException("未识别运算符（允许 >= <= > < == != 或 PHASE_LEGAL）");
        }
        int idx = expr.indexOf(op);
        String left = expr.substring(0, idx).trim();
        String right = expr.substring(idx + op.length()).trim();
        long lhs = fieldValue(left, obs);
        // 右操作数：已知字段别名优先按 Observation 取值，否则按常量（CAP/MAX_PROJECT/数字）解析
        long rhs = isFieldAlias(right) ? fieldValue(right, obs) : constantValue(right);
        return switch (op) {
            case ">=" -> lhs >= rhs;
            case "<=" -> lhs <= rhs;
            case ">" -> lhs > rhs;
            case "<" -> lhs < rhs;
            case "==" -> lhs == rhs;
            case "!=" -> lhs != rhs;
            default -> throw new IllegalArgumentException("不支持的运算符: " + op);
        };
    }

    /** 从左起匹配最长运算符（避免 <= / >= 被拆成 < / >） */
    private static String findOperator(String expr) {
        String[] ops = {">=", "<=", "==", "!=", ">", "<"};
        for (String op : ops) {
            if (expr.contains(op)) {
                return op;
            }
        }
        return null;
    }

    private static long fieldValue(String field, GameObservation obs) {
        return switch (field) {
            case "HP" -> obs.hp();
            case "MaxHP" -> obs.maxHp();
            case "ENEMY_COUNT" -> obs.enemyCount();
            case "PROJECTILE" -> obs.projectileCount();
            case "TICK" -> obs.tick();
            case "SCORE" -> obs.score();
            default -> throw new IllegalArgumentException("未识别字段: " + field
                    + "（允许 HP/MaxHP/ENEMY_COUNT/PROJECTILE/TICK/SCORE/CAP/MAX_PROJECT）");
        };
    }

    /** 是否已知字段别名（用于区分 RHS 是字段引用还是常量） */
    private static boolean isFieldAlias(String token) {
        return switch (token) {
            case "HP", "MaxHP", "ENEMY_COUNT", "PROJECTILE", "TICK", "SCORE" -> true;
            default -> false;
        };
    }

    private static long constantValue(String token) {
        return switch (token) {
            case "CAP" -> ENEMY_CAP;
            case "MAX_PROJECT" -> MAX_PROJECTILE;
            default -> {
                try {
                    yield Long.parseLong(token);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("未识别常量: " + token
                            + "（允许数字字面量或 CAP/MAX_PROJECT）");
                }
            }
        };
    }

    /** 违规描述中附带当前观测值（便于 Finding 的 actual 字段复用） */
    private static String snapshotOf(String expr, GameObservation obs) {
        String op = findOperator(expr);
        if (op == null) {
            return "phase=" + obs.phase();
        }
        int idx = expr.indexOf(op);
        String left = expr.substring(0, idx).trim();
        return left + "=" + fieldValue(left, obs);
    }

    /**
     * T3-4 链路入口：以 Tester 内置默认白名单（技术设计 §5 全量谓词）重算给定
     * Observation 的不变量，并把结果与原快照已有的 violations 合并去重，返回新快照。
     *
     * 用途：observe/act 工具在返回 Observation 前调用，使 Tester 层的不变量判定
     * 覆盖环境内置检查（FakeGameEnvironment.checkInvariants 已填基础项），
     * 形成"零漏报"统一出口。FakeGameEnvironment 保持冻结不动。
     */
    public static GameObservation enrich(GameObservation obs) {
        List<String> merged = new ArrayList<>(obs.invariantViolations());
        for (String v : evaluate(DEFAULT_INVARIANTS, obs)) {
            if (!merged.contains(v)) {
                merged.add(v);
            }
        }
        return withViolations(obs, merged);
    }

    /** 重建 Observation（record 不可变，无 wither），仅替换 invariantViolations 字段 */
    private static GameObservation withViolations(GameObservation o, List<String> violations) {
        return new GameObservation(o.sessionId(), o.tick(), o.phase(), o.hp(), o.maxHp(),
                o.positionX(), o.positionY(), o.enemyCount(), o.projectileCount(),
                o.score(), o.level(), o.upgrades(), o.flags(), violations);
    }

    /** Tester 内置默认白名单（技术设计 §5；与 TestPlanService.DEFAULT_INVARIANTS 同源，
     *  并补充 PROJECTILE<=MAX_PROJECT 以覆盖环境内置检查器全集） */
    static final List<String> DEFAULT_INVARIANTS = List.of(
            "HP>=0", "HP<=MaxHP", "ENEMY_COUNT<=CAP",
            "PROJECTILE<=MAX_PROJECT", "TICK>=0", "PHASE_LEGAL");
}
