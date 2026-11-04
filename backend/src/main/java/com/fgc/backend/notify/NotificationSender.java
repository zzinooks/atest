package com.fgc.backend.notify;

import java.util.Map;

/** 푸시 전송 추상화. 전송 성공 = 공급자가 받았다는 뜻일 뿐, 사용자가 봤다는 뜻이 아니다. */
public interface NotificationSender {
    enum Outcome { SENT, RETRYABLE_ERROR, PERMANENT_ERROR, INVALID_TOKEN }

    record Result(Outcome outcome, String providerMessageId, String error) {}

    String provider();

    Result send(String pushToken, String title, String body, Map<String, String> data);
}
