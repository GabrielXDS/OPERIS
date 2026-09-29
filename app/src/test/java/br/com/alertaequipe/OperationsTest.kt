package br.com.alertaequipe

import org.junit.Assert.*
import org.junit.Test

class OperationsTest {
    @Test fun inviteVariantsNormalize() {
        listOf("AB7K92QD","AB7K-92QD","ab7k92qd","ab7k-92qd").forEach {
            assertEquals("AB7K92QD",Operational.normalizeInvite(it))
            assertTrue(Operational.validInvite(it))
        }
        listOf("AB0K92QD","AB1K92QD","ABL792QD","AAAA","../teams").forEach {assertFalse(Operational.validInvite(it))}
    }
    @Test fun offlineOverridesPauseAtExpiry() {
        assertEquals("paused",Operational.status(1000,"paused",181000))
        assertEquals("offline",Operational.status(1000,"paused",181001))
        assertEquals("offline",Operational.status(null,"available",1000))
        assertEquals("offline",Operational.status(10000,"available",1000))
    }
    @Test fun availabilityIsBounded() {
        assertTrue(Operational.validAvailability("available",null))
        listOf("lunch","dinner","break","other").forEach {assertTrue(Operational.validAvailability("paused",it))}
        assertFalse(Operational.validAvailability("owner",null))
        assertFalse(Operational.validAvailability("paused","anything"))
        assertFalse(Operational.validAvailability("available","lunch"))
    }
@Test fun releaseOnlyWhenVersionChanges() {
        assertTrue(Operational.shouldShowRelease(2,1))
        assertFalse(Operational.shouldShowRelease(2,2))
        assertTrue(Operational.shouldShowRelease(2,null))
        assertTrue(BuildConfig.VERSION_CODE > 0)
        assertTrue(BuildConfig.VERSION_NAME.matches(Regex("""\d+\.\d+\.\d+""")))
    }
@Test fun statusLabelMapsThreeMainStates() {
        assertEquals("Dispon\u00EDvel",Operational.statusLabel("available",null))
        assertEquals("Em pausa",Operational.statusLabel("paused","break"))
        assertEquals("Indispon\u00EDvel",Operational.statusLabel("paused","other"))
        assertEquals("Dispon\u00EDvel",Operational.statusLabel(null,null))
    }
    @Test fun sharingRequiresRealHttpsWithoutCredentials() {
        assertTrue(ReleaseInfo.validDownload("https://example.com/alerta.apk"))
        listOf("","http://example.com/file","https://user:password@example.com/file",
            "https://example.com/file?token=secret").forEach {assertFalse(ReleaseInfo.validDownload(it))}
    }
    @Test fun ownerAndMemberSurviveSelection() {
        val list=listOf(TeamMembership("a","A","owner"),TeamMembership("b","B","member"))
        assertEquals("member",TeamSelection.resolve(list,"b")?.role)
        assertEquals("A",TeamSelection.receivedTeamName("a",list))
    }
    @Test fun teamIdentifierValidation() {
        listOf("equipe-alfa", "equipe_beta", "TEAM123", "a", "A", "equipe-01-a_b").forEach {
            assertTrue("Expected valid: $it", Operational.validTeamIdentifier(it))
        }
        listOf(null, "", "team/1", "../path", "team with spaces", "team@symbol", "a".repeat(65)).forEach {
            assertFalse("Expected invalid: $it", Operational.validTeamIdentifier(it))
        }
    }
    @Test fun validAlertPayloadAcceptsApprovedMemberWithoutLocalCache() {
        val data = mapOf(
            "type" to "PANIC_ALERT",
            "alertId" to "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            "teamId" to "equipe-gabriel",
            "senderDeviceId" to "gabriel-device",
            "senderName" to "Gabriel",
            "timestamp" to "100000"
        )
        // Renato has not opened the app yet, but validAlertPayload succeeds
        assertTrue(Operational.validAlertPayload(data, "renato-device", 105000))
    }
    @Test fun validAlertPayloadRejectsSelfAlert() {
        val data = mapOf(
            "type" to "PANIC_ALERT",
            "alertId" to "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            "teamId" to "equipe-gabriel",
            "senderDeviceId" to "my-device",
            "senderName" to "Gabriel",
            "timestamp" to "100000"
        )
        assertFalse(Operational.validAlertPayload(data, "my-device", 105000))
    }
    @Test fun validAlertPayloadRejectsMalformedFields() {
        val base = mapOf(
            "type" to "PANIC_ALERT",
            "alertId" to "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            "teamId" to "equipe-gabriel",
            "senderDeviceId" to "sender-device",
            "senderName" to "Gabriel",
            "timestamp" to "100000"
        )
        assertFalse("Bad type", Operational.validAlertPayload(base + ("type" to "OTHER"), "my-device", 105000))
        assertFalse("Bad UUID", Operational.validAlertPayload(base + ("alertId" to "not-a-uuid"), "my-device", 105000))
        assertFalse("Bad team", Operational.validAlertPayload(base + ("teamId" to "team/with/slash"), "my-device", 105000))
        assertFalse("Empty name", Operational.validAlertPayload(base + ("senderName" to "   "), "my-device", 105000))
        assertFalse("Too long name", Operational.validAlertPayload(base + ("senderName" to "A".repeat(41)), "my-device", 105000))
        assertTrue("Max length name", Operational.validAlertPayload(base + ("senderName" to "A".repeat(40)), "my-device", 105000))
        assertFalse("Bad timestamp", Operational.validAlertPayload(base + ("timestamp" to "not_a_number"), "my-device", 105000))
    }
    @Test fun validAlertPayloadEnforcesTtlAndClockSkew() {
        val base = mapOf(
            "type" to "PANIC_ALERT",
            "alertId" to "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            "teamId" to "equipe-gabriel",
            "senderDeviceId" to "sender-device",
            "senderName" to "Gabriel"
        )
        val now = 100_000L
        assertTrue("Current time", Operational.validAlertPayload(base + ("timestamp" to "$now"), "my-device", now))
        assertTrue("Within TTL", Operational.validAlertPayload(base + ("timestamp" to "${now - 59_999}"), "my-device", now))
        assertFalse("Expired TTL", Operational.validAlertPayload(base + ("timestamp" to "${now - 60_000}"), "my-device", now))
        assertTrue("Slight future clock skew", Operational.validAlertPayload(base + ("timestamp" to "${now + 30_000}"), "my-device", now))
        assertFalse("Excessive future clock skew", Operational.validAlertPayload(base + ("timestamp" to "${now + 30_001}"), "my-device", now))
    }
}


