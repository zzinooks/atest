package com.fgc.backend.notify;

import com.fgc.backend.config.FgcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class NotificationConfig {
    private static final Logger log = LoggerFactory.getLogger(NotificationConfig.class);

    @Bean
    NotificationSender notificationSender(FgcProperties props) throws IOException {
        if (props.fcm() != null && props.fcm().configured()) {
            log.info("Push provider: FCM");
            return new FcmSender(props.fcm().credentialsPath());
        }
        log.warn("Push provider: dev-outbox (FCM not configured) — notifications are NOT delivered to devices");
        return new DevOutboxSender();
    }
}
