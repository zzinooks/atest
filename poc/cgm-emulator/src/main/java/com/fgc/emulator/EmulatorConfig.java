package com.fgc.emulator;

import java.time.Duration;
import java.time.ZoneId;

/**
 * 에뮬레이터 설정. 바로잰 Fit 공개 사양(5분 주기, 최대 15일, 안정화 30분)을 기본값으로 쓰고,
 * 공개되지 않은 값은 [가정]으로 표시한다.
 */
public record EmulatorConfig(
        long seed,
        Scenario scenario,
        ZoneId zone,
        Duration samplingInterval,      // [확인] 5분 (barozenfit.com)
        Duration warmup,                // [확인] 30분 (2025-04 업그레이드 제품)
        Duration sensorLifetime,        // [확인] 최대 15일
        int reportableMinMgdl,          // [가정] 40 — 실제 표시 범위 UNKNOWN
        int reportableMaxMgdl,          // [가정] 400 — 실제 표시 범위 UNKNOWN
        Duration uploadBaseDelay,       // [가정] 휴대폰 → 클라우드 업로드 지연
        Duration uploadJitterMax,       // [가정]
        Duration healthConnectDelay,    // [가정] 휴대폰 앱 → Health Connect 기록 지연
        double duplicateRate,           // 응답에 같은 측정값이 중복으로 섞이는 비율
        double serverErrorRate,         // 5xx 응답 비율
        int rateLimitPerMinute,         // 토큰당 분당 요청 수 (실시간 기준)
        Duration tokenTtl) {            // 액세스 토큰 유효시간 (에뮬레이터 시간 기준)

    public static EmulatorConfig defaults(long seed, Scenario scenario) {
        return new EmulatorConfig(
                seed, scenario, ZoneId.of("Asia/Seoul"),
                Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofDays(15),
                40, 400,
                Duration.ofSeconds(60), Duration.ofSeconds(120),
                Duration.ofMinutes(10),
                0.0, 0.0, 120, Duration.ofHours(1));
    }

    public EmulatorConfig withFaults(double duplicateRate, double serverErrorRate) {
        return new EmulatorConfig(seed, scenario, zone, samplingInterval, warmup, sensorLifetime,
                reportableMinMgdl, reportableMaxMgdl, uploadBaseDelay, uploadJitterMax, healthConnectDelay,
                duplicateRate, serverErrorRate, rateLimitPerMinute, tokenTtl);
    }

    public EmulatorConfig withRateLimit(int perMinute) {
        return new EmulatorConfig(seed, scenario, zone, samplingInterval, warmup, sensorLifetime,
                reportableMinMgdl, reportableMaxMgdl, uploadBaseDelay, uploadJitterMax, healthConnectDelay,
                duplicateRate, serverErrorRate, perMinute, tokenTtl);
    }
}
