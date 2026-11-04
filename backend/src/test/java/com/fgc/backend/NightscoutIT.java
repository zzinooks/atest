package com.fgc.backend;

import com.fgc.backend.common.Secrets;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** xDrip+ 가 보내는 Nightscout entries 형식으로 업로드 수신 검증 (실제 xDrip+ 연동은 미검증). */
class NightscoutIT extends IntegrationTest {

    HttpResponse<String> ns(String path, String secretSha1, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (secretSha1 != null) b.header("api-secret", secretSha1);
        b.method(body == null ? "GET" : "POST", body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void acceptsSgvEntriesWithValidSecretOnly() throws Exception {
        String p = signup("ns@test.dev", "NS");
        String pid = patient(p);
        Res src = post("/api/v1/patients/" + pid + "/data-sources", p, Map.of("type", "NIGHTSCOUT_UPLOAD"));
        assertEquals(201, src.status());
        String path = src.body().get("nightscoutUploadPath").asText();
        String secret = src.body().get("nightscoutSecret").asText();
        assertEquals(1, get("/api/v1/patients/" + pid + "/data-sources", p).body().size());
        assertFalse(get("/api/v1/patients/" + pid + "/data-sources", p).body().toString().contains(secret));

        long t = T0.minus(Duration.ofMinutes(5)).toEpochMilli();
        String body = """
                [{"type":"sgv","sgv":112,"date":%d,"direction":"Flat","device":"xDrip-CareSens"},
                 {"type":"sgv","sgv":108,"date":%d,"direction":"FortyFiveDown"},
                 {"type":"sgv","sgv":5,"date":%d},
                 {"type":"mbg","mbg":120,"date":%d}]""".formatted(t, t + 300_000, t - 300_000, t);
        assertEquals(401, ns(path + "entries", null, body).statusCode());
        assertEquals(401, ns(path + "entries", Secrets.sha1Hex("wrong"), body).statusCode());
        HttpResponse<String> ok = ns(path + "entries", Secrets.sha1Hex(secret), body);
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals(2, json.readTree(ok.body()).get("inserted").asInt());
        assertEquals(200, ns(path + "entries", Secrets.sha1Hex(secret), body).statusCode()); // 재전송
        assertEquals(2, count("SELECT count(*) FROM glucose_readings WHERE source = 'NIGHTSCOUT_UPLOAD'"));
        assertEquals(200, ns(path + "status.json", Secrets.sha1Hex(secret), null).statusCode());

        // 일시정지된 소스는 거부
        String sourceId = path.split("/")[2];
        assertEquals(204, call("PUT", "/api/v1/patients/" + pid + "/data-sources/" + sourceId + "/status?value=PAUSED", p, null).status());
        assertEquals(401, ns(path + "entries", Secrets.sha1Hex(secret), body).statusCode());
    }
}
