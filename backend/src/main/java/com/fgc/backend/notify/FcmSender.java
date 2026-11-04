package com.fgc.backend.notify;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

/** Firebase Cloud Messaging 전송기. 서비스 계정 키 파일 경로는 환경변수로만 받는다. */
public class FcmSender implements NotificationSender {
    static final String CHANNEL_ID = "glucose_alerts";
    private final FirebaseMessaging messaging;

    public FcmSender(String credentialsPath) throws IOException {
        try (FileInputStream in = new FileInputStream(credentialsPath)) {
            FirebaseOptions options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(in)).build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty() ? FirebaseApp.initializeApp(options) : FirebaseApp.getInstance();
            this.messaging = FirebaseMessaging.getInstance(app);
        }
    }

    @Override
    public String provider() { return "fcm"; }

    @Override
    public Result send(String pushToken, String title, String body, Map<String, String> data) {
        Message msg = Message.builder()
                .setToken(pushToken)
                .putAllData(data)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder().setChannelId(CHANNEL_ID).build()).build())
                .build();
        try {
            return new Result(Outcome.SENT, messaging.send(msg), null);
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.SENDER_ID_MISMATCH) {
                return new Result(Outcome.INVALID_TOKEN, null, String.valueOf(code));
            }
            if (code == MessagingErrorCode.INVALID_ARGUMENT || code == MessagingErrorCode.THIRD_PARTY_AUTH_ERROR) {
                return new Result(Outcome.PERMANENT_ERROR, null, String.valueOf(code));
            }
            return new Result(Outcome.RETRYABLE_ERROR, null, String.valueOf(code));
        }
    }
}
