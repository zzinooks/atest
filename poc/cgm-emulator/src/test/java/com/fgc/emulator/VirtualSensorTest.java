package com.fgc.emulator;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VirtualSensorTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant START = LocalDateTime.of(2026, 11, 1, 9, 0).atZone(SEOUL).toInstant();

    private static VirtualSensor sensor(Scenario s) {
        return new VirtualSensor(EmulatorConfig.defaults(7, s), START);
    }

    private static Instant local(int day, int h, int m) {
        return LocalDateTime.of(2026, 11, day, h, m).atZone(SEOUL).toInstant();
    }

    @Test
    void noReadingsDuringWarmupAndFirstReadingAfter30Minutes() {
        VirtualSensor s = sensor(Scenario.NORMAL);
        assertEquals(SensorState.WARMUP, s.stateAt(START.plusSeconds(60)));
        List<Reading> r = s.measuredBetween(START, START.plus(Duration.ofHours(1)));
        assertEquals(START.plus(Duration.ofMinutes(30)), r.get(0).measuredAt());
        assertEquals(6, r.size()); // 30분 ~ 60분, 5분 간격
    }

    @Test
    void readingsEveryFiveMinutes288PerDay() {
        List<Reading> r = sensor(Scenario.NORMAL).measuredBetween(local(2, 0, 0), local(3, 0, 0));
        assertEquals(288, r.size());
        for (int i = 1; i < r.size(); i++) {
            assertEquals(Duration.ofMinutes(5), Duration.between(r.get(i - 1).measuredAt(), r.get(i).measuredAt()));
            assertEquals(r.get(i - 1).sequence() + 1, r.get(i).sequence());
        }
    }

    @Test
    void expiresAfter15Days() {
        VirtualSensor s = sensor(Scenario.NORMAL);
        assertEquals(SensorState.EXPIRED, s.stateAt(START.plus(Duration.ofDays(15))));
        assertTrue(s.measuredBetween(START.plus(Duration.ofDays(15)), START.plus(Duration.ofDays(16))).isEmpty());
        List<Reading> lastDay = s.measuredBetween(START.plus(Duration.ofDays(14)), START.plus(Duration.ofDays(16)));
        assertTrue(lastDay.get(lastDay.size() - 1).measuredAt().isBefore(s.expiresAt()));
    }

    @Test
    void deterministicRegardlessOfQueryWindow() {
        VirtualSensor a = sensor(Scenario.NORMAL);
        VirtualSensor b = sensor(Scenario.NORMAL);
        Reading wide = a.measuredBetween(local(2, 0, 0), local(3, 0, 0)).get(100);
        Reading narrow = b.measuredBetween(wide.measuredAt(), wide.measuredAt().plusSeconds(1)).get(0);
        assertEquals(wide, narrow);
    }

    @Test
    void normalDayStaysInPlausibleRange() {
        for (Reading r : sensor(Scenario.NORMAL).measuredBetween(local(2, 0, 0), local(4, 0, 0))) {
            assertNotNull(r.valueMgdl());
            assertTrue(r.valueMgdl() >= 70 && r.valueMgdl() <= 220, () -> "out of range " + r);
        }
    }

    @Test
    void nightHypoGoesBelow70Overnight() {
        int min = sensor(Scenario.NIGHT_HYPO).measuredBetween(local(2, 1, 30), local(2, 4, 0)).stream()
                .mapToInt(r -> r.valueMgdl() == null ? 0 : r.valueMgdl()).min().orElseThrow();
        assertTrue(min < 70, "min=" + min);
    }

    @Test
    void postMealHyperExceeds250() {
        int max = sensor(Scenario.POST_MEAL_HYPER).measuredBetween(local(2, 12, 0), local(2, 15, 0)).stream()
                .mapToInt(r -> r.valueMgdl() == null ? 999 : r.valueMgdl()).max().orElseThrow();
        assertTrue(max > 250, "max=" + max);
    }

    @Test
    void rapidDropShowsFallingQuicklyTrend() {
        boolean found = sensor(Scenario.RAPID_DROP).measuredBetween(local(2, 15, 0), local(2, 15, 30)).stream()
                .anyMatch(r -> r.trend() == TrendArrow.FALLING_QUICKLY);
        assertTrue(found);
    }

    @Test
    void signalLossLeavesGapOfEightReadings() {
        VirtualSensor s = sensor(Scenario.SIGNAL_LOSS);
        assertEquals(SensorState.SIGNAL_LOSS, s.stateAt(local(2, 10, 20)));
        List<Reading> r = s.measuredBetween(local(2, 9, 0), local(2, 12, 0));
        assertEquals(36 - 8, r.size());
        assertTrue(r.stream().noneMatch(x -> x.measuredAt().atZone(SEOUL).getHour() == 10
                && x.measuredAt().atZone(SEOUL).getMinute() < 40));
    }

    @Test
    void uploadOutageDelaysUploadUntilReconnect() {
        for (Reading r : sensor(Scenario.UPLOAD_OUTAGE).measuredBetween(local(2, 13, 0), local(2, 14, 0))) {
            assertFalse(r.uploadedAt().isBefore(local(2, 14, 0)), r::toString);
        }
        Reading normal = sensor(Scenario.UPLOAD_OUTAGE).measuredBetween(local(2, 15, 0), local(2, 15, 5)).get(0);
        assertTrue(Duration.between(normal.measuredAt(), normal.uploadedAt()).toMinutes() < 4);
    }

    @Test
    void trendArrowThresholds() {
        assertEquals(TrendArrow.NOT_COMPUTABLE, TrendArrow.fromRate(null));
        assertEquals(TrendArrow.STABLE, TrendArrow.fromRate(0.5));
        assertEquals(TrendArrow.RISING_SLIGHTLY, TrendArrow.fromRate(1.0));
        assertEquals(TrendArrow.FALLING, TrendArrow.fromRate(-2.5));
        assertEquals(TrendArrow.RISING_QUICKLY, TrendArrow.fromRate(3.2));
    }
}
