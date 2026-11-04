package com.fgc.backend.glucose;

import java.time.OffsetDateTime;

/** 어댑터가 외부에서 받은 정규화 전 측정값. */
public record RawReading(String externalId, Integer valueMgdl, Flag flag, OffsetDateTime measuredAt, String sourceTrend) {}
