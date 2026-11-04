package com.fgc.emulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorServerTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = LocalDateTime.of(2026, 11, 3, 12, 0).atZone(SEOUL).toInstant();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private EmulatorServer server;
    private SimClock clock;
    private String base;

    private void start(EmulatorConfig config) throws Exception {
        clock = SimClock.manual(NOW);
        VirtualSensor sensor = new VirtualSensor(config, NOW.minus(Duration.ofDays(2)));
        server = new EmulatorServer(config, clock, sensor, "patient01", "test-only-pw");
        base = "http://127.0.0.1:" + server.start("127.0.0.1", 0);
    }

    @AfterEach
    void stop() { if (server != null) server.stop(); }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode login() throws Exception {
        HttpResponse<String> r = post("/api/v1/auth/token", "{\"username\":\"patient01\",\"password\":\"test-only-pw\"}");
        assertEquals(200, r.statusCode());
        return json.readTree(r.body());
    }

    private static String enc(Instant t) { return URLEncoder.encode(t.toString(), StandardCharsets.UTF_8); }

    @Test
    void rejectsWrongPasswordAndMissingToken() throws Exception {
        start(EmulatorConfig.defaults(1, Scenario.NORMAL));
        assertEquals(401, post("/api/v1/auth/token", "{\"username\":\"patient01\",\"password\":\"nope\"}").statusCode());
        assertEquals(401, get("/api/v1/me", null).statusCode());
        assertEquals(401, get("/api/v1/me", "forged").statusCode());
    }

    @Test
    void paginationReturnsEveryUploadedReadingOnceAndHidesNotYetUploaded() throws Exception {
        start(EmulatorConfig.defaults(1, Scenario.NORMAL));
        String token = login().get("accessToken").asText();
        Instant from = NOW.minus(Duration.ofHours(6));
        Set<String> ids = new LinkedHashSet<>();
        List<JsonNode> all = new ArrayList<>();
        String cursor = null;
        do {
            String path = "/api/v1/glucose?from=" + enc(from) + "&limit=20" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = json.readTree(get(path, token).body());
            page.get("items").forEach(n -> { all.add(n); ids.add(n.get("id").asText()); });
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);
        assertEquals(all.size(), ids.size(), "no duplicates without fault injection");
        // 6시간 = 72개. 업로드 지연(1~3분) 때문에 마지막 측정값은 아직 안 보일 수 있다.
        assertTrue(all.size() >= 71 && all.size() <= 72, "size=" + all.size());
        for (JsonNode n : all) {
            assertFalse(Instant.parse(n.get("uploadedAt").asText()).isAfter(NOW));
            assertTrue(n.get("measuredAt").asText().endsWith("+09:00"), "measuredAt carries local offset");
        }
    }

    @Test
    void expiredTokenIsRejectedAndRefreshWorks() throws Exception {
        start(EmulatorConfig.defaults(1, Scenario.NORMAL));
        JsonNode t = login();
        assertEquals(200, get("/api/v1/sensors/current", t.get("accessToken").asText()).statusCode());
        clock.advance(Duration.ofMinutes(61));
        assertEquals(401, get("/api/v1/sensors/current", t.get("accessToken").asText()).statusCode());
        HttpResponse<String> r = post("/api/v1/auth/refresh", "{\"refreshToken\":\"" + t.get("refreshToken").asText() + "\"}");
        assertEquals(200, r.statusCode());
        String fresh = json.readTree(r.body()).get("accessToken").asText();
        assertEquals(200, get("/api/v1/sensors/current", fresh).statusCode());
        // 같은 refresh token 재사용 불가 (rotation)
        assertEquals(401, post("/api/v1/auth/refresh", "{\"refreshToken\":\"" + t.get("refreshToken").asText() + "\"}").statusCode());
    }

    @Test
    void rateLimitReturns429WithRetryAfter() throws Exception {
        start(EmulatorConfig.defaults(1, Scenario.NORMAL).withRateLimit(3));
        String token = login().get("accessToken").asText();
        for (int i = 0; i < 3; i++) assertEquals(200, get("/api/v1/me", token).statusCode());
        HttpResponse<String> r = get("/api/v1/me", token);
        assertEquals(429, r.statusCode());
        assertEquals("60", r.headers().firstValue("Retry-After").orElseThrow());
    }

    @Test
    void injectedDuplicatesAndServerErrorsAppear() throws Exception {
        start(EmulatorConfig.defaults(1, Scenario.NORMAL).withFaults(0.3, 0.3));
        String token = login().get("accessToken").asText();
        int errors = 0, dupPages = 0;
        for (int i = 0; i < 20; i++) {
            HttpResponse<String> r = get("/api/v1/glucose?from=" + enc(NOW.minus(Duration.ofHours(2))), token);
            if (r.statusCode() == 503) { errors++; continue; }
            JsonNode items = json.readTree(r.body()).get("items");
            Set<String> ids = new HashSet<>();
            items.forEach(n -> ids.add(n.get("id").asText()));
            if (ids.size() < items.size()) dupPages++;
        }
        assertTrue(errors > 0, "some 503s injected");
        assertTrue(dupPages > 0, "some duplicate items injected");
    }

    @Test
    void healthConnectRecordsUseBloodGlucoseRecordShapeAndLag() throws Exception {
        start(EmulatorConfig.defaults(1, Scenario.NORMAL));
        String token = login().get("accessToken").asText();
        JsonNode recs = json.readTree(get("/hc/v1/blood-glucose-records?from=" + enc(NOW.minus(Duration.ofHours(1))), token).body())
                .get("records");
        assertTrue(recs.size() > 0);
        JsonNode last = recs.get(recs.size() - 1);
        assertEquals("BloodGlucoseRecord", last.get("recordType").asText());
        assertEquals("+09:00", last.get("zoneOffset").asText());
        assertTrue(last.get("level").get("inMilligramsPerDeciliter").asInt() > 0);
        assertEquals("SPECIMEN_SOURCE_INTERSTITIAL_FLUID", last.get("specimenSource").asText());
        Instant lastTime = Instant.parse(last.get("time").asText());
        assertFalse(lastTime.isAfter(NOW.minus(Duration.ofMinutes(10))), "Health Connect lags by configured delay");
    }

    @Test
    void latestAndBadRequest() throws Exception {
        start(EmulatorConfig.defaults(1, Scenario.NORMAL));
        String token = login().get("accessToken").asText();
        JsonNode latest = json.readTree(get("/api/v1/glucose/latest", token).body()).get("item");
        assertTrue(latest.has("value"));
        assertEquals(400, get("/api/v1/glucose", token).statusCode());
        assertEquals(404, get("/api/v1/nope", token).statusCode());
    }
}
