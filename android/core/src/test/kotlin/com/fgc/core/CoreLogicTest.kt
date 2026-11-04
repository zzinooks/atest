package com.fgc.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class CoreLogicTest {
    private val now = Instant.parse("2026-11-03T03:00:00Z")
    private fun reading(v: Int?, flag: Flag = Flag.NORMAL, minutesAgo: Long = 2) =
        Reading(v, flag, now.minus(Duration.ofMinutes(minutesAgo)), now, "EMULATOR")

    @Test fun freshReadingShowsValueTrendAndAge() {
        val d = GlucoseDisplay.present(Latest(reading(120), Freshness.FRESH, 2, 15, -2.4, now), null)
        assertEquals("120", d.valueText)
        assertEquals("↓", d.trendArrow)
        assertEquals("2분 전", d.ageText)
        assertFalse(d.dimmed)
        assertNull(d.banner)
        assertEquals(Level.IN_RANGE, d.level)
    }

    @Test fun staleReadingIsDimmedWithWarningAndNoTrend() {
        val d = GlucoseDisplay.present(Latest(reading(65, minutesAgo = 95), Freshness.STALE, 95, 15, -1.0, now), null)
        assertTrue(d.dimmed)
        assertEquals("", d.trendArrow)
        assertEquals("1시간 35분 전", d.ageText)
        assertTrue(d.banner!!.contains("지금 혈당과 다를 수 있습니다"))
        assertEquals(Level.LOW, d.level)
    }

    @Test fun noDataAndOutOfRange() {
        assertEquals("--", GlucoseDisplay.present(Latest(null, Freshness.NO_DATA, null, 15, null, now), null).valueText)
        val lo = GlucoseDisplay.present(Latest(reading(null, Flag.LOW_OUT_OF_RANGE), Freshness.FRESH, 2, 15, null, now), null)
        assertEquals("LO", lo.valueText)
        assertEquals(Level.URGENT_LOW, lo.level)
    }

    @Test fun levelsFollowPatientRules() {
        val rules = AlertRules(60, 80, 200, 2.0, 30, 15, 30, 15, true, true, true, true)
        assertEquals(Level.LOW, GlucoseDisplay.level(reading(78), rules))
        assertEquals(Level.HIGH, GlucoseDisplay.level(reading(200), rules))
        assertEquals(Level.URGENT_LOW, GlucoseDisplay.level(reading(60), rules))
    }

    @Test fun trendThresholds() {
        assertEquals("→", GlucoseDisplay.trend(0.4).first)
        assertEquals("↗", GlucoseDisplay.trend(1.0).first)
        assertEquals("↑↑", GlucoseDisplay.trend(3.5).first)
        assertEquals("↓↓", GlucoseDisplay.trend(-3.0).first)
    }

    @Test fun graphBreaksOnGapsAndClampsLoHi() {
        val rs = listOf(reading(100, minutesAgo = 60), reading(105, minutesAgo = 55), reading(null, Flag.HIGH_OUT_OF_RANGE, 50),
            reading(110, minutesAgo = 20), reading(115, minutesAgo = 15))
        val segs = GraphSeries.segments(rs)
        assertEquals(2, segs.size)
        assertEquals(3, segs[0].size)
        assertEquals(400, segs[0][2].mgdl)
        assertTrue(segs[0][2].outOfRange)
    }

    @Test fun healthConnectMappingFiltersAndKeepsOffset() {
        val kst = ZoneOffset.ofHours(9)
        val recs = listOf(
            HcGlucoseRecord("a", now, kst, 101.6, 2, "com.barozen.fit"),
            HcGlucoseRecord("a", now, kst, 101.6, 2, "com.barozen.fit"),          // 중복
            HcGlucoseRecord("b", now.minusSeconds(300), null, 99.0, 2, "com.barozen.fit"),
            HcGlucoseRecord("c", now, kst, 140.0, 1, "com.barozen.fit"),          // 손끝(모세혈) 제외
            HcGlucoseRecord("d", now, kst, 130.0, 2, "com.other.app"),            // 선택하지 않은 앱
            HcGlucoseRecord("e", now, kst, 900.0, 2, "com.barozen.fit"))          // 비정상 값
        val up = HealthConnectMapper.toUpload(recs, setOf("com.barozen.fit"))
        assertEquals(listOf("hc:b", "hc:a"), up.map { it.externalId })
        assertEquals(102, up[1].valueMgdl)
        assertEquals("+09:00", up[1].measuredAt.offset.id)
        assertEquals(ZoneOffset.UTC, up[0].measuredAt.offset)
    }

    @Test fun syncWindowOverlapsAndRespectsHistoryLimit() {
        val w = HcSyncPlanner.next(now.minus(Duration.ofMinutes(15)), now)
        assertEquals(now.minus(Duration.ofMinutes(15)).minus(Duration.ofHours(3)), w.from)
        val first = HcSyncPlanner.next(null, now, Duration.ofDays(90))
        assertEquals(now.minus(Duration.ofDays(30)), first.from)
    }
}
