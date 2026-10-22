package com.noahwallet.ui.home

import androidx.compose.runtime.*
import kotlin.math.*

/**
 * The prototype's springs as functions of time (SwiftUI response/dampingFraction step response), so
 * time-driven sequences such as the first payment resume at the right frame after a pose change.
 */
internal class SpringCurve(response: Float, private val damping: Float) {
    private val w0 = 2 * PI.toFloat() / response
    fun value(t: Float): Float {
        if (t <= 0f) return 0f
        val z = damping
        return if (z < 1f) {
            val wd = w0 * sqrt(1 - z * z)
            1 - exp(-z * w0 * t) * (cos(wd * t) + (z / sqrt(1 - z * z)) * sin(wd * t))
        } else 1 - exp(-w0 * t) * (1 + w0 * t)
    }
    /** Seconds until the curve stays within 0.2 % of 1 for a quarter second (prototype `spring()`). */
    val settle: Float = run {
        var t = .05f
        while (t < 3f) {
            var ok = true
            var s = t
            while (s < t + .25f) { if (abs(1 - value(s)) > .002f) { ok = false; break }; s += .01f }
            if (ok) break
            t += .01f
        }
        t
    }
    /** The curve's shape stretched over an explicit duration, as the prototype does with `dur`. */
    fun over(fraction: Float) = if (fraction >= 1f) 1f else value(fraction.coerceAtLeast(0f) * settle)

    companion object {
        val snappy = SpringCurve(.30f, .85f)
        val soft = SpringCurve(.50f, 1f)
        val bouncy = SpringCurve(.40f, .60f)
    }
}

/** Progress of one step of a timed sequence: 0 before [delay], the spring (or its stretched shape) after. */
internal fun step(elapsed: Long, delay: Long, curve: SpringCurve, duration: Long? = null, reduceMotion: Boolean = false): Float {
    if (reduceMotion) return ((elapsed - delay * .3f) / 180f).coerceIn(0f, 1f)
    val t = elapsed - delay
    if (t <= 0) return 0f
    return if (duration != null) curve.over(t / duration.toFloat()) else if (t / 1000f >= curve.settle) 1f else curve.value(t / 1000f)
}

/** Milliseconds since [start] (an elapsedRealtime), ticking every frame until [until]. */
@Composable
internal fun rememberElapsed(start: Long?, until: Long): Long {
    var elapsed by remember(start) { mutableLongStateOf(if (start == null) until else (android.os.SystemClock.elapsedRealtime() - start).coerceAtLeast(0)) }
    LaunchedEffect(start) {
        if (start == null) return@LaunchedEffect
        while (elapsed < until) {
            withFrameMillis { }
            elapsed = (android.os.SystemClock.elapsedRealtime() - start).coerceAtLeast(0)
        }
    }
    return elapsed
}

internal fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction
