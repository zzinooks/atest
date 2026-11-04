package com.fgc.core

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Health Connect BloodGlucoseRecord → 업로드 형식 변환 (Android SDK 비의존, 테스트 가능).
 * 앱 모듈은 androidx.health.connect 의 레코드를 이 클래스로 옮겨 담기만 한다.
 */
data class HcGlucoseRecord(
    val recordId: String,
    val time: Instant,
    val zoneOffset: ZoneOffset?,
    val mgdl: Double,
    val specimenSource: Int,          // BloodGlucoseRecord.SPECIMEN_SOURCE_* (2 = INTERSTITIAL_FLUID)
    val originPackage: String,
)

object HealthConnectMapper {
    const val SPECIMEN_SOURCE_INTERSTITIAL_FLUID = 2
    private const val MIN = 20
    private const val MAX = 600

    /**
     * @param allowedOrigins 사용자가 고른 원천 앱 패키지 (비어 있으면 전부). 여러 앱이 같은 측정을 쓰면 중복이 되므로 하나를 고르게 한다.
     * @param cgmOnly true 면 간질액(CGM) 측정만. 손끝 채혈 기록 제외.
     */
    fun toUpload(records: List<HcGlucoseRecord>, allowedOrigins: Set<String>, cgmOnly: Boolean = true): List<UploadReading> =
        records.asSequence()
            .filter { allowedOrigins.isEmpty() || it.originPackage in allowedOrigins }
            .filter { !cgmOnly || it.specimenSource == SPECIMEN_SOURCE_INTERSTITIAL_FLUID }
            .map { it to it.mgdl.roundToInt() }
            .filter { (_, v) -> v in MIN..MAX }
            .distinctBy { (r, _) -> r.recordId }
            .map { (r, v) ->
                UploadReading("hc:${r.recordId}", v, Flag.NORMAL, OffsetDateTime.ofInstant(r.time, r.zoneOffset ?: ZoneOffset.UTC))
            }
            .sortedBy { it.measuredAt }
            .toList()

    fun batches(readings: List<UploadReading>, size: Int = 500): List<List<UploadReading>> = readings.chunked(size)
}

/** Health Connect 읽기 구간 계획: 마지막 성공 시점에서 lookback 만큼 겹쳐 읽는다 (늦게 기록된 값 회수). */
object HcSyncPlanner {
    val LOOKBACK: Duration = Duration.ofHours(3)
    /** 기록 이력 권한(READ_HEALTH_DATA_HISTORY)이 없으면 권한 부여 30일 이전 데이터는 읽을 수 없다 [Health Connect 정책, 재확인 필요] */
    val DEFAULT_HISTORY: Duration = Duration.ofDays(30)

    data class Window(val from: Instant, val to: Instant)

    fun next(lastSuccessTo: Instant?, now: Instant, firstSyncSpan: Duration = Duration.ofDays(1)): Window {
        val from = lastSuccessTo?.minus(LOOKBACK) ?: now.minus(firstSyncSpan)
        val floor = now.minus(DEFAULT_HISTORY)
        return Window(if (from.isBefore(floor)) floor else from, now)
    }
}
