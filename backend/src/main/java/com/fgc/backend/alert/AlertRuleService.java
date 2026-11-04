package com.fgc.backend.alert;

import com.fgc.backend.common.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static com.fgc.backend.common.Db.ts;

@Service
public class AlertRuleService {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AlertRuleService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public AlertRules get(UUID patientId) {
        List<AlertRules> r = jdbc.query("SELECT * FROM alert_rules WHERE patient_id = :p", new MapSqlParameterSource("p", patientId),
                (rs, i) -> new AlertRules(rs.getInt("urgent_low_mgdl"), rs.getInt("low_mgdl"), rs.getInt("high_mgdl"),
                        rs.getBigDecimal("rapid_rate"), rs.getInt("no_data_minutes"), rs.getInt("stale_minutes"),
                        rs.getInt("repeat_minutes"), rs.getInt("cooldown_minutes"), rs.getBoolean("low_enabled"),
                        rs.getBoolean("high_enabled"), rs.getBoolean("rapid_enabled"), rs.getBoolean("no_data_enabled")));
        if (r.isEmpty()) throw ApiException.notFound("Patient");
        return r.get(0);
    }

    public void createDefaults(UUID patientId) {
        upsert(patientId, AlertRules.defaults());
    }

    public void upsert(UUID patientId, AlertRules r) {
        jdbc.update("""
                INSERT INTO alert_rules (patient_id, urgent_low_mgdl, low_mgdl, high_mgdl, rapid_rate, no_data_minutes,
                    stale_minutes, repeat_minutes, cooldown_minutes, low_enabled, high_enabled, rapid_enabled,
                    no_data_enabled, updated_at)
                VALUES (:p, :ul, :l, :h, :rr, :nd, :st, :rep, :cd, :le, :he, :re, :ne, :t)
                ON CONFLICT (patient_id) DO UPDATE SET urgent_low_mgdl = :ul, low_mgdl = :l, high_mgdl = :h,
                    rapid_rate = :rr, no_data_minutes = :nd, stale_minutes = :st, repeat_minutes = :rep,
                    cooldown_minutes = :cd, low_enabled = :le, high_enabled = :he, rapid_enabled = :re,
                    no_data_enabled = :ne, updated_at = :t""",
                new MapSqlParameterSource().addValue("p", patientId).addValue("ul", r.urgentLowMgdl())
                        .addValue("l", r.lowMgdl()).addValue("h", r.highMgdl()).addValue("rr", r.rapidRate())
                        .addValue("nd", r.noDataMinutes()).addValue("st", r.staleMinutes())
                        .addValue("rep", r.repeatMinutes()).addValue("cd", r.cooldownMinutes())
                        .addValue("le", r.lowEnabled()).addValue("he", r.highEnabled())
                        .addValue("re", r.rapidEnabled()).addValue("ne", r.noDataEnabled())
                        .addValue("t", ts(clock.instant())));
    }
}
