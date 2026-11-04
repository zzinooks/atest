package com.fgc.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fgc.backend.notion.NotionSyncService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Notion 연동: api.notion.com 이 이 환경의 네트워크 정책으로 차단되어 있어, 로컬 스텁 서버로 요청 형식·재시도·실패 격리를 검증한다.
 * 실제 Notion 워크스페이스 기록은 미검증 (토큰·네트워크 허용 필요).
 */
class NotionSyncIT extends IntegrationTest {
    record Captured(String method, String path, Map<String, String> headers, String body) {}

    static final List<Captured> requests = new CopyOnWriteArrayList<>();
    static final AtomicInteger failNext = new AtomicInteger();
    static volatile int failStatus = 429;
    static final HttpServer stub;

    static {
        try {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/", ex -> {
                Map<String, String> h = new HashMap<>();
                ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
                requests.add(new Captured(ex.getRequestMethod(), ex.getRequestURI().getPath(), h,
                        new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] out;
                int status;
                if (failNext.getAndDecrement() > 0) {
                    status = failStatus;
                    ex.getResponseHeaders().add("Retry-After", "0.01");
                    out = "{\"object\":\"error\"}".getBytes();
                } else {
                    status = 200;
                    out = ("{\"object\":\"page\",\"id\":\"page-" + requests.size() + "\"}").getBytes();
                }
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            stub.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("fgc.notion.base-url", () -> "http://127.0.0.1:" + stub.getAddress().getPort());
        r.add("fgc.notion.token", () -> "secret_test_token");
        r.add("fgc.notion.daily-database-id", () -> "db-daily");
        r.add("fgc.notion.weekly-database-id", () -> "db-weekly");
    }

    @AfterAll
    static void stopStub() { stub.stop(0); }

    @Autowired NotionSyncService notion;
    String p, pid;

    @BeforeEach
    void setup() {
        requests.clear();
        failNext.set(0);
        p = signup("notion@test.dev", "노션환자");
        pid = patient(p);
        assertEquals(200, call("PUT", "/api/v1/patients/" + pid + "/notion?enabled=true", p, null).status());
        // 어제(KST 11/2) 데이터 + 기록
        List<Map<String, Object>> rs = new ArrayList<>();
        java.time.Instant dayStart = LocalDate.of(2026, 11, 2).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        for (int i = 0; i < 288; i++) rs.add(reading("n" + i, 90 + (i % 50), "NORMAL", dayStart.plus(Duration.ofMinutes(5L * i))));
        upload(p, pid, rs);
        post("/api/v1/patients/" + pid + "/events", p, Map.of("type", "MEAL", "occurredAt", dayStart.plus(Duration.ofHours(8)).toString(),
                "title", "아침 식사", "carbsG", 60));
        requests.clear();
    }

    @Test
    void dailySummaryPageHasStatsPropertiesAndEvents() throws Exception {
        assertEquals(1, notion.syncDailyForAll());
        assertEquals(1, requests.size());
        Captured c = requests.get(0);
        assertEquals("POST", c.method());
        assertEquals("/v1/pages", c.path());
        assertEquals("Bearer secret_test_token", c.headers().get("authorization"));
        assertEquals("2022-06-28", c.headers().get("notion-version"));
        JsonNode b = json.readTree(c.body());
        assertEquals("db-daily", b.at("/parent/database_id").asText());
        assertEquals("2026-11-02 혈당 요약", b.at("/properties/Name/title/0/text/content").asText());
        assertEquals(288, b.at("/properties/Readings/number").asInt());
        assertEquals(100.0, b.at("/properties/Coverage/number").asDouble(), 0.01);
        assertTrue(b.at("/properties/TIR/number").asDouble() > 0);
        assertTrue(c.body().contains("아침 식사"));
        assertTrue(c.body().contains("의료적 판단이 아닙니다"));

        // 재실행 → 같은 날짜는 PATCH 로 갱신 (중복 페이지 없음)
        requests.clear();
        assertTrue(notion.sync(UUID.fromString(pid), NotionSyncService.Period.DAY, LocalDate.of(2026, 11, 2)));
        assertEquals("PATCH", requests.get(0).method());
        assertEquals("/v1/pages/page-1", requests.get(0).path());
    }

    @Test
    void rateLimitIsRetriedAndPersistentFailureIsIsolated() {
        failNext.set(1);
        failStatus = 429;
        assertEquals(1, notion.syncDailyForAll());
        assertEquals(2, requests.size()); // 429 → 재시도 성공

        requests.clear();
        failNext.set(10);
        failStatus = 503;
        clock.advance(Duration.ofDays(1)); // 다음 날 (11/3 요약)
        p = login("notion@test.dev");
        assertEquals(0, notion.syncDailyForAll()); // 예외 없이 실패 기록
        assertEquals(1, count("SELECT count(*) FROM notion_sync WHERE status = 'FAILED'"));
        // Notion 실패와 무관하게 혈당 저장·조회는 정상
        assertEquals(200, upload(p, pid, List.of(reading("after", 100, "NORMAL", clock.instant()))).status());
        assertEquals("FRESH", get("/api/v1/patients/" + pid + "/glucose/latest", p).body().get("freshness").asText());

        // 다음 실행에서 실패분 재시도
        failNext.set(0);
        requests.clear();
        notion.syncDailyForAll();
        assertEquals(0, count("SELECT count(*) FROM notion_sync WHERE status = 'FAILED'"));
    }

    @Test
    void weeklySummaryAndConsentRespected() {
        clock.set(LocalDate.of(2026, 11, 9).atTime(2, 0).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant());
        assertEquals(1, notion.syncWeeklyForAll());
        assertTrue(requests.get(0).body().contains("db-weekly"));
        assertTrue(requests.get(0).body().contains("주간 혈당 요약"));
        // 환자가 동의 해제하면 보내지 않는다
        p = login("notion@test.dev");
        call("PUT", "/api/v1/patients/" + pid + "/notion?enabled=false", p, null);
        requests.clear();
        assertEquals(0, notion.syncDailyForAll());
        assertEquals(0, requests.size());
    }
}
