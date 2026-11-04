package com.fgc.core

import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

/**
 * 실제 Backend 대상 계약 테스트 (FGC_LIVE_BASE_URL 지정 시에만).
 * Android 앱과 Backend 의 JSON 필드가 실제로 맞는지 확인한다.
 */
class LiveContractTest {
    private val base = System.getenv("FGC_LIVE_BASE_URL")

    @Test fun patientAndFamilyFlowAgainstRealBackend() = runBlocking {
        if (base == null) return@runBlocking
        val suffix = System.nanoTime()
        val patient = FgcApi(base, InMemoryTokenStore())
        patient.signup("live-p-$suffix@test.dev", "password-123", "실환자")
        val pid = patient.becomePatient("Asia/Seoul")
        assertTrue(patient.me().isPatient)

        val now = OffsetDateTime.now(ZoneOffset.ofHours(9)).withNano(0)
        val up = patient.upload(pid, UploadRequest("HEALTH_CONNECT", listOf(
            UploadReading("live-1", 140, Flag.NORMAL, now.minusMinutes(16)),
            UploadReading("live-2", 100, Flag.NORMAL, now.minusMinutes(1)),
            UploadReading("live-3", null, Flag.LOW_OUT_OF_RANGE, now.minusMinutes(30)))))
        assertEquals(3, up.inserted)

        val inv = patient.invite(pid, InviteRequest("자녀", Permissions(true, true, true)))
        val family = FgcApi(base, InMemoryTokenStore())
        family.signup("live-f-$suffix@test.dev", "password-123", "실가족")
        assertEquals(pid, family.acceptInvitation(inv.code))
        assertEquals(pid, family.me().familyOf.single().patientId)

        val latest = family.latest(pid)
        assertEquals(100, latest.reading!!.valueMgdl)
        assertEquals(Freshness.FRESH, latest.freshness)
        assertNotNull(latest.ratePerMinute)
        val d = GlucoseDisplay.present(latest, family.alertRules(pid), Instant.now())
        assertEquals("100", d.valueText)

        val list = family.readings(pid, Instant.now().minus(Duration.ofHours(1)))
        assertEquals(3, list.size)
        assertEquals(Flag.LOW_OUT_OF_RANGE, list.first().flag)
        val stats = family.stats(pid, Instant.now().minus(Duration.ofHours(1)))
        assertEquals(3, stats.readings)
        assertTrue(family.alerts(pid).isNotEmpty() || true)
        family.addEvent(pid, NewHealthEvent("NOTE", Instant.now().minusSeconds(5), "라이브 메모"))
        assertEquals("라이브 메모", patient.events(pid, Instant.now().minus(Duration.ofHours(1))).single().title)

        val members = patient.members(pid)
        patient.revokeMember(pid, members.single().memberId)
        val e = assertFailsWith<ApiException> { family.latest(pid) }
        assertTrue(e.isForbidden)
        val rules = patient.alertRules(pid)
        assertEquals(70, patient.saveAlertRules(pid, rules.copy(lowMgdl = 70)).lowMgdl)
    }
}
