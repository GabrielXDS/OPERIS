package br.com.alertaequipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsClientTest {
    private val now = 1_700_000_000_000L
    private fun payload(recordType: String = "occurrence", recordId: String = "abc-123",
        teamId: String = "team-1", createdBy: String = "other-device", title: String = "Acionamento de alarme",
        createdAt: Long = now - 5_000) =
        mapOf("type" to "record_created", "recordType" to recordType, "recordId" to recordId,
            "teamId" to teamId, "createdBy" to createdBy, "title" to title, "createdAt" to createdAt.toString())

    @Test fun `validRecordPayload aceita registro valido`() =
        assertTrue(Operational.validRecordPayload(payload(), "me", now))

    @Test fun `rejeita tipo errado e recordType invalido`() {
        assertFalse(Operational.validRecordPayload(payload().plus("type" to "PANIC_ALERT"), "me", now))
        assertFalse(Operational.validRecordPayload(payload(recordType = "outro"), "me", now))
    }

    @Test fun `rejeita recordId invalido ou ausente`() {
        assertFalse(Operational.validRecordPayload(payload(recordId = ""), "me", now))
        assertFalse(Operational.validRecordPayload(payload(recordId = "id com espaco"), "me", now))
        assertFalse(Operational.validRecordPayload(payload(recordId = "x".repeat(65)), "me", now))
    }

    @Test fun `rejeita titulo vazio ou longo, autor proprio ou ausente`() {
        assertFalse(Operational.validRecordPayload(payload(title = "   "), "me", now))
        assertFalse(Operational.validRecordPayload(payload(title = "x".repeat(81)), "me", now))
        assertFalse(Operational.validRecordPayload(payload(createdBy = "me"), "me", now))
        assertFalse(Operational.validRecordPayload(payload(createdBy = ""), "me", now))
    }

    @Test fun `rejeita createdAt invalido ou fora da janela`() {
        assertFalse(Operational.validRecordPayload(payload(createdAt = 0), "me", now))
        assertFalse(Operational.validRecordPayload(payload(createdAt = now + 31_000), "me", now))
        assertFalse(Operational.validRecordPayload(payload(createdAt = now - 4L * 60 * 60 * 1000 - 1), "me", now))
    }

    @Test fun `aceita janelas de borda`() {
        assertTrue(Operational.validRecordPayload(payload(createdAt = now - 30_000), "me", now))
        assertTrue(Operational.validRecordPayload(payload(createdAt = now - 4L * 60 * 60 * 1000), "me", now))
        assertTrue(Operational.validRecordPayload(payload(recordType = "round"), "me", now))
    }

    @Test fun `badge formata contagem e cap em 99+`() {
        assertNull(Badges.label(0))
        assertEquals("1", Badges.label(1))
        assertEquals("42", Badges.label(42))
        assertEquals("99+", Badges.label(100))
    }

    @Test fun `unreadText soma modulos com singular e plural`() {
        assertEquals("", unreadText(0, 0))
        assertEquals("1 ocorrência", unreadText(1, 0))
        assertEquals("2 ocorrências", unreadText(2, 0))
        assertEquals("1 ronda", unreadText(0, 1))
        assertEquals("2 rondas", unreadText(0, 2))
        assertEquals("2 ocorrências • 1 ronda", unreadText(2, 1))
    }

    @Test fun `seenPolicy adota servidor a frente e nunca retrocede`() {
        assertEquals(10_000L to false, seenPolicy(5_000L, 10_000L))
        assertEquals(5_000L to true, seenPolicy(5_000L, 4_000L))
        assertEquals(5_000L to false, seenPolicy(5_000L, null))
        assertEquals(0L to false, seenPolicy(0L, null))
    }

    @Test fun `seenPolicy sinaliza reconciliacao quando cache local esta a frente`() {
        val (effective, push) = seenPolicy(50_000L, 30_000L)
        assertEquals(50_000L, effective)
        assertTrue(push)
    }

    @Test fun `nextSeen usa visto-at e nunca retrocede`() {
        assertEquals(0L, nextSeen(0L, 0L))
        assertEquals(40_000L, nextSeen(10_000L, 40_000L))
        assertEquals(10_000L, nextSeen(10_000L, 4_000L))
        assertEquals(10_000L, nextSeen(10_000L, -5))
        assertEquals(10_000L, nextSeen(10_000L, 0L))
        assertEquals(100L, nextSeen(0L, 100L))
    }
}