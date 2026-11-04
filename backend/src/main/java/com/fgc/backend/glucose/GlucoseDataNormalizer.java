package com.fgc.backend.glucose;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 외부 측정값을 저장 가능한 형태로 검증·정규화 (ARCH-001 2.2).
 * 거부 사유는 건수만 집계하고 값 자체는 로그에 남기지 않는다.
 */
@Component
public class GlucoseDataNormalizer {
    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);
    static final int MIN_MGDL = 20, MAX_MGDL = 600;

    private final Clock clock;

    public GlucoseDataNormalizer(Clock clock) {
        this.clock = clock;
    }

    public enum Rejection { MISSING_FIELD, FUTURE_TIMESTAMP, VALUE_OUT_OF_BOUNDS, FLAG_VALUE_MISMATCH, DUPLICATE_IN_BATCH }

    public record Normalized(String externalId, Integer valueMgdl, Flag flag, Instant measuredAt, String offset, String sourceTrend) {}

    public record Result(List<Normalized> accepted, Map<Rejection, Integer> rejected) {
        public int rejectedCount() { return rejected.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    public Result normalize(List<RawReading> raw) {
        Instant now = clock.instant();
        List<Normalized> ok = new ArrayList<>();
        Map<Rejection, Integer> bad = new EnumMap<>(Rejection.class);
        Set<String> seen = new HashSet<>();
        for (RawReading r : raw) {
            Rejection why = check(r, now);
            if (why == null && !seen.add(r.externalId())) why = Rejection.DUPLICATE_IN_BATCH;
            if (why != null) { bad.merge(why, 1, Integer::sum); continue; }
            ok.add(new Normalized(r.externalId(), r.flag() == Flag.NORMAL ? r.valueMgdl() : null, r.flag(),
                    r.measuredAt().toInstant(), r.measuredAt().getOffset().getId(), r.sourceTrend()));
        }
        ok.sort(Comparator.comparing(Normalized::measuredAt));
        return new Result(ok, bad);
    }

    private static Rejection check(RawReading r, Instant now) {
        if (r.externalId() == null || r.externalId().isBlank() || r.measuredAt() == null || r.flag() == null) {
            return Rejection.MISSING_FIELD;
        }
        if (r.measuredAt().toInstant().isAfter(now.plus(FUTURE_TOLERANCE))) return Rejection.FUTURE_TIMESTAMP;
        if (r.flag() == Flag.NORMAL) {
            if (r.valueMgdl() == null) return Rejection.FLAG_VALUE_MISMATCH;
            if (r.valueMgdl() < MIN_MGDL || r.valueMgdl() > MAX_MGDL) return Rejection.VALUE_OUT_OF_BOUNDS;
        }
        return null;
    }
}
