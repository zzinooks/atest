package com.fgc.backend;

import com.fgc.backend.alert.AlertEngine;
import com.fgc.backend.notify.NotificationDispatcher;
import com.fgc.backend.source.SyncService;
import com.fgc.emulator.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STEP 2 대체 PoC: 가상 CGM 에뮬레이터(별도 HTTP 서버) → EmulatorCloudAdapter → PostgreSQL → 알람 → 전송 큐.
 * 에뮬레이터의 "정답 데이터"와 저장 결과를 1:1 대조해 정확성·측정 시각·중복·누락·지연을 수치로 검증한다.
 */
class EmulatorEndToEndIT extends IntegrationTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    @Autowired SyncService sync;
    @Autowired AlertEngine engine;
    @Autowired NotificationDispatcher dispatcher;

    EmulatorServer server;
    SimClock emuClock;
    VirtualSensor sensor;
    String p, pid, fam;

    static Instant kst(int day, int h, int m) { return LocalDateTime.of(2026, 11, day, h, m).atZone(SEOUL).toInstant(); }

    void start(EmulatorConfig cfg, Instant at) throws Exception {
        clock.set(at);
        emuClock = SimClock.manual(at);
        sensor = new VirtualSensor(cfg, at.minus(Duration.ofDays(2)));
        server = new EmulatorServer(cfg, emuClock, sensor, "patient01", "emu-test-pw");
        int port = server.start("127.0.0.1", 0);
        p = signup("e2e@test.dev", "환자");
        pid = patient(p);
        fam = signup("e2e-fam@test.dev", "보호자");
        link(p, pid, fam, true, true, false);
        registerDevice(fam, "tok-fam");
        Res src = post("/api/v1/patients/" + pid + "/data-sources", p, Map.of("type", "EMULATOR",
                "baseUrl", "http://127.0.0.1:" + port, "username", "patient01", "password", "emu-test-pw"));
        assertEquals(201, src.status(), src.body().toString());
        assertFalse(src.body().toString().contains("emu-test-pw"), "credential never echoed");
    }

    @AfterEach
    void stop() { if (server != null) server.stop(); }

    void advanceBoth(Duration d) {
        clock.advance(d);
        emuClock.advance(d);
    }

    void syncNow() {
        sync.syncAll();
        engine.checkNoData();
    }

    /** 에뮬레이터 정답: 지금까지 클라우드에 업로드된 측정값 */
    Map<String, Reading> truth(Instant from) {
        Instant now = emuClock.now();
        return sensor.measuredBetween(from, now).stream().filter(r -> !r.uploadedAt().isAfter(now))
                .collect(Collectors.toMap(Reading::externalId, r -> r, (a, b) -> a, LinkedHashMap::new));
    }

    record Stored(String id, Integer value, String flag, Instant measured, Instant received) {}

    List<Stored> stored() {
        return db.query("SELECT external_id, value_mgdl, flag, measured_at, received_at FROM glucose_readings ORDER BY measured_at",
                (rs, i) -> new Stored(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()));
    }

    void assertMatchesTruth(Instant from, Map<String, Object> metrics) {
        Map<String, Reading> truth = truth(from);
        List<Stored> got = stored();
        Set<String> ids = got.stream().map(Stored::id).collect(Collectors.toSet());
        assertEquals(got.size(), ids.size(), "no duplicate rows");
        Set<String> missing = new TreeSet<>(truth.keySet());
        missing.removeAll(ids);
        assertEquals(Set.of(), missing, "no missing readings");
        int valueMismatch = 0, timeMismatch = 0;
        for (Stored s : got) {
            Reading t = truth.get(s.id());
            assertNotNull(t, "stored reading exists in truth: " + s.id());
            if (!Objects.equals(t.valueMgdl(), s.value()) || !t.flag().name().equals(s.flag())) valueMismatch++;
            if (!t.measuredAt().equals(s.measured())) timeMismatch++;
        }
        assertEquals(0, valueMismatch, "values identical to source");
        assertEquals(0, timeMismatch, "measuredAt identical to source");
        metrics.put("readings", got.size());
        metrics.put("missing", missing.size());
        metrics.put("duplicates", got.size() - ids.size());
        metrics.put("valueMismatch", valueMismatch);
        metrics.put("measuredAtMismatch", timeMismatch);
    }

    static Map<String, Object> latencyStats(List<Stored> rows, Instant since) {
        List<Long> l = rows.stream().filter(s -> !s.measured().isBefore(since))
                .map(s -> Duration.between(s.measured(), s.received()).toSeconds()).sorted().toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", l.size());
        m.put("p50Sec", l.get(l.size() / 2));
        m.put("p95Sec", l.get((int) Math.floor(l.size() * 0.95)));
        m.put("maxSec", l.get(l.size() - 1));
        return m;
    }

    static void writeMetrics(String name, Map<String, Object> m) throws Exception {
        Path dir = Path.of("build", "poc-metrics");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(m));
    }

    @Test
    void nightHypoIsCollectedAccuratelyAndAlertsFamilyWithinMinutes() throws Exception {
        Instant start = kst(3, 0, 30);
        start(EmulatorConfig.defaults(7, Scenario.NIGHT_HYPO), start);
        Instant from = start.minus(Duration.ofDays(1));
        syncNow();                                       // 첫 동기화: 24시간 backfill
        int initial = count("SELECT count(*) FROM glucose_readings");
        assertTrue(initial >= 280, "initial backfill " + initial);
        assertEquals(List.of(), db.queryForList("SELECT type FROM alerts", String.class), "backfill alone triggers no alerts");

        Instant firstLowMeasured = null, alertOpened = null;
        for (int i = 0; i < 48; i++) {                   // 4시간, 5분 간격 폴링
            advanceBoth(Duration.ofMinutes(5));
            syncNow();
            if (firstLowMeasured == null) {
                firstLowMeasured = db.query("SELECT min(measured_at) FROM glucose_readings WHERE value_mgdl <= 70 AND measured_at > ?",
                        (rs, n) -> rs.getTimestamp(1), java.sql.Timestamp.from(start)).stream().filter(Objects::nonNull)
                        .map(java.sql.Timestamp::toInstant).findFirst().orElse(null);
            }
            if (alertOpened == null) {
                alertOpened = db.query("SELECT min(opened_at) FROM alerts WHERE type IN ('LOW', 'URGENT_LOW')",
                        (rs, n) -> rs.getTimestamp(1)).stream().filter(Objects::nonNull).map(java.sql.Timestamp::toInstant).findFirst().orElse(null);
            }
        }
        assertNotNull(firstLowMeasured, "scenario produced a low");
        assertNotNull(alertOpened, "low alert opened");
        long detectionMinutes = Duration.between(firstLowMeasured, alertOpened).toMinutes();
        assertTrue(detectionMinutes <= 10, "detected within 10 min (poll 5 + upload ≤3), was " + detectionMinutes);
        // 03:30 이후 회복 → 해소
        assertEquals(0, count("SELECT count(*) FROM alerts WHERE type IN ('LOW','URGENT_LOW') AND status <> 'RESOLVED'"));
        dispatcher.dispatchDue();
        assertTrue(sender.outbox.messages().stream().anyMatch(m -> m.pushToken().equals("tok-fam")), "family notified");

        Map<String, Object> metrics = new LinkedHashMap<>();
        assertMatchesTruth(from, metrics);
        metrics.put("ingestLatency", latencyStats(stored(), start));
        metrics.put("lowDetectionMinutes", detectionMinutes);
        metrics.put("alertsOpened", db.queryForList("SELECT type FROM alerts ORDER BY opened_at", String.class));
        metrics.put("syncRuns", count("SELECT count(*) FROM sync_history"));
        metrics.put("syncFailures", count("SELECT count(*) FROM sync_history WHERE status = 'FAILED'"));
        writeMetrics("night-hypo", metrics);
    }

    @Test
    void faultsAndUploadOutageLeaveNoGapsOrDuplicates() throws Exception {
        Instant start = kst(3, 12, 0);
        start(EmulatorConfig.defaults(11, Scenario.UPLOAD_OUTAGE).withFaults(0.3, 0.2), start);
        Instant from = start.minus(Duration.ofDays(1));
        syncNow();
        for (int i = 0; i < 36; i++) {                   // 12:00~15:00
            advanceBoth(Duration.ofMinutes(5));
            syncNow();
        }
        advanceBoth(Duration.ofMinutes(5));
        syncNow();
        Map<String, Object> metrics = new LinkedHashMap<>();
        assertMatchesTruth(from, metrics);
        // 13:00~14:00 측정분은 14:00 이후에 들어왔다 = backfill 회수 확인
        int late = count("SELECT count(*) FROM glucose_readings WHERE measured_at >= ? AND measured_at < ? AND received_at >= ?",
                java.sql.Timestamp.from(kst(3, 13, 0)), java.sql.Timestamp.from(kst(3, 14, 0)), java.sql.Timestamp.from(kst(3, 14, 0)));
        assertEquals(12, late);
        assertEquals(0, count("SELECT count(*) FROM alerts WHERE type IN ('LOW','URGENT_LOW','HIGH')"),
                "backfilled values did not raise glucose alerts");
        metrics.put("backfilledDuringOutage", late);
        metrics.put("syncRuns", count("SELECT count(*) FROM sync_history"));
        metrics.put("syncFailures", count("SELECT count(*) FROM sync_history WHERE status = 'FAILED'"));
        metrics.put("ingestLatency", latencyStats(stored(), start));
        writeMetrics("faults-outage", metrics);
    }

    @Test
    void signalLossRaisesNoDataAndRecovers() throws Exception {
        Instant start = kst(3, 9, 30);
        start(EmulatorConfig.defaults(5, Scenario.SIGNAL_LOSS), start);
        syncNow();
        List<Instant> noDataOpened = new ArrayList<>();
        for (int i = 0; i < 18; i++) {                   // 09:30~11:00
            advanceBoth(Duration.ofMinutes(5));
            syncNow();
            noDataOpened.addAll(db.query("SELECT opened_at FROM alerts WHERE type = 'NO_DATA' AND status = 'OPEN'",
                    (rs, n) -> rs.getTimestamp(1).toInstant()));
        }
        assertFalse(noDataOpened.isEmpty(), "NO_DATA opened during 40-min signal loss");
        assertEquals(0, count("SELECT count(*) FROM alerts WHERE type = 'NO_DATA' AND status <> 'RESOLVED'"), "resolved after recovery");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("noDataOpenedAtKst", noDataOpened.get(0).atZone(SEOUL).toLocalTime().toString());
        writeMetrics("signal-loss", m);
    }

    @Test
    void wrongCredentialsProduceSyncErrorAlertAfterThreeFailures() throws Exception {
        Instant start = kst(3, 12, 0);
        start(EmulatorConfig.defaults(1, Scenario.NORMAL), start);
        String srcId = db.queryForObject("SELECT id::text FROM data_sources", String.class);
        db.update("UPDATE data_sources SET username = 'nobody' WHERE id = ?::uuid", srcId);
        for (int i = 0; i < 3; i++) { advanceBoth(Duration.ofMinutes(1)); syncNow(); }
        assertEquals(1, count("SELECT count(*) FROM alerts WHERE type = 'SYNC_ERROR' AND status = 'OPEN'"));
        assertEquals(3, count("SELECT consecutive_failures FROM data_sources"));
        db.update("UPDATE data_sources SET username = 'patient01' WHERE id = ?::uuid", srcId);
        advanceBoth(Duration.ofMinutes(1));
        syncNow();
        assertEquals(0, count("SELECT count(*) FROM alerts WHERE type = 'SYNC_ERROR' AND status <> 'RESOLVED'"));
        // 응답·이력에 자격증명 노출 없음
        Res list = get("/api/v1/patients/" + pid + "/data-sources", p);
        assertFalse(list.body().toString().contains("emu-test-pw"));
        assertEquals(0, count("SELECT count(*) FROM sync_history WHERE error LIKE '%emu-test-pw%'"));
    }
}
