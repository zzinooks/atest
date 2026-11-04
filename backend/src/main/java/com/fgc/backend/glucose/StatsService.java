package com.fgc.backend.glucose;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 혈당 통계 (REQ-001 2.3, International Consensus on TIR 2019 기준 구간).
 * LO 플래그는 &lt;54, HI 플래그는 &gt;250 구간에 포함하고 평균·SD 계산에서는 제외한다.
 */
@Service
public class StatsService {
    static final int EXPECTED_INTERVAL_MINUTES = 5;
    static final double MIN_COVERAGE_FOR_TRUST = 0.70;

    private final GlucoseRepository repo;

    public StatsService(GlucoseRepository repo) {
        this.repo = repo;
    }

    public record Stats(Instant from, Instant to, int readings, double coverage, boolean reliable,
                        Double mean, Integer min, Integer max, Double sd, Double cvPercent, Double gmiPercent,
                        double veryLowPct, double lowPct, double inRangePct, double highPct, double veryHighPct) {}

    public record DailyStats(LocalDate date, Stats stats) {}

    public Stats compute(UUID patientId, Instant from, Instant to) {
        return compute(repo.between(patientId, from, to), from, to);
    }

    public List<DailyStats> daily(UUID patientId, ZoneId zone, LocalDate lastDay, int days) {
        List<DailyStats> out = new ArrayList<>();
        Instant start = lastDay.minusDays(days - 1L).atStartOfDay(zone).toInstant();
        Instant end = lastDay.plusDays(1).atStartOfDay(zone).toInstant();
        List<GlucoseReading> all = repo.between(patientId, start, end);
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = lastDay.minusDays(i);
            Instant f = d.atStartOfDay(zone).toInstant(), t = d.plusDays(1).atStartOfDay(zone).toInstant();
            out.add(new DailyStats(d, compute(all.stream().filter(r -> !r.measuredAt().isBefore(f) && r.measuredAt().isBefore(t)).toList(), f, t)));
        }
        return out;
    }

    public static Stats compute(List<GlucoseReading> rs, Instant from, Instant to) {
        int n = rs.size();
        long expected = Math.max(1, Duration.between(from, to).toMinutes() / EXPECTED_INTERVAL_MINUTES);
        double coverage = Math.min(1.0, n / (double) expected);
        if (n == 0) return new Stats(from, to, 0, 0, false, null, null, null, null, null, null, 0, 0, 0, 0, 0);
        int veryLow = 0, low = 0, inRange = 0, high = 0, veryHigh = 0;
        List<Integer> values = new ArrayList<>();
        for (GlucoseReading r : rs) {
            if (r.flag() == Flag.LOW_OUT_OF_RANGE) { veryLow++; continue; }
            if (r.flag() == Flag.HIGH_OUT_OF_RANGE) { veryHigh++; continue; }
            int v = r.valueMgdl();
            values.add(v);
            if (v < 54) veryLow++;
            else if (v < 70) low++;
            else if (v <= 180) inRange++;
            else if (v <= 250) high++;
            else veryHigh++;
        }
        Double mean = null, sd = null, cv = null, gmi = null;
        Integer min = null, max = null;
        if (!values.isEmpty()) {
            double m = values.stream().mapToInt(Integer::intValue).average().orElseThrow();
            double var = values.stream().mapToDouble(v -> (v - m) * (v - m)).sum() / Math.max(1, values.size() - 1);
            mean = round1(m);
            sd = round1(Math.sqrt(var));
            cv = round1(Math.sqrt(var) / m * 100);
            gmi = round1(3.31 + 0.02392 * m);
            min = values.stream().mapToInt(Integer::intValue).min().orElseThrow();
            max = values.stream().mapToInt(Integer::intValue).max().orElseThrow();
        }
        return new Stats(from, to, n, round3(coverage), coverage >= MIN_COVERAGE_FOR_TRUST, mean, min, max, sd, cv, gmi,
                pct(veryLow, n), pct(low, n), pct(inRange, n), pct(high, n), pct(veryHigh, n));
    }

    private static double pct(int k, int n) { return round1(100.0 * k / n); }
    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static double round3(double v) { return Math.round(v * 1000) / 1000.0; }
}
