package br.com.alertaequipe

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UpdateOperationTest {
    private fun freshPolicy(latest: Int, min: Int, now: Long = 1_000_000L) = ReleasePolicy(
        latestVersionCode = latest,
        latestVersionName = "0.3.6",
        minSupportedVersionCode = min,
        downloadUrl = "https://example.com/operis.apk",
        releaseNotes = listOf("Nota"),
        sha256 = "a".repeat(64),
        apkSize = 1024,
        cachedAtMs = now
    )

    @Test fun mandatoryWhenBelowMinimum() {
        assertEquals(ReleaseStatus.REQUIRED, Operational.classifyRelease(9, freshPolicy(latest = 11, min = 10), 1_000_000L))
    }

    @Test fun optionalWhenAtOrAboveMinimumButBelowLatest() {
        assertEquals(ReleaseStatus.UPDATE_AVAILABLE, Operational.classifyRelease(10, freshPolicy(latest = 11, min = 10), 1_000_000L))
    }

    @Test fun upToDateWhenCurrentEqualsLatest() {
        assertEquals(ReleaseStatus.UP_TO_DATE, Operational.classifyRelease(11, freshPolicy(latest = 11, min = 10), 1_000_000L))
    }

    @Test fun draftReleaseNeverOffersUpdate() {
        val draft = freshPolicy(latest = 11, min = 10).copy(status = ReleasePolicyStatus.DRAFT)
        assertEquals(ReleaseStatus.UNKNOWN, Operational.classifyRelease(9, draft, 1_000_000L))
    }

    @Test fun announcedReleaseNeverOffersUpdate() {
        val announced = freshPolicy(latest = 11, min = 10).copy(status = ReleasePolicyStatus.ANNOUNCED)
        assertEquals(ReleaseStatus.UNKNOWN, Operational.classifyRelease(9, announced, 1_000_000L))
    }

    @Test fun onlyPublishedWithCompleteApkCanDownload() {
        val published = freshPolicy(latest = 11, min = 10).copy(apkSize = 1024)
        assertTrue(published.canDownload())
        assertFalse(published.copy(status = ReleasePolicyStatus.DRAFT).canDownload())
        assertFalse(published.copy(apkSize = null).canDownload())
        assertFalse(published.copy(sha256 = null).canDownload())
        assertFalse(published.copy(downloadUrl = null).canDownload())
    }

    @Test fun installableRequiresSamePackageAndNewerVersionCode() {
        assertTrue(UpdateFlow.installable("br.com.alertaequipe", 15, "br.com.alertaequipe", 14))
        assertFalse(UpdateFlow.installable("br.com.outro.app", 15, "br.com.alertaequipe", 14))
        assertFalse(UpdateFlow.installable("br.com.alertaequipe", 14, "br.com.alertaequipe", 14))
        assertFalse(UpdateFlow.installable("br.com.alertaequipe", 13, "br.com.alertaequipe", 14))
        assertFalse(UpdateFlow.installable(null, 15, "br.com.alertaequipe", 14))
    }

    @Test fun candidateMustMatchAdvertisedVersion() {
        assertTrue(UpdateFlow.installable("app", 36, "app", 35, 36))
        assertFalse(UpdateFlow.installable("app", 37, "app", 35, 36))
        assertFalse(UpdateFlow.installable("app", 35, "app", 35, 35))
        assertFalse(UpdateFlow.installable("other", 36, "app", 35, 36))
    }

    @Test fun manualCheckAlwaysHasAnExplicitOutcome() {
        assertEquals("Você está na versão mais recente.", UpdateFlow.checkMessage(ReleaseStatus.UP_TO_DATE, "4.3.0"))
        assertEquals("Nova versão disponível: 4.4.0.", UpdateFlow.checkMessage(ReleaseStatus.UPDATE_AVAILABLE, "4.4.0"))
        assertEquals("Nova versão disponível: 4.4.0.", UpdateFlow.checkMessage(ReleaseStatus.REQUIRED, "4.4.0"))
        assertEquals("Não foi possível verificar agora. Tente novamente.", UpdateFlow.checkMessage(ReleaseStatus.UNKNOWN, ""))
    }

    @Test fun apkSizeFormatting() {
        assertEquals("1.0 MB", UpdateFlow.formatApkSize(1_048_576))
        assertEquals("76.2 MB", UpdateFlow.formatApkSize(79_856_000))
        assertEquals("512 B", UpdateFlow.formatApkSize(512))
    }

    @Test fun unknownWithoutPolicyNeverBlocks() {
        assertEquals(ReleaseStatus.UNKNOWN, Operational.classifyRelease(9, null, 1_000_000L))
    }

    @Test fun validCachedMandatoryStillBlocks() {
        val policy = freshPolicy(latest = 11, min = 10, now = 1_000_000L)
        assertTrue(policy.valid(1_000_000L))
        assertEquals(ReleaseStatus.REQUIRED, Operational.classifyRelease(9, policy, 1_000_000L))
    }

    @Test fun expiredCacheIsNotTrustedForBlocking() {
        val policy = freshPolicy(latest = 11, min = 10, now = 1_000_000L)
        assertFalse(policy.valid(1_000_000L + ReleasePolicy.VALID_FOR_MS + 1))
        assertEquals(ReleaseStatus.UNKNOWN, Operational.classifyRelease(9, policy, 1_000_000L + ReleasePolicy.VALID_FOR_MS + 1))
    }

    @Test fun downloadUrlMustBeHttpsWithoutSecrets() {
        val valid = "https://example.com/operis-0.3.6.apk"
        assertTrue(ReleaseInfo.validDownload(valid))
        listOf("", "http://example.com/file", "https://user:password@example.com/file",
            "https://example.com/file?token=secret", "https://example.com/file#frag").forEach {
            assertFalse(ReleaseInfo.validDownload(it))
        }
    }

    @Test fun sha256MismatchDeletesCandidate() {
        val bytesB = "conteudo-b".toByteArray()
        val file = Files.createTempFile("update-test-b", ".apk").toFile()
        try {
            file.writeBytes(bytesB)
            val expected = "0b9c2625dc4ef7287063703cf46efd460f6f07577f169fc106891de4a5e1596d" // sha256("alerta")
            assertFalse(UpdateFlow.verified(expected, file))
        } finally { file.delete() }
    }

    @Test fun sha256MatchEligibleToInstall() {
        val bytes = "amostra-operis".toByteArray()
        val file = Files.createTempFile("update-test-ok", ".apk").toFile()
        try {
            file.writeBytes(bytes)
            val actual = UpdateFlow.sha256(file)
            assertNotNull(actual)
            assertTrue(UpdateFlow.verified(actual, file))
            assertTrue(UpdateFlow.verified(actual!!.uppercase(java.util.Locale.ROOT), file))
            assertFalse(UpdateFlow.verified("z".repeat(64), file))
            assertFalse(UpdateFlow.verified(null, file))
        } finally { file.delete() }
    }

    @Test fun novidadesOncePerVersionCode() {
        assertTrue(Operational.shouldShowRelease(11, null))
        assertTrue(Operational.shouldShowRelease(11, 10))
        assertFalse(Operational.shouldShowRelease(11, 11))
    }

    @Test fun policyParseSanitizesAndClamps() {
        val map = mapOf<Any, Any>(
            "latestVersionCode" to 11,
            "latestVersionName" to "0.3.6",
            "minSupportedVersionCode" to 99,
            "downloadUrl" to "https://example.com/a",
            "releaseNotes" to listOf(" nota ", " ", "segunda"),
            "sha256" to "ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            "status" to "DRAFT",
            "apkSize" to 2_000_000
        )
        val parsed = ReleasePolicy.parse(map, 1234L)
        assertNotNull(parsed)
        assertEquals(1234L, parsed!!.cachedAtMs)
        assertEquals(11, parsed.minSupportedVersionCode)
        assertEquals(listOf("nota", "segunda"), parsed.releaseNotes)
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", parsed.sha256)
        assertEquals(ReleasePolicyStatus.DRAFT, parsed.status)
        assertEquals(2_000_000L, parsed.apkSize)
    }

    @Test fun policyParseDefaultsToPublishedWhenStatusAbsent() {
        val base = mapOf<Any, Any>(
            "latestVersionCode" to 11,
            "latestVersionName" to "0.3.6",
            "minSupportedVersionCode" to 10,
            "downloadUrl" to "https://example.com/a",
            "releaseNotes" to listOf("nota"),
            "sha256" to "a".repeat(64)
        )
        assertEquals(ReleasePolicyStatus.PUBLISHED, ReleasePolicy.parse(base, 0L)!!.status)
        assertNull(ReleasePolicy.parse(mapOf<Any, Any>("latestVersionCode" to 0, "latestVersionName" to "x",
            "releaseNotes" to emptyList<Any>(), "downloadUrl" to "http://insecure/file", "sha256" to "zz"), 0L))
    }
}
