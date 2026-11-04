package com.fgc.backend.notify;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * FCM 자격증명이 없을 때 쓰는 개발용 전송기: 실제로 보내지 않고 메모리에 쌓는다.
 * 이 상태의 SENT 는 "실제 기기에 전달됨"이 아니다 → 운영에서는 FCM 을 반드시 설정한다.
 */
public class DevOutboxSender implements NotificationSender {
    public record Message(String pushToken, String title, String body, Map<String, String> data) {}

    private final ConcurrentLinkedQueue<Message> outbox = new ConcurrentLinkedQueue<>();

    @Override
    public String provider() { return "dev-outbox"; }

    @Override
    public Result send(String pushToken, String title, String body, Map<String, String> data) {
        outbox.add(new Message(pushToken, title, body, Map.copyOf(data)));
        return new Result(Outcome.SENT, "dev-" + UUID.randomUUID(), null);
    }

    public List<Message> messages() { return List.copyOf(outbox); }

    public void clear() { outbox.clear(); }
}
