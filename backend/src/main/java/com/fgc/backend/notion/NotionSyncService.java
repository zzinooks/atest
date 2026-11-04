package com.fgc.backend.notion;

import com.fgc.backend.event.HealthEventController;
import com.fgc.backend.glucose.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.fgc.backend.common.Db.ts;
import static com.fgc.backend.common.Db.uuid;

/**
 * 일별·주간 혈당 요약을 Notion 데이터베이스에 기록 (F-41). 원본은 PostgreSQL.
 * 이 서비스의 실패는 저장·알림 흐름과 분리되어 있다 (별도 스케줄러, 예외를 밖으로 던지지 않음).
 *
 * 필요한 Notion DB 속성: Name(title), Date(date), Patient(rich_text), Mean/Min/Max/SD/CV/TIR/TBR/TAR/Coverage(number), Readings(number)
 */
@Service
public class NotionSyncService {
    private static final Logger log = LoggerFactory.getLogger(NotionSyncService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final NotionClient notion;
    private final StatsService stats;
    private final HealthEventController events;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public NotionSyncService(NotionClient notion, StatsService stats, HealthEventController events,
                             NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.notion = notion;
        this.stats = stats;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public enum Period { DAY, WEEK }

    /** 대상 환자 전체에 대해 어제(일별) 요약 + 최근 7일 중 실패분 재시도. */
    public int syncDailyForAll() {
        if (!notion.configured()) return 0;
        int ok = 0;
        for (Object[] p : targets()) {
            ZoneId zone = (ZoneId) p[1];
            LocalDate yesterday = LocalDate.now(clock.withZone(zone)).minusDays(1);
            for (LocalDate d = yesterday.minusDays(6); !d.isAfter(yesterday); d = d.plusDays(1)) {
                if (d.equals(yesterday) || failed((UUID) p[0], Period.DAY, d)) ok += sync((UUID) p[0], Period.DAY, d) ? 1 : 0;
            }
        }
        return ok;
    }

    public int syncWeeklyForAll() {
        if (!notion.configured() || notion.config().weeklyDatabaseId() == null || notion.config().weeklyDatabaseId().isBlank()) return 0;
        int ok = 0;
        for (Object[] p : targets()) {
            LocalDate lastMonday = LocalDate.now(clock.withZone((ZoneId) p[1])).with(DayOfWeek.MONDAY).minusWeeks(1);
            ok += sync((UUID) p[0], Period.WEEK, lastMonday) ? 1 : 0;
        }
        return ok;
    }

    /** @return 성공 여부. 예외를 던지지 않는다. */
    public boolean sync(UUID patientId, Period period, LocalDate start) {
        Map<String, Object> pat = jdbc.queryForMap("""
                SELECT u.display_name, pp.time_zone FROM patient_profiles pp JOIN users u ON u.id = pp.user_id WHERE pp.id = :p""",
                new MapSqlParameterSource("p", patientId));
        ZoneId zone = ZoneId.of((String) pat.get("time_zone"));
        LocalDate end = period == Period.DAY ? start.plusDays(1) : start.plusWeeks(1);
        Instant from = start.atStartOfDay(zone).toInstant(), to = end.atStartOfDay(zone).toInstant();
        String existing = jdbc.query("SELECT page_id FROM notion_sync WHERE patient_id = :p AND period = :pe AND period_start = :s",
                params(patientId, period, start), (rs, i) -> rs.getString(1)).stream().filter(Objects::nonNull).findFirst().orElse(null);
        try {
            StatsService.Stats s = stats.compute(patientId, from, to);
            Map<String, Object> props = properties(period, start, end, (String) pat.get("display_name"), s);
            String pageId;
            if (existing != null) {
                notion.updatePage(existing, Map.of("properties", props));
                pageId = existing;
            } else {
                String db = period == Period.DAY ? notion.config().dailyDatabaseId() : notion.config().weeklyDatabaseId();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("parent", Map.of("database_id", db));
                body.put("properties", props);
                body.put("children", children(patientId, from, to, zone, s));
                pageId = notion.createPage(body);
            }
            record(patientId, period, start, "SUCCESS", pageId, null);
            return true;
        } catch (RuntimeException e) {
            String err = e instanceof NotionClient.NotionException ? e.getMessage() : e.getClass().getSimpleName();
            record(patientId, period, start, "FAILED", existing, err);
            log.warn("notion sync failed patient={} period={} start={} error={}", patientId, period, start, err);
            return false;
        }
    }

    private Map<String, Object> properties(Period period, LocalDate start, LocalDate end, String name, StatsService.Stats s) {
        Map<String, Object> p = new LinkedHashMap<>();
        String title = period == Period.DAY ? start + " 혈당 요약" : start + " ~ " + end.minusDays(1) + " 주간 혈당 요약";
        p.put("Name", Map.of("title", List.of(text(title))));
        Map<String, Object> date = new LinkedHashMap<>();
        date.put("start", start.toString());
        if (period == Period.WEEK) date.put("end", end.minusDays(1).toString());
        p.put("Date", Map.of("date", date));
        p.put("Patient", Map.of("rich_text", List.of(text(name))));
        p.put("Readings", num(s.readings()));
        p.put("Coverage", num(Math.round(s.coverage() * 1000) / 10.0));
        p.put("Mean", num(s.mean()));
        p.put("Min", num(s.min()));
        p.put("Max", num(s.max()));
        p.put("SD", num(s.sd()));
        p.put("CV", num(s.cvPercent()));
        p.put("TIR", num(s.inRangePct()));
        p.put("TBR", num(s.veryLowPct() + s.lowPct()));
        p.put("TAR", num(s.highPct() + s.veryHighPct()));
        return p;
    }

    private List<Map<String, Object>> children(UUID patientId, Instant from, Instant to, ZoneId zone, StatsService.Stats s) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(paragraph(s.reliable() ? "데이터 충족률이 충분합니다." : "주의: 데이터 충족률이 70% 미만이라 통계 신뢰도가 낮습니다."));
        blocks.add(paragraph("이 요약은 의료적 판단이 아닙니다. 치료 결정은 의료진과 상의하세요."));
        List<HealthEventController.EventDto> ev = events.query(patientId, from, to);
        if (!ev.isEmpty()) {
            blocks.add(Map.of("object", "block", "type", "heading_3", "heading_3", Map.of("rich_text", List.of(text("기록")))));
            for (HealthEventController.EventDto e : ev.subList(0, Math.min(ev.size(), 90))) { // Notion children 최대 100
                String line = e.occurredAt().atZone(zone).format(TIME) + " [" + e.type() + "] " + e.title()
                        + (e.authorName() != null ? " — " + e.authorName() : "");
                blocks.add(Map.of("object", "block", "type", "bulleted_list_item",
                        "bulleted_list_item", Map.of("rich_text", List.of(text(line)))));
            }
        }
        return blocks;
    }

    private List<Object[]> targets() {
        return jdbc.query("SELECT id, time_zone FROM patient_profiles WHERE notion_enabled",
                (rs, i) -> new Object[]{uuid(rs, "id"), ZoneId.of(rs.getString("time_zone"))});
    }

    private boolean failed(UUID patientId, Period period, LocalDate d) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notion_sync WHERE patient_id = :p AND period = :pe AND period_start = :s
                               AND status = 'FAILED' AND attempts < 5)""", params(patientId, period, d), Boolean.class));
    }

    private void record(UUID patientId, Period period, LocalDate start, String status, String pageId, String err) {
        jdbc.update("""
                INSERT INTO notion_sync (patient_id, period, period_start, status, page_id, attempts, last_error, updated_at)
                VALUES (:p, :pe, :s, :st, :pg, 1, :e, :t)
                ON CONFLICT (patient_id, period, period_start) DO UPDATE SET status = :st,
                    page_id = COALESCE(:pg, notion_sync.page_id), attempts = notion_sync.attempts + 1, last_error = :e, updated_at = :t""",
                params(patientId, period, start).addValue("st", status).addValue("pg", pageId).addValue("e", err)
                        .addValue("t", ts(clock.instant())));
    }

    private static MapSqlParameterSource params(UUID p, Period pe, LocalDate s) {
        return new MapSqlParameterSource().addValue("p", p).addValue("pe", pe.name()).addValue("s", s);
    }

    private static Map<String, Object> text(String s) { return Map.of("type", "text", "text", Map.of("content", s)); }

    private static Map<String, Object> paragraph(String s) {
        return Map.of("object", "block", "type", "paragraph", "paragraph", Map.of("rich_text", List.of(text(s))));
    }

    private static Map<String, Object> num(Number n) {
        Map<String, Object> m = new HashMap<>();
        m.put("number", n);
        return m;
    }
}
