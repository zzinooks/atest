package com.fgc.emulator;

import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * 생리학을 단순화한 결정적(deterministic) 혈당 모델. 의학적으로 정확한 모델이 아니라,
 * 알람·통계·장애 처리 로직을 시험할 수 있는 "그럴듯한" 곡선을 만드는 것이 목적이다.
 * 같은 seed·시각이면 항상 같은 값을 돌려준다(조회 순서와 무관).
 */
public final class GlucoseModel {
    private static final double BASELINE = 105;
    private static final int[] MEAL_MINUTES = {7 * 60 + 30, 12 * 60 + 30, 18 * 60 + 30};
    private static final double[] MEAL_AMPLITUDE = {55, 70, 65};
    private static final double MEAL_PEAK_MINUTES = 45;

    private final EmulatorConfig config;
    private final double phase1;
    private final double phase2;

    public GlucoseModel(EmulatorConfig config) {
        this.config = config;
        this.phase1 = unit(config.seed(), 1) * 2 * Math.PI;
        this.phase2 = unit(config.seed(), 2) * 2 * Math.PI;
    }

    /** 범위 제한 전 원시 혈당값 (mg/dL). */
    public double rawValueAt(Instant t) {
        ZonedDateTime local = t.atZone(config.zone());
        double minuteOfDay = local.getHour() * 60 + local.getMinute() + local.getSecond() / 60.0;

        double v = BASELINE;
        v += 15 * bump(minuteOfDay, 6 * 60, 90);                  // 새벽 현상
        double mealScale = config.scenario() == Scenario.POST_MEAL_HYPER ? 2.6 : 1.0;
        for (int i = 0; i < MEAL_MINUTES.length; i++) {           // 오늘 식사 + 전날 저녁 잔여
            v += mealResponse(minuteOfDay - MEAL_MINUTES[i], MEAL_AMPLITUDE[i] * mealScale);
            v += mealResponse(minuteOfDay + 1440 - MEAL_MINUTES[i], MEAL_AMPLITUDE[i] * mealScale);
        }
        v += scenarioOverlay(minuteOfDay);

        double minutesSinceEpoch = t.getEpochSecond() / 60.0;
        v += 6 * Math.sin(2 * Math.PI * minutesSinceEpoch / 97 + phase1);
        v += 4 * Math.sin(2 * Math.PI * minutesSinceEpoch / 41 + phase2);
        v += (unit(config.seed(), t.getEpochSecond() / 60) - 0.5) * 6;   // 측정 잡음 ±3
        return v;
    }

    private double scenarioOverlay(double m) {
        return switch (config.scenario()) {
            case NIGHT_HYPO -> -68 * bump(m, 165, 50);
            case RAPID_DROP -> {
                if (m >= 840 && m < 900) yield 70 * (m - 840) / 60;          // 14:00~15:00 상승
                if (m >= 900 && m < 920) yield 70 - 4 * (m - 900);            // 15:00~15:20 분당 -4
                if (m >= 920 && m < 980) yield -10 * (1 - (m - 920) / 60);   // 회복
                yield 0;
            }
            default -> 0;
        };
    }

    private static double mealResponse(double minutesAfterMeal, double amplitude) {
        if (minutesAfterMeal <= 0) return 0;
        double x = minutesAfterMeal / MEAL_PEAK_MINUTES;
        return amplitude * x * Math.exp(1 - x);
    }

    /** 중심 center, 반폭 halfWidth 의 매끄러운 종 모양 (0~1). */
    private static double bump(double m, double center, double halfWidth) {
        double d = (m - center) / halfWidth;
        return Math.exp(-d * d);
    }

    /** seed 와 key 로 결정되는 [0,1) 난수. */
    static double unit(long seed, long key) {
        long z = seed * 0x9E3779B97F4A7C15L + key * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }
}
