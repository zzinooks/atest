package com.fgc.backend;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IngestAndStatsIT extends IntegrationTest {

    @Test
    void duplicatesIgnoredAndBadDataRejected() {
        String p = signup("i@test.dev", "I");
        String pid = patient(p);
        Instant t = T0.minus(Duration.ofMinutes(10));
        Res r = upload(p, pid, List.of(
                reading("a", 100, "NORMAL", t),
                reading("a", 100, "NORMAL", t),                                  // 배치 내 중복
                reading("b", null, "LOW_OUT_OF_RANGE", t.plusSeconds(300)),      // LO
                reading("c", 120, "NORMAL", T0.plus(Duration.ofHours(9))),       // 시간대 오류로 미래
                reading("d", 900, "NORMAL", t),                                  // 범위 밖
                reading("e", null, "NORMAL", t)));                                // 플래그-값 불일치
        assertEquals(200, r.status());
        assertEquals(6, r.body().get("received").asInt());
        assertEquals(2, r.body().get("inserted").asInt());
        assertEquals(4, r.body().get("rejected").asInt());
        // 재전송은 저장되지 않는다
        Res again = upload(p, pid, List.of(reading("a", 100, "NORMAL", t)));
        assertEquals(0, again.body().get("inserted").asInt());
        assertEquals(1, again.body().get("duplicates").asInt());
        assertEquals(2, count("SELECT count(*) FROM glucose_readings"));
        // 측정 시각(UTC 저장)과 원본 오프셋, 수신 시각이 분리 저장된다
        assertEquals(1, count("SELECT count(*) FROM glucose_readings WHERE external_id = 'a' AND measured_offset = '+09:00' AND measured_at < received_at"));
    }

    @Test
    void latestShowsFreshnessAndRate() {
        String p = signup("l@test.dev", "L");
        String pid = patient(p);
        assertEquals("NO_DATA", get("/api/v1/patients/" + pid + "/glucose/latest", p).body().get("freshness").asText());
        upload(p, pid, List.of(reading("1", 150, "NORMAL", T0.minus(Duration.ofMinutes(17))),
                reading("2", 120, "NORMAL", T0.minus(Duration.ofMinutes(2)))));
        Res fresh = get("/api/v1/patients/" + pid + "/glucose/latest", p);
        assertEquals("FRESH", fresh.body().get("freshness").asText());
        assertEquals(2, fresh.body().get("ageMinutes").asInt());
        assertEquals(-2.0, fresh.body().get("ratePerMinute").asDouble(), 0.01);
        clock.advance(Duration.ofMinutes(20));
        Res stale = get("/api/v1/patients/" + pid + "/glucose/latest", p);
        assertEquals("STALE", stale.body().get("freshness").asText());
        assertEquals(120, stale.body().get("reading").get("valueMgdl").asInt()); // 값은 주되 상태로 구분
    }

    @Test
    void statsComputedOverRange() {
        String p = signup("s@test.dev", "S");
        String pid = patient(p);
        List<Map<String, Object>> rs = new ArrayList<>();
        Instant start = T0.minus(Duration.ofHours(1));
        int[] values = {50, 65, 100, 150, 180, 200, 260, 100, 100, 100, 100};
        for (int i = 0; i < values.length; i++) rs.add(reading("s" + i, values[i], "NORMAL", start.plus(Duration.ofMinutes(5L * i))));
        rs.add(reading("lo", null, "LOW_OUT_OF_RANGE", start.plus(Duration.ofMinutes(55))));
        upload(p, pid, rs);
        Res s = get("/api/v1/patients/" + pid + "/stats?from=" + start + "&to=" + T0, p);
        assertEquals(200, s.status());
        JsonAssert a = new JsonAssert(s);
        assertEquals(12, a.i("readings"));
        assertEquals(1.0, a.d("coverage"), 0.001);           // 60분 / 5분 = 12
        assertEquals(16.7, a.d("veryLowPct"), 0.1);          // 50 + LO
        assertEquals(8.3, a.d("lowPct"), 0.1);               // 65
        assertEquals(58.3, a.d("inRangePct"), 0.1);          // 100,150,180,100x4
        assertEquals(8.3, a.d("highPct"), 0.1);              // 200
        assertEquals(8.3, a.d("veryHighPct"), 0.1);          // 260
        assertEquals(50, a.i("min"));
        assertEquals(260, a.i("max"));
        assertEquals(400, get("/api/v1/patients/" + pid + "/stats?from=" + T0 + "&to=" + start, p).status());
        assertEquals(400, get("/api/v1/patients/" + pid + "/stats?from=" + T0.minus(Duration.ofDays(40)), p).status());
        Res daily = get("/api/v1/patients/" + pid + "/stats/daily?days=3", p);
        assertEquals(3, daily.body().size());
    }

    record JsonAssert(Res r) {
        int i(String f) { return r.body().get(f).asInt(); }
        double d(String f) { return r.body().get(f).asDouble(); }
    }
}
