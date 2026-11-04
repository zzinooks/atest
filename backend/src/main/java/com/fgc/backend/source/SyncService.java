package com.fgc.backend.source;

import com.fgc.backend.alert.AlertEngine;
import com.fgc.backend.config.FgcProperties;
import com.fgc.backend.glucose.GlucoseIngestService;
import com.fgc.backend.glucose.GlucoseRepository;
import com.fgc.backend.glucose.RawReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.fgc.backend.common.Db.ts;

/** pull 방식 소스 동기화. 한 소스의 실패가 다른 소스에 영향을 주지 않는다. */
@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final Map<String, GlucoseSourceAdapter> adapters;
    private final DataSourceRepository sources;
    private final GlucoseRepository glucose;
    private final GlucoseIngestService ingest;
    private final AlertEngine alerts;
    private final NamedParameterJdbcTemplate jdbc;
    private final FgcProperties props;
    private final Clock clock;

    public SyncService(List<GlucoseSourceAdapter> adapters, DataSourceRepository sources, GlucoseRepository glucose,
                       GlucoseIngestService ingest, AlertEngine alerts, NamedParameterJdbcTemplate jdbc,
                       FgcProperties props, Clock clock) {
        this.adapters = adapters.stream().collect(Collectors.toMap(GlucoseSourceAdapter::type, Function.identity()));
        this.sources = sources;
        this.glucose = glucose;
        this.ingest = ingest;
        this.alerts = alerts;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public record SyncOutcome(boolean success, int fetched, int inserted, int duplicates, int rejected, String error) {}

    public void syncAll() {
        for (String type : adapters.keySet()) {
            for (DataSource s : sources.activeOfType(type)) {
                try {
                    sync(s);
                } catch (RuntimeException e) {
                    log.error("sync crashed source={} type={}", s.id(), e.getClass().getSimpleName());
                }
            }
        }
    }

    public SyncOutcome sync(DataSource s) {
        GlucoseSourceAdapter adapter = adapters.get(s.type());
        if (adapter == null) throw new IllegalArgumentException("No pull adapter for " + s.type());
        Instant started = clock.instant();
        // backfill 대응: 마지막 측정 시각보다 lookback 만큼 앞에서 다시 조회 (STEP1 D-1)
        Instant from = glucose.latestMeasuredAt(s.patientId(), adapter.source())
                .map(t -> t.minus(props.sync().lookback()))
                .orElse(started.minus(props.sync().initialBackfill()));
        SyncOutcome out;
        try {
            List<RawReading> raw = adapter.fetch(s, from);
            GlucoseIngestService.IngestResult r = ingest.ingest(s.patientId(), adapter.source(), raw);
            out = new SyncOutcome(true, r.received(), r.inserted(), r.duplicates(), r.rejected(), null);
        } catch (GlucoseSourceAdapter.SourceException e) {
            out = new SyncOutcome(false, 0, 0, 0, 0, e.getMessage());
        }
        Instant finished = clock.instant();
        record(s, started, finished, out);
        return out;
    }

    private void record(DataSource s, Instant started, Instant finished, SyncOutcome o) {
        jdbc.update("""
                INSERT INTO sync_history (data_source_id, patient_id, started_at, finished_at, status, fetched, inserted,
                    duplicates, rejected, error)
                VALUES (:s, :p, :st, :f, :status, :fe, :i, :d, :r, :e)""",
                new MapSqlParameterSource().addValue("s", s.id()).addValue("p", s.patientId()).addValue("st", ts(started))
                        .addValue("f", ts(finished)).addValue("status", o.success() ? "SUCCESS" : "FAILED")
                        .addValue("fe", o.fetched()).addValue("i", o.inserted()).addValue("d", o.duplicates())
                        .addValue("r", o.rejected()).addValue("e", o.error()));
        int failures;
        if (o.success()) {
            jdbc.update("UPDATE data_sources SET consecutive_failures = 0, last_success_at = :t WHERE id = :id",
                    new MapSqlParameterSource().addValue("t", ts(finished)).addValue("id", s.id()));
            failures = 0;
        } else {
            failures = jdbc.queryForObject("""
                    UPDATE data_sources SET consecutive_failures = consecutive_failures + 1, last_error = :e, last_error_at = :t
                    WHERE id = :id RETURNING consecutive_failures""",
                    new MapSqlParameterSource().addValue("e", o.error()).addValue("t", ts(finished)).addValue("id", s.id()), Integer.class);
            log.warn("sync failed source={} failures={} error={}", s.id(), failures, o.error());
        }
        alerts.onSyncResult(s.patientId(), o.success(), failures);
    }
}
