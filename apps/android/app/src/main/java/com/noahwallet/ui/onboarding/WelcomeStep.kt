package com.noahwallet.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahwallet.localization.tr
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.settings.preferenceName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeStep(model: AppModel, presentation: OnboardingPresentation) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val play = remember { presentation.firstShow("welcome") }
    SceneLayout(presentation.scene, SceneMode.welcome, topBar = {
        LanguageButton(model.localeIdentifier) { presentation.languageOpen = true }
    }, below = {
        Wordmark(play)
        Text(tr("Bitcoin, simply."), Modifier.padding(top = NoahTokens.Space.s).rise(play, 1500, 10f),
            style = NoahTokens.Typography.body, color = palette.muted)
    }, bottom = {
        Spacer(Modifier.height(NoahTokens.Space.xxl - NoahTokens.Space.xs))
        PillButton(tr("Create wallet"), Modifier.fillMaxWidth().entrance(6, play).testTag("onboarding-create")) {
            haptics.light(); presentation.betaError = null; presentation.betaOpen = true
        }
        TextAction(tr("I already have a wallet"),
            Modifier.fillMaxWidth().heightIn(min = 52.dp).entrance(7, play).testTag("onboarding-restore")) { model.step = "restore" }
    })
    if (presentation.languageOpen) LanguageSheet(model, presentation)
    if (presentation.betaOpen) BetaSheet(model, presentation)
}

/**
 * Welcome's top bar, words and buttons, invisible and inert: Creating and Restoring reserve exactly this
 * space so the tree stays where Welcome left it, at any font size and after recreation in another pose.
 */
internal object WelcomeGhost {
    @Composable fun Top(locale: String) = Box(Modifier.alpha(0f).clearAndSetSemantics { }) { LanguageButton(locale, enabled = false) {} }
    @Composable fun Words() = Column(Modifier.alpha(0f).clearAndSetSemantics { }) {
        Wordmark(play = false)
        Text(tr("Bitcoin, simply."), Modifier.padding(top = NoahTokens.Space.s), style = NoahTokens.Typography.body)
    }
    @Composable fun Buttons() = Column(Modifier.alpha(0f).clearAndSetSemantics { }) {
        Spacer(Modifier.height(NoahTokens.Space.xxl - NoahTokens.Space.xs))
        PillButton(tr("Create wallet"), Modifier.fillMaxWidth(), enabled = false) {}
        TextAction(tr("I already have a wallet"), Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = false) {}
    }
}

/** The "Noah" letters rise with the bouncy spring after the tree has grown. */
@Composable
private fun Wordmark(play: Boolean) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    // A logo, not reading text: grow with font scale, but only so far.
    val size = (80 * minOf(density.fontScale, 1.3f) / density.fontScale).sp
    Row(Modifier.offset(x = (-3).dp).clearAndSetSemantics { contentDescription = "Noah"; heading() }) {
        "Noah".forEachIndexed { index, letter ->
            val progress = remember { Animatable(if (play) 0f else 1f) }
            LaunchedEffect(Unit) {
                if (progress.value >= 1f) return@LaunchedEffect
                delay(1200L + index * 65L)
                if (reduceMotion) progress.animateTo(1f, tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) else progress.animateTo(1f, NoahTokens.Motion.bouncy())
            }
            Text(letter.toString(), Modifier.graphicsLayer {
                alpha = progress.value.coerceIn(0f, 1f)
                if (!reduceMotion) translationY = (1 - progress.value) * 52 * density.density
            }, style = NoahTokens.Typography.wordmark.copy(fontSize = size, lineHeight = size), color = palette.foreground)
        }
    }
}

/** Fade and rise once, after [delayMillis] (snappy). */
@Composable
internal fun Modifier.rise(play: Boolean, delayMillis: Long, distance: Float): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value >= 1f) return@LaunchedEffect
        delay(delayMillis)
        if (reduceMotion) progress.animateTo(1f, tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) else progress.animateTo(1f, NoahTokens.Motion.snappy())
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        if (!reduceMotion) translationY = (1 - progress.value) * distance * density
    }
}

@Composable
private fun LanguageButton(locale: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val label = tr("Language")
    TextButton(onClick, Modifier.heightIn(min = NoahTokens.Size.hitTarget).semantics { contentDescription = label }, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = palette.muted)) {
        GlobeIcon(palette.muted)
        Spacer(Modifier.width(6.dp))
        Text(locale.uppercase(Locale.ROOT), Modifier.clearAndSetSemantics { },
            style = NoahTokens.Typography.button.copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(model: AppModel, presentation: OnboardingPresentation) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    OnboardingSheet({ presentation.languageOpen = false }, sheet) {
        Text(tr("Language"), Modifier.padding(bottom = NoahTokens.Space.s).semantics { heading() },
            style = NoahTokens.Typography.sheetTitle, color = palette.foreground)
        val locale = Locale.forLanguageTag(model.localeIdentifier)
        val codes = listOf("en", "es", "ja", "ko", "sv")
        codes.forEachIndexed { index, code ->
            val selected = code == model.localeIdentifier
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(role = Role.RadioButton) {
                    haptics.selection()
                    scope.launch {
                        sheet.hide(); presentation.languageOpen = false
                        model.action("Couldn't save your preference.") { model.setLanguage(code) }
                    }
                }.semantics { this.selected = selected },
                verticalAlignment = Alignment.CenterVertically) {
                Text(preferenceName(com.noahwallet.ui.settings.Preference.language, code, locale), Modifier.weight(1f),
                    style = NoahTokens.Typography.body, color = palette.foreground)
                if (selected) CheckIcon(palette.orange)
            }
            if (index < codes.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hairline))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BetaSheet(model: AppModel, presentation: OnboardingPresentation) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    // Never dismiss while the wallet is being created.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !model.busy })
    LaunchedEffect(model.step) {
        if (model.step != "welcome") { sheet.hide(); presentation.betaOpen = false }
    }
    OnboardingSheet({ if (!model.busy) presentation.betaOpen = false }, sheet) {
        Text(tr("Beta"), Modifier.padding(bottom = 14.dp).background(palette.orangeTint, CircleShape)
            .padding(horizontal = 11.dp, vertical = 4.dp), style = NoahTokens.Typography.caption.copy(fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = palette.orangeText)
        Text(tr("Before you start"), Modifier.semantics { heading() }, style = NoahTokens.Typography.sheetTitle, color = palette.foreground)
        Text(tr("Noah is in beta. Only keep what you’re comfortable losing."), Modifier.padding(top = NoahTokens.Space.s, bottom = NoahTokens.Space.screen),
            style = NoahTokens.Typography.body, color = palette.muted)
        presentation.betaError?.let {
            Text(tr(it), Modifier.padding(bottom = NoahTokens.Space.l).semantics { liveRegion = LiveRegionMode.Assertive },
                style = NoahTokens.Typography.sub, color = palette.failure)
        }
        PillButton(tr("Got it"), Modifier.fillMaxWidth().testTag("onboarding-beta-confirm"), loading = model.busy) {
            haptics.medium()
            model.action("Creation failed. Please try again.", inline = { presentation.betaError = it }) { model.create() }
        }
    }
}
