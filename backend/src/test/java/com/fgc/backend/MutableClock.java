package com.fgc.backend;

import java.time.*;

public class MutableClock extends Clock {
    private volatile Instant now;

    public MutableClock(Instant start) { this.now = start; }

    public void set(Instant t) { now = t; }
    public void advance(Duration d) { now = now.plus(d); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    @Override public Instant instant() { return now; }
}
