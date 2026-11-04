package com.fgc.backend.notify;

import com.fgc.backend.alert.AlertType;
import com.fgc.backend.family.AccessPolicy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.fgc.backend.common.Db.ts;

/**
 * 알림을 전송 큐(notification_deliveries)에 넣는다. 실제 전송은 NotificationDispatcher.
 * 알림 문구에 혈당 숫자를 넣지 않는다 (잠금화면 노출 방지, ARCH-001 2.4).
 */
@Service
public class NotificationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final Clock clock;

    public NotificationService(NamedParameterJdbcTemplate jdbc, AccessPolicy access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    public void notifyAlert(UUID alertId, UUID patientId, AlertType type, boolean reminder) {
        UUID patientUser = jdbc.queryForObject("SELECT user_id FROM patient_profiles WHERE id = :p",
                new MapSqlParameterSource("p", patientId), UUID.class);
        String patientName = jdbc.queryForObject("SELECT display_name FROM users WHERE id = :u",
                new MapSqlParameterSource("u", patientUser), String.class);
        String title = (reminder ? "[재알림] " : "") + type.label;
        for (UUID recipient : access.alertRecipients(patientId)) {
            String body = recipient.equals(patientUser)
                    ? "혈당 상태를 확인하세요. 앱에서 자세히 볼 수 있습니다."
                    : patientName + "님의 혈당 확인이 필요합니다. 앱에서 자세히 볼 수 있습니다.";
            enqueue(recipient, alertId, "ALERT", title, body);
        }
    }

    public void notifyUser(UUID userId, String kind, String title, String body) {
        enqueue(userId, null, kind, title, body);
    }

    private void enqueue(UUID userId, UUID alertId, String kind, String title, String body) {
        Instant now = clock.instant();
        List<UUID> devices = jdbc.queryForList("SELECT id FROM devices WHERE user_id = :u AND disabled_at IS NULL",
                new MapSqlParameterSource("u", userId), UUID.class);
        if (devices.isEmpty()) { // 기기가 없어 알릴 수 없음 → 기록해서 관리자·앱이 볼 수 있게
            insert(userId, null, alertId, kind, title, body, "NO_DEVICE", now);
            return;
        }
        for (UUID d : devices) insert(userId, d, alertId, kind, title, body, "PENDING", now);
    }

    private void insert(UUID userId, UUID deviceId, UUID alertId, String kind, String title, String body, String status, Instant now) {
        jdbc.update("""
                INSERT INTO notification_deliveries (alert_id, recipient_user_id, device_id, kind, title, body, status,
                    next_attempt_at, created_at) VALUES (:a, :u, :d, :k, :ti, :b, :s, :n, :n)""",
                new MapSqlParameterSource().addValue("a", alertId).addValue("u", userId).addValue("d", deviceId)
                        .addValue("k", kind).addValue("ti", title).addValue("b", body).addValue("s", status).addValue("n", ts(now)));
    }
}
