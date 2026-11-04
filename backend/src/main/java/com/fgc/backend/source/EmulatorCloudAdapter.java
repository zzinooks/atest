package com.fgc.backend.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fgc.backend.common.CryptoBox;
import com.fgc.backend.glucose.Flag;
import com.fgc.backend.glucose.GlucoseSource;
import com.fgc.backend.glucose.RawReading;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 가상 CGM 에뮬레이터 클라우드 API 어댑터 (ADR-0002).
 * 토큰 캐시·만료 시 재로그인, 429(Retry-After)·5xx 재시도, 커서 페이지네이션을 처리한다.
 */
@Component
public class EmulatorCloudAdapter implements GlucoseSourceAdapter {
    static final int PAGE = 288;
    static final int MAX_PAGES = 50;
    static final int MAX_TRIES = 3;
    static final long MAX_INLINE_WAIT_MS = 2000;

    private final HttpClient http;
    private final CryptoBox crypto;
    private final ObjectMapper json;
    private final Map<UUID, String> tokens = new ConcurrentHashMap<>();

    public EmulatorCloudAdapter(HttpClient outboundHttpClient, CryptoBox crypto, ObjectMapper json) {
        this.http = outboundHttpClient;
        this.crypto = crypto;
        this.json = json;
    }

    @Override public String type() { return "EMULATOR"; }
    @Override public GlucoseSource source() { return GlucoseSource.EMULATOR; }

    @Override
    public List<RawReading> fetch(DataSource src, Instant from) {
        List<RawReading> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String path = "/api/v1/glucose?from=" + enc(from.toString()) + "&limit=" + PAGE + (cursor == null ? "" : "&cursor=" + enc(cursor));
            JsonNode body = getAuthorized(src, path);
            for (JsonNode n : body.path("items")) {
                Integer value = n.path("value").isNull() || n.path("value").isMissingNode() ? null : n.path("value").asInt();
                out.add(new RawReading(n.path("id").asText(null), value, parseFlag(n.path("flag").asText()),
                        n.hasNonNull("measuredAt") ? OffsetDateTime.parse(n.path("measuredAt").asText()) : null,
                        n.path("trend").asText(null)));
            }
            cursor = body.path("nextCursor").isNull() ? null : body.path("nextCursor").asText(null);
            if (cursor == null) return out;
        }
        throw new SourceException("too many pages");
    }

    private JsonNode getAuthorized(DataSource src, String path) {
        boolean relogged = false;
        for (int attempt = 1; ; attempt++) {
            String token = tokens.computeIfAbsent(src.id(), id -> login(src));
            HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(src.baseUrl() + path))
                    .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(20)).GET().build());
            int s = r.statusCode();
            if (s == 200) return parse(r.body());
            if (s == 401 && !relogged) { tokens.remove(src.id()); relogged = true; attempt--; continue; }
            if ((s == 429 || s >= 500) && attempt < MAX_TRIES) {
                long wait = s == 429 ? retryAfterMs(r) : 250L << attempt;
                if (wait > MAX_INLINE_WAIT_MS) throw new SourceException("rate limited (retry after " + wait / 1000 + "s)");
                sleep(wait);
                continue;
            }
            throw new SourceException("HTTP " + s);
        }
    }

    private String login(DataSource src) {
        String body;
        try {
            body = json.writeValueAsString(Map.of("username", src.username(), "password", crypto.decrypt(src.secret())));
        } catch (IOException e) {
            throw new SourceException("cannot build login request");
        }
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(src.baseUrl() + "/api/v1/auth/token"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (r.statusCode() != 200) throw new SourceException("login failed: HTTP " + r.statusCode());
        return parse(r.body()).path("accessToken").asText();
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SourceException("network error: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("interrupted");
        }
    }

    private JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (IOException e) {
            throw new SourceException("invalid JSON from source");
        }
    }

    private static Flag parseFlag(String f) {
        try { return Flag.valueOf(f); } catch (IllegalArgumentException e) { return null; }
    }

    private static long retryAfterMs(HttpResponse<?> r) {
        return r.headers().firstValue("Retry-After").map(v -> {
            try { return Long.parseLong(v.trim()) * 1000; } catch (NumberFormatException e) { return 60_000L; }
        }).orElse(60_000L);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** 자격증명 변경·소스 삭제 시 토큰 캐시 제거 */
    public void forget(UUID sourceId) { tokens.remove(sourceId); }
}
