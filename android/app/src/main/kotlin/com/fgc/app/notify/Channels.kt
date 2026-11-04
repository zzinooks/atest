package com.fgc.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Channels {
    /** Backend FcmSender.CHANNEL_ID 와 같아야 한다 */
    const val ALERTS = "glucose_alerts"
    const val GENERAL = "general"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "혈당 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "저혈당·고혈당·급변동·데이터 미수신 알림"
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(GENERAL, "공유·계정", NotificationManager.IMPORTANCE_DEFAULT))
    }
}
