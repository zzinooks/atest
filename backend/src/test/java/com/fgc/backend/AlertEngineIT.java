package com.fgc.backend;

import com.fgc.backend.alert.AlertEngine;
import com.fgc.backend.notify.NotificationDispatcher;
import com.fgc.backend.notify.NotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AlertEngineIT extends IntegrationTest {
    @Autowired AlertEngine engine;
    @Autowired NotificationDispatcher dispatcher;

    String p, pid, fam, noAlertFam;
    int seq;

    @BeforeEach
    void setup() {
        p = signup("ap@test.dev", "김환자");
        pid = patient(p);
        fam = signup("af@test.dev", "가족");
        noAlertFam = signup("an@test.dev", "조회만");
        link(p, pid, fam, true, true, false);
        link(p, pid, noAlertFam, true, false, false);
        registerDevice(p, "tok-patient");
        registerDevice(fam, "tok-family");
        registerDevice(noAlertFam, "tok-noalert");
        // 연결 알림(SHARE_CHANGE) 전송분 정리
        dispatcher.dispatchDue();
        sender.outbox.clear();
        seq = 0;
    }

    /** 지금(clock) 시각에 측정된 값 하나 업로드 */
    void now(Integer v) {
        upload(p, pid, List.of(reading("x" + (seq++), v, v == null ? "LOW_OUT_OF_RANGE" : "NORMAL", clock.instant())));
    }

    void step(Integer v) {
        clock.advance(Duration.ofMinutes(5));
        now(v);
    }

    List<String> active() {
        return db.queryForList("SELECT type FROM alerts WHERE status <> 'RESOLVED' ORDER BY type", String.class);
    }

    @Test
    void lowAlertNotifiesPatientAndAlertFamilyOnlyWithoutGlucoseNumbers() {
        now(110);
        step(68);
        assertEquals(List.of("LOW"), active());
        assertEquals(2, dispatcher.dispatchDue()); // 환자 + 알림 권한 가족. 조회만 가능한 가족은 제외
        List<String> tokens = sender.outbox.messages().stream().map(m -> m.pushToken()).sorted().toList();
        assertEquals(List.of("tok-family", "tok-patient"), tokens.stream().distinct().toList());
        sender.outbox.messages().forEach(m -> {
            assertFalse(m.body().matches(".*\\d{2,3}.*"), "no glucose numbers in push: " + m.body());
            assertEquals("ALERT", m.data().get("kind"));
        });
        assertTrue(sender.outbox.messages().stream().anyMatch(m -> m.body().contains("김환자님")));
    }

    @Test
    void urgentLowReplacesLowAndResolvesOnRecovery() {
        now(75);
        step(66);
        assertEquals(List.of("LOW"), active());
        step(52);
        assertEquals(List.of("URGENT_LOW"), active());
        step(null); // LO 플래그
        assertEquals(List.of("URGENT_LOW"), active());
        step(60);   // 아직 low 이하 → URGENT 유지, LOW 새로 안 엶
        assertEquals(List.of("URGENT_LOW"), active());
        step(85);
        // 저혈당 알림은 해소. 15분 전 52 → 85 는 +2.2/분이라 급상승 알림은 정상 동작
        assertEquals(List.of("RAPID_RISE"), active());
    }

    @Test
    void onlyOneOpenAlertPerTypeAndCooldownPreventsFlapping() {
        now(100);
        step(65);
        step(64);
        step(63);
        assertEquals(1, count("SELECT count(*) FROM alerts WHERE type = 'LOW'"));
        step(90);                       // 해소
        step(65);                       // 쿨다운(15분) 안 → 새 알림 없음
        assertEquals(List.of(), active());
        clock.advance(Duration.ofMinutes(15));
        now(64);
        assertEquals(List.of("LOW"), active());
        assertEquals(2, count("SELECT count(*) FROM alerts WHERE type = 'LOW'"));
    }

    @Test
    void highAndRapidChangeUseOwnComputedRate() {
        now(150);
        step(150);
        step(160);
        step(185);                      // 15분 전(150) 대비 +35 → 2.33/분 ≥ 2
        assertEquals(List.of("RAPID_RISE"), active());
        step(260);
        assertTrue(active().contains("HIGH"));
        step(240);
        step(210);
        step(160);                      // 15분 전 260 → -6.7/분
        assertTrue(active().contains("RAPID_FALL"));
        assertFalse(active().contains("HIGH"));
    }

    @Test
    void staleBackfillDoesNotTriggerGlucoseAlerts() {
        // 2시간 전 저혈당 값이 지금 도착 (업로드 지연) → 알림 없음
        upload(p, pid, List.of(reading("old1", 50, "NORMAL", T0.minus(Duration.ofHours(2))),
                reading("old2", 48, "NORMAL", T0.minus(Duration.ofHours(2)).plusSeconds(300))));
        assertEquals(List.of(), active());
        assertEquals(2, count("SELECT count(*) FROM glucose_readings"));
    }

    @Test
    void remindersRepeatUntilAcknowledged() {
        now(100);
        step(60);
        dispatcher.dispatchDue();
        sender.outbox.clear();
        clock.advance(Duration.ofMinutes(30));
        engine.sendReminders();
        dispatcher.dispatchDue();
        assertTrue(sender.outbox.messages().stream().allMatch(m -> m.title().startsWith("[재알림]")));
        assertEquals(2, sender.outbox.messages().size());

        String alertId = get("/api/v1/patients/" + pid + "/alerts?activeOnly=true", fam).body().get(0).get("id").asText();
        assertEquals(403, post("/api/v1/alerts/" + alertId + "/ack", noAlertFam, null).status());
        assertEquals(204, post("/api/v1/alerts/" + alertId + "/ack", fam, null).status());
        Res list = get("/api/v1/patients/" + pid + "/alerts", p);
        assertEquals("ACKNOWLEDGED", list.body().get(0).get("status").asText());
        assertEquals("가족", list.body().get(0).get("acknowledgedBy").asText());
        sender.outbox.clear();
        clock.advance(Duration.ofMinutes(60));
        engine.sendReminders();
        dispatcher.dispatchDue();
        assertEquals(0, sender.outbox.messages().size());
    }

    @Test
    void noDataAlertOpensAndResolves() {
        db.update("INSERT INTO data_sources (id, patient_id, type, status, created_at) VALUES (gen_random_uuid(), ?::uuid, 'HEALTH_CONNECT', 'ACTIVE', ?)",
                pid, java.sql.Timestamp.from(T0.minus(Duration.ofHours(1))));
        now(120);
        clock.advance(Duration.ofMinutes(29));
        engine.checkNoData();
        assertEquals(List.of(), active());
        clock.advance(Duration.ofMinutes(2));
        engine.checkNoData();
        assertEquals(List.of("NO_DATA"), active());
        now(118);
        assertEquals(List.of(), active());
    }

    @Test
    void deliveryRetriesWithBackoffThenFails() {
        sender.forced = NotificationSender.Outcome.RETRYABLE_ERROR;
        now(100);
        step(60);
        dispatcher.dispatchDue();
        assertEquals(2, count("SELECT count(*) FROM notification_deliveries WHERE status = 'PENDING' AND attempts = 1"));
        dispatcher.dispatchDue();               // 아직 재시도 시각 전
        assertEquals(2, count("SELECT count(*) FROM notification_deliveries WHERE attempts = 1"));
        for (int i = 0; i < 6; i++) { clock.advance(Duration.ofMinutes(16)); dispatcher.dispatchDue(); }
        assertEquals(2, count("SELECT count(*) FROM notification_deliveries WHERE status = 'FAILED' AND attempts = 5"));
        // 알림 자체는 여전히 열려 있다 (전송 실패 ≠ 해소)
        assertEquals(List.of("LOW"), active());
    }

    @Test
    void invalidTokenDisablesDeviceAndNoDeviceIsRecorded() {
        sender.forced = NotificationSender.Outcome.INVALID_TOKEN;
        now(100);
        step(60);
        dispatcher.dispatchDue();
        assertEquals(2, count("SELECT count(*) FROM devices WHERE disabled_at IS NOT NULL"));
        sender.forced = null;
        step(100);
        clock.advance(Duration.ofMinutes(20));
        now(50);                                // 새 알림: 활성 기기 없음 → NO_DEVICE 기록
        assertTrue(count("SELECT count(*) FROM notification_deliveries WHERE status = 'NO_DEVICE'") >= 2);
    }

    @Test
    void patientCanChangeRulesWithValidation() {
        Res rules = get("/api/v1/patients/" + pid + "/alert-rules", p);
        Map<String, Object> m = json.convertValue(rules.body(), Map.class);
        m.put("lowMgdl", 80);
        assertEquals(200, call("PUT", "/api/v1/patients/" + pid + "/alert-rules", p, m).status());
        m.put("urgentLowMgdl", 90); // urgentLow >= low → 거부
        assertEquals(400, call("PUT", "/api/v1/patients/" + pid + "/alert-rules", p, m).status());
        now(100);
        step(78);
        assertEquals(List.of("LOW"), active());
    }
}
