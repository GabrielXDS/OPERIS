package br.com.alertaequipe

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Pure splash timeline, isolated so the phases are unit-testable (splash tests A-F). */
internal data class SplashFrame(
    val structureAlpha: Float,
    val structureScale: Float,
    val coreAlpha: Float,
    val coreScale: Float,
    val wordmarkAlpha: Float,
    val wordmarkScale: Float
)

internal object SplashController {
    const val PHASE_STRUCTURE_END = 0.15f
    const val PHASE_CORE_END = 0.40f
    const val PHASE_WORDMARK_START = 0.70f
    const val TOTAL_MS = 1000L
    const val MAX_MS = 1100L

    /** Splash plays only on a cold start, and never when updates/splash are skipped. */
    fun shouldShow(coldStart: Boolean, skipSplash: Boolean = false): Boolean = coldStart && !skipSplash

    /** Normalized progress for a frame; prevents drift and caps near 1.0s. */
    fun t(nowMs: Long, startMs: Long): Float = ((nowMs - startMs) / TOTAL_MS.toFloat()).coerceIn(0f, 1f)

    /** Static final frame, also what reduced-motion users see (no pulses). */
    fun static(): SplashFrame = SplashFrame(1f, 1f, 0.85f, 1f, 1f, 1f)

    fun phase(t: Float): SplashFrame {
        val tc = t.coerceIn(0f, 1f)
        val structureAlpha = when {
            tc < PHASE_STRUCTURE_END -> tc / PHASE_STRUCTURE_END
            tc < PHASE_CORE_END -> 0.6f
            else -> 0.6f + 0.4f * ((tc - PHASE_CORE_END) / (PHASE_WORDMARK_START - PHASE_CORE_END)).coerceIn(0f, 1f)
        }
        val structureScale = 0.9f + 0.1f * (tc / PHASE_STRUCTURE_END).coerceIn(0f, 1f)
        val coreAlpha = when {
            tc < PHASE_STRUCTURE_END -> 0f
            tc < 0.25f -> (tc - PHASE_STRUCTURE_END) / 0.10f
            tc < PHASE_CORE_END -> 1f - 0.15f * ((tc - 0.25f) / 0.15f)
            else -> 0.85f
        }
        val coreScale = when {
            tc < PHASE_STRUCTURE_END -> 1f
            tc < 0.275f -> 1f + 0.04f * ((tc - PHASE_STRUCTURE_END) / 0.125f)
            tc < PHASE_CORE_END -> 1f + 0.04f * ((PHASE_CORE_END - tc) / 0.125f)
            else -> 1f
        }
        val wordmarkAlpha = when {
            tc < PHASE_WORDMARK_START -> 0f
            else -> ((tc - PHASE_WORDMARK_START) / (1f - PHASE_WORDMARK_START)).coerceIn(0f, 1f)
        }
        val wordmarkScale = 1.02f - 0.02f * wordmarkAlpha
        return SplashFrame(structureAlpha, structureScale, coreAlpha, coreScale, wordmarkAlpha, wordmarkScale)
    }

    fun endFrame(t: Float): Int = when {
        t < PHASE_STRUCTURE_END -> 0
        t < PHASE_CORE_END -> 1
        t < PHASE_WORDMARK_START -> 2
        else -> 3
    }
}

@Composable
internal fun OperisSplash(onFinished: () -> Unit) {
    val reduceMotion = remember { !android.animation.ValueAnimator.areAnimatorsEnabled() }
    var t by remember { mutableFloatStateOf(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) {
            t = 1f
            delay(SplashController.TOTAL_MS)
            onFinished()
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            t = SplashController.t(now / 1_000_000L, start / 1_000_000L)
            if (t >= 1f) {
                onFinished()
                break
            }
        }
    }
    val frame = if (reduceMotion) SplashController.static() else SplashController.phase(t)
    Box(Modifier.fillMaxSize().background(AppInk), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(196.dp)) {
                Image(
                    painterResource(R.drawable.operis_symbol_structure),
                    contentDescription = null,
                    modifier = Modifier.size(196.dp)
                        .alpha(frame.structureAlpha)
                        .scale(frame.structureScale)
                )
                Image(
                    painterResource(R.drawable.operis_symbol_core),
                    contentDescription = null,
                    modifier = Modifier.size(196.dp)
                        .alpha(frame.coreAlpha)
                        .scale(frame.coreScale)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "OPERIS",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(frame.wordmarkAlpha).scale(frame.wordmarkScale)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                BRAND_TAGLINE,
                color = AppMuted,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(frame.wordmarkAlpha).scale(frame.wordmarkScale)
            )
        }
    }
}