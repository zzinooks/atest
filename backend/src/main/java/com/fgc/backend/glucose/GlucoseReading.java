package com.fgc.backend.glucose;

import java.time.Instant;
import java.util.UUID;

public record GlucoseReading(long id, UUID patientId, Integer valueMgdl, Flag flag, Instant measuredAt,
                             String measuredOffset, Instant receivedAt, GlucoseSource source, String externalId,
                             String sourceTrend) {}
