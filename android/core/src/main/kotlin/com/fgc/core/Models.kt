package com.fgc.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime

/** Backend JSON 계약 (backend 의 각 Controller DTO). 필드명이 바뀌면 LiveContractTest 가 깨진다. */

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = OffsetDateTime.parse(decoder.decodeString()).toInstant()
}

object OffsetDateTimeSerializer : KSerializer<OffsetDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("OffsetDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): OffsetDateTime = OffsetDateTime.parse(decoder.decodeString())
}

typealias Time = @Serializable(with = InstantSerializer::class) Instant

@Serializable data class Tokens(val accessToken: String, val refreshToken: String, val tokenType: String, val expiresIn: Long)

@Serializable data class FamilyOf(val patientId: String, val displayName: String, val relation: String,
                                  val viewGlucose: Boolean, val receiveAlerts: Boolean, val writeNotes: Boolean)

@Serializable data class Me(val id: String, val email: String, val displayName: String, val admin: Boolean,
                            val patientId: String? = null, val familyOf: List<FamilyOf> = emptyList(),
                            val isPatient: Boolean = false)

enum class Flag { NORMAL, LOW_OUT_OF_RANGE, HIGH_OUT_OF_RANGE }
enum class Freshness { FRESH, STALE, NO_DATA }

@Serializable data class Reading(val valueMgdl: Int? = null, val flag: Flag, val measuredAt: Time, val receivedAt: Time,
                                 val source: String, val sourceTrend: String? = null)

@Serializable data class Latest(val reading: Reading? = null, val freshness: Freshness, val ageMinutes: Long? = null,
                                val staleAfterMinutes: Int, val ratePerMinute: Double? = null, val serverTime: Time)

@Serializable data class Stats(val from: Time, val to: Time, val readings: Int, val coverage: Double, val reliable: Boolean,
                               val mean: Double? = null, val min: Int? = null, val max: Int? = null, val sd: Double? = null,
                               val cvPercent: Double? = null, val gmiPercent: Double? = null,
                               val veryLowPct: Double, val lowPct: Double, val inRangePct: Double,
                               val highPct: Double, val veryHighPct: Double)

@Serializable data class DailyStats(val date: String, val stats: Stats)

@Serializable data class AlertRules(val urgentLowMgdl: Int, val lowMgdl: Int, val highMgdl: Int, val rapidRate: Double,
                                    val noDataMinutes: Int, val staleMinutes: Int, val repeatMinutes: Int,
                                    val cooldownMinutes: Int, val lowEnabled: Boolean, val highEnabled: Boolean,
                                    val rapidEnabled: Boolean, val noDataEnabled: Boolean, val ordered: Boolean? = null)

@Serializable data class AlertItem(val id: String, val type: String, val label: String, val status: String,
                                   val measuredAt: Time? = null, val openedAt: Time, val acknowledgedAt: Time? = null,
                                   val acknowledgedBy: String? = null, val resolvedAt: Time? = null)

@Serializable data class Permissions(val viewGlucose: Boolean, val receiveAlerts: Boolean, val writeNotes: Boolean)

@Serializable data class InviteRequest(val relation: String, val permissions: Permissions)

@Serializable data class Invitation(val code: String, val expiresAt: Time)

@Serializable data class Member(val memberId: String, val userId: String, val displayName: String, val relation: String,
                                val permissions: Permissions, val status: String, val joinedAt: Time, val revokedAt: Time? = null)

@Serializable data class HealthEvent(val id: String, val type: String, val occurredAt: Time, val title: String,
                                     val detail: String? = null, val carbsG: Int? = null, val durationMinutes: Int? = null,
                                     val authorUserId: String? = null, val authorName: String? = null, val createdAt: Time)

@Serializable data class NewHealthEvent(val type: String, val occurredAt: Time, val title: String, val detail: String? = null,
                                        val carbsG: Int? = null, val durationMinutes: Int? = null)

@Serializable data class DataSourceItem(val id: String, val type: String, val baseUrl: String? = null, val status: String,
                                        val consecutiveFailures: Int, val lastSuccessAt: Time? = null,
                                        val lastError: String? = null, val lastErrorAt: Time? = null, val createdAt: Time)

@Serializable data class NewDataSource(val type: String, val baseUrl: String? = null, val username: String? = null,
                                       val password: String? = null)

@Serializable data class CreatedDataSource(val source: DataSourceItem, val nightscoutUploadPath: String? = null,
                                           val nightscoutSecret: String? = null)

@Serializable data class UploadReading(val externalId: String, val valueMgdl: Int?, val flag: Flag,
                                       @Serializable(with = OffsetDateTimeSerializer::class) val measuredAt: OffsetDateTime)

@Serializable data class UploadRequest(val source: String, val readings: List<UploadReading>)

@Serializable data class IngestResult(val received: Int, val inserted: Int, val duplicates: Int, val rejected: Int)

@Serializable data class ApiErrorBody(val error: String = "unknown", val message: String = "")
