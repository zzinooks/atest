package com.fgc.backend.alert;

import com.fgc.backend.glucose.Flag;
import com.fgc.backend.glucose.GlucoseReading;
import com.fgc.backend.glucose.GlucoseRepository;
import com.fgc.backend.notify.NotificationService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.fgc.backend.common.Db.*;

/**
 * 혈당 알람 엔진 (ADR-0004).
 * - 측정 시각이 stale_minutes 보다 오래된 값으로는 혈당 알림을 열지 않는다 (backfill 방어).
 * - 같은 종류의 미해소 알림은 하나 (DB 부분 유니크 인덱스로 보장, 동시 실행에도 안전).
 * - 해소 직후 cooldown 동안 같은 종류를 다시 열지 않는다.
 */
@Service
public class AlertEngine {
    static final Duration RATE_LOOKBACK = Duration.ofMinutes(15);
    static final Duration RATE_WINDOW = Duration.ofSeconds(150);
    static final int SYNC_FAILURE_THRESHOLD = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final GlucoseRepository glucose;
    private final AlertRuleService rules;
    private final NotificationService notifications;
    private final Clock clock;

    public AlertEngine(NamedParameterJdbcTemplate jdbc, GlucoseRepository glucose, AlertRuleService rules,
                       NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.glucose = glucose;
        this.rules = rules;
        this.notifications = notifications;
        this.clock = clock;
    }

    public void evaluateGlucose(UUID patientId) {
        AlertRules r = rules.get(patientId);
        Instant now = clock.instant();
        Optional<GlucoseReading> latestOpt = glucose.latest(patientId);
        if (latestOpt.isEmpty()) return;
        GlucoseReading latest = latestOpt.get();

        if (!latest.measuredAt().isBefore(now.minus(Duration.ofMinutes(r.noDataMinutes())))) resolve(patientId, AlertType.NO_DATA);
        if (latest.measuredAt().isBefore(now.minus(Duration.ofMinutes(r.staleMinutes())))) return; // 오래된 값으로 판단하지 않음

        boolean lo = latest.flag() == Flag.LOW_OUT_OF_RANGE;
        boolean hi = latest.flag() == Flag.HIGH_OUT_OF_RANGE;
        Integer v = latest.valueMgdl();

        if (r.lowEnabled()) {
            if (lo || v <= r.urgentLowMgdl()) {
                open(patientId, AlertType.URGENT_LOW, latest, r);
                resolve(patientId, AlertType.LOW); // 더 심각한 알림으로 대체
            } else if (v <= r.lowMgdl()) {
                if (!isActive(patientId, AlertType.URGENT_LOW)) open(patientId, AlertType.LOW, latest, r);
            } else {
                resolve(patientId, AlertType.LOW);
                resolve(patientId, AlertType.URGENT_LOW);
            }
        }
        if (r.highEnabled()) {
            if (hi || (v != null && v >= r.highMgdl())) open(patientId, AlertType.HIGH, latest, r);
            else resolve(patientId, AlertType.HIGH);
        }
        if (r.rapidEnabled() && v != null) {
            glucose.nearest(patientId, latest.measuredAt().minus(RATE_LOOKBACK), RATE_WINDOW).ifPresent(prev -> {
                double minutes = Duration.between(prev.measuredAt(), latest.measuredAt()).toSeconds() / 60.0;
                if (minutes <= 0) return;
                BigDecimal rate = BigDecimal.valueOf((v - prev.valueMgdl()) / minutes);
                if (rate.compareTo(r.rapidRate().negate()) <= 0) {
                    open(patientId, AlertType.RAPID_FALL, latest, r);
                    resolve(patientId, AlertType.RAPID_RISE);
                } else if (rate.compareTo(r.rapidRate()) >= 0) {
                    open(patientId, AlertType.RAPID_RISE, latest, r);
                    resolve(patientId, AlertType.RAPID_FALL);
                } else {
                    resolve(patientId, AlertType.RAPID_FALL);
                    resolve(patientId, AlertType.RAPID_RISE);
                }
            });
        }
    }

    /** 미수신 검사: 활성 데이터 소스가 있는 환자만. 스케줄러가 1분마다 호출. */
    public void checkNoData() {
        Instant now = clock.instant();
        List<Object[]> rows = jdbc.query("""
                SELECT pp.id, ar.no_data_minutes, ar.no_data_enabled,
                       (SELECT max(measured_at) FROM glucose_readings g WHERE g.patient_id = pp.id) AS last_measured,
                       (SELECT min(created_at) FROM data_sources d WHERE d.patient_id = pp.id AND d.status = 'ACTIVE') AS source_since
                FROM patient_profiles pp JOIN alert_rules ar ON ar.patient_id = pp.id
                WHERE EXISTS (SELECT 1 FROM data_sources d WHERE d.patient_id = pp.id AND d.status = 'ACTIVE')""",
                (rs, i) -> new Object[]{uuid(rs, "id"), rs.getInt("no_data_minutes"), rs.getBoolean("no_data_enabled"),
                        instant(rs, "last_measured"), instant(rs, "source_since")});
        for (Object[] row : rows) {
            UUID pid = (UUID) row[0];
            Instant threshold = now.minus(Duration.ofMinutes((Integer) row[1]));
            Instant reference = row[3] != null ? (Instant) row[3] : (Instant) row[4];
            boolean missing = reference.isBefore(threshold);
            if (missing && (Boolean) row[2]) open(pid, AlertType.NO_DATA, null, rules.get(pid));
            else if (!missing) resolve(pid, AlertType.NO_DATA);
        }
    }

    public void onSyncResult(UUID patientId, boolean success, int consecutiveFailures) {
        if (success) resolve(patientId, AlertType.SYNC_ERROR);
        else if (consecutiveFailures >= SYNC_FAILURE_THRESHOLD) open(patientId, AlertType.SYNC_ERROR, null, rules.get(patientId));
    }

    /** 확인하지 않은 열린 알림을 repeat_minutes 마다 다시 보낸다. */
    public void sendReminders() {
        Instant now = clock.instant();
        List<Object[]> due = jdbc.query("""
                SELECT a.id, a.patient_id, a.type FROM alerts a JOIN alert_rules r ON r.patient_id = a.patient_id
                WHERE a.status = 'OPEN' AND a.last_notified_at <= CAST(:now AS timestamptz) - make_interval(mins => r.repeat_minutes)""",
                new MapSqlParameterSource("now", ts(now)),
                (rs, i) -> new Object[]{uuid(rs, "id"), uuid(rs, "patient_id"), AlertType.valueOf(rs.getString("type"))});
        for (Object[] d : due) {
            int n = jdbc.update("UPDATE alerts SET last_notified_at = :t WHERE id = :id AND status = 'OPEN'",
                    new MapSqlParameterSource().addValue("t", ts(now)).addValue("id", d[0]));
            if (n == 1) notifications.notifyAlert((UUID) d[0], (UUID) d[1], (AlertType) d[2], true);
        }
    }

    boolean isActive(UUID patientId, AlertType type) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM alerts WHERE patient_id = :p AND type = :t AND status <> 'RESOLVED')",
                new MapSqlParameterSource().addValue("p", patientId).addValue("t", type.name()), Boolean.class));
    }

    private void open(UUID patientId, AlertType type, GlucoseReading reading, AlertRules r) {
        Instant now = clock.instant();
        Boolean inCooldown = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM alerts WHERE patient_id = :p AND type = :t AND status = 'RESOLVED'
                               AND resolved_at > :since)""",
                new MapSqlParameterSource().addValue("p", patientId).addValue("t", type.name())
                        .addValue("since", ts(now.minus(Duration.ofMinutes(r.cooldownMinutes())))), Boolean.class);
        if (Boolean.TRUE.equals(inCooldown)) return;
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO alerts (id, patient_id, type, status, reading_id, measured_at, opened_at, last_notified_at)
                VALUES (:id, :p, :t, 'OPEN', :r, :m, :now, :now)
                ON CONFLICT (patient_id, type) WHERE status <> 'RESOLVED' DO NOTHING""",
                new MapSqlParameterSource().addValue("id", id).addValue("p", patientId).addValue("t", type.name())
                        .addValue("r", reading == null ? null : reading.id())
                        .addValue("m", reading == null ? null : ts(reading.measuredAt())).addValue("now", ts(now)));
        if (inserted == 1) notifications.notifyAlert(id, patientId, type, false);
    }

    private void resolve(UUID patientId, AlertType type) {
        jdbc.update("UPDATE alerts SET status = 'RESOLVED', resolved_at = :t WHERE patient_id = :p AND type = :ty AND status <> 'RESOLVED'",
                new MapSqlParameterSource().addValue("t", ts(clock.instant())).addValue("p", patientId).addValue("ty", type.name()));
    }
}
