package com.fgc.app.data

import android.content.Context
import java.time.Instant

/** 민감하지 않은 앱 설정 */
class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("fgc_settings", Context.MODE_PRIVATE)

    var hcEnabled: Boolean
        get() = p.getBoolean("hc_enabled", false)
        set(v) = p.edit().putBoolean("hc_enabled", v).apply()

    /** Health Connect 에서 읽을 원천 앱 패키지 (예: 바로잰 Fit). 실제 패키지명은 실기기에서 확인 필요 */
    var hcOrigin: String?
        get() = p.getString("hc_origin", null)
        set(v) = p.edit().putString("hc_origin", v).apply()

    var lastHcSyncTo: Instant?
        get() = p.getString("hc_last_to", null)?.let(Instant::parse)
        set(v) = p.edit().putString("hc_last_to", v?.toString()).apply()

    var patientId: String?
        get() = p.getString("patient_id", null)
        set(v) = p.edit().putString("patient_id", v).apply()
}
