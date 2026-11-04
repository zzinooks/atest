package com.fgc.backend.event;

import com.fgc.backend.common.ApiException;
import com.fgc.backend.common.AuditService;
import com.fgc.backend.family.AccessPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
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
import static com.fgc.backend.family.AccessPolicy.Permission.WRITE_NOTES;

/** 가족 메모·식사·운동·복약 기록 (F-40). */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/events")
public class HealthEventController {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final AuditService audit;
    private final Clock clock;

    public HealthEventController(NamedParameterJdbcTemplate jdbc, AccessPolicy access, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    public record EventRequest(@NotNull @Pattern(regexp = "MEAL|EXERCISE|MEDICATION|NOTE") String type,
                               @NotNull @PastOrPresent Instant occurredAt,
                               @NotBlank @Size(max = 100) String title,
                               @Size(max = 2000) String detail,
                               @Min(0) @Max(500) Integer carbsG,
                               @Min(0) @Max(1440) Integer durationMinutes) {}

    public record EventDto(UUID id, String type, Instant occurredAt, String title, String detail, Integer carbsG,
                           Integer durationMinutes, UUID authorUserId, String authorName, Instant createdAt) {}

    @GetMapping
    public List<EventDto> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        access.require(userId(jwt), patientId, VIEW_GLUCOSE);
        return query(patientId, from, to == null ? clock.instant() : to);
    }

    public List<EventDto> query(UUID patientId, Instant from, Instant to) {
        return jdbc.query("""
                SELECT e.*, u.display_name FROM health_events e LEFT JOIN users u ON u.id = e.author_user_id
                WHERE e.patient_id = :p AND e.occurred_at >= :f AND e.occurred_at < :t ORDER BY e.occurred_at""",
                new MapSqlParameterSource().addValue("p", patientId).addValue("f", ts(from)).addValue("t", ts(to)),
                (rs, i) -> new EventDto(uuid(rs, "id"), rs.getString("type"), instant(rs, "occurred_at"), rs.getString("title"),
                        rs.getString("detail"), integer(rs, "carbs_g"), integer(rs, "duration_minutes"),
                        uuid(rs, "author_user_id"), rs.getString("display_name"), instant(rs, "created_at")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventDto create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @Valid @RequestBody EventRequest r) {
        UUID uid = userId(jwt);
        access.require(uid, patientId, WRITE_NOTES);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        if (r.occurredAt().isAfter(now)) throw ApiException.badRequest("occurredAt in the future");
        jdbc.update("""
                INSERT INTO health_events (id, patient_id, author_user_id, type, occurred_at, title, detail, carbs_g,
                    duration_minutes, created_at) VALUES (:id, :p, :a, :t, :o, :ti, :d, :c, :dm, :n)""",
                new MapSqlParameterSource().addValue("id", id).addValue("p", patientId).addValue("a", uid)
                        .addValue("t", r.type()).addValue("o", ts(r.occurredAt())).addValue("ti", r.title())
                        .addValue("d", r.detail()).addValue("c", r.carbsG()).addValue("dm", r.durationMinutes())
                        .addValue("n", ts(now)));
        audit.log(uid, patientId, "EVENT_CREATED", r.type());
        return query(patientId, r.occurredAt(), r.occurredAt().plusMillis(1)).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    /** 작성자 본인 또는 환자만 삭제 */
    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @PathVariable UUID eventId) {
        UUID uid = userId(jwt);
        boolean owner = access.require(uid, patientId, VIEW_GLUCOSE);
        int n = jdbc.update("DELETE FROM health_events WHERE id = :id AND patient_id = :p" + (owner ? "" : " AND author_user_id = :u"),
                new MapSqlParameterSource().addValue("id", eventId).addValue("p", patientId).addValue("u", uid));
        if (n == 0) throw ApiException.notFound("Event");
        audit.log(uid, patientId, "EVENT_DELETED", null);
    }
}
