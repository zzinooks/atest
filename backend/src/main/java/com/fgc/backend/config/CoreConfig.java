package com.fgc.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class CoreConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public HttpClient outboundHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 스케줄러는 설정으로 끌 수 있다 (테스트는 작업을 직접 호출한다). */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "fgc.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingOn {}
}
