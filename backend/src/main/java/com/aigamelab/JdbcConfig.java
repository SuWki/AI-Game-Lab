package com.aigamelab;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLErrorCodes;

/**
 * SQLite 异常翻译配置。
 * Spring 默认的 sql-error-codes.xml 不含 SQLite 方言，约束冲突（error code 19）
 * 会落成 UncategorizedSQLException；这里注册翻译规则使其归一化为
 * DataIntegrityViolationException——这是蓝图 §13/P0-3 的前置条件。
 *
 * 精确性说明（实测 sqlite-jdbc 3.46.1.3）：UNIQUE / NOT NULL / CHECK / FK 冲突的
 * errorCode 全部是 19，无法用错误码区分；但异常消息带独立标记
 * "[SQLITE_CONSTRAINT_UNIQUE]"。因此 UNIQUE 单独翻译为 DuplicateKeyException
 * （其子类，供 ExperimentRepository 幂等复用判断），其余 19 仍为普通完整性违例。
 */
@Configuration
public class JdbcConfig {

    /** sqlite-jdbc 对 UNIQUE 冲突的消息标记（实测稳定出现于 message 首部） */
    private static final String SQLITE_UNIQUE_MARKER = "SQLITE_CONSTRAINT_UNIQUE";
    /** 兜底：SQLite 部分版本/场景输出小写短语而非标记常量 */
    private static final String SQLITE_UNIQUE_PHRASE = "unique constraint failed";

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);

        SQLErrorCodes codes = new SQLErrorCodes();
        codes.setDatabaseProductName("SQLite");
        // SQLite 主错误码：19 = SQLITE_CONSTRAINT（含 UNIQUE/NOT NULL/CHECK/FK）
        codes.setDataIntegrityViolationCodes(new String[] {"19"});

        SQLErrorCodeSQLExceptionTranslator translator = new SQLErrorCodeSQLExceptionTranslator(codes) {
            @Override
            protected DataAccessException customTranslate(String task, String sql, SQLException ex) {
                // 仅 UNIQUE 冲突升级为 DuplicateKeyException；NOT NULL/CHECK/FK 不误判
                String msg = ex.getMessage();
                if (msg != null
                        && (msg.contains(SQLITE_UNIQUE_MARKER)
                                || msg.toLowerCase().contains(SQLITE_UNIQUE_PHRASE))) {
                    return new DuplicateKeyException(task + "; SQL [" + sql + "]; " + ex.getMessage(), ex);
                }
                return null; // 其余走默认错误码映射（19→DataIntegrityViolationException）
            }
        };
        template.setExceptionTranslator(translator);
        return template;
    }
}
