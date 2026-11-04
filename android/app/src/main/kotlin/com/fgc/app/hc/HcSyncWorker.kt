package com.fgc.app.hc

import android.content.Context
import androidx.work.*
import com.fgc.app.FgcApp
import com.fgc.core.ApiException
import com.fgc.core.HealthConnectMapper
import com.fgc.core.HcSyncPlanner
import com.fgc.core.UploadRequest
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Health Connect → Backend 주기 업로드. WorkManager 최소 주기는 15분이라 이 경로의 지연은 최대 15분+ 이다.
 * 그래서 저혈당 즉시성은 이 경로만으로 보장되지 않는다 → 서버의 미수신 알림(NO_DATA)이 안전망 (STEP 8 보고서).
 */
class HcSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        private const val NAME = "hc-sync"
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<HcSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        fun runNow(context: Context) =
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<HcSyncWorker>().build())
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as FgcApp
        val pid = app.settings.patientId ?: return Result.success()
        val reader = HealthConnectReader(applicationContext)
        if (!reader.grantedPermissions().contains(HealthConnectReader.READ_GLUCOSE)) return Result.failure()
        val now = Instant.now()
        val window = HcSyncPlanner.next(app.settings.lastHcSyncTo, now)
        return try {
            val records = reader.read(window.from, window.to)
            val uploads = HealthConnectMapper.toUpload(records, setOfNotNull(app.settings.hcOrigin))
            for (batch in HealthConnectMapper.batches(uploads)) {
                app.api.upload(pid, UploadRequest("HEALTH_CONNECT", batch))
            }
            app.settings.lastHcSyncTo = window.to
            Result.success()
        } catch (e: ApiException) {
            if (e.isUnauthorized || e.isForbidden) Result.failure() else Result.retry()
        } catch (e: java.io.IOException) {
            Result.retry()
        }
    }
}
