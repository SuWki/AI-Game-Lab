package com.aigamelab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P0-2 验收测试：真实 HTTP 请求验证统一包络与六码异常映射。
 * 覆盖：成功包络 / 参数校验 40001 / 资源不存在 40401 / 业务冲突 40901 /
 * UNIQUE→DuplicateKeyException→409/40901 全链路（SQLite 真实约束触发）/
 * 非 UNIQUE 完整性冲突 → 40901 / 未知异常 → 500 且零信息泄漏。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("P0-2 统一响应包络与异常体系")
class ApiResponseEnvelopeTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    /** 唯一临时库文件：与 ApplicationTests 同策略，每 JVM 独立 */
    private static final Path DB = tempDb();

    private static Path tempDb() {
        try {
            return Files.createTempFile("agl-p02-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException("cannot create temp db", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    @BeforeEach
    void cleanProbeTables() {
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> get(String url) {
        return rest.getForEntity(url, Map.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> postJson(String url, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url, new HttpEntity<>(json, headers), Map.class);
    }

    /** 错误包络断言：HTTP 状态、code、message 非空且不含异常类名、data 为 null */
    @SuppressWarnings("rawtypes")
    private void assertErrorEnvelope(ResponseEntity<Map> resp, HttpStatus status, int code) {
        assertThat(resp.getStatusCode()).isEqualTo(status);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(code);
        Object msg = body.get("message");
        assertThat(msg).isInstanceOf(String.class);
        assertThat((String) msg).isNotBlank();
        assertThat((String) msg).doesNotContain("Exception");
        assertThat(body.containsKey("data")).isTrue();
        assertThat(body.get("data")).isNull();
    }

    @Test
    void successEnvelopeShape() {
        ResponseEntity<Map> resp = get("/api/dashboard/stats");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = resp.getBody();
        assertThat(body.get("code")).isEqualTo(0);
        assertThat(body.get("message")).isEqualTo("ok");
        assertThat(body.get("data")).isInstanceOf(Map.class);
    }

    @Test
    void validationFailureReturns40001() {
        // 空 JSON：name 缺失 → @NotBlank 校验失败 → 400/40001
        assertErrorEnvelope(postJson("/test/probe/validate", "{}"), HttpStatus.BAD_REQUEST, 40001);
    }

    @Test
    void malformedBodyReturns40001() {
        // 非 JSON 文本 → HttpMessageNotReadable → 400/40001
        assertErrorEnvelope(postJson("/test/probe/validate", "not-json"), HttpStatus.BAD_REQUEST, 40001);
    }

    @Test
    void businessNotFoundReturns40401() {
        assertErrorEnvelope(get("/test/probe/not-found"), HttpStatus.NOT_FOUND, 40401);
    }

    @Test
    void businessConflictReturns40901() {
        assertErrorEnvelope(get("/test/probe/conflict"), HttpStatus.CONFLICT, 40901);
    }

    @Test
    void uniqueViolationEndToEndReturnsConflict() {
        // 端到端：真实 SQLite UNIQUE 冲突 → JdbcConfig 翻译为 DuplicateKeyException → 409/40901
        assertErrorEnvelope(postJson("/test/probe/unique-violation", null), HttpStatus.CONFLICT, 40901);
    }

    @Test
    void integrityViolationReturnsConflict() {
        // FK 违规（非 UNIQUE）→ DataIntegrityViolationException → 409/40901
        assertErrorEnvelope(postJson("/test/probe/integrity-violation", null), HttpStatus.CONFLICT, 40901);
    }

    @Test
    void unknownExceptionReturns50000WithoutLeak() {
        ResponseEntity<Map> resp = get("/test/probe/boom");
        assertErrorEnvelope(resp, HttpStatus.INTERNAL_SERVER_ERROR, 50000);

        String raw = String.valueOf(resp.getBody());
        // 泄漏断言：异常消息标记、SQL 关键字、内部类名均不得出现在响应体
        assertThat(raw).doesNotContain("SECRET");
        assertThat(raw).doesNotContain("SELECT");
        assertThat(raw).doesNotContain("SQLITE_");
        assertThat(raw).doesNotContain("java.lang");
        assertThat(raw).doesNotContain("IllegalState");
    }

    @Test
    void unknownPathReturns40401Envelope() {
        assertErrorEnvelope(get("/api/no-such-endpoint"), HttpStatus.NOT_FOUND, 40401);
    }
}
