package br.com.alertaequipe

import org.junit.Assert.*
import org.junit.Test

class SplashControllerTest {
    @Test fun coldStartShowsSplash() {
        assertTrue(SplashController.shouldShow(coldStart = true))
    }

    @Test fun backgroundResumeAndRestoreNeverShowSplash() {
        // Returning from background or restoring the activity is not a cold start.
        assertFalse(SplashController.shouldShow(coldStart = false))
    }

    @Test fun reducedMotionUsesStaticFrameWithoutPulses() {
        val frame = SplashController.static()
        assertEquals(1f, frame.structureAlpha)
        assertEquals(1f, frame.structureScale)
        assertEquals(0.85f, frame.coreAlpha)
        assertEquals(1f, frame.coreScale)
        assertEquals(1f, frame.wordmarkAlpha)
        assertEquals(1f, frame.wordmarkScale)
    }

    @Test fun progressIsClampedAndTotalStaysNearOneSecond() {
        assertTrue(SplashController.TOTAL_MS in 950L..1100L)
        assertTrue(SplashController.MAX_MS >= SplashController.TOTAL_MS)
        assertEquals(0f, SplashController.t(1000L, 2000L))
        assertEquals(0.5f, SplashController.t(1500L, 1000L))
        assertEquals(1f, SplashController.t(5000L, 1000L))
        assertEquals(1f, SplashController.t(2000L, 1000L))
    }

    @Test fun skipSplashFutureFlagSkipsColdStart() {
        assertFalse(SplashController.shouldShow(coldStart = true, skipSplash = true))
    }

    @Test fun phaseTimelineIsOrderedAndBounded() {
        val end = SplashController.endFrame(1f)
        assertEquals(3, end)
        assertTrue(SplashController.endFrame(SplashController.PHASE_STRUCTURE_END / 2f) < 3)
        // Wordmark fades only after the structure phase; all frames stay in alpha range.
        val early = SplashController.phase(0.1f)
        assertEquals(0f, early.wordmarkAlpha)
        val late = SplashController.phase(0.9f)
        assertTrue(late.wordmarkAlpha > 0f)
        for (t in listOf(0f, 0.05f, 0.15f, 0.25f, 0.4f, 0.7f, 0.85f, 1f)) {
            val f = SplashController.phase(t)
            assertTrue("alpha out of range", f.structureAlpha in 0f..1f)
            assertTrue("core alpha out of range", f.coreAlpha in 0f..1f)
            assertTrue("wordmark alpha out of range", f.wordmarkAlpha in 0f..1f)
            assertTrue("scale out of range", f.structureScale in 0f..1.2f)
            assertTrue("scale out of range", f.coreScale in 0f..1.2f)
            assertTrue("scale out of range", f.wordmarkScale in 0f..1.2f)
        }
    }
}