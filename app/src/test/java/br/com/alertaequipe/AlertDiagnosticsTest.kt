package br.com.alertaequipe

import org.junit.Assert.*
import org.junit.Test

class AlertDiagnosticsTest {
    private val prepared = AlertDiagnostics(
        notificationsEnabled = true, channelReady = true, fullScreenIntentAllowed = true, microphoneGranted = true,
        notificationPolicyAccess = true, batteryOptimizationIgnored = true,
        doNotDisturb = false, audioWarning = false, alarmVolume = 0, maximumAlarmVolume = 7
    )

    @Test fun zeroAlarmVolumeDoesNotInvalidateExistingAutomaticVolumeBehavior() {
        assertFalse(prepared.needsAttention)
    }
    @Test fun blockedNotificationOrChannelNeedsAttention() {
        assertTrue(prepared.copy(notificationsEnabled = false).needsAttention)
        assertTrue(prepared.copy(channelReady = false).needsAttention)
    }
    @Test fun restrictionsAndAudioWarningRemainVisible() {
        assertTrue(prepared.copy(doNotDisturb = true).needsAttention)
        assertTrue(prepared.copy(audioWarning = true).needsAttention)
    }
    @Test fun missingFullScreenPermissionNeedsAttention() {
        assertTrue(prepared.copy(fullScreenIntentAllowed = false).needsAttention)
    }
    @Test fun operationalGateExcludesRecommendations() {
        assertTrue(prepared.copy(microphoneGranted = false).needsAttention)
        assertFalse(prepared.copy(notificationPolicyAccess = false).needsAttention)
        assertFalse(prepared.copy(batteryOptimizationIgnored = false).needsAttention)
        assertFalse(prepared.copy(cameraGranted = false).needsAttention)
    }

    @Test fun preparationDoesNotGateOnOptionalRecommendationsOrAlarmVolume() {
        val optionalPending = prepared.copy(cameraGranted = false, batteryOptimizationIgnored = false,
            notificationPolicyAccess = false, alarmVolume = 0)
        assertEquals(0, optionalPending.preparationAdjustments(online = true, connectedToTeam = true))
    }
    @Test fun preparationCountsEachActionableRequirement() {
        val pending = prepared.copy(notificationsEnabled = false, channelReady = false,
            fullScreenIntentAllowed = false, microphoneGranted = false, doNotDisturb = true, audioWarning = true)
        assertEquals(6, pending.preparationAdjustments(true, true))
        assertEquals(7, pending.preparationAdjustments(false, false))
    }
    @Test fun connectionProblemsAreOneActionEvenWhenBothChecksFail() {
        assertEquals(1, prepared.preparationAdjustments(false, false))
        assertEquals(1, prepared.preparationAdjustments(true, false))
        assertEquals(1, prepared.preparationAdjustments(false, true))
    }
}
