package com.fgc.backend.common;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/** 감사 로그. detail 에 혈당값·토큰 등 민감정보를 넣지 않는다. */
@Service
public class AuditService {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AuditService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void log(UUID actor, UUID patientId, String action, String detail) {
        jdbc.update("INSERT INTO audit_logs (actor_user_id, patient_id, action, detail, created_at) VALUES (:a, :p, :act, :d, :t)",
                new MapSqlParameterSource().addValue("a", actor).addValue("p", patientId).addValue("act", action)
                        .addValue("d", detail).addValue("t", Timestamp.from(clock.instant())));
    }
}
