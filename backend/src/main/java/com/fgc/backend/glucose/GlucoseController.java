package com.fgc.backend.glucose;

import com.fgc.backend.alert.AlertRuleService;
import com.fgc.backend.common.ApiException;
import com.fgc.backend.common.AuditService;
import com.fgc.backend.family.AccessPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static com.fgc.backend.common.Auth.userId;
import static com.fgc.backend.family.AccessPolicy.Permission.VIEW_GLUCOSE;

@RestController
@RequestMapping("/api/v1/patients/{patientId}")
public class GlucoseController {
    static final Duration MAX_RANGE = Duration.ofDays(31);

    private final GlucoseRepository repo;
    private final GlucoseIngestService ingest;
    private final StatsService stats;
    private final AccessPolicy access;
    private final AlertRuleService rules;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public GlucoseController(GlucoseRepository repo, GlucoseIngestService ingest, StatsService stats, AccessPolicy access,
                             AlertRuleService rules, AuditService audit, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.repo = repo;
        this.ingest = ingest;
        this.stats = stats;
        this.access = access;
        this.rules = rules;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public enum Freshness { FRESH, STALE, NO_DATA }

    public record ReadingDto(Integer valueMgdl, Flag flag, Instant measuredAt, Instant receivedAt, GlucoseSource source,
                             String sourceTrend) {
        static ReadingDto of(GlucoseReading r) {
            return new ReadingDto(r.valueMgdl(), r.flag(), r.measuredAt(), r.receivedAt(), r.source(), r.sourceTrend());
        }
    }

    public record Latest(ReadingDto reading, Freshness freshness, Long ageMinutes, int staleAfterMinutes,
                         Double ratePerMinute, Instant serverTime) {}

    public record UploadReading(@NotBlank @Size(max = 200) String externalId, Integer valueMgdl,
                                @NotNull Flag flag, @NotNull OffsetDateTime measuredAt) {}

    public record UploadRequest(@NotNull GlucoseSource source, @NotNull @Size(max = 1000) List<@Valid UploadReading> readings) {}

    @GetMapping("/glucose")
    public List<ReadingDto> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant end = to == null ? clock.instant() : to;
        checkRange(from, end);
        auditIfFamily(jwt, patientId, "GLUCOSE_VIEWED");
        return repo.between(patientId, from, end).stream().map(ReadingDto::of).toList();
    }

    /** 현재 혈당은 반드시 측정 시각·신선도와 함께 준다. 오래된 값을 "현재"로 보이지 않게 한다. */
    @GetMapping("/glucose/latest")
    public Latest latest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        auditIfFamily(jwt, patientId, "GLUCOSE_LATEST_VIEWED");
        int staleMin = rules.get(patientId).staleMinutes();
        Instant now = clock.instant();
        return repo.latest(patientId).map(r -> {
            long age = Duration.between(r.measuredAt(), now).toMinutes();
            Double rate = null;
            if (r.valueMgdl() != null) {
                rate = repo.nearest(patientId, r.measuredAt().minus(Duration.ofMinutes(15)), Duration.ofSeconds(150))
                        .map(p -> Math.round((r.valueMgdl() - p.valueMgdl())
                                / (Duration.between(p.measuredAt(), r.measuredAt()).toSeconds() / 60.0) * 10) / 10.0)
                        .orElse(null);
            }
            return new Latest(ReadingDto.of(r), age <= staleMin ? Freshness.FRESH : Freshness.STALE, age, staleMin, rate, now);
        }).orElse(new Latest(null, Freshness.NO_DATA, null, staleMin, null, now));
    }

    /** Android 앱이 Health Connect 에서 읽은 값을 올리는 경로 (환자 본인만). */
    @PostMapping("/glucose/batch")
    public GlucoseIngestService.IngestResult upload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                                    @Valid @RequestBody UploadRequest r) {
        access.requireOwner(userId(jwt), patientId);
        if (r.source() != GlucoseSource.HEALTH_CONNECT && r.source() != GlucoseSource.MANUAL) {
            throw ApiException.badRequest("Only HEALTH_CONNECT or MANUAL can be uploaded by the app");
        }
        return ingest.ingest(patientId, r.source(), r.readings().stream()
                .map(u -> new RawReading(u.externalId(), u.valueMgdl(), u.flag(), u.measuredAt(), null)).toList());
    }

    @GetMapping("/stats")
    public StatsService.Stats stats(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant end = to == null ? clock.instant() : to;
        checkRange(from, end);
        auditIfFamily(jwt, patientId, "STATS_VIEWED");
        return stats.compute(patientId, from, end);
    }

    @GetMapping("/stats/daily")
    public List<StatsService.DailyStats> daily(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                               @RequestParam(defaultValue = "7") @Min(1) @Max(31) int days) {
        auditIfFamily(jwt, patientId, "STATS_VIEWED");
        ZoneId zone = ZoneId.of(jdbc.queryForObject("SELECT time_zone FROM patient_profiles WHERE id = :p",
                new MapSqlParameterSource("p", patientId), String.class));
        return stats.daily(patientId, zone, LocalDate.now(clock.withZone(zone)), Math.min(Math.max(days, 1), 31));
    }

    private void auditIfFamily(Jwt jwt, UUID patientId, String action) {
        UUID uid = userId(jwt);
        if (!access.require(uid, patientId, VIEW_GLUCOSE)) audit.log(uid, patientId, action, null);
    }

    private static void checkRange(Instant from, Instant to) {
        if (!from.isBefore(to)) throw ApiException.badRequest("from must be before to");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) throw ApiException.badRequest("Range too large (max 31 days)");
    }
}
