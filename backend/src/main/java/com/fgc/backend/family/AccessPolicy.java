package com.fgc.backend.family;

import com.fgc.backend.common.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 환자 데이터 접근 권한의 단일 판단 지점 (ARCH-001 2.3).
 * 권한은 토큰이 아니라 매 요청 DB 에서 확인하므로 공유 해제가 즉시 반영된다.
 */
@Component
public class AccessPolicy {
    public enum Permission { VIEW_GLUCOSE, RECEIVE_ALERTS, WRITE_NOTES }

    private final NamedParameterJdbcTemplate jdbc;

    public AccessPolicy(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> patientIdOf(UUID userId) {
        List<UUID> r = jdbc.queryForList("SELECT id FROM patient_profiles WHERE user_id = :u",
                new MapSqlParameterSource("u", userId), UUID.class);
        return r.stream().findFirst();
    }

    public boolean isOwner(UUID userId, UUID patientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM patient_profiles WHERE id = :p AND user_id = :u)",
                new MapSqlParameterSource().addValue("p", patientId).addValue("u", userId), Boolean.class));
    }

    public boolean memberHas(UUID userId, UUID patientId, Permission p) {
        String col = switch (p) {
            case VIEW_GLUCOSE -> "can_view_glucose";
            case RECEIVE_ALERTS -> "can_receive_alerts";
            case WRITE_NOTES -> "can_write_notes";
        };
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM family_members m JOIN family_groups g ON g.id = m.group_id
                               JOIN users u ON u.id = m.user_id
                               WHERE g.patient_id = :p AND m.user_id = :u AND m.status = 'ACTIVE'
                                 AND u.deleted_at IS NULL AND m.%s)""".formatted(col),
                new MapSqlParameterSource().addValue("p", patientId).addValue("u", userId), Boolean.class));
    }

    public void requireOwner(UUID userId, UUID patientId) {
        if (!isOwner(userId, patientId)) throw ApiException.forbidden();
    }

    /** @return true 면 본인, false 면 권한 있는 가족 */
    public boolean require(UUID userId, UUID patientId, Permission p) {
        if (isOwner(userId, patientId)) return true;
        if (memberHas(userId, patientId, p)) return false;
        throw ApiException.forbidden();
    }

    /** 알림 수신자: 환자 본인 + 알림 권한이 있는 ACTIVE 가족 (탈퇴 사용자 제외) */
    public List<UUID> alertRecipients(UUID patientId) {
        return jdbc.queryForList("""
                SELECT pp.user_id FROM patient_profiles pp JOIN users u ON u.id = pp.user_id
                 WHERE pp.id = :p AND u.deleted_at IS NULL
                UNION
                SELECT m.user_id FROM family_members m JOIN family_groups g ON g.id = m.group_id JOIN users u ON u.id = m.user_id
                 WHERE g.patient_id = :p AND m.status = 'ACTIVE' AND m.can_receive_alerts AND u.deleted_at IS NULL""",
                new MapSqlParameterSource("p", patientId), UUID.class);
    }
}
