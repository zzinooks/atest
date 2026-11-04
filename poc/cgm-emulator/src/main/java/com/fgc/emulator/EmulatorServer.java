package com.fgc.emulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 가상 클라우드 API ("VirtualCare API"). 실제 바로잰 Care API를 흉내 낸 것이 아니라,
 * 공식 앱 클라우드가 일반적으로 가질 법한 성질(토큰 인증, 업로드 지연, 페이지네이션, 중복, 5xx, 429)을
 * 갖춘 연습용 가상 API다. 실제 엔드포인트·필드명과 무관하다.
 */
public final class EmulatorServer {
    private static final int MAX_PAGE = 288;   // 하루치 (5분 × 288)

    private final EmulatorConfig config;
    private final SimClock clock;
    private final VirtualSensor sensor;
    private final String username;
    private final byte[] passwordHash;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, Session> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, Session> refreshTokens = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> requestLog = new ConcurrentHashMap<>();
    private final Random faults;
    private final SecureRandom tokens = new SecureRandom();
    private HttpServer http;

    private record Session(String access, String refresh, Instant expiresAt) {}

    public EmulatorServer(EmulatorConfig config, SimClock clock, VirtualSensor sensor, String username, String password) {
        this.config = config;
        this.clock = clock;
        this.sensor = sensor;
        this.username = username;
        this.passwordHash = sha256(password);
        this.faults = new Random(config.seed() ^ 0xFA17L);
    }

    public int start(String host, int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/", this::route);
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();
        return http.getAddress().getPort();
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.startsWith("/emulator/")) { emulatorControl(ex, method, path); return; }
            if (method.equals("POST") && path.equals("/api/v1/auth/token")) { login(ex); return; }
            if (method.equals("POST") && path.equals("/api/v1/auth/refresh")) { refresh(ex); return; }

            Session s = authenticate(ex);
            if (s == null) { error(ex, 401, "invalid_token", "Access token missing, unknown or expired"); return; }
            if (!withinRateLimit(s.access())) {
                ex.getResponseHeaders().add("Retry-After", "60");
                error(ex, 429, "rate_limited", "Too many requests"); return;
            }
            if (config.serverErrorRate() > 0 && faults.nextDouble() < config.serverErrorRate()) {
                error(ex, 503, "unavailable", "Injected server fault"); return;
            }
            Map<String, String> q = query(ex);
            switch (method + " " + path) {
                case "GET /api/v1/me" -> send(ex, 200, Map.of(
                        "patientId", "P-0001", "displayName", "가상 환자", "unit", "mg/dL", "timeZone", config.zone().getId()));
                case "GET /api/v1/sensors/current" -> send(ex, 200, sensorInfo());
                case "GET /api/v1/glucose" -> glucose(ex, q);
                case "GET /api/v1/glucose/latest" -> latest(ex);
                case "GET /hc/v1/blood-glucose-records" -> healthConnect(ex, q);
                default -> error(ex, 404, "not_found", "No such endpoint");
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            error(ex, 400, "bad_request", e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "internal", e.getClass().getSimpleName());
        } finally {
            ex.close(); // catch 블록에서 오류 응답을 보낸 뒤 닫는다 (try-with-resources 는 catch 전에 닫아버림)
        }
    }

    // ---- auth -------------------------------------------------------------------------------

    private void login(HttpExchange ex) throws IOException {
        Map<?, ?> body = json.readValue(readBody(ex), Map.class);
        Object u = body.get("username"), p = body.get("password");
        if (!(u instanceof String us) || !(p instanceof String ps)
                || !us.equals(username) || !MessageDigest.isEqual(sha256(ps), passwordHash)) {
            error(ex, 401, "invalid_credentials", "Wrong username or password"); return;
        }
        send(ex, 200, issue());
    }

    private void refresh(HttpExchange ex) throws IOException {
        Map<?, ?> body = json.readValue(readBody(ex), Map.class);
        Session old = body.get("refreshToken") instanceof String r ? refreshTokens.remove(r) : null;
        if (old == null) { error(ex, 401, "invalid_refresh_token", "Unknown refresh token"); return; }
        accessTokens.remove(old.access());
        send(ex, 200, issue());
    }

    private Map<String, Object> issue() {
        Session s = new Session(randomToken(), randomToken(), clock.now().plus(config.tokenTtl()));
        accessTokens.put(s.access(), s);
        refreshTokens.put(s.refresh(), s);
        return Map.of("accessToken", s.access(), "refreshToken", s.refresh(), "tokenType", "Bearer",
                "expiresIn", config.tokenTtl().toSeconds(), "expiresAt", s.expiresAt());
    }

    private Session authenticate(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        Session s = accessTokens.get(h.substring(7));
        if (s == null || !clock.now().isBefore(s.expiresAt())) return null;
        return s;
    }

    private boolean withinRateLimit(String token) {
        long now = System.currentTimeMillis();
        Deque<Long> log = requestLog.computeIfAbsent(token, k -> new ArrayDeque<>());
        synchronized (log) {
            while (!log.isEmpty() && now - log.peekFirst() > 60_000) log.pollFirst();
            if (log.size() >= config.rateLimitPerMinute()) return false;
            log.addLast(now);
            return true;
        }
    }

    // ---- data -------------------------------------------------------------------------------

    private Map<String, Object> sensorInfo() {
        Instant now = clock.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serial", sensor.serial());
        m.put("state", sensor.stateAt(now));
        m.put("startedAt", sensor.startedAt());
        m.put("firstReadingAt", sensor.firstReadingAt());
        m.put("expiresAt", sensor.expiresAt());
        m.put("serverTime", now);
        return m;
    }

    /** measuredAt 기준 [from, to) 조회. 클라우드에 업로드된(uploadedAt <= now) 값만 보인다. */
    private void glucose(HttpExchange ex, Map<String, String> q) throws IOException {
        Instant now = clock.now();
        Instant from = Instant.parse(require(q, "from"));
        Instant to = q.containsKey("to") ? Instant.parse(q.get("to")) : now;
        int limit = Math.min(Integer.parseInt(q.getOrDefault("limit", "100")), MAX_PAGE);
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        long afterSeq = q.containsKey("cursor") ? decodeCursor(q.get("cursor")) : -1;

        List<Reading> visible = sensor.measuredBetween(from, to).stream()
                .filter(r -> !r.uploadedAt().isAfter(now))
                .filter(r -> r.sequence() > afterSeq)
                .toList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Reading r : visible.subList(0, Math.min(limit, visible.size()))) {
            items.add(toApi(r));
            if (config.duplicateRate() > 0 && faults.nextDouble() < config.duplicateRate()) items.add(toApi(r));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextCursor", visible.size() > limit ? encodeCursor(visible.get(limit - 1).sequence()) : null);
        body.put("serverTime", now);
        send(ex, 200, body);
    }

    private void latest(HttpExchange ex) throws IOException {
        Instant now = clock.now();
        List<Reading> recent = sensor.measuredBetween(now.minus(Duration.ofHours(3)), now).stream()
                .filter(r -> !r.uploadedAt().isAfter(now)).toList();
        if (recent.isEmpty()) { send(ex, 200, Map.of("item", Map.of(), "serverTime", now)); return; }
        send(ex, 200, Map.of("item", toApi(recent.get(recent.size() - 1)), "serverTime", now));
    }

    private Map<String, Object> toApi(Reading r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.externalId());
        m.put("sensorSerial", r.sensorSerial());
        m.put("seq", r.sequence());
        m.put("value", r.valueMgdl());
        m.put("unit", "mg/dL");
        m.put("flag", r.flag());
        m.put("trend", r.trend());
        m.put("trendRate", r.trendRateMgdlPerMin());
        m.put("measuredAt", r.measuredAt().atZone(config.zone()).toOffsetDateTime()); // 로컬 오프셋 포함 ISO-8601
        m.put("uploadedAt", r.uploadedAt());
        return m;
    }

    /**
     * Health Connect BloodGlucoseRecord 모양의 JSON. 필드명은 androidx.health.connect.client 의
     * BloodGlucoseRecord(time, zoneOffset, level, specimenSource, mealType, relationToMeal, metadata)를 따른다.
     * Health Connect 는 범위 밖(LO/HI) 값을 표현할 수 없으므로 제외한다.
     */
    private void healthConnect(HttpExchange ex, Map<String, String> q) throws IOException {
        Instant now = clock.now();
        Instant from = Instant.parse(require(q, "from"));
        Instant to = q.containsKey("to") ? Instant.parse(q.get("to")) : now;
        List<Map<String, Object>> records = new ArrayList<>();
        for (Reading r : sensor.measuredBetween(from, to)) {
            Instant writtenAt = r.measuredAt().plus(config.healthConnectDelay());
            if (r.valueMgdl() == null || writtenAt.isAfter(now)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recordType", "BloodGlucoseRecord");
            m.put("time", r.measuredAt());
            m.put("zoneOffset", r.measuredAt().atZone(config.zone()).getOffset().getId());
            m.put("level", Map.of("inMilligramsPerDeciliter", r.valueMgdl()));
            m.put("specimenSource", "SPECIMEN_SOURCE_INTERSTITIAL_FLUID");
            m.put("mealType", "MEAL_TYPE_UNKNOWN");
            m.put("relationToMeal", "RELATION_TO_MEAL_UNKNOWN");
            m.put("metadata", Map.of(
                    "id", UUID.nameUUIDFromBytes(r.externalId().getBytes(StandardCharsets.UTF_8)).toString(),
                    "clientRecordId", r.externalId(),
                    "dataOrigin", Map.of("packageName", "com.fgc.emulator.virtualcgm"),
                    "lastModifiedTime", writtenAt));
            records.add(m);
        }
        send(ex, 200, Map.of("records", records, "serverTime", now));
    }

    // ---- emulator control (로컬 테스트 전용) -------------------------------------------------

    private void emulatorControl(HttpExchange ex, String method, String path) throws IOException {
        if (method.equals("GET") && path.equals("/emulator/state")) {
            Map<String, Object> m = new LinkedHashMap<>(sensorInfo());
            m.put("scenario", config.scenario());
            m.put("clockSpeed", clock.speed());
            send(ex, 200, m);
        } else if (method.equals("POST") && path.equals("/emulator/advance")) {
            long minutes = Long.parseLong(require(query(ex), "minutes"));
            clock.advance(Duration.ofMinutes(minutes));
            send(ex, 200, Map.of("serverTime", clock.now()));
        } else {
            error(ex, 404, "not_found", "No such emulator endpoint");
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private void error(HttpExchange ex, int status, String code, String message) {
        try { send(ex, status, Map.of("error", code, "message", message)); } catch (IOException ignored) { }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) q.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static String require(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing parameter: " + key);
        return v;
    }

    private static String encodeCursor(long seq) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("s:" + seq).getBytes(StandardCharsets.UTF_8));
    }

    private static long decodeCursor(String c) {
        String s = new String(Base64.getUrlDecoder().decode(c), StandardCharsets.UTF_8);
        if (!s.startsWith("s:")) throw new IllegalArgumentException("invalid cursor");
        return Long.parseLong(s.substring(2));
    }

    private String randomToken() {
        byte[] b = new byte[24];
        tokens.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
