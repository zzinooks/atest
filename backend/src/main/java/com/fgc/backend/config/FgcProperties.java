package com.fgc.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 애플리케이션 설정. 비밀값은 환경변수로만 주입한다 (application.yml 에는 참조만). */
@ConfigurationProperties(prefix = "fgc")
public record FgcProperties(
        String jwtSecret,
        String encryptionKey,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Sync sync,
        int retentionDays,
        Notion notion,
        Fcm fcm) {

    public record Sync(Duration lookback, Duration initialBackfill, int maxAttempts) {}

    public record Notion(String baseUrl, String token, String dailyDatabaseId, String weeklyDatabaseId, String version) {
        public boolean configured() {
            return token != null && !token.isBlank() && dailyDatabaseId != null && !dailyDatabaseId.isBlank();
        }
    }

    public record Fcm(String credentialsPath) {
        public boolean configured() {
            return credentialsPath != null && !credentialsPath.isBlank();
        }
    }
}
