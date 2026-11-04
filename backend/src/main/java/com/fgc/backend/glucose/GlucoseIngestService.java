package com.fgc.backend.glucose;

import com.fgc.backend.alert.AlertEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 모든 수집 경로의 공통 입구: 정규화 → 중복 제거 저장 → 알람 평가. */
@Service
public class GlucoseIngestService {
    private static final Logger log = LoggerFactory.getLogger(GlucoseIngestService.class);

    private final GlucoseDataNormalizer normalizer;
    private final GlucoseRepository repo;
    private final AlertEngine alerts;
    private final Clock clock;

    public GlucoseIngestService(GlucoseDataNormalizer normalizer, GlucoseRepository repo, AlertEngine alerts, Clock clock) {
        this.normalizer = normalizer;
        this.repo = repo;
        this.alerts = alerts;
        this.clock = clock;
    }

    public record IngestResult(int received, int inserted, int duplicates, int rejected) {}

    public IngestResult ingest(UUID patientId, GlucoseSource source, List<RawReading> raw) {
        Instant now = clock.instant();
        GlucoseDataNormalizer.Result n = normalizer.normalize(raw);
        int inserted = repo.insertIgnoringDuplicates(patientId, source, n.accepted(), now);
        IngestResult r = new IngestResult(raw.size(), inserted, n.accepted().size() - inserted, n.rejectedCount());
        if (n.rejectedCount() > 0) {
            log.warn("ingest rejected patient={} source={} reasons={}", patientId, source, n.rejected()); // 값은 기록하지 않음
        }
        if (inserted > 0) alerts.evaluateGlucose(patientId);
        return r;
    }
}
