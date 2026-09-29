package br.com.alertaequipe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyTest {
    @Test
    fun ownerCanViewLastCommunication() {
        assertTrue(Operational.canViewLastCommunication("owner"))
    }

    @Test
    fun adminCanViewLastCommunication() {
        assertTrue(Operational.canViewLastCommunication("admin"))
    }

    @Test
    fun commonMemberCannotViewLastCommunication() {
        assertFalse(Operational.canViewLastCommunication("member"))
        assertFalse(Operational.canViewLastCommunication(null))
    }

    @Test
    fun omittedBackendTimestampParsesToNullForCommonMember() {
        val member = MemberStatus("u", "Wesley", "member", "online", "BRIGADISTA", null, null, true, "0.3.6")
        assertTrue(member.lastSeenAt == null)
    }
}