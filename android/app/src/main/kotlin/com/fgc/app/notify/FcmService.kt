package com.fgc.app.notify

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fgc.app.FgcApp
import com.fgc.app.ui.MainActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** FCM 수신. 알림 문구에는 혈당 숫자가 없다(서버 정책). 탭하면 앱에서 상세 확인 → 확인(ack). */
class FcmService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val app = application as FgcApp
        scope.launch { runCatching { app.api.registerDevice(token) } } // 로그인 전이면 실패 → 로그인 후 다시 등록
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data["kind"] ?: "ALERT"
        val title = message.notification?.title ?: "가족 혈당 케어"
        val body = message.notification?.body ?: "앱에서 확인하세요."
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            message.data["alertId"]?.let { putExtra("alertId", it) }
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, if (kind == "ALERT") Channels.ALERTS else Channels.GENERAL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(body)
            .setPriority(if (kind == "ALERT") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true).setContentIntent(pi).build()
        runCatching { NotificationManagerCompat.from(this).notify(message.messageId.hashCode(), n) } // 알림 권한 거부 시 SecurityException
    }
}
