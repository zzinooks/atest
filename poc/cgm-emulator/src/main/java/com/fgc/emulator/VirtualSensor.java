package com.fgc.emulator;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 가상 CGM 센서 1개(부착~만료)의 측정값을 만든다.
 * 측정 시각 = 부착 시각 + 안정화 시간 + k × 측정 주기 (k = sequence).
 */
public final class VirtualSensor {
    private final EmulatorConfig config;
    private final GlucoseModel model;
    private final String serial;
    private final Instant startedAt;

    public VirtualSensor(EmulatorConfig config, Instant startedAt) {
        this.config = config;
        this.model = new GlucoseModel(config);
        this.startedAt = startedAt;
        this.serial = String.format(Locale.ROOT, "VBZ%08X", (int) (GlucoseModel.unit(config.seed(), 99) * 0xFFFFFFFFL));
    }

    public String serial() { return serial; }
    public Instant startedAt() { return startedAt; }
    public Instant firstReadingAt() { return startedAt.plus(config.warmup()); }
    public Instant expiresAt() { return startedAt.plus(config.sensorLifetime()); }

    public SensorState stateAt(Instant now) {
        if (now.isBefore(firstReadingAt())) return SensorState.WARMUP;
        if (!now.isBefore(expiresAt())) return SensorState.EXPIRED;
        if (inSignalLoss(now)) return SensorState.SIGNAL_LOSS;
        return SensorState.ACTIVE;
    }

    /** measuredAt 이 [from, to) 에 속하는 측정값. 신호 끊김 구간은 비어 있다. uploadedAt 과 무관하게 모두 반환. */
    public List<Reading> measuredBetween(Instant from, Instant to) {
        List<Reading> out = new ArrayList<>();
        long step = config.samplingInterval().toSeconds();
        Instant first = firstReadingAt();
        Instant lo = from.isBefore(first) ? first : from;
        Instant hi = to.isAfter(expiresAt()) ? expiresAt() : to;
        if (!lo.isBefore(hi)) return out;
        long k = ceilDiv(Duration.between(first, lo).toSeconds(), step);
        for (; ; k++) {
            Instant t = first.plusSeconds(k * step);
            if (!t.isBefore(hi)) break;
            if (inSignalLoss(t)) continue;
            out.add(readingAt(k, t));
        }
        return out;
    }

    private Reading readingAt(long k, Instant t) {
        double raw = model.rawValueAt(t);
        Integer value = (int) Math.round(raw);
        Reading.Flag flag = Reading.Flag.NORMAL;
        if (raw < config.reportableMinMgdl()) { value = null; flag = Reading.Flag.LOW_OUT_OF_RANGE; }
        else if (raw > config.reportableMaxMgdl()) { value = null; flag = Reading.Flag.HIGH_OUT_OF_RANGE; }

        Double rate = null;
        Instant prev = t.minus(Duration.ofMinutes(15));
        if (!prev.isBefore(firstReadingAt()) && !inSignalLoss(prev)) {
            rate = Math.round((raw - model.rawValueAt(prev)) / 15.0 * 100) / 100.0;
        }
        return new Reading(serial + "-" + k, serial, k, value, flag, TrendArrow.fromRate(rate), rate, t, uploadTime(k, t));
    }

    private Instant uploadTime(long k, Instant measuredAt) {
        long jitter = (long) (GlucoseModel.unit(config.seed() ^ 0x5EED, k) * config.uploadJitterMax().toSeconds());
        Instant normal = measuredAt.plus(config.uploadBaseDelay()).plusSeconds(jitter);
        if (config.scenario() == Scenario.UPLOAD_OUTAGE) {
            ZonedDateTime local = measuredAt.atZone(config.zone());
            LocalTime lt = local.toLocalTime();
            if (!lt.isBefore(LocalTime.of(13, 0)) && lt.isBefore(LocalTime.of(14, 0))) {
                Instant reconnect = local.with(LocalTime.of(14, 0)).toInstant();
                return reconnect.plus(config.uploadBaseDelay()).plusSeconds(jitter);
            }
        }
        return normal;
    }

    private boolean inSignalLoss(Instant t) {
        if (config.scenario() != Scenario.SIGNAL_LOSS) return false;
        LocalTime lt = t.atZone(config.zone()).toLocalTime();
        return !lt.isBefore(LocalTime.of(10, 0)) && lt.isBefore(LocalTime.of(10, 40));
    }

    private static long ceilDiv(long a, long b) {
        return Math.floorDiv(a + b - 1, b);
    }
}
