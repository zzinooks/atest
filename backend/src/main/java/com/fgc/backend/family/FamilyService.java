package com.fgc.backend.family;

import com.fgc.backend.common.ApiException;
import com.fgc.backend.common.AuditService;
import com.fgc.backend.common.Secrets;
import com.fgc.backend.notify.NotificationService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.fgc.backend.common.Db.*;

@Service
public class FamilyService {
    static final Duration INVITE_TTL = Duration.ofHours(48);

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public FamilyService(NamedParameterJdbcTemplate jdbc, AccessPolicy access, AuditService audit,
                         NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    public record Permissions(boolean viewGlucose, boolean receiveAlerts, boolean writeNotes) {
        String describe() { return "view=" + viewGlucose + ",alerts=" + receiveAlerts + ",notes=" + writeNotes; }
    }

    public record Invitation(String code, Instant expiresAt) {}

    public record Member(UUID memberId, UUID userId, String displayName, String relation, Permissions permissions,
                         String status, Instant joinedAt, Instant revokedAt) {}

    @Transactional
    public Invitation invite(UUID actor, UUID patientId, String relation, Permissions p) {
        access.requireOwner(actor, patientId);
        if (!p.viewGlucose() && p.receiveAlerts()) throw ApiException.badRequest("receiveAlerts requires viewGlucose");
        String code = Secrets.inviteCode();
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO sharing_invitations (id, group_id, code_hash, relation, can_view_glucose, can_receive_alerts,
                    can_write_notes, expires_at, created_at)
                SELECT :id, g.id, :h, :r, :v, :a, :n, :e, :c FROM family_groups g WHERE g.patient_id = :p""",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("h", Secrets.sha256Hex(code))
                        .addValue("r", relation).addValue("v", p.viewGlucose()).addValue("a", p.receiveAlerts())
                        .addValue("n", p.writeNotes()).addValue("e", ts(now.plus(INVITE_TTL))).addValue("c", ts(now))
                        .addValue("p", patientId));
        audit.log(actor, patientId, "INVITATION_CREATED", "relation=" + relation);
        return new Invitation(code, now.plus(INVITE_TTL));
    }

    /** 초대 수락 = 가족이 초대 코드를 입력. 환자가 초대를 만든 것 자체가 공유 동의다. */
    @Transactional
    public UUID accept(UUID userId, String code) {
        Instant now = clock.instant();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.id, i.group_id, i.relation, i.can_view_glucose, i.can_receive_alerts, i.can_write_notes,
                       i.expires_at, i.accepted_at, i.revoked_at, g.patient_id, pp.user_id AS owner
                FROM sharing_invitations i JOIN family_groups g ON g.id = i.group_id
                JOIN patient_profiles pp ON pp.id = g.patient_id
                WHERE i.code_hash = :h FOR UPDATE OF i""",
                new MapSqlParameterSource("h", Secrets.sha256Hex(code.trim().toUpperCase())));
        if (rows.isEmpty()) throw ApiException.notFound("Invitation");
        Map<String, Object> i = rows.get(0);
        if (i.get("accepted_at") != null || i.get("revoked_at") != null
                || !now.isBefore(((java.sql.Timestamp) i.get("expires_at")).toInstant())) {
            throw ApiException.conflict("Invitation already used, revoked or expired");
        }
        UUID patientId = (UUID) i.get("patient_id");
        if (userId.equals(i.get("owner"))) throw ApiException.badRequest("Cannot join your own family group");
        MapSqlParameterSource m = new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                .addValue("g", i.get("group_id")).addValue("u", userId).addValue("r", i.get("relation"))
                .addValue("v", i.get("can_view_glucose")).addValue("a", i.get("can_receive_alerts"))
                .addValue("n", i.get("can_write_notes")).addValue("t", ts(now));
        jdbc.update("""
                INSERT INTO family_members (id, group_id, user_id, relation, can_view_glucose, can_receive_alerts,
                    can_write_notes, status, joined_at)
                VALUES (:id, :g, :u, :r, :v, :a, :n, 'ACTIVE', :t)
                ON CONFLICT (group_id, user_id) DO UPDATE SET relation = EXCLUDED.relation,
                    can_view_glucose = EXCLUDED.can_view_glucose, can_receive_alerts = EXCLUDED.can_receive_alerts,
                    can_write_notes = EXCLUDED.can_write_notes, status = 'ACTIVE', joined_at = EXCLUDED.joined_at,
                    revoked_at = NULL""", m);
        jdbc.update("UPDATE sharing_invitations SET accepted_by = :u, accepted_at = :t WHERE id = :id",
                new MapSqlParameterSource().addValue("u", userId).addValue("t", ts(now)).addValue("id", i.get("id")));
        Permissions p = new Permissions((Boolean) i.get("can_view_glucose"), (Boolean) i.get("can_receive_alerts"),
                (Boolean) i.get("can_write_notes"));
        consent(patientId, userId, userId, "GRANTED", p);
        audit.log(userId, patientId, "INVITATION_ACCEPTED", null);
        notifications.notifyUser((UUID) i.get("owner"), "SHARE_CHANGE", "가족 연결", "새 가족 구성원이 혈당 공유에 참여했습니다.");
        return patientId;
    }

    public List<Member> members(UUID actor, UUID patientId) {
        access.requireOwner(actor, patientId);
        return jdbc.query("""
                SELECT m.id, m.user_id, u.display_name, m.relation, m.can_view_glucose, m.can_receive_alerts,
                       m.can_write_notes, m.status, m.joined_at, m.revoked_at
                FROM family_members m JOIN family_groups g ON g.id = m.group_id JOIN users u ON u.id = m.user_id
                WHERE g.patient_id = :p ORDER BY m.joined_at""",
                new MapSqlParameterSource("p", patientId), (rs, n) -> new Member(uuid(rs, "id"), uuid(rs, "user_id"),
                        rs.getString("display_name"), rs.getString("relation"),
                        new Permissions(rs.getBoolean("can_view_glucose"), rs.getBoolean("can_receive_alerts"),
                                rs.getBoolean("can_write_notes")),
                        rs.getString("status"), instant(rs, "joined_at"), instant(rs, "revoked_at")));
    }

    @Transactional
    public void updatePermissions(UUID actor, UUID patientId, UUID memberId, Permissions p) {
        access.requireOwner(actor, patientId);
        if (!p.viewGlucose() && p.receiveAlerts()) throw ApiException.badRequest("receiveAlerts requires viewGlucose");
        UUID memberUser = memberUser(patientId, memberId, true);
        jdbc.update("""
                UPDATE family_members SET can_view_glucose = :v, can_receive_alerts = :a, can_write_notes = :n
                WHERE id = :id""", new MapSqlParameterSource().addValue("v", p.viewGlucose())
                .addValue("a", p.receiveAlerts()).addValue("n", p.writeNotes()).addValue("id", memberId));
        consent(patientId, memberUser, actor, "UPDATED", p);
        audit.log(actor, patientId, "MEMBER_PERMISSIONS_UPDATED", p.describe());
        notifications.notifyUser(memberUser, "SHARE_CHANGE", "공유 권한 변경", "혈당 공유 권한이 변경되었습니다.");
    }

    /** 공유 해제: 즉시 접근 회수 (같은 트랜잭션에서 REVOKED). */
    @Transactional
    public void revoke(UUID actor, UUID patientId, UUID memberId) {
        access.requireOwner(actor, patientId);
        UUID memberUser = memberUser(patientId, memberId, true);
        jdbc.update("UPDATE family_members SET status = 'REVOKED', revoked_at = :t WHERE id = :id",
                new MapSqlParameterSource().addValue("t", ts(clock.instant())).addValue("id", memberId));
        consent(patientId, memberUser, actor, "REVOKED", new Permissions(false, false, false));
        audit.log(actor, patientId, "MEMBER_REVOKED", null);
        notifications.notifyUser(memberUser, "SHARE_CHANGE", "공유 해제", "혈당 공유가 해제되었습니다.");
    }

    /** 가족이 스스로 공유에서 나간다. */
    @Transactional
    public void leave(UUID userId, UUID patientId) {
        int n = jdbc.update("""
                UPDATE family_members m SET status = 'REVOKED', revoked_at = :t FROM family_groups g
                WHERE g.id = m.group_id AND g.patient_id = :p AND m.user_id = :u AND m.status = 'ACTIVE'""",
                new MapSqlParameterSource().addValue("t", ts(clock.instant())).addValue("p", patientId).addValue("u", userId));
        if (n == 0) throw ApiException.notFound("Membership");
        consent(patientId, userId, userId, "LEFT", new Permissions(false, false, false));
        audit.log(userId, patientId, "MEMBER_LEFT", null);
    }

    private UUID memberUser(UUID patientId, UUID memberId, boolean activeOnly) {
        List<UUID> r = jdbc.queryForList("""
                SELECT m.user_id FROM family_members m JOIN family_groups g ON g.id = m.group_id
                WHERE m.id = :id AND g.patient_id = :p""" + (activeOnly ? " AND m.status = 'ACTIVE'" : ""),
                new MapSqlParameterSource().addValue("id", memberId).addValue("p", patientId), UUID.class);
        if (r.isEmpty()) throw ApiException.notFound("Member");
        return r.get(0);
    }

    private void consent(UUID patientId, UUID member, UUID actor, String action, Permissions p) {
        jdbc.update("""
                INSERT INTO sharing_consents (patient_id, member_user_id, actor_user_id, action, permissions, created_at)
                VALUES (:p, :m, :a, :act, :perm, :t)""", new MapSqlParameterSource().addValue("p", patientId)
                .addValue("m", member).addValue("a", actor).addValue("act", action).addValue("perm", p.describe())
                .addValue("t", ts(clock.instant())));
    }
}
