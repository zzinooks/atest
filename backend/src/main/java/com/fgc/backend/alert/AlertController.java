package com.fgc.backend.alert;

import com.fgc.backend.common.ApiException;
import com.fgc.backend.common.AuditService;
import com.fgc.backend.family.AccessPolicy;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.fgc.backend.common.Auth.userId;
import static com.fgc.backend.common.Db.*;
import static com.fgc.backend.family.AccessPolicy.Permission.VIEW_GLUCOSE;

@RestController
@RequestMapping("/api/v1")
public class AlertController {
    private final AlertRuleService rules;
    private final AccessPolicy access;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AlertController(AlertRuleService rules, AccessPolicy access, AuditService audit, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.rules = rules;
        this.access = access;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record AlertDto(UUID id, AlertType type, String label, String status, Instant measuredAt, Instant openedAt,
                           Instant acknowledgedAt, String acknowledgedBy, Instant resolvedAt) {}

    @GetMapping("/patients/{patientId}/alert-rules")
    public AlertRules getRules(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        access.require(userId(jwt), patientId, VIEW_GLUCOSE);
        return rules.get(patientId);
    }

    @PutMapping("/patients/{patientId}/alert-rules")
    public AlertRules putRules(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @Valid @RequestBody AlertRules r) {
        access.requireOwner(userId(jwt), patientId);
        rules.upsert(patientId, r);
        audit.log(userId(jwt), patientId, "ALERT_RULES_UPDATED", null);
        return rules.get(patientId);
    }

    @GetMapping("/patients/{patientId}/alerts")
    public List<AlertDto> alerts(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                 @RequestParam(defaultValue = "false") boolean activeOnly,
                                 @RequestParam(defaultValue = "50") int limit) {
        access.require(userId(jwt), patientId, VIEW_GLUCOSE);
        return jdbc.query("""
                SELECT a.*, u.display_name AS ack_name FROM alerts a LEFT JOIN users u ON u.id = a.acknowledged_by
                WHERE a.patient_id = :p""" + (activeOnly ? " AND a.status <> 'RESOLVED'" : "") +
                        " ORDER BY a.opened_at DESC LIMIT :l",
                new MapSqlParameterSource().addValue("p", patientId).addValue("l", Math.min(Math.max(limit, 1), 200)),
                (rs, i) -> {
                    AlertType t = AlertType.valueOf(rs.getString("type"));
                    return new AlertDto(uuid(rs, "id"), t, t.label, rs.getString("status"), instant(rs, "measured_at"),
                            instant(rs, "opened_at"), instant(rs, "acknowledged_at"), rs.getString("ack_name"),
                            instant(rs, "resolved_at"));
                });
    }

    /** 알림 확인: 재알림을 멈춘다. 해소(RESOLVED)는 혈당이 기준 안으로 돌아올 때 엔진이 한다. */
    @PostMapping("/alerts/{alertId}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ack(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID alertId) {
        UUID uid = userId(jwt);
        List<UUID> p = jdbc.queryForList("SELECT patient_id FROM alerts WHERE id = :id", new MapSqlParameterSource("id", alertId), UUID.class);
        if (p.isEmpty()) throw ApiException.notFound("Alert");
        access.require(uid, p.get(0), AccessPolicy.Permission.RECEIVE_ALERTS);
        jdbc.update("""
                UPDATE alerts SET status = 'ACKNOWLEDGED', acknowledged_at = :t, acknowledged_by = :u
                WHERE id = :id AND status = 'OPEN'""",
                new MapSqlParameterSource().addValue("t", ts(clock.instant())).addValue("u", uid).addValue("id", alertId));
        audit.log(uid, p.get(0), "ALERT_ACKNOWLEDGED", null);
    }
}
