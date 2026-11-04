package com.fgc.emulator;

import java.time.Instant;

/**
 * 센서 측정값 1건.
 * @param externalId   에뮬레이터가 부여하는 안정적인 ID (센서 시리얼 + 순번). 같은 측정은 항상 같은 ID.
 * @param valueMgdl    측정값. 범위를 벗어나면 null 이고 flag 로 LO/HI 표시.
 * @param measuredAt   센서가 측정한 시각 (UTC)
 * @param uploadedAt   (가상) 클라우드에 올라간 시각. 측정 시각보다 늦다.
 */
public record Reading(
        String externalId,
        String sensorSerial,
        long sequence,
        Integer valueMgdl,
        Flag flag,
        TrendArrow trend,
        Double trendRateMgdlPerMin,
        Instant measuredAt,
        Instant uploadedAt) {

    public enum Flag { NORMAL, LOW_OUT_OF_RANGE, HIGH_OUT_OF_RANGE }
}
