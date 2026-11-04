package com.fgc.backend.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fgc.backend.config.FgcProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Notion REST API 최소 클라이언트. 토큰은 Backend 환경변수에만 둔다 (앱에 포함 금지). */
@Component
public class NotionClient {
    static final int MAX_TRIES = 3;
    static final long MAX_WAIT_MS = 10_000;

    private final HttpClient http;
    private final ObjectMapper json;
    private final FgcProperties.Notion cfg;

    public NotionClient(HttpClient outboundHttpClient, ObjectMapper json, FgcProperties props) {
        this.http = outboundHttpClient;
        this.json = json;
        this.cfg = props.notion();
    }

    public boolean configured() { return cfg != null && cfg.configured(); }

    public FgcProperties.Notion config() { return cfg; }

    public static class NotionException extends RuntimeException {
        public NotionException(String m) { super(m); }
    }

    public String createPage(Map<String, Object> body) {
        return call("POST", "/v1/pages", body).path("id").asText();
    }

    public void updatePage(String pageId, Map<String, Object> body) {
        call("PATCH", "/v1/pages/" + pageId, body);
    }

    private JsonNode call(String method, String path, Map<String, Object> body) {
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> r;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + path))
                        .header("Authorization", "Bearer " + cfg.token())
                        .header("Notion-Version", cfg.version())
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
                r = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt < MAX_TRIES) { sleep(500L << attempt); continue; }
                throw new NotionException("network: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NotionException("interrupted");
            }
            int s = r.statusCode();
            if (s >= 200 && s < 300) {
                try { return json.readTree(r.body()); } catch (IOException e) { throw new NotionException("invalid JSON"); }
            }
            if ((s == 429 || s >= 500) && attempt < MAX_TRIES) {
                long wait = s == 429 ? r.headers().firstValue("Retry-After").map(v -> {
                    try { return (long) (Double.parseDouble(v) * 1000); } catch (NumberFormatException e) { return 1000L; }
                }).orElse(1000L) : 500L << attempt;
                sleep(Math.min(wait, MAX_WAIT_MS));
                continue;
            }
            throw new NotionException("HTTP " + s);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
