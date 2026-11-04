package com.fgc.backend.source;

import com.fgc.backend.common.ApiException;
import com.fgc.backend.common.AuditService;
import com.fgc.backend.common.CryptoBox;
import com.fgc.backend.common.Secrets;
import com.fgc.backend.family.AccessPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.fgc.backend.common.Auth.userId;
import static com.fgc.backend.common.Db.ts;
import static com.fgc.backend.family.AccessPolicy.Permission.VIEW_GLUCOSE;

/** 환자의 데이터 소스 연결 관리. 자격증명은 암호화 저장하고 응답에 절대 포함하지 않는다. */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/data-sources")
public class DataSourceController {
    private final DataSourceRepository repo;
    private final SyncService sync;
    private final EmulatorCloudAdapter emulator;
    private final AccessPolicy access;
    private final CryptoBox crypto;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public DataSourceController(DataSourceRepository repo, SyncService sync, EmulatorCloudAdapter emulator, AccessPolicy access,
                                CryptoBox crypto, AuditService audit, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.repo = repo;
        this.sync = sync;
        this.emulator = emulator;
        this.access = access;
        this.crypto = crypto;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record CreateRequest(@NotNull @Pattern(regexp = "EMULATOR|HEALTH_CONNECT|NIGHTSCOUT_UPLOAD") String type,
                                @Size(max = 300) @Pattern(regexp = "https?://[^\\s]+") String baseUrl,
                                @Size(max = 200) String username,
                                @Size(max = 200) String password) {}

    public record SourceDto(UUID id, String type, String baseUrl, String status, int consecutiveFailures,
                            Instant lastSuccessAt, String lastError, Instant lastErrorAt, Instant createdAt) {
        static SourceDto of(DataSource s) {
            return new SourceDto(s.id(), s.type(), s.baseUrl(), s.status(), s.consecutiveFailures(), s.lastSuccessAt(),
                    s.lastError(), s.lastErrorAt(), s.createdAt());
        }
    }

    /** Nightscout 업로드용 비밀값은 생성 시 한 번만 보여준다. */
    public record Created(SourceDto source, String nightscoutUploadPath, String nightscoutSecret) {}

    @GetMapping
    public List<SourceDto> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        access.require(userId(jwt), patientId, VIEW_GLUCOSE);
        return repo.byPatient(patientId).stream().map(SourceDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @Valid @RequestBody CreateRequest r) {
        access.requireOwner(userId(jwt), patientId);
        UUID id = UUID.randomUUID();
        byte[] secret = null;
        String nsSecret = null;
        switch (r.type()) {
            case "EMULATOR" -> {
                if (r.baseUrl() == null || r.username() == null || r.password() == null) {
                    throw ApiException.badRequest("EMULATOR requires baseUrl, username, password");
                }
                secret = crypto.encrypt(r.password());
            }
            case "NIGHTSCOUT_UPLOAD" -> {
                nsSecret = Secrets.randomToken(18);
                // xDrip+ 는 api-secret 헤더에 SHA-1(secret) 을 보낸다 → 그 값의 SHA-256 만 저장
                secret = Secrets.sha256Hex(Secrets.sha1Hex(nsSecret)).getBytes(StandardCharsets.UTF_8);
            }
            default -> { }
        }
        jdbc.update("""
                INSERT INTO data_sources (id, patient_id, type, base_url, username, secret_ciphertext, status, created_at)
                VALUES (:id, :p, :t, :b, :u, :s, 'ACTIVE', :c)""",
                new MapSqlParameterSource().addValue("id", id).addValue("p", patientId).addValue("t", r.type())
                        .addValue("b", "EMULATOR".equals(r.type()) ? stripSlash(r.baseUrl()) : null)
                        .addValue("u", "EMULATOR".equals(r.type()) ? r.username() : null).addValue("s", secret)
                        .addValue("c", ts(clock.instant())));
        audit.log(userId(jwt), patientId, "DATA_SOURCE_CREATED", r.type());
        DataSource s = repo.find(id).orElseThrow();
        return new Created(SourceDto.of(s), nsSecret == null ? null : "/ns/" + id + "/api/v1/", nsSecret);
    }

    @PostMapping("/{sourceId}/sync")
    public SyncService.SyncOutcome syncNow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @PathVariable UUID sourceId) {
        access.requireOwner(userId(jwt), patientId);
        DataSource s = owned(patientId, sourceId);
        if (!"EMULATOR".equals(s.type())) throw ApiException.badRequest("Only pull sources can be synced by the server");
        return sync.sync(s);
    }

    @PutMapping("/{sourceId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @PathVariable UUID sourceId,
                       @RequestParam @Pattern(regexp = "ACTIVE|PAUSED") String value) {
        access.requireOwner(userId(jwt), patientId);
        owned(patientId, sourceId);
        jdbc.update("UPDATE data_sources SET status = :s WHERE id = :id", new MapSqlParameterSource().addValue("s", value).addValue("id", sourceId));
        audit.log(userId(jwt), patientId, "DATA_SOURCE_STATUS", value);
    }

    @DeleteMapping("/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @PathVariable UUID sourceId) {
        access.requireOwner(userId(jwt), patientId);
        owned(patientId, sourceId);
        jdbc.update("DELETE FROM data_sources WHERE id = :id", new MapSqlParameterSource("id", sourceId));
        emulator.forget(sourceId);
        audit.log(userId(jwt), patientId, "DATA_SOURCE_DELETED", null);
    }

    private DataSource owned(UUID patientId, UUID sourceId) {
        return repo.find(sourceId).filter(s -> s.patientId().equals(patientId)).orElseThrow(() -> ApiException.notFound("Data source"));
    }

    private static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
}
