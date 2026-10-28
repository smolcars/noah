package com.noahwallet.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.localization.tr
import com.noahwallet.services.backup.WalletRestorer
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.design.NoahTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale

/** Creating: phases follow the scene clock, so folding or recreation neither restarts nor repeats them. */
@Composable
internal fun CreatingStep(model: AppModel, presentation: OnboardingPresentation) {
    val clock = presentation.scene
    val haptics = rememberHaptics()
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { clock.mode }.first { it == SceneMode.create }
        val marks = listOf(1.35f, 2.55f, 3.6f)
        while (true) {
            val elapsed = clock.time(System.nanoTime())
            phase = marks.count { elapsed >= it }.coerceAtMost(2)
            if (phase == 2 && presentation.felt.add("created")) haptics.success()
            if (elapsed >= marks[2]) break
            delay(((marks.first { it > elapsed } - elapsed) * 1000).toLong().coerceAtLeast(16))
        }
        model.step = "name"
    }
    SceneLayout(clock, SceneMode.create, welcomeGhost = model.localeIdentifier, below = {
        PhaseText(listOf(tr("Creating your keys"), tr("Joining the Ark"), tr("Ready"))[phase])
    }, bottom = {})
}

/**
 * Restoring: the phase comes from the real restore steps and may hold on "Finding your bitcoin".
 * After success the balance counts up, then Home appears; errors keep today's RestoreIssue copy, shown
 * here for a typed phrase and back on "Recovery phrase found" for a stored one, as on iOS.
 */
@Composable
internal fun RestoringStep(model: AppModel, presentation: OnboardingPresentation) {
    val clock = presentation.scene
    val flow = model.restore
    val issue = flow.issue
    val storedSeed = model.launchState == LaunchState.seedWithoutWalletData
    // Back from a failed phrase restore (a stored phrase goes back on its own).
    val canGoBack = issue != null && !storedSeed
    BackHandler { if (canGoBack && flow.canLeave && !model.busy) { flow.clearIssue(); model.step = "restore" } }
    RestoreProgressEffects(model, presentation, storedSeed)
    SceneLayout(clock, SceneMode.restore, welcomeGhost = model.localeIdentifier, below = {
        RestoreProgress(model, presentation, issue?.takeUnless { storedSeed })
    }, bottom = {
        if (issue != null && !issue.requiresRestart && !storedSeed) PillButton(tr("Back"), Modifier.fillMaxWidth(), enabled = flow.canLeave && !model.busy) {
            flow.clearIssue(); model.step = "restore"
        }
    })
}

/** Phase text crossfades upward (snappy); the first phase only fades in. */
@Composable
private fun PhaseText(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(text, modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, transitionSpec = {
        if (reduceMotion) fadeIn(tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) togetherWith fadeOut(tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS))
        else (slideInVertically(NoahTokens.Motion.snappy()) { it / 2 } + fadeIn(NoahTokens.Motion.snappy())) togetherWith
            (slideOutVertically(NoahTokens.Motion.snappy()) { -it / 2 } + fadeOut(NoahTokens.Motion.snappy()))
    }, contentAlignment = Alignment.Center, label = "phase") { phase ->
        Text(phase, Modifier.fillMaxWidth(), style = NoahTokens.Typography.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = palette.foreground, textAlign = TextAlign.Center)
    }
}

/** The restored balance counts up in the display face; orange never sits on an amount. */
@Composable
private fun CountUp(total: Long, model: AppModel, presentation: OnboardingPresentation) {
    val palette = LocalPalette.current
    val elapsed = ((System.nanoTime() - presentation.restoreReadyAt) / 1_000_000).toFloat()
    val progress = remember { Animatable((elapsed / 900f).coerceIn(0f, 1f)) }
    LaunchedEffect(total) { progress.animateTo(1f,
        tween((900 * (1 - progress.value)).toInt().coerceAtLeast(0), easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val shown = countUpSats(total, progress.value)
    Text(BitcoinAmount.format(shown, model.amountUnit, Locale.forLanguageTag(model.localeIdentifier)),
        Modifier.fillMaxWidth().padding(top = NoahTokens.Space.l).graphicsLayer { alpha = (progress.value * 3).coerceAtMost(1f) },
        style = NoahTokens.Typography.amount.copy(fontFeatureSettings = "tnum"), color = palette.foreground, textAlign = TextAlign.Center)
}

/**
 * The restore's haptics and holds: light taps as each of your leaves lights up, success and a short hold on "Ready"
 * before Home, and on failure an error, the scene frozen grey and (a stored phrase) the way back to its screen.
 */
@Composable
private fun RestoreProgressEffects(model: AppModel, presentation: OnboardingPresentation, storedSeed: Boolean) {
    val clock = presentation.scene
    val flow = model.restore
    val haptics = rememberHaptics()
    val issue = flow.issue
    LaunchedEffect(Unit) {
        // The prototype's light taps as each of your leaves lights up.
        snapshotFlow { clock.mode }.first { it == SceneMode.restore }
        for (leaf in foundLeafTimes) {
            val wait = leaf - clock.time(System.nanoTime())
            if (wait < 0f) continue
            delay((wait * 1000).toLong())
            haptics.light()
        }
    }
    LaunchedEffect(flow.completed) {
        if (!flow.completed) return@LaunchedEffect
        if (presentation.restoreReadyAt == 0L) presentation.restoreReadyAt = System.nanoTime()
        if (clock.readyAt == null) clock.readyAt = clock.time(presentation.restoreReadyAt)
        if (presentation.felt.add("restored")) haptics.success()
        // Hold "Ready" and the count before Home, measured from the moment restore finished.
        val shown = (System.nanoTime() - presentation.restoreReadyAt) / 1_000_000
        delay((1400 - shown).coerceAtLeast(0))
        model.holdRestoreCompletion = false
    }
    LaunchedEffect(issue) {
        // A failed restore found nothing: the scene greys out and stops claiming leaves.
        clock.frozenAt = if (issue != null) clock.frozenAt ?: clock.time(System.nanoTime()) else null
        if (issue != null) { model.holdRestoreCompletion = false; haptics.error(); if (storedSeed) model.step = "storedSeed" }
    }
}

/** Under the tree: the phase, the restored balance counting up once ready, and a phrase restore's [issue]. */
@Composable
private fun RestoreProgress(model: AppModel, presentation: OnboardingPresentation, issue: com.noahwallet.ui.RestoreIssue?) {
    val flow = model.restore
    val finding = flow.step != null && flow.step != WalletRestorer.Step.fetching
    val phase = when {
        flow.completed -> 2
        finding -> 1
        else -> 0
    }
    PhaseText(listOf(tr("Restoring your wallet"), tr("Finding your bitcoin"), tr("Ready"))[phase], Modifier.testTag("restore-progress"))
    val total = model.summary?.balance?.totalSats?.toLong()
    if (flow.completed && total != null && !model.hidden) CountUp(total, model, presentation)
    issue?.let {
        Text(tr(it.message), Modifier.fillMaxWidth().padding(top = NoahTokens.Space.m).semantics { liveRegion = LiveRegionMode.Assertive },
            style = NoahTokens.Typography.sub, color = LocalPalette.current.failure, textAlign = TextAlign.Center)
    }
}
