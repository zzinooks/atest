package com.fgc.emulator;

import java.time.Duration;
import java.time.Instant;

/**
 * 에뮬레이터 시계. 실제 시간보다 빠르게 흐르게 하거나(배속), 테스트에서 수동으로 진행시킨다.
 * now = origin + (경과 실시간 × speed) + manualOffset
 */
public final class SimClock {
    private final Instant origin;
    private final long realStartNanos;
    private final double speed;
    private volatile Duration manualOffset = Duration.ZERO;

    private SimClock(Instant origin, double speed) {
        this.origin = origin;
        this.speed = speed;
        this.realStartNanos = System.nanoTime();
    }

    /** 실시간 대비 speed 배로 흐르는 시계. speed=0 이면 멈춘 시계(수동 진행 전용). */
    public static SimClock scaled(Instant origin, double speed) {
        if (speed < 0) throw new IllegalArgumentException("speed must be >= 0");
        return new SimClock(origin, speed);
    }

    public static SimClock manual(Instant origin) {
        return new SimClock(origin, 0);
    }

    public Instant now() {
        long realElapsedNanos = System.nanoTime() - realStartNanos;
        long simNanos = (long) (realElapsedNanos * speed);
        return origin.plusNanos(simNanos).plus(manualOffset);
    }

    public synchronized void advance(Duration d) {
        if (d.isNegative()) throw new IllegalArgumentException("clock cannot go backwards");
        manualOffset = manualOffset.plus(d);
    }

    public double speed() {
        return speed;
    }
}
