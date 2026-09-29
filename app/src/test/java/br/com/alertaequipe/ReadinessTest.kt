package br.com.alertaequipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    @Test fun audioWarningPersistsWhilePanicIsRecent() {
        assertTrue(audioWarningShouldWarn(recentActivePanic = true, alarmVolume = 10))
        assertTrue(audioWarningShouldWarn(recentActivePanic = true, alarmVolume = 0))
    }
    @Test fun audioWarningHealsAfterTheWindowWhenVolumeIsFine() {
        assertFalse(audioWarningShouldWarn(recentActivePanic = false, alarmVolume = 10))
    }
    @Test fun audioWarningStaysWhenAlarmVolumeIsZero() {
        assertTrue(audioWarningShouldWarn(recentActivePanic = false, alarmVolume = 0))
    }
}