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
    @Test fun missingOperationalPermissionsNeedAttention() {
        assertTrue(prepared.copy(microphoneGranted = false).needsAttention)
        assertTrue(prepared.copy(notificationPolicyAccess = false).needsAttention)
        assertTrue(prepared.copy(batteryOptimizationIgnored = false).needsAttention)
    }
}
