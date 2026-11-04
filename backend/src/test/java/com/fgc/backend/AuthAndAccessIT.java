package com.fgc.backend;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthAndAccessIT extends IntegrationTest {

    @Test
    void signupLoginRefreshRotationAndReuseDetection() {
        signup("a@test.dev", "A");
        assertEquals(409, post("/api/v1/auth/signup", null, Map.of("email", "A@test.dev", "password", "password-123", "displayName", "x")).status());
        assertEquals(401, post("/api/v1/auth/login", null, Map.of("email", "a@test.dev", "password", "wrong-pass")).status());
        Res login = post("/api/v1/auth/login", null, Map.of("email", "a@test.dev", "password", "password-123"));
        assertEquals(200, login.status());
        String refresh = login.body().get("refreshToken").asText();

        Res r1 = post("/api/v1/auth/refresh", null, Map.of("refreshToken", refresh));
        assertEquals(200, r1.status());
        String refresh2 = r1.body().get("refreshToken").asText();
        // 이미 회전된 토큰 재사용 → 거부 + 그 사용자의 모든 refresh 토큰 폐기
        assertEquals(401, post("/api/v1/auth/refresh", null, Map.of("refreshToken", refresh)).status());
        assertEquals(401, post("/api/v1/auth/refresh", null, Map.of("refreshToken", refresh2)).status());
    }

    @Test
    void loginThrottledAfterFiveFailures() {
        signup("t@test.dev", "T");
        for (int i = 0; i < 5; i++) post("/api/v1/auth/login", null, Map.of("email", "t@test.dev", "password", "nope-nope"));
        assertEquals(429, post("/api/v1/auth/login", null, Map.of("email", "t@test.dev", "password", "password-123")).status());
    }

    @Test
    void accessTokenExpires() {
        String t = signup("e@test.dev", "E");
        assertEquals(200, get("/api/v1/me", t).status());
        assertEquals(401, get("/api/v1/me", null).status());
        assertEquals(401, get("/api/v1/me", t + "x").status());
        clock.advance(java.time.Duration.ofHours(25)); // 테스트 TTL(1일) 초과
        assertEquals(401, get("/api/v1/me", t).status());
        assertEquals(200, get("/api/v1/me", login("e@test.dev")).status());
    }

    @Test
    void familySharingPermissionsAndImmediateRevocation() {
        String p = signup("patient@test.dev", "환자");
        String pid = patient(p);
        String viewer = signup("viewer@test.dev", "가족1");
        String noView = signup("noview@test.dev", "가족2");
        String stranger = signup("stranger@test.dev", "남");
        link(p, pid, viewer, true, true, false);
        link(p, pid, noView, false, false, true);

        upload(p, pid, List.of(reading("r1", 120, "NORMAL", T0.minusSeconds(60))));
        assertEquals(200, get("/api/v1/patients/" + pid + "/glucose/latest", viewer).status());
        assertEquals(403, get("/api/v1/patients/" + pid + "/glucose/latest", noView).status());
        assertEquals(403, get("/api/v1/patients/" + pid + "/glucose/latest", stranger).status());
        // 가족은 혈당 업로드·알림 기준 변경·초대 불가
        assertEquals(403, upload(viewer, pid, List.of(reading("x", 100, "NORMAL", T0))).status());
        Res rules = get("/api/v1/patients/" + pid + "/alert-rules", viewer);
        assertEquals(200, rules.status()); // 가족은 기준을 볼 수 있지만
        assertEquals(403, call("PUT", "/api/v1/patients/" + pid + "/alert-rules", viewer, rules.body()).status()); // 바꿀 수는 없다
        assertEquals(403, post("/api/v1/patients/" + pid + "/invitations", viewer,
                Map.of("relation", "x", "permissions", Map.of("viewGlucose", true, "receiveAlerts", false, "writeNotes", false))).status());
        // 메모 권한만 있는 가족은 기록 작성 가능
        assertEquals(201, post("/api/v1/patients/" + pid + "/events", noView,
                Map.of("type", "NOTE", "occurredAt", T0.minusSeconds(10).toString(), "title", "저녁 잘 드심")).status());

        // /me 에 가족 연결이 보인다
        Res me = get("/api/v1/me", viewer);
        assertEquals(pid, me.body().get("familyOf").get(0).get("patientId").asText());
        assertFalse(me.body().get("isPatient").asBoolean());

        // 공유 해제 → 같은 토큰으로 즉시 403
        String memberId = get("/api/v1/patients/" + pid + "/members", p).body().get(0).get("memberId").asText();
        assertEquals(204, call("DELETE", "/api/v1/patients/" + pid + "/members/" + memberId, p, null).status());
        assertEquals(403, get("/api/v1/patients/" + pid + "/glucose/latest", viewer).status());
        assertEquals(1, count("SELECT count(*) FROM sharing_consents WHERE action = 'REVOKED'"));
        // 가족의 조회는 감사 로그에 남는다 (값 없이)
        assertTrue(count("SELECT count(*) FROM audit_logs WHERE action = 'GLUCOSE_LATEST_VIEWED'") >= 1);
        assertEquals(0, count("SELECT count(*) FROM audit_logs WHERE detail ~ '[0-9]{2,3} ?mg'"));
    }

    @Test
    void invitationSingleUseAndExpiry() {
        String p = signup("p2@test.dev", "P");
        String pid = patient(p);
        Res inv = post("/api/v1/patients/" + pid + "/invitations", p,
                Map.of("relation", "배우자", "permissions", Map.of("viewGlucose", true, "receiveAlerts", true, "writeNotes", true)));
        String code = inv.body().get("code").asText();
        assertEquals(400, post("/api/v1/invitations/accept", p, Map.of("code", code)).status()); // 본인 초대 수락 불가
        String f1 = signup("f1@test.dev", "F1");
        String f2 = signup("f2@test.dev", "F2");
        assertEquals(200, post("/api/v1/invitations/accept", f1, Map.of("code", code.toLowerCase())).status());
        assertEquals(409, post("/api/v1/invitations/accept", f2, Map.of("code", code)).status());

        String code2 = post("/api/v1/patients/" + pid + "/invitations", p,
                Map.of("relation", "x", "permissions", Map.of("viewGlucose", true, "receiveAlerts", false, "writeNotes", false)))
                .body().get("code").asText();
        clock.advance(Duration.ofHours(49));
        f2 = login("f2@test.dev");
        p = login("p2@test.dev");
        assertEquals(409, post("/api/v1/invitations/accept", f2, Map.of("code", code2)).status());
        // 알림 권한만 있고 조회 권한이 없는 초대는 거부
        assertEquals(400, post("/api/v1/patients/" + pid + "/invitations", p,
                Map.of("relation", "x", "permissions", Map.of("viewGlucose", false, "receiveAlerts", true, "writeNotes", false))).status());
    }

    @Test
    void withdrawalDeletesPatientDataAndDisablesAccount() {
        String p = signup("w@test.dev", "W");
        String pid = patient(p);
        upload(p, pid, List.of(reading("r1", 110, "NORMAL", T0.minusSeconds(60))));
        assertEquals(401, post("/api/v1/me/withdraw", p, Map.of("password", "wrong-pass")).status());
        assertEquals(204, post("/api/v1/me/withdraw", p, Map.of("password", "password-123")).status());
        assertEquals(0, count("SELECT count(*) FROM glucose_readings"));
        assertEquals(0, count("SELECT count(*) FROM patient_profiles"));
        assertEquals(0, count("SELECT count(*) FROM users WHERE email = 'w@test.dev'"));
        assertEquals(401, post("/api/v1/auth/login", null, Map.of("email", "w@test.dev", "password", "password-123")).status());
        assertEquals(401, get("/api/v1/me", p).status()); // 남은 access token 으로도 계정 정보 접근 불가
        // 같은 이메일로 재가입 가능
        assertEquals(201, post("/api/v1/auth/signup", null, Map.of("email", "w@test.dev", "password", "password-123", "displayName", "W2")).status());
    }

    @Test
    void adminEndpointsRequireAdmin() {
        String u = signup("adm@test.dev", "Admin");
        assertEquals(403, get("/api/v1/admin/summary", u).status());
        db.update("UPDATE users SET admin = true WHERE email = 'adm@test.dev'");
        Res r = get("/api/v1/admin/summary", u);
        assertEquals(200, r.status());
        assertEquals(1, r.body().get("users").asInt());
        assertNotNull(UUID.fromString(get("/api/v1/me", u).body().get("id").asText()));
    }
}
