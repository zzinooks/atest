package com.fgc.app

import android.app.Application
import com.fgc.app.data.AppSettings
import com.fgc.app.data.SecureTokenStore
import com.fgc.app.hc.HcSyncWorker
import com.fgc.app.notify.Channels
import com.fgc.core.FgcApi

class FgcApp : Application() {
    lateinit var api: FgcApi; private set
    lateinit var settings: AppSettings; private set
    lateinit var tokens: SecureTokenStore; private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        tokens = SecureTokenStore(this)
        api = FgcApi(BuildConfig.BASE_URL, tokens)
        Channels.create(this)
        if (settings.hcEnabled) HcSyncWorker.schedule(this)
    }
}
