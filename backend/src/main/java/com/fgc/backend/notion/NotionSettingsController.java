package com.fgc.backend.notion;

import com.fgc.backend.common.AuditService;
import com.fgc.backend.family.AccessPolicy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

import static com.fgc.backend.common.Auth.userId;

@RestController
@RequestMapping("/api/v1/patients/{patientId}/notion")
public class NotionSettingsController {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final NotionClient notion;
    private final AuditService audit;

    public NotionSettingsController(NamedParameterJdbcTemplate jdbc, AccessPolicy access, NotionClient notion, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.notion = notion;
        this.audit = audit;
    }

    /** 환자가 Notion 요약 기록에 동의/해제 (건강정보가 외부 서비스로 나가므로 명시적 동의) */
    @PutMapping
    public Map<String, Object> set(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @RequestParam boolean enabled) {
        access.requireOwner(userId(jwt), patientId);
        jdbc.update("UPDATE patient_profiles SET notion_enabled = :e WHERE id = :p",
                new MapSqlParameterSource().addValue("e", enabled).addValue("p", patientId));
        audit.log(userId(jwt), patientId, "NOTION_EXPORT_" + (enabled ? "ENABLED" : "DISABLED"), null);
        return Map.of("enabled", enabled, "serverConfigured", notion.configured());
    }
}
