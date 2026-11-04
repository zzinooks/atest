package com.fgc.backend.user;

import com.fgc.backend.alert.AlertRuleService;
import com.fgc.backend.auth.AuthService;
import com.fgc.backend.auth.TokenService;
import com.fgc.backend.common.ApiException;
import com.fgc.backend.common.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.fgc.backend.common.Db.*;

@Service
public class AccountService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AlertRuleService rules;
    private final AuthService auth;
    private final TokenService tokens;
    private final AuditService audit;
    private final Clock clock;

    public AccountService(NamedParameterJdbcTemplate jdbc, AlertRuleService rules, AuthService auth, TokenService tokens,
                          AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.auth = auth;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    public record FamilyOf(UUID patientId, String displayName, String relation,
                           boolean viewGlucose, boolean receiveAlerts, boolean writeNotes) {}

    public record Me(UUID id, String email, String displayName, boolean admin, UUID patientId, List<FamilyOf> familyOf) {
        @com.fasterxml.jackson.annotation.JsonProperty("isPatient")
        public boolean isPatient() { return patientId != null; }
    }

    public Me me(UUID userId) {
        List<Map<String, Object>> u = jdbc.queryForList("""
                SELECT u.email, u.display_name, u.admin, pp.id AS patient_id FROM users u
                LEFT JOIN patient_profiles pp ON pp.user_id = u.id WHERE u.id = :id AND u.deleted_at IS NULL""",
                new MapSqlParameterSource("id", userId));
        if (u.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "account_inactive", "Account not active");
        List<FamilyOf> fam = jdbc.query("""
                SELECT g.patient_id, pu.display_name, m.relation, m.can_view_glucose, m.can_receive_alerts, m.can_write_notes
                FROM family_members m JOIN family_groups g ON g.id = m.group_id
                JOIN patient_profiles pp ON pp.id = g.patient_id JOIN users pu ON pu.id = pp.user_id
                WHERE m.user_id = :id AND m.status = 'ACTIVE' AND pu.deleted_at IS NULL ORDER BY pu.display_name""",
                new MapSqlParameterSource("id", userId), (rs, i) -> new FamilyOf(uuid(rs, "patient_id"),
                        rs.getString("display_name"), rs.getString("relation"), rs.getBoolean("can_view_glucose"),
                        rs.getBoolean("can_receive_alerts"), rs.getBoolean("can_write_notes")));
        Map<String, Object> r = u.get(0);
        return new Me(userId, (String) r.get("email"), (String) r.get("display_name"), (Boolean) r.get("admin"),
                (UUID) r.get("patient_id"), fam);
    }

    @Transactional
    public UUID createPatientProfile(UUID userId, String timeZone) {
        String tz = timeZone == null || timeZone.isBlank() ? "Asia/Seoul" : timeZone;
        try { ZoneId.of(tz); } catch (DateTimeException e) { throw ApiException.badRequest("Unknown time zone"); }
        List<UUID> existing = jdbc.queryForList("SELECT id FROM patient_profiles WHERE user_id = :u",
                new MapSqlParameterSource("u", userId), UUID.class);
        if (!existing.isEmpty()) throw ApiException.conflict("Patient profile already exists");
        UUID pid = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("INSERT INTO patient_profiles (id, user_id, time_zone, created_at) VALUES (:id, :u, :tz, :c)",
                new MapSqlParameterSource().addValue("id", pid).addValue("u", userId).addValue("tz", tz).addValue("c", ts(now)));
        jdbc.update("INSERT INTO family_groups (id, patient_id, created_at) VALUES (:id, :p, :c)",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("p", pid).addValue("c", ts(now)));
        rules.createDefaults(pid);
        audit.log(userId, pid, "PATIENT_PROFILE_CREATED", null);
        return pid;
    }

    /** 같은 푸시 토큰이 다른 계정에 있으면 이 계정으로 옮긴다 (기기 로그인 계정 변경). */
    public UUID registerDevice(UUID userId, String platform, String token) {
        Instant now = clock.instant();
        return jdbc.queryForObject("""
                INSERT INTO devices (id, user_id, platform, push_token, last_seen_at, created_at)
                VALUES (:id, :u, :pl, :t, :n, :n)
                ON CONFLICT (push_token) DO UPDATE SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform,
                    last_seen_at = EXCLUDED.last_seen_at, disabled_at = NULL
                RETURNING id""", new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("u", userId)
                .addValue("pl", platform).addValue("t", token).addValue("n", ts(now)), UUID.class);
    }

    public void removeDevice(UUID userId, UUID deviceId) {
        int n = jdbc.update("DELETE FROM devices WHERE id = :id AND user_id = :u",
                new MapSqlParameterSource().addValue("id", deviceId).addValue("u", userId));
        if (n == 0) throw ApiException.notFound("Device");
    }

    @Transactional
    public void withdraw(UUID userId, String password) {
        if (!auth.passwordMatches(userId, password)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Password mismatch");
        }
        Instant now = clock.instant();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("u", userId).addValue("t", ts(now));
        // 환자 데이터 전부 삭제 (혈당·알림·기록·데이터 소스·가족 그룹은 FK CASCADE)
        jdbc.update("DELETE FROM patient_profiles WHERE user_id = :u", p);
        jdbc.update("UPDATE family_members SET status = 'REVOKED', revoked_at = :t WHERE user_id = :u AND status = 'ACTIVE'", p);
        jdbc.update("DELETE FROM devices WHERE user_id = :u", p);
        tokens.revokeAll(userId);
        jdbc.update("""
                UPDATE users SET email = 'deleted+' || id || '@invalid', display_name = '탈퇴한 사용자',
                    password_hash = '!', deleted_at = :t WHERE id = :u""", p);
        audit.log(userId, null, "ACCOUNT_WITHDRAWN", null);
    }
}
