package com.fgc.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

/** 실제 HTTP + 실제 PostgreSQL 통합 테스트 기반. 각 테스트 전에 모든 테이블을 비운다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {
    public static final Instant T0 = Instant.parse("2026-11-03T03:00:00Z"); // 12:00 KST

    @TestConfiguration
    public static class TestBeans {
        @Bean @Primary MutableClock testClock() { return new MutableClock(T0); }
        @Bean @Primary FlakySender testSender() { return new FlakySender(); }
    }

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate db;
    @Autowired protected MutableClock clock;
    @Autowired protected FlakySender sender;
    protected final ObjectMapper json = new ObjectMapper();
    protected final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanDatabase() {
        db.execute("""
                TRUNCATE users, refresh_tokens, patient_profiles, family_groups, family_members, sharing_invitations,
                  sharing_consents, devices, data_sources, glucose_readings, alert_rules, alerts, notification_deliveries,
                  health_events, sync_history, notion_sync, audit_logs CASCADE""");
        clock.set(T0);
        sender.forced = null;
        sender.outbox.clear();
    }

    public record Res(int status, JsonNode body, HttpResponse<String> raw) {}

    protected Res call(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json.writeValueAsString(body)));
            if (token != null) b.header("Authorization", "Bearer " + token);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n = r.body() == null || r.body().isBlank() ? json.nullNode() : json.readTree(r.body());
            return new Res(r.statusCode(), n, r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected Res get(String path, String token) { return call("GET", path, token, null); }
    protected Res post(String path, String token, Object body) { return call("POST", path, token, body); }

    /** 가입 → access token */
    protected String signup(String email, String name) {
        Res r = post("/api/v1/auth/signup", null, Map.of("email", email, "password", "password-123", "displayName", name));
        if (r.status() != 201) throw new IllegalStateException("signup " + r.status() + " " + r.body());
        return r.body().get("accessToken").asText();
    }

    protected String login(String email) {
        Res r = post("/api/v1/auth/login", null, Map.of("email", email, "password", "password-123"));
        if (r.status() != 200) throw new IllegalStateException("login " + r.status());
        return r.body().get("accessToken").asText();
    }

    protected String patient(String token) {
        Res r = post("/api/v1/me/patient-profile", token, Map.of());
        if (r.status() != 201) throw new IllegalStateException("profile " + r.status());
        return r.body().get("patientId").asText();
    }

    protected void registerDevice(String token, String push) {
        Res r = post("/api/v1/me/devices", token, Map.of("pushToken", push, "platform", "ANDROID"));
        if (r.status() != 200) throw new IllegalStateException("device " + r.status());
    }

    /** 환자가 초대 → 가족 수락 */
    protected void link(String patientToken, String pid, String familyToken, boolean view, boolean alerts, boolean notes) {
        Res inv = post("/api/v1/patients/" + pid + "/invitations", patientToken, Map.of("relation", "자녀",
                "permissions", Map.of("viewGlucose", view, "receiveAlerts", alerts, "writeNotes", notes)));
        if (inv.status() != 201) throw new IllegalStateException("invite " + inv.status() + inv.body());
        Res acc = post("/api/v1/invitations/accept", familyToken, Map.of("code", inv.body().get("code").asText()));
        if (acc.status() != 200) throw new IllegalStateException("accept " + acc.status());
    }

    protected Map<String, Object> reading(String id, Integer value, String flag, Instant at) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("externalId", id);
        m.put("valueMgdl", value);
        m.put("flag", flag);
        m.put("measuredAt", at.atOffset(java.time.ZoneOffset.ofHours(9)).toString());
        return m;
    }

    protected Res upload(String token, String pid, java.util.List<Map<String, Object>> readings) {
        return post("/api/v1/patients/" + pid + "/glucose/batch", token, Map.of("source", "HEALTH_CONNECT", "readings", readings));
    }

    protected int count(String sql, Object... args) {
        return db.queryForObject(sql, Integer.class, args);
    }
}
