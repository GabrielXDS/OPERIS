package br.com.alertaequipe

import org.junit.Assert.assertEquals
import org.junit.Test

class PttReconnectTest {
    @Test fun firstRetryWaitsForRoomCleanup() {
        assertEquals(1_000L, PttReconnect.delayMs(1))
    }
    @Test fun backoffRecoversQuicklyAtFirst() {
        assertEquals(2_000L, PttReconnect.delayMs(2))
        assertEquals(4_000L, PttReconnect.delayMs(3))
        assertEquals(8_000L, PttReconnect.delayMs(4))
        assertEquals(15_000L, PttReconnect.delayMs(5))
    }
    @Test fun prolongedOfflineStateBacksOffForBattery() {
        assertEquals(30_000L, PttReconnect.delayMs(6))
        assertEquals(60_000L, PttReconnect.delayMs(7))
        assertEquals(60_000L, PttReconnect.delayMs(8))
        assertEquals(60_000L, PttReconnect.delayMs(100))
    }
}
