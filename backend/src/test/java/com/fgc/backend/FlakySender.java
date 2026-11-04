package com.fgc.backend;

import com.fgc.backend.notify.DevOutboxSender;
import com.fgc.backend.notify.NotificationSender;

import java.util.Map;

/** 테스트용 전송기: 기본은 DevOutbox, failNext 로 실패를 주입 */
public class FlakySender implements NotificationSender {
    public final DevOutboxSender outbox = new DevOutboxSender();
    public volatile Outcome forced;

    @Override public String provider() { return "test"; }

    @Override
    public Result send(String token, String title, String body, Map<String, String> data) {
        if (forced != null) return new Result(forced, null, "forced-" + forced);
        return outbox.send(token, title, body, data);
    }
}
