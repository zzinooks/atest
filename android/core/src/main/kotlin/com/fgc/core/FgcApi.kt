package com.fgc.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/** 토큰 저장소. Android 에서는 암호화 저장소로 구현한다 (평문 SharedPreferences 금지). */
interface TokenStore {
    val accessToken: String?
    val refreshToken: String?
    fun save(tokens: Tokens)
    fun clear()
}

class InMemoryTokenStore : TokenStore {
    @Volatile override var accessToken: String? = null; private set
    @Volatile override var refreshToken: String? = null; private set
    override fun save(tokens: Tokens) { accessToken = tokens.accessToken; refreshToken = tokens.refreshToken }
    override fun clear() { accessToken = null; refreshToken = null }
}

class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message) {
    val isUnauthorized get() = status == 401
    val isForbidden get() = status == 403
}

/**
 * Family Glucose Care Backend 클라이언트.
 * - 401 이면 refresh 토큰으로 한 번 갱신 후 재시도. 갱신 실패 시 토큰을 지우고 401 을 던진다 → 앱은 로그인 화면으로.
 * - 혈당값·토큰을 로그로 남기지 않는다 (HttpLoggingInterceptor 를 BODY 레벨로 붙이지 말 것).
 */
class FgcApi(baseUrl: String, private val tokens: TokenStore, client: OkHttpClient? = null) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val refreshLock = Any()

    private val http: OkHttpClient = (client?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val t = tokens.accessToken
            chain.proceed(if (t != null && req.header("Authorization") == null && !req.url.encodedPath.contains("/auth/"))
                req.newBuilder().header("Authorization", "Bearer $t").build() else req)
        }
        .authenticator { _, response -> reauthenticate(response) }
        .build()

    private fun reauthenticate(response: Response): Request? {
        if (response.request.url.encodedPath.contains("/auth/")) return null
        if (response.priorResponse != null) return null // 이미 한 번 재시도함
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        synchronized(refreshLock) {
            val current = tokens.accessToken
            if (current != null && current != failedToken) { // 다른 요청이 이미 갱신함
                return response.request.newBuilder().header("Authorization", "Bearer $current").build()
            }
            val refresh = tokens.refreshToken ?: return null
            val body = json.encodeToString(serializer<Map<String, String>>(), mapOf("refreshToken" to refresh)).toRequestBody(jsonType)
            val r = http.newCall(Request.Builder().url(url("/api/v1/auth/refresh")).post(body).build()).execute()
            r.use {
                if (!it.isSuccessful) { tokens.clear(); return null }
                val t = json.decodeFromString<Tokens>(it.body!!.string())
                tokens.save(t)
                return response.request.newBuilder().header("Authorization", "Bearer ${t.accessToken}").build()
            }
        }
    }

    private fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val b = base.newBuilder()
        path.trim('/').split('/').forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    @PublishedApi internal suspend fun raw(method: String, path: String, body: String? = null, query: Map<String, String?> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url(path, query))
                .method(method, body?.toRequestBody(jsonType) ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(jsonType))
                .build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val err = runCatching { json.decodeFromString<ApiErrorBody>(text) }.getOrDefault(ApiErrorBody())
                    throw ApiException(r.code, err.error, err.message.ifBlank { "HTTP ${r.code}" })
                }
                text
            }
        }

    private suspend inline fun <reified T> call(method: String, path: String, body: String? = null, query: Map<String, String?> = emptyMap()): T {
        val text = raw(method, path, body, query)
        return json.decodeFromString(serializer<T>(), text)
    }

    private inline fun <reified T> enc(v: T) = json.encodeToString(serializer<T>(), v)

    // ---- auth ----
    suspend fun signup(email: String, password: String, displayName: String): Tokens =
        call<Tokens>("POST", "/api/v1/auth/signup", enc(mapOf("email" to email, "password" to password, "displayName" to displayName))).also(tokens::save)

    suspend fun login(email: String, password: String): Tokens =
        call<Tokens>("POST", "/api/v1/auth/login", enc(mapOf("email" to email, "password" to password))).also(tokens::save)

    suspend fun logout() {
        tokens.refreshToken?.let { runCatching { raw("POST", "/api/v1/auth/logout", enc(mapOf("refreshToken" to it))) } }
        tokens.clear()
    }

    // ---- me ----
    suspend fun me(): Me = call("GET", "/api/v1/me")
    suspend fun becomePatient(timeZone: String): String =
        call<Map<String, String>>("POST", "/api/v1/me/patient-profile", enc(mapOf("timeZone" to timeZone)))["patientId"]!!
    suspend fun registerDevice(pushToken: String) {
        raw("POST", "/api/v1/me/devices", enc(mapOf("pushToken" to pushToken, "platform" to "ANDROID")))
    }
    suspend fun withdraw(password: String) { raw("POST", "/api/v1/me/withdraw", enc(mapOf("password" to password))); tokens.clear() }
    suspend fun leaveFamily(patientId: String) { raw("DELETE", "/api/v1/me/family/$patientId") }

    // ---- glucose ----
    suspend fun latest(patientId: String): Latest = call("GET", "/api/v1/patients/$patientId/glucose/latest")
    suspend fun readings(patientId: String, from: Instant, to: Instant? = null): List<Reading> =
        call("GET", "/api/v1/patients/$patientId/glucose", query = mapOf("from" to from.toString(), "to" to to?.toString()))
    suspend fun upload(patientId: String, request: UploadRequest): IngestResult =
        call("POST", "/api/v1/patients/$patientId/glucose/batch", enc(request))
    suspend fun stats(patientId: String, from: Instant, to: Instant? = null): Stats =
        call("GET", "/api/v1/patients/$patientId/stats", query = mapOf("from" to from.toString(), "to" to to?.toString()))
    suspend fun dailyStats(patientId: String, days: Int): List<DailyStats> =
        call("GET", "/api/v1/patients/$patientId/stats/daily", query = mapOf("days" to days.toString()))

    // ---- alerts ----
    suspend fun alertRules(patientId: String): AlertRules = call("GET", "/api/v1/patients/$patientId/alert-rules")
    suspend fun saveAlertRules(patientId: String, rules: AlertRules): AlertRules =
        call("PUT", "/api/v1/patients/$patientId/alert-rules", enc(rules.copy(ordered = null)))
    suspend fun alerts(patientId: String, activeOnly: Boolean = false): List<AlertItem> =
        call("GET", "/api/v1/patients/$patientId/alerts", query = mapOf("activeOnly" to activeOnly.toString()))
    suspend fun acknowledge(alertId: String) { raw("POST", "/api/v1/alerts/$alertId/ack") }

    // ---- family ----
    suspend fun invite(patientId: String, request: InviteRequest): Invitation =
        call("POST", "/api/v1/patients/$patientId/invitations", enc(request))
    suspend fun acceptInvitation(code: String): String =
        call<Map<String, String>>("POST", "/api/v1/invitations/accept", enc(mapOf("code" to code)))["patientId"]!!
    suspend fun members(patientId: String): List<Member> = call("GET", "/api/v1/patients/$patientId/members")
    suspend fun updatePermissions(patientId: String, memberId: String, p: Permissions) {
        raw("PUT", "/api/v1/patients/$patientId/members/$memberId/permissions", enc(p))
    }
    suspend fun revokeMember(patientId: String, memberId: String) { raw("DELETE", "/api/v1/patients/$patientId/members/$memberId") }

    // ---- events ----
    suspend fun events(patientId: String, from: Instant, to: Instant? = null): List<HealthEvent> =
        call("GET", "/api/v1/patients/$patientId/events", query = mapOf("from" to from.toString(), "to" to to?.toString()))
    suspend fun addEvent(patientId: String, e: NewHealthEvent): HealthEvent = call("POST", "/api/v1/patients/$patientId/events", enc(e))

    // ---- data sources ----
    suspend fun dataSources(patientId: String): List<DataSourceItem> = call("GET", "/api/v1/patients/$patientId/data-sources")
    suspend fun addDataSource(patientId: String, s: NewDataSource): CreatedDataSource =
        call("POST", "/api/v1/patients/$patientId/data-sources", enc(s))
    suspend fun setNotion(patientId: String, enabled: Boolean) {
        raw("PUT", "/api/v1/patients/$patientId/notion", query = mapOf("enabled" to enabled.toString()))
    }
}
