package com.fgc.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class FgcApiTest {
    private val server = MockWebServer()
    private val store = InMemoryTokenStore()
    private lateinit var api: FgcApi

    @BeforeTest fun setup() { server.start(); api = FgcApi(server.url("/").toString(), store) }
    @AfterTest fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private val tokens = """{"accessToken":"A1","refreshToken":"R1","tokenType":"Bearer","expiresIn":900}"""

    @Test fun loginStoresTokensAndAuthorizesNextCall() = runBlocking {
        server.enqueue(json(tokens))
        server.enqueue(json("""{"id":"u","email":"e","displayName":"d","admin":false,"familyOf":[],"isPatient":false}"""))
        api.login("e", "p")
        assertEquals("A1", store.accessToken)
        val me = api.me()
        assertEquals("u", me.id)
        server.takeRequest()
        assertEquals("Bearer A1", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun expiredAccessTokenIsRefreshedOnceAndRetried() = runBlocking {
        store.save(Tokens("OLD", "R0", "Bearer", 900))
        server.enqueue(json("""{"error":"invalid_token"}""", 401))
        server.enqueue(json(tokens))
        server.enqueue(json("""{"freshness":"NO_DATA","staleAfterMinutes":15,"serverTime":"2026-11-03T03:00:00Z"}"""))
        val l = api.latest("p1")
        assertEquals(Freshness.NO_DATA, l.freshness)
        assertEquals("Bearer OLD", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("R0"))
        assertEquals("Bearer A1", server.takeRequest().getHeader("Authorization"))
        assertEquals("R1", store.refreshToken)
    }

    @Test fun failedRefreshClearsTokensAndSurfaces401() = runBlocking {
        store.save(Tokens("OLD", "R0", "Bearer", 900))
        server.enqueue(json("""{"error":"invalid_token"}""", 401))
        server.enqueue(json("""{"error":"invalid_refresh_token","message":"expired"}""", 401))
        val e = assertFailsWith<ApiException> { api.me() }
        assertTrue(e.isUnauthorized)
        assertNull(store.accessToken)
    }

    @Test fun forbiddenIsNotRetriedAndCarriesCode() = runBlocking {
        store.save(Tokens("A", "R", "Bearer", 900))
        server.enqueue(json("""{"error":"forbidden","message":"No permission"}""", 403))
        val e = assertFailsWith<ApiException> { api.latest("p") }
        assertTrue(e.isForbidden)
        assertEquals("forbidden", e.code)
        assertEquals(1, server.requestCount)
    }

    @Test fun queryParamsAreEncoded() = runBlocking {
        store.save(Tokens("A", "R", "Bearer", 900))
        server.enqueue(json("[]"))
        api.readings("p", java.time.Instant.parse("2026-11-03T00:00:00Z"))
        assertEquals("/api/v1/patients/p/glucose?from=2026-11-03T00%3A00%3A00Z", server.takeRequest().path)
    }
}
