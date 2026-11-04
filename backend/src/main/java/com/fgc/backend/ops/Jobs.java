package com.fgc.backend.ops;

import com.fgc.backend.alert.AlertEngine;
import com.fgc.backend.config.FgcProperties;
import com.fgc.backend.glucose.GlucoseRepository;
import com.fgc.backend.notify.NotificationDispatcher;
import com.fgc.backend.notion.NotionSyncService;
import com.fgc.backend.source.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static com.fgc.backend.common.Db.ts;

/** 주기 작업. 각 작업은 서로 독립 — 하나가 실패해도 다른 작업은 계속된다. */
@Component
public class Jobs {
    private static final Logger log = LoggerFactory.getLogger(Jobs.class);

    private final SyncService sync;
    private final AlertEngine alerts;
    private final NotificationDispatcher dispatcher;
    private final NotionSyncService notion;
    private final GlucoseRepository glucose;
    private final NamedParameterJdbcTemplate jdbc;
    private final FgcProperties props;
    private final Clock clock;

    public Jobs(SyncService sync, AlertEngine alerts, NotificationDispatcher dispatcher, NotionSyncService notion,
                GlucoseRepository glucose, NamedParameterJdbcTemplate jdbc, FgcProperties props, Clock clock) {
        this.sync = sync;
        this.alerts = alerts;
        this.dispatcher = dispatcher;
        this.notion = notion;
        this.glucose = glucose;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${fgc.jobs.sync-interval:PT1M}")
    public void syncSources() { run("sync", sync::syncAll); }

    @Scheduled(fixedDelayString = "${fgc.jobs.alert-interval:PT1M}")
    public void alertChecks() {
        run("no-data", alerts::checkNoData);
        run("reminders", alerts::sendReminders);
    }

    @Scheduled(fixedDelayString = "${fgc.jobs.dispatch-interval:PT10S}")
    public void dispatch() { run("dispatch", dispatcher::dispatchDue); }

    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Seoul")
    public void notionDaily() { run("notion-daily", notion::syncDailyForAll); }

    @Scheduled(cron = "0 0 1 * * MON", zone = "Asia/Seoul")
    public void notionWeekly() { run("notion-weekly", notion::syncWeeklyForAll); }

    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Seoul")
    public void retention() {
        run("retention", () -> {
            Instant now = clock.instant();
            int g = glucose.deleteOlderThan(now.minus(Duration.ofDays(props.retentionDays())));
            MapSqlParameterSource p = new MapSqlParameterSource("t", ts(now.minus(Duration.ofDays(365))));
            int a = jdbc.update("DELETE FROM audit_logs WHERE created_at < :t", p);
            int n = jdbc.update("DELETE FROM notification_deliveries WHERE created_at < :t", p);
            int r = jdbc.update("DELETE FROM refresh_tokens WHERE expires_at < :t OR revoked_at < :t",
                    new MapSqlParameterSource("t", ts(now.minus(Duration.ofDays(7)))));
            log.info("retention purge readings={} audit={} deliveries={} tokens={}", g, a, n, r);
        });
    }

    private static void run(String name, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.error("job {} failed: {}", name, e.getClass().getSimpleName(), e);
        }
    }
}
