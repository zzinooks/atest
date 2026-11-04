package com.fgc.backend.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fgc.backend.common.ApiException;
import com.fgc.backend.common.Secrets;
import com.fgc.backend.glucose.Flag;
import com.fgc.backend.glucose.GlucoseIngestService;
import com.fgc.backend.glucose.GlucoseSource;
import com.fgc.backend.glucose.RawReading;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nightscout 호환 업로드 수신 (최소 구현: entries, status). xDrip+ 의 "Nightscout Sync (REST-API)" 대상.
 * Base URL 예: https://SECRET@host/ns/{sourceId}/api/v1/
 * 실제 xDrip+ 연동은 실기기 없이 검증하지 못했다 (STEP 8 보고서).
 */
@RestController
@RequestMapping("/ns/{sourceId}/api/v1")
public class NightscoutController {
    private final DataSourceRepository sources;
    private final GlucoseIngestService ingest;

    public NightscoutController(DataSourceRepository sources, GlucoseIngestService ingest) {
        this.sources = sources;
        this.ingest = ingest;
    }

    @GetMapping("/status.json")
    public Map<String, Object> status(@PathVariable UUID sourceId, @RequestHeader(value = "api-secret", required = false) String secret) {
        authorize(sourceId, secret);
        return Map.of("status", "ok", "name", "family-glucose-care", "apiEnabled", true, "serverTimeEpoch", System.currentTimeMillis());
    }

    @PostMapping("/entries")
    public GlucoseIngestService.IngestResult entries(@PathVariable UUID sourceId,
                                                     @RequestHeader(value = "api-secret", required = false) String secret,
                                                     @RequestBody JsonNode body) {
        DataSource s = authorize(sourceId, secret);
        List<RawReading> raw = new ArrayList<>();
        for (JsonNode e : body.isArray() ? body : List.of(body)) {
            if (!"sgv".equals(e.path("type").asText("sgv")) || !e.has("sgv") || !e.has("date")) continue;
            int sgv = e.path("sgv").asInt();
            if (sgv < 40) continue; // Nightscout 특수 코드(센서 오류 등)는 혈당값이 아니다
            OffsetDateTime at = Instant.ofEpochMilli(e.path("date").asLong()).atOffset(ZoneOffset.UTC);
            String id = e.hasNonNull("_id") ? e.path("_id").asText() : "date-" + e.path("date").asLong();
            raw.add(new RawReading(id, sgv, Flag.NORMAL, at, e.path("direction").asText(null)));
        }
        if (raw.size() > 1000) throw ApiException.badRequest("Too many entries");
        return ingest.ingest(s.patientId(), GlucoseSource.NIGHTSCOUT_UPLOAD, raw);
    }

    private DataSource authorize(UUID sourceId, String apiSecretSha1) {
        DataSource s = sources.find(sourceId).filter(d -> "NIGHTSCOUT_UPLOAD".equals(d.type()) && "ACTIVE".equals(d.status()))
                .orElseThrow(NightscoutController::unauthorized);
        if (apiSecretSha1 == null || !Secrets.constantTimeEquals(Secrets.sha256Hex(apiSecretSha1.trim().toLowerCase()),
                new String(s.secret(), StandardCharsets.UTF_8))) {
            throw unauthorized();
        }
        return s;
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid api-secret");
    }
}
