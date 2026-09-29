package br.com.alertaequipe

import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PttSessionControllerTest {

    @Test
    fun idleToConnectedMicOff() {
        val c = PttSessionController()
        c.onConnecting()
        assertEquals(PttConnectionStatus.CONNECTING, c.state.status)
        c.onConnected(3)
        assertEquals(PttConnectionStatus.CONNECTED, c.state.status)
        assertEquals(3, c.state.connectedCount)
        assertFalse(c.state.micEnabled)
    }

    @Test
    fun pressThenGrantedTransmits() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        assertTrue(token > 0)
        assertTrue(c.state.requesting)
        assertFalse(c.state.micEnabled)
        c.onFloorGranted(token)
        assertEquals(PttFloorPhase.TRANSMITTING, c.state.floor)
        assertTrue(c.state.micEnabled)
    }

    @Test
    fun pressDeniedBusyMicOff() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.onFloorDenied(token, "Maria")
        assertEquals(PttFloorPhase.BUSY, c.state.floor)
        assertEquals("Maria", c.state.holderName)
        assertFalse(c.state.micEnabled)
    }

    @Test
    fun pressDeniedWithoutHolder() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.onFloorDenied(token, null)
        assertEquals(PttFloorPhase.BUSY, c.state.floor)
        assertNull(c.state.holderName)
    }

    @Test
    fun grantedThenReleaseReturnsToIdleMicOff() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.onFloorGranted(token)
        assertTrue(c.state.micEnabled)
        c.releaseStart()
        assertEquals(PttFloorPhase.IDLE, c.state.floor)
        assertFalse(c.state.micEnabled)
    }

    @Test
    fun deniedThenReleaseClearsBusy() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.onFloorDenied(token, "Joao")
        c.releaseStart()
        assertEquals(PttFloorPhase.IDLE, c.state.floor)
        assertNull(c.state.holderName)
        assertFalse(c.state.micEnabled)
    }

    @Test
    fun lateGrantIgnoredAfterRelease() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.releaseStart()
        c.onFloorGranted(token)
        assertEquals(PttFloorPhase.IDLE, c.state.floor)
        assertFalse(c.state.micEnabled)
    }

    @Test
    fun staleTokenIgnored() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.releaseStart()
        val token2 = c.pressStart()
        c.onFloorGranted(token) // respostas antigas não devem valer
        assertEquals(PttFloorPhase.REQUESTING, c.state.floor)
        c.onFloorGranted(token2)
        assertEquals(PttFloorPhase.TRANSMITTING, c.state.floor)
    }

    @Test
    fun pressWhileNotConnectedIgnored() {
        val c = PttSessionController()
        assertEquals(0, c.pressStart())
        c.onConnecting()
        assertEquals(0, c.pressStart())
        assertEquals(PttFloorPhase.IDLE, c.state.floor)
    }

    @Test
    fun disconnectDuringTransmissionLiftsMic() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.onFloorGranted(token)
        c.onDisconnected("quedas")
        assertEquals(PttConnectionStatus.DISCONNECTED, c.state.status)
        assertEquals(PttFloorPhase.IDLE, c.state.floor)
        assertFalse(c.state.micEnabled)
        assertEquals("quedas", c.state.error)
    }

    @Test
    fun reconnectingResetsFloorAndMic() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.onFloorGranted(token)
        c.onReconnecting()
        assertEquals(PttConnectionStatus.RECONNECTING, c.state.status)
        assertEquals(PttFloorPhase.IDLE, c.state.floor)
        assertFalse(c.state.micEnabled)
        assertEquals(0, c.state.connectedCount)
    }

    @Test
    fun reconnectedLiftsMicAndRestoresCount() {
        val c = PttSessionController()
        c.onConnected(2)
        c.onReconnecting()
        c.onConnected(4)
        assertEquals(PttConnectionStatus.CONNECTED, c.state.status)
        assertEquals(4, c.state.connectedCount)
        assertFalse(c.state.micEnabled)
    }

    @Test
    fun heartbeatLostDuringTransmissionGoesBusy() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.onFloorGranted(token)
        c.onHeartbeatLost()
        assertEquals(PttFloorPhase.BUSY, c.state.floor)
        assertFalse(c.state.micEnabled)
    }

    @Test
    fun heartbeatLostWhileIdleIsIgnored() {
        val c = PttSessionController()
        c.onConnected(1)
        c.onHeartbeatLost()
        assertEquals(PttFloorPhase.IDLE, c.state.floor)
    }

    @Test
    fun maxTransmissionReachedReturnsToIdle() {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.onFloorGranted(token)
        assertTrue(c.state.micEnabled)
        c.onMaxTransmissionReached()
        assertEquals(PttFloorPhase.IDLE, c.state.floor)
        assertFalse(c.state.micEnabled)
    }

    @Test
    fun speakingNameSetAndCleared() {
        val c = PttSessionController()
        c.onConnected(1)
        c.onSpeakersChanged("Ana")
        assertEquals("Ana", c.state.speakingName)
        c.onSpeakersChanged(null)
        assertNull(c.state.speakingName)
    }

    @Test
    fun stateFlowEmitsMicChanges() = kotlinx.coroutines.runBlocking {
        val c = PttSessionController()
        c.onConnected(1)
        val token = c.pressStart()
        c.onFloorGranted(token)
        assertTrue(c.stateFlow.first { it.micEnabled }.micEnabled)
        c.releaseStart()
        assertFalse(c.stateFlow.first { !it.micEnabled }.micEnabled)
    }

    @Test
    fun participantCountUpdatesWhileConnected() {
        val c = PttSessionController()
        c.onConnected(2)
        c.onParticipantsChanged(3)
        assertEquals(3, c.state.connectedCount)
    }

    @Test
    fun participantCountIgnoredWhenNotConnected() {
        val c = PttSessionController()
        c.onConnected(2)
        c.onReconnecting()
        c.onParticipantsChanged(3)
        assertEquals(0, c.state.connectedCount)
        c.onDisconnected(null)
        c.onParticipantsChanged(5)
        assertEquals(0, c.state.connectedCount)
    }
}