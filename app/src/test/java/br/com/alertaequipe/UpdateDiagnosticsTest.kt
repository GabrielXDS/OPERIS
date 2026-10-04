package br.com.alertaequipe

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException

class UpdateDiagnosticsTest {
    @Test fun fieldVersion432IsNewerThanPublished431AndCannotDowngrade() {
        val policy = ReleasePolicy(36, "4.3.1", 9, downloadUrl = "https://example.com/operis.apk", releaseNotes = listOf("release"), sha256 = "a".repeat(64), apkSize = 72069939, cachedAtMs = 100)
        val status = Operational.classifyRelease(37, policy, 100)
        assertEquals(ReleaseStatus.NEWER_THAN_PUBLISHED, status)
        assertEquals("Você está em uma versão mais recente que a versão atualmente publicada.", UpdateFlow.checkMessage(status, "4.3.1"))
        assertFalse(UpdateFlow.installable("br.com.alertaequipe", 36, "br.com.alertaequipe", 37))
    }
    @Test fun dnsIsNotReportedAsGenericOffline() {
        assertEquals("DNS", UpdateDiagnostics.describe(Exception("private", UnknownHostException("secret"))).code)
    }
    @Test fun timeoutIsDistinct() {
        assertEquals("TIMEOUT", UpdateDiagnostics.describe(SocketTimeoutException("secret")).code)
    }
    @Test fun httpFailuresAreDistinct() {
        assertEquals("HTTP_403", UpdateDiagnostics.http(403).code)
        assertEquals("HTTP_404", UpdateDiagnostics.http(404).code)
        assertEquals("HTTP_5XX", UpdateDiagnostics.http(503).code)
        assertEquals("STORAGE", UpdateDiagnostics.download(1006).code)
    }
    @Test fun unexpectedErrorsDoNotExposeSensitiveText() {
        assertFalse(UpdateDiagnostics.describe(Exception("secret-token-uid")).message.contains("secret"))
    }
    @Test fun signatureMustMatchInstalledApp() {
        assertTrue(UpdateFlow.signaturesMatch(setOf("original"), setOf("original")))
        assertFalse(UpdateFlow.signaturesMatch(setOf("new"), setOf("original")))
        assertFalse(UpdateFlow.signaturesMatch(emptySet(), emptySet()))
    }
    @Test fun obsoleteDraftCannotClaimLatest() {
        val policy = ReleasePolicy(15, "4.0", 9, ReleasePolicyStatus.DRAFT, null, listOf("draft"), null, cachedAtMs = 100)
        assertEquals(ReleaseStatus.UNKNOWN, Operational.classifyRelease(37, policy, 100))
    }
    @Test fun incompleteNewReleaseCannotOfferImpossibleDownload() {
        val policy = ReleasePolicy(39, "4.3.3", 37, downloadUrl = null, releaseNotes = listOf("new"), sha256 = null, cachedAtMs = 100)
        assertEquals(ReleaseStatus.UNKNOWN, Operational.classifyRelease(37, policy, 100))
    }
}
