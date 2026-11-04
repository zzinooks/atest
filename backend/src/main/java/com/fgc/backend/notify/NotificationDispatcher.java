package com.fgc.backend.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.fgc.backend.common.Db.*;

/** 전송 큐 처리: 재시도 1·2·4·8분 후, 5번째 실패에서 FAILED. 여러 인스턴스에서도 SKIP LOCKED 로 중복 전송 방지. */
@Service
public class NotificationDispatcher {
    static final int MAX_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationSender sender;
    private final Clock clock;

    public NotificationDispatcher(NamedParameterJdbcTemplate jdbc, NotificationSender sender, Clock clock) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.clock = clock;
    }

    private record Job(long id, UUID deviceId, String token, String title, String body, String kind, UUID alertId, int attempts) {}

    @Transactional
    public int dispatchDue() {
        Instant now = clock.instant();
        List<Job> jobs = jdbc.query("""
                SELECT n.id, n.device_id, d.push_token, n.title, n.body, n.kind, n.alert_id, n.attempts
                FROM notification_deliveries n JOIN devices d ON d.id = n.device_id
                WHERE n.status = 'PENDING' AND n.next_attempt_at <= :now
                ORDER BY n.next_attempt_at LIMIT 100 FOR UPDATE OF n SKIP LOCKED""",
                new MapSqlParameterSource("now", ts(now)),
                (rs, i) -> new Job(rs.getLong("id"), uuid(rs, "device_id"), rs.getString("push_token"), rs.getString("title"),
                        rs.getString("body"), rs.getString("kind"), uuid(rs, "alert_id"), rs.getInt("attempts")));
        for (Job j : jobs) {
            Map<String, String> data = new HashMap<>();
            data.put("kind", j.kind());
            if (j.alertId() != null) data.put("alertId", j.alertId().toString());
            NotificationSender.Result r;
            try {
                r = sender.send(j.token(), j.title(), j.body(), data);
            } catch (RuntimeException e) {
                r = new NotificationSender.Result(NotificationSender.Outcome.RETRYABLE_ERROR, null, e.getClass().getSimpleName());
            }
            int attempts = j.attempts() + 1;
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", j.id()).addValue("a", attempts)
                    .addValue("now", ts(now)).addValue("prov", sender.provider()).addValue("err", r.error())
                    .addValue("mid", r.providerMessageId());
            switch (r.outcome()) {
                case SENT -> jdbc.update("""
                        UPDATE notification_deliveries SET status = 'SENT', attempts = :a, sent_at = :now, provider = :prov,
                            provider_message_id = :mid, last_error = NULL WHERE id = :id""", p);
                case INVALID_TOKEN, PERMANENT_ERROR -> {
                    jdbc.update("""
                            UPDATE notification_deliveries SET status = 'FAILED', attempts = :a, provider = :prov,
                                last_error = :err WHERE id = :id""", p);
                    if (r.outcome() == NotificationSender.Outcome.INVALID_TOKEN) {
                        jdbc.update("UPDATE devices SET disabled_at = :now WHERE id = :d",
                                new MapSqlParameterSource().addValue("now", ts(now)).addValue("d", j.deviceId()));
                    }
                }
                case RETRYABLE_ERROR -> {
                    boolean giveUp = attempts >= MAX_ATTEMPTS;
                    p.addValue("next", ts(now.plus(Duration.ofMinutes(1L << (attempts - 1)))));
                    jdbc.update("UPDATE notification_deliveries SET status = " + (giveUp ? "'FAILED'" : "'PENDING'") + """
                            , attempts = :a, provider = :prov, last_error = :err, next_attempt_at = :next WHERE id = :id""", p);
                    log.warn("push delivery {} attempt {} failed: {}", j.id(), attempts, r.error());
                }
            }
        }
        return jobs.size();
    }
}
