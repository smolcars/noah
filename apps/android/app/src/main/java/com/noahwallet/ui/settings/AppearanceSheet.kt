package com.noahwallet.ui.settings

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.localization.tr
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.Appearance
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.onboarding.rememberHaptics
import java.util.Locale

/**
 * Appearance (appearance.md): Automatic, Light and Dark as three tiny Homes. A pick re-themes Noah at once, uncovered in
 * a circle from the tile, and the sheet stays open to compare. [initial] is the name chip's, as Settings shows yours.
 */
@Composable
internal fun AppearanceSheet(model: AppModel, initial: String, dismiss: () -> Unit) {
    val palette = LocalPalette.current
    val dark = palette === NoahTokens.Dark
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    val reveal = remember { AppearanceReveal() }
    DisposableEffect(reveal) { onDispose { reveal.cancel() } }
    SideEffect { reveal.shown(dark) }
    val frameHeight = tileFrameHeight()
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    val balance = if (model.hidden) BitcoinAmount.format(0, model.amountUnit, locale).replace("0", "••••")
    else BitcoinAmount.format(model.summary?.balance?.totalSats?.toLong() ?: 0L, model.amountUnit, locale)
    SettingsSheet(dismiss, tr("Appearance"), tr("Follow your phone, or keep Noah light or dark."),
        modifier = Modifier.testTag("appearance-sheet")) { _ ->
        val view = LocalView.current
        fun pick(choice: Appearance, origin: Offset) {
            val current = model.appearance
            if (choice == current || reveal.running) return
            haptics.selection()
            val turns = Appearance.turns(current, choice, phoneDark(context, current, dark))
            if (turns == false || reduceMotion) model.chooseAppearance(choice)
            else {
                // Light and Dark give the platform its mode once the circle is done: its uiMode change recomposes
                // everything and would stall the circle. Automatic can't wait; it needs the phone's own mode back.
                val later = choice != Appearance.automatic
                reveal.start(view, origin, dark, change = { model.chooseAppearance(choice, platform = !later) }) {
                    if (later) choice.applyToPlatform(context)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            for (choice in Appearance.entries) {
                AppearanceTile(choice, model.appearance == choice, initial, balance, frameHeight,
                    Modifier.weight(1f).widthIn(max = 160.dp)) { pick(choice, it) }
            }
        }
        AppearanceHint(model.appearance, dark)
    }
}

/** What the choice means now; Automatic's follows the phone live. */
@Composable
private fun AppearanceHint(appearance: Appearance, dark: Boolean) {
    Text(when (appearance) {
        Appearance.automatic -> if (dark) tr("Now dark · follows your phone") else tr("Now light · follows your phone")
        Appearance.light -> tr("Always light")
        Appearance.dark -> tr("Always dark")
    }, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("appearance-hint"), style = HomeType.small, color = LocalPalette.current.muted,
        textAlign = TextAlign.Center)
}

/**
 * The phone's own light or dark while Noah shows [shownDark] under [current]; null when Android can't say yet: from
 * API 31 Noah's own choice hides the phone's from the app, and a scheduled dark theme doesn't say whether it's on.
 */
private fun phoneDark(context: Context, current: Appearance, shownDark: Boolean): Boolean? = when {
    current == Appearance.automatic -> shownDark
    Build.VERSION.SDK_INT < 31 -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    else -> when (context.getSystemService(UiModeManager::class.java).nightMode) {
        UiModeManager.MODE_NIGHT_YES -> true; UiModeManager.MODE_NIGHT_NO -> false; else -> null
    }
}

/**
 * One choice (prototype `.tile`): its tiny Home, its name and a check circle. The selected one gets the Bitcoin unit
 * sheet's 2 dp foreground stroke, 3 dp outside the picture. One radio button for screen readers, which skip the picture.
 */
@Composable
private fun AppearanceTile(choice: Appearance, selected: Boolean, initial: String, balance: String, frameHeight: Dp, modifier: Modifier,
    onPick: (Offset) -> Unit) {
    val palette = LocalPalette.current
    var centre by remember { mutableStateOf(Offset.Zero) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .95f else 1f, NoahTokens.Motion.tap(), label = "appearance-press")
    val name = when (choice) { Appearance.automatic -> tr("Automatic"); Appearance.light -> tr("Light"); Appearance.dark -> tr("Dark") }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.selectable(selected, interaction, null, role = Role.RadioButton) { onPick(centre) }.testTag("appearance-${choice.name}"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(5.dp).heightIn(max = frameHeight).aspectRatio(9f / 17f)
            .onGloballyPositioned { centre = it.boundsInWindow().center }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                if (selected) drawRoundRect(palette.foreground, Offset(-4.dp.toPx(), -4.dp.toPx()), Size(size.width + 8.dp.toPx(), size.height + 8.dp.toPx()),
                    CornerRadius(20.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
            .clip(shape).border(1.dp, palette.hairline, shape).clearAndSetSemantics {}) {
            when (choice) {
                Appearance.light -> MiniHome(NoahTokens.Light, initial, balance)
                Appearance.dark -> MiniHome(NoahTokens.Dark, initial, balance)
                Appearance.automatic -> {
                    MiniHome(NoahTokens.Light, initial, balance)
                    MiniHome(NoahTokens.Dark, initial, balance, Modifier.clip(lowerRight))
                    Canvas(Modifier.fillMaxSize()) {
                        drawLine(Color(0x55888888), Offset(size.width, 0f), Offset(0f, size.height), 1.dp.toPx())
                    }
                }
            }
        }
        Text(name, Modifier.padding(top = 3.dp), style = NoahTokens.Typography.control, color = palette.foreground, textAlign = TextAlign.Center)
        val check = if (selected) Modifier.background(palette.foreground, CircleShape) else Modifier.border(1.5.dp, palette.muted, CircleShape)
        Box(Modifier.padding(top = 8.dp).size(22.dp).then(check), contentAlignment = Alignment.Center) {
            if (selected) Icon(painterResource(SettingsIcons.check), null, Modifier.size(14.dp), tint = palette.background)
        }
    }
}

/**
 * The tallest a tile's picture can be with its name, check and the hint still in the sheet: tabletop gives the sheet
 * only the half below the fold, and a phone on its side is short too. The rest of the sheet takes about 300 dp.
 */
@Composable
private fun tileFrameHeight(): Dp {
    val density = LocalDensity.current
    val fold = com.noahwallet.ui.LocalFoldingFeatures.current.filter { it.width > it.height }.maxOfOrNull { it.bottom }
        ?.takeIf { com.noahwallet.ui.onboarding.LocalPose.current is com.noahwallet.ui.onboarding.OnboardingPose.Tabletop }
    val sheet = with(density) { (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height - (fold ?: 0)).toDp() }
    return (sheet - 300.dp).coerceAtLeast(120.dp)
}

/** The Automatic tile's dark half, below the diagonal from the top-right corner. */
private val lowerRight = GenericShape { size, _ -> moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }

/**
 * A tiny Home (prototype `.mini`) in [palette], a fixed light or dark, never the theme's: the name chip, the balance in
 * Space Grotesk, the fiat line, three Recent rows and the tab bar. Drawn at the prototype's sizes scaled to the tile
 * (76 px wide there), and not by font scale: it's a picture.
 */
@Composable
private fun MiniHome(palette: NoahTokens.Palette, initial: String, balance: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
        val px = maxWidth / 76f
        val density = LocalDensity.current
        fun font(size: Float) = with(density) { (px * size).toSp() }
        fun Modifier.bar(fraction: Float) = fillMaxWidth(fraction).height(px * 3).background(palette.hairline, RoundedCornerShape(px * 2))
        Column(Modifier.fillMaxSize().padding(horizontal = px * 7, vertical = px * 9), verticalArrangement = Arrangement.spacedBy(px * 4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(px * 3)) {
                Box(Modifier.size(px * 10).border(px * 1.5f, palette.orange, CircleShape), contentAlignment = Alignment.Center) {
                    Text(initial.lowercase(), style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.SemiBold,
                        fontSize = font(5f), lineHeight = font(5f)), color = palette.foreground)
                }
                Box(Modifier.bar(.3f))
            }
            BasicText(balance, Modifier.padding(top = px * 8), style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.03).em, lineHeight = 1.em, color = palette.foreground), maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = font(6f), maxFontSize = font(17f), stepSize = font(.5f)))
            Box(Modifier.padding(bottom = px * 6).bar(.4f))
            for (width in listOf(.6f, .45f, .52f)) Row(Modifier.fillMaxWidth().padding(vertical = px * 3), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(px * 4)) {
                Box(Modifier.size(px * 9).background(palette.soft, CircleShape))
                Box(Modifier.bar(width))
            }
            Spacer(Modifier.weight(1f))
            TabPill(palette, px)
        }
    }
}

/** The tiny Home's tab bar: a soft pill with three dots, the first (Home) in the foreground. */
@Composable
private fun TabPill(palette: NoahTokens.Palette, px: Dp) {
    Row(Modifier.fillMaxWidth().height(px * 14).background(palette.soft, RoundedCornerShape(px * 7)).padding(horizontal = px * 6),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        for (index in 0..2) Box(Modifier.size(px * 4).background(if (index == 0) palette.foreground else palette.hairline, CircleShape))
    }
}
