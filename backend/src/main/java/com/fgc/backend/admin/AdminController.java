package com.fgc.backend.admin;

import com.fgc.backend.common.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.fgc.backend.common.Auth.userId;

/** 관리자: 시스템·동기화·전송 상태. 혈당값 등 건강정보는 노출하지 않는다. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final NamedParameterJdbcTemplate jdbc;

    public AdminController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void requireAdmin(Jwt jwt) {
        Boolean admin = jdbc.queryForObject("SELECT COALESCE((SELECT admin FROM users WHERE id = :u AND deleted_at IS NULL), false)",
                new MapSqlParameterSource("u", userId(jwt)), Boolean.class);
        if (!Boolean.TRUE.equals(admin)) throw ApiException.forbidden();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM users WHERE deleted_at IS NULL) AS users,
                       (SELECT count(*) FROM patient_profiles) AS patients,
                       (SELECT count(*) FROM family_members WHERE status = 'ACTIVE') AS active_family_links,
                       (SELECT count(*) FROM data_sources WHERE status = 'ACTIVE') AS active_sources,
                       (SELECT count(*) FROM data_sources WHERE consecutive_failures >= 3) AS failing_sources,
                       (SELECT count(*) FROM alerts WHERE status <> 'RESOLVED') AS active_alerts,
                       (SELECT count(*) FROM notification_deliveries WHERE status = 'PENDING') AS pending_deliveries,
                       (SELECT count(*) FROM notification_deliveries WHERE status = 'FAILED') AS failed_deliveries,
                       (SELECT count(*) FROM notification_deliveries WHERE status = 'NO_DEVICE') AS undeliverable,
                       (SELECT count(*) FROM notion_sync WHERE status = 'FAILED') AS notion_failures""", Map.of());
    }

    @GetMapping("/sync-status")
    public List<Map<String, Object>> sync(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return jdbc.queryForList("""
                SELECT d.id, d.patient_id, d.type, d.status, d.consecutive_failures, d.last_success_at, d.last_error, d.last_error_at,
                       (SELECT json_build_object('status', h.status, 'fetched', h.fetched, 'inserted', h.inserted,
                               'duplicates', h.duplicates, 'rejected', h.rejected, 'at', h.finished_at)
                          FROM sync_history h WHERE h.data_source_id = d.id ORDER BY h.started_at DESC LIMIT 1)::text AS last_sync
                FROM data_sources d ORDER BY d.consecutive_failures DESC, d.last_success_at NULLS FIRST""", Map.of());
    }

    @GetMapping("/deliveries/failed")
    public List<Map<String, Object>> failedDeliveries(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return jdbc.queryForList("""
                SELECT id, alert_id, recipient_user_id, kind, status, attempts, last_error, provider, created_at
                FROM notification_deliveries WHERE status IN ('FAILED', 'NO_DEVICE') ORDER BY created_at DESC LIMIT 200""", Map.of());
    }
}
