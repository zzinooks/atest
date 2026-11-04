package com.fgc.app.hc

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fgc.core.HcGlucoseRecord
import java.time.Instant

/** Health Connect 에서 혈당 레코드를 읽어 :core 의 플랫폼 독립 모델로 옮긴다. */
class HealthConnectReader(private val context: Context) {
    companion object {
        val READ_GLUCOSE = HealthPermission.getReadPermission(BloodGlucoseRecord::class)
        const val READ_BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        val ALL = setOf(READ_GLUCOSE, READ_BACKGROUND)
    }

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    @androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi
    fun backgroundReadSupported(): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    suspend fun read(from: Instant, to: Instant): List<HcGlucoseRecord> {
        val out = mutableListOf<HcGlucoseRecord>()
        var page: String? = null
        do {
            val res = client.readRecords(ReadRecordsRequest(BloodGlucoseRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to), pageSize = 1000, pageToken = page))
            res.records.forEach { r ->
                out += HcGlucoseRecord(r.metadata.id, r.time, r.zoneOffset, r.level.inMilligramsPerDeciliter,
                    r.specimenSource, r.metadata.dataOrigin.packageName)
            }
            page = res.pageToken
        } while (page != null)
        return out
    }

    /** 혈당을 기록한 앱 목록 — 사용자가 원천 앱(예: 바로잰 Fit)을 고르게 한다. */
    suspend fun origins(from: Instant, to: Instant): Set<String> = read(from, to).map { it.originPackage }.toSet()
}
