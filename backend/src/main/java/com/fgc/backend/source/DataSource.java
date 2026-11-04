package com.fgc.backend.source;

import java.time.Instant;
import java.util.UUID;

public record DataSource(UUID id, UUID patientId, String type, String baseUrl, String username, byte[] secret,
                         String status, int consecutiveFailures, Instant lastSuccessAt, String lastError,
                         Instant lastErrorAt, Instant createdAt) {}
