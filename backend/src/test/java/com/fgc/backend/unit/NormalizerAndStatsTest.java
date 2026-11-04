package com.fgc.backend.unit;

import com.fgc.backend.glucose.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NormalizerAndStatsTest {
    static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
    final GlucoseDataNormalizer n = new GlucoseDataNormalizer(Clock.fixed(NOW, ZoneOffset.UTC));

    static RawReading raw(String id, Integer v, Flag f, OffsetDateTime t) { return new RawReading(id, v, f, t, null); }

    @Test
    void normalizesToUtcKeepsOffsetAndSorts() {
        OffsetDateTime kst = OffsetDateTime.of(2026, 11, 3, 11, 50, 0, 0, ZoneOffset.ofHours(9));
        var r = n.normalize(List.of(raw("b", 100, Flag.NORMAL, kst), raw("a", 90, Flag.NORMAL, kst.minusMinutes(5))));
        assertEquals("a", r.accepted().get(0).externalId());
        assertEquals(Instant.parse("2026-11-03T02:50:00Z"), r.accepted().get(1).measuredAt());
        assertEquals("+09:00", r.accepted().get(1).offset());
    }

    @Test
    void timezoneMistakeShowsUpAsFutureAndIsRejected() {
        // 로컬 시각을 UTC 로 잘못 표기한 경우: 9시간 미래 → 거부
        OffsetDateTime wrong = OffsetDateTime.of(2026, 11, 3, 11, 55, 0, 0, ZoneOffset.UTC);
        var r = n.normalize(List.of(raw("x", 100, Flag.NORMAL, wrong)));
        assertEquals(0, r.accepted().size());
        assertEquals(1, r.rejected().get(GlucoseDataNormalizer.Rejection.FUTURE_TIMESTAMP));
        // 5분 이내의 시계 오차는 허용
        assertEquals(1, n.normalize(List.of(raw("y", 100, Flag.NORMAL, NOW.plusSeconds(200).atOffset(ZoneOffset.UTC)))).accepted().size());
    }

    @Test
    void loHiKeepNoValue() {
        var r = n.normalize(List.of(raw("lo", 30, Flag.LOW_OUT_OF_RANGE, NOW.atOffset(ZoneOffset.UTC))));
        assertNull(r.accepted().get(0).valueMgdl());
    }

    @Test
    void statsEmptyAndCoverage() {
        Instant f = NOW.minus(Duration.ofHours(1));
        var empty = StatsService.compute(List.of(), f, NOW);
        assertEquals(0, empty.readings());
        assertFalse(empty.reliable());
        List<GlucoseReading> half = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) half.add(new GlucoseReading(i, UUID.randomUUID(), 100 + i * 10, Flag.NORMAL,
                f.plus(Duration.ofMinutes(5L * i)), "+09:00", NOW, GlucoseSource.EMULATOR, "e" + i, null));
        var s = StatsService.compute(half, f, NOW);
        assertEquals(0.5, s.coverage(), 0.001);
        assertFalse(s.reliable());
        assertEquals(125.0, s.mean());
        assertEquals(18.7, s.sd(), 0.05);
        assertEquals(6.3, s.gmiPercent(), 0.05); // 3.31 + 0.02392*125 = 6.30
    }
}
