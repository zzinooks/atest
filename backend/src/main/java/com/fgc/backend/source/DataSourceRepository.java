package com.fgc.backend.source;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.fgc.backend.common.Db.*;

@Repository
public class DataSourceRepository {
    static final RowMapper<DataSource> MAPPER = (rs, i) -> new DataSource(uuid(rs, "id"), uuid(rs, "patient_id"),
            rs.getString("type"), rs.getString("base_url"), rs.getString("username"), rs.getBytes("secret_ciphertext"),
            rs.getString("status"), rs.getInt("consecutive_failures"), instant(rs, "last_success_at"),
            rs.getString("last_error"), instant(rs, "last_error_at"), instant(rs, "created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public DataSourceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DataSource> find(UUID id) {
        return jdbc.query("SELECT * FROM data_sources WHERE id = :id", new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    public List<DataSource> byPatient(UUID patientId) {
        return jdbc.query("SELECT * FROM data_sources WHERE patient_id = :p ORDER BY created_at", new MapSqlParameterSource("p", patientId), MAPPER);
    }

    public List<DataSource> activeOfType(String type) {
        return jdbc.query("SELECT * FROM data_sources WHERE status = 'ACTIVE' AND type = :t", new MapSqlParameterSource("t", type), MAPPER);
    }
}
