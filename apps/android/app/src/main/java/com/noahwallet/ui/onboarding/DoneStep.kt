package com.noahwallet.ui.onboarding

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.noahwallet.localization.tr
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.design.NoahTokens
import kotlin.math.pow

/** The orange ring draws, then the check, two ripples and the success haptic; "Open wallet" finishes onboarding. */
@Composable
internal fun DoneStep(model: AppModel, presentation: OnboardingPresentation) {
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    // Timed from the first arrival, so folding or recreation neither replays nor jumps it.
    val start = remember { presentation.doneAt.takeIf { it != 0L } ?: SystemClock.elapsedRealtime().also { presentation.doneAt = it } }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime() - start) }
    LaunchedEffect(start) {
        while (elapsed < 2800) withFrameMillis { elapsed = SystemClock.elapsedRealtime() - start }
    }
    LaunchedEffect(elapsed >= (if (reduceMotion) 200 else 1000)) {
        if (elapsed >= (if (reduceMotion) 200 else 1000) && presentation.felt.add("done")) haptics.success()
    }
    val t = if (reduceMotion) 10_000L else elapsed
    val ring: @Composable () -> Unit = { DoneRing(t) }
    val words: @Composable ColumnScope.() -> Unit = {
        Headline(tr("You’re all set"), presentation.savedAddress ?: tr("Your wallet is ready."),
            centered = LocalPose.current == OnboardingPose.Compact, modifier = Modifier.fillMaxWidth())
    }
    val button: @Composable () -> Unit = {
        PillButton(tr("Open wallet"), Modifier.fillMaxWidth().testTag("onboarding-open-wallet"), loading = model.busy) {
            haptics.light()
            model.action("Couldn't finish setup. Please try again.") { model.finish() }
        }
    }
    when (val pose = LocalPose.current) {
        OnboardingPose.Compact -> Column(Modifier.fillMaxSize().padding(horizontal = NoahTokens.Space.screen),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(.8f))
            ring()
            Spacer(Modifier.height(34.dp))
            words()
            Spacer(Modifier.weight(1f))
            button()
            Spacer(Modifier.height(NoahTokens.Space.s))
        }
        else -> SplitPanes(pose, picture = { ring() }) {
            Column(Modifier.fillMaxSize().padding(horizontal = NoahTokens.Space.screen),
                verticalArrangement = if (pose is OnboardingPose.Wide) Arrangement.Center else Arrangement.Top) {
                if (pose is OnboardingPose.Tabletop) Spacer(Modifier.height(NoahTokens.Space.l))
                words()
                Spacer(Modifier.height(NoahTokens.Space.xxl))
                button()
            }
        }
    }
}

private fun ease(x: Float) = 1 - (1 - x.coerceIn(0f, 1f)).pow(3)

/** A critically damped settle, close to the soft and snappy springs over the prototype's durations. */
private fun settle(t: Long, delay: Long, millis: Long) = ease((t - delay) / millis.toFloat())

@Composable
private fun DoneRing(t: Long) {
    val palette = LocalPalette.current
    val ring = settle(t, 120, 950)
    val check = settle(t, 840, 320)
    val bounce = if (t in 1000..1400) 1 + .08f * kotlin.math.sin(((t - 1000) / 400f) * Math.PI.toFloat()) else 1f
    Canvas(Modifier.size(96.dp).clearAndSetSemantics { }) {
        val unit = size.width / 96f
        for (i in 0 until 2) {
            val q = ((t - 1000 - i * 260) / 1400f).coerceIn(0f, 1f)
            if (q > 0f && q < 1f) {
                val grow = 1 + 1.3f * (1 - (1 - q).pow(2))
                drawCircle(palette.orange.copy(alpha = .7f * (1 - q)), 48f * unit * grow, center, style = Stroke(1.5f * unit))
            }
        }
        scale(bounce) {
            drawArc(palette.orange, -90f, 360f * ring, false, Offset(2f * unit, 2f * unit), Size(92f * unit, 92f * unit),
                style = Stroke(2.5f * unit, cap = StrokeCap.Round))
            if (check > 0f) drawPath(trimmed(Path().apply { moveTo(31f * unit, 49f * unit); lineTo(42f * unit, 60f * unit); lineTo(65f * unit, 36f * unit) }, check),
                palette.foreground, style = Stroke(3.2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
