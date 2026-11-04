package com.fgc.backend.glucose;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.fgc.backend.common.Db.*;

@Repository
public class GlucoseRepository {
    private static final RowMapper<GlucoseReading> MAPPER = (rs, i) -> new GlucoseReading(
            rs.getLong("id"), uuid(rs, "patient_id"), integer(rs, "value_mgdl"), Flag.valueOf(rs.getString("flag")),
            instant(rs, "measured_at"), rs.getString("measured_offset"), instant(rs, "received_at"),
            GlucoseSource.valueOf(rs.getString("source")), rs.getString("external_id"), rs.getString("source_trend"));

    private final NamedParameterJdbcTemplate jdbc;

    public GlucoseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 실제로 새로 들어간 건수 (중복은 ON CONFLICT 로 무시) */
    public int insertIgnoringDuplicates(UUID patientId, GlucoseSource source, List<GlucoseDataNormalizer.Normalized> rows, Instant now) {
        if (rows.isEmpty()) return 0;
        SqlParameterSource[] batch = rows.stream().map(r -> new MapSqlParameterSource()
                .addValue("p", patientId).addValue("v", r.valueMgdl()).addValue("f", r.flag().name())
                .addValue("m", ts(r.measuredAt())).addValue("o", r.offset()).addValue("rcv", ts(now))
                .addValue("s", source.name()).addValue("x", r.externalId()).addValue("tr", r.sourceTrend())
                .addValue("c", ts(now))).toArray(SqlParameterSource[]::new);
        int[] res = jdbc.batchUpdate("""
                INSERT INTO glucose_readings (patient_id, value_mgdl, flag, measured_at, measured_offset, received_at,
                    source, external_id, source_trend, created_at)
                VALUES (:p, :v, :f, :m, :o, :rcv, :s, :x, :tr, :c)
                ON CONFLICT (patient_id, source, external_id) DO NOTHING""", batch);
        int n = 0;
        for (int r : res) n += Math.max(r, 0);
        return n;
    }

    public List<GlucoseReading> between(UUID patientId, Instant from, Instant to) {
        return jdbc.query("""
                SELECT * FROM glucose_readings WHERE patient_id = :p AND measured_at >= :f AND measured_at < :t
                ORDER BY measured_at""", new MapSqlParameterSource().addValue("p", patientId)
                .addValue("f", ts(from)).addValue("t", ts(to)), MAPPER);
    }

    public Optional<GlucoseReading> latest(UUID patientId) {
        return jdbc.query("SELECT * FROM glucose_readings WHERE patient_id = :p ORDER BY measured_at DESC LIMIT 1",
                new MapSqlParameterSource("p", patientId), MAPPER).stream().findFirst();
    }

    public Optional<Instant> latestMeasuredAt(UUID patientId, GlucoseSource source) {
        return jdbc.query("SELECT max(measured_at) AS m FROM glucose_readings WHERE patient_id = :p AND source = :s",
                new MapSqlParameterSource().addValue("p", patientId).addValue("s", source.name()),
                (rs, i) -> instant(rs, "m")).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /** 기준 시각 근처(±window)에서 값이 있는 측정값 중 가장 가까운 것 */
    public Optional<GlucoseReading> nearest(UUID patientId, Instant at, java.time.Duration window) {
        return jdbc.query("""
                SELECT * FROM glucose_readings WHERE patient_id = :p AND value_mgdl IS NOT NULL
                  AND measured_at BETWEEN :f AND :t
                ORDER BY abs(extract(epoch FROM measured_at - :at)) LIMIT 1""",
                new MapSqlParameterSource().addValue("p", patientId).addValue("f", ts(at.minus(window)))
                        .addValue("t", ts(at.plus(window))).addValue("at", ts(at)), MAPPER).stream().findFirst();
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM glucose_readings WHERE measured_at < :c", new MapSqlParameterSource("c", ts(cutoff)));
    }
}
