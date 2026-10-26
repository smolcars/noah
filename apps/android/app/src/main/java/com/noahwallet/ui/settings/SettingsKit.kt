package com.noahwallet.ui.settings

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.noahwallet.R
import com.noahwallet.localization.tr
import com.noahwallet.ui.activity.ActivityType
import com.noahwallet.ui.activity.BarButton
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeIcons
import com.noahwallet.ui.home.HomeToast
import com.noahwallet.ui.home.HomeToasts
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.home.tonal
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.onboarding.OnboardingSheet
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.send.SendPill
import kotlinx.coroutines.launch

/** Settings' icons (from the settings-v1 prototype), with Home's and Activity's where they're the same. */
internal object SettingsIcons {
    val cloud = R.drawable.ic_set_cloud; val key = R.drawable.ic_set_key; val finger = R.drawable.ic_set_finger
    val coins = R.drawable.ic_set_coins; val chain = HomeIcons.chain; val repeat = HomeIcons.repeat; val exit = R.drawable.ic_set_exit
    val cash = R.drawable.ic_set_cash; val globe = R.drawable.ic_set_globe; val eyeOff = R.drawable.ic_set_eyeoff
    val bell = R.drawable.ic_set_bell; val bellOff = R.drawable.ic_set_belloff; val sliders = R.drawable.ic_set_sliders
    val chat = R.drawable.ic_set_chat; val ark = R.drawable.ic_sr_ark; val trash = R.drawable.ic_set_trash; val lock = R.drawable.ic_set_lock
    val server = R.drawable.ic_set_server; val doc = R.drawable.ic_activity_doc; val bug = R.drawable.ic_set_bug
    val refresh = R.drawable.ic_activity_refresh; val plus = R.drawable.ic_set_plus; val qr = R.drawable.ic_set_qr
    val play = R.drawable.ic_set_play; val pause = R.drawable.ic_set_pause; val stop = R.drawable.ic_set_stop
    val send = R.drawable.ic_set_send; val github = R.drawable.ic_set_github; val telegram = R.drawable.ic_set_telegram
    val battery = R.drawable.ic_set_battery
    val push = R.drawable.ic_set_push; val clock = R.drawable.ic_set_clock; val fee = R.drawable.ic_set_fee
    val noUndo = R.drawable.ic_set_noundo; val shield = R.drawable.ic_set_shield; val eye = R.drawable.ic_set_eye
    val calendar = R.drawable.ic_set_cal; val image = R.drawable.ic_set_image; val bolt = R.drawable.ic_set_bolt
    val copy = R.drawable.ic_sr_copy; val check = R.drawable.ic_activity_check; val external = R.drawable.ic_activity_external
    val gear = HomeIcons.settings; val share = R.drawable.ic_activity_share; val more = R.drawable.ic_activity_more
    val lightning = HomeIcons.lightning; val person = HomeIcons.person; val map = R.drawable.ic_set_map
}

/** Settings' type (prototype sizes, Android): Roboto words, Space Grotesk amounts and the wordmark. */
internal object SettingsType {
    private val box = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        androidx.compose.ui.text.style.LineHeightStyle.Trim.None)
    val row = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val value = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, lineHeightStyle = box)
    val number = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp,
        fontFeatureSettings = "tnum", lineHeightStyle = box)
    val name = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, lineHeightStyle = box)
    val hero = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em, lineHeightStyle = box)
    val status = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, lineHeightStyle = box)
    val total = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 42.sp,
        letterSpacing = (-0.025).em, fontFeatureSettings = "tnum", lineHeightStyle = box)
    val word = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val index = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, lineHeightStyle = box)
    val tick = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp,
        fontFeatureSettings = "tnum", lineHeightStyle = box)
    val current = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, lineHeightStyle = box)
    val log = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp)
    val result = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)
}

/** Where a page sits: one column, the list beside a page (leading) or the page beside the list (trailing). */
internal enum class SettingsPane { compact, leading, trailing }
internal val LocalSettingsPane = staticCompositionLocalOf { SettingsPane.compact }
/** Settings' toasts, shared by every page and sheet. */
internal val LocalSettingsToasts = staticCompositionLocalOf { HomeToasts() }

private val navHeight = 56.dp

/**
 * A Settings page (prototype `fill`): the bar (Back, the tucked title, [actions]), a large title with [chip] and one [line],
 * then [content]; [foot] holds the bottom actions over a fade. Split panes put the title in the bar. [hero] pages draw
 * their own header instead of a large title. [scroll] null: the content scrolls itself.
 */
@Composable
internal fun SettingsFrame(title: String, back: (() -> Unit)?, modifier: Modifier = Modifier, line: String? = null, hero: Boolean = false,
                           chip: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {},
                           foot: (@Composable RowScope.() -> Unit)? = null, scroll: ScrollState? = rememberScrollState(),
                           bare: Boolean = false, footFade: Float = .46f, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    val pane = LocalSettingsPane.current
    val wide = pane != SettingsPane.compact
    val start = if (pane == SettingsPane.trailing) 32.dp else 24.dp
    val end = if (pane == SettingsPane.trailing) 32.dp else 24.dp
    val scroll = if (bare) null else scroll
    // The fixed foot measures itself (two-line pills at large text): nothing sits under it.
    var footHeight by remember { mutableIntStateOf(0) }
    val bottom = if (foot != null) maxOf(if (wide) 140.dp else 150.dp, with(LocalDensity.current) { footHeight.toDp() } + 12.dp) else if (wide) 110.dp else 56.dp
    Box(modifier.fillMaxSize().background(palette.background)) {
        val body: @Composable ColumnScope.() -> Unit = {
            if (!wide && !hero) Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, Modifier.semantics { heading() }, style = ActivityType.large, color = palette.foreground)
                chip?.invoke()
            }
            line?.let { Text(it, Modifier.padding(bottom = 4.dp), style = HomeType.line, color = palette.muted) }
            content()
        }
        val padding = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(start = start, end = end, top = navHeight + if (wide) 6.dp else 0.dp, bottom = bottom)
        if (bare) Column(Modifier.fillMaxSize().padding(top = navHeight), content = content)
        else if (scroll != null) Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(padding, content = body)
        } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(padding.weight(1f).padding(bottom = 0.dp), content = body)
        }
        val scrolled = (scroll?.value ?: 0) > 2
        val edge by animateFloatAsState(if (wide || scrolled || bare) 1f else 0f, tween(200), label = "edge")
        Box(Modifier.fillMaxWidth().height(navHeight).graphicsLayer { alpha = edge }
            .background(Brush.verticalGradient(0f to palette.background, .78f to palette.background, 1f to palette.background.copy(alpha = 0f))))
        Row(Modifier.fillMaxWidth().height(navHeight).padding(start = if (wide && back == null) start - 4.dp else 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            back?.let { BarButton(R.drawable.ic_activity_back, tr("Back"), Modifier.testTag("settings-back")) { haptics.light(); it() } }
            if (wide) {
                if (!hero) {
                    Text(title, Modifier.padding(start = if (back != null) 8.dp else 0.dp).semantics { heading() }, style = ActivityType.wide,
                        color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    chip?.let { Spacer(Modifier.width(10.dp)); it() }
                }
                Spacer(Modifier.weight(1f))
            } else {
                val tucked = bare || (scroll?.value ?: 0) > with(androidx.compose.ui.platform.LocalDensity.current) { (if (hero) 120 else 40).dp.toPx() }
                val alpha by animateFloatAsState(if (tucked) 1f else 0f, tween(if (reduceMotion) 180 else 250), label = "mini")
                Text(title, Modifier.padding(start = 12.dp).weight(1f).graphicsLayer { this.alpha = alpha }.clearAndSetSemantics { },
                    style = ActivityType.mini, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
        foot?.let { actionsRow ->
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { footHeight = it.height }
                .background(Brush.verticalGradient(0f to palette.background.copy(alpha = 0f), footFade to palette.background))
                .padding(start = start, end = if (wide) 24.dp else end, top = 48.dp, bottom = if (wide) 24.dp else 28.dp)) {
                Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically, content = actionsRow)
            }
        }
        HomeToast(LocalSettingsToasts.current.current, Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
    }
}

/** A bottom action (56 dp, prototype `.foot .pill`). */
@Composable
internal fun RowScope.FootPill(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false,
                               danger: Boolean = false, loading: Boolean = false, icon: Int? = null, weight: Float = 1f, wrap: Boolean = false, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    SendPill(title, modifier.weight(weight), enabled = enabled, outlined = outlined, height = 56.dp, danger = danger, loading = loading, icon = icon, wrap = wrap) {
        haptics.light(); onClick()
    }
}

/** A group: its label, then rows, each told whether to draw its divider (not under the last). */
@Composable
internal fun SettingsGroup(label: String? = null, modifier: Modifier = Modifier, first: Boolean = false, rows: List<@Composable (divider: Boolean) -> Unit>) {
    val palette = LocalPalette.current
    Column(modifier.padding(top = if (first) 10.dp else 26.dp)) {
        label?.let { Text(it, Modifier.padding(bottom = 2.dp).semantics { heading() }, style = HomeType.header, color = palette.muted) }
        rows.forEachIndexed { index, row -> row(index < rows.lastIndex) }
    }
}

/** What ends a row: a chevron, nothing, the external-link glyph, a switch or an expanding chevron. */
internal sealed interface RowEnd {
    data object Chevron : RowEnd
    data object None : RowEnd
    data object External : RowEnd
    data class Toggle(val checked: Boolean, val enabled: Boolean = true, val change: (Boolean) -> Unit) : RowEnd
    data class Expand(val open: Boolean) : RowEnd
}

/** A row's attention mark before its value: orange (needs you) or red (failed). */
internal enum class RowDot { attention, failure }

/**
 * A Settings row (prototype `R`): an icon in a soft circle, the title (and an optional [sub] line), a live [value] and
 * what ends the row. A switch row toggles from anywhere on the row. [selected] is the open page beside a split list.
 */
@Composable
internal fun SettingsRow(title: String, icon: Int? = null, glyph: String? = null, value: String? = null, sub: String? = null, dot: RowDot? = null,
                         failed: Boolean = false, numeric: Boolean = false, end: RowEnd = RowEnd.Chevron, danger: Boolean = false, bold: Boolean = false,
                         enabled: Boolean = true, selected: Boolean = false, divider: Boolean = true, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val highlight by animateFloatAsState(if (pressed || selected) 1f else 0f, tween(200), label = "row")
    val leading = icon != null || glyph != null
    val action = when (end) {
        is RowEnd.Toggle -> Modifier.toggleable(end.checked, interaction, null, enabled = enabled && end.enabled, role = Role.Switch) {
            haptics.selection(); end.change(it)
        }
        else -> if (onClick != null) Modifier.clickable(interaction, null, enabled = enabled, role = Role.Button) { haptics.light(); onClick() } else Modifier
    }
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).graphicsLayer { alpha = if (enabled) 1f else .4f }
        .drawBehind {
            if (highlight > 0f) drawRoundRect(palette.soft.copy(alpha = palette.soft.alpha * highlight), Offset(-12.dp.toPx(), 3.dp.toPx()),
                Size(size.width + 24.dp.toPx(), size.height - 6.dp.toPx()), CornerRadius(16.dp.toPx()))
            if (divider && !selected) {
                val inset = if (leading) 48.dp.toPx() else 0f
                drawRect(palette.hairline, Offset(inset, size.height - 1.dp.toPx()), Size(size.width - inset, 1.dp.toPx()))
            }
        }.then(action).semantics(mergeDescendants = true) { if (selected) this.selected = true },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val ink = if (danger) palette.failure else palette.foreground
        if (leading) Box(Modifier.size(34.dp).background(palette.soft, CircleShape), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(19.dp), tint = ink)
            else Text(glyph!!, style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 17.sp), color = ink)
        }
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(title, style = SettingsType.row.copy(fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal), color = ink)
            sub?.let { Text(it, Modifier.padding(top = 1.dp), style = HomeType.small, color = palette.muted) }
        }
        if (value != null || dot != null) Row(Modifier.widthIn(max = 200.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            dot?.let { Box(Modifier.size(8.dp).background(if (it == RowDot.failure) palette.failure else palette.orange, CircleShape)) }
            value?.let { Text(it, style = if (numeric) SettingsType.number else SettingsType.value, color = if (failed) palette.failure else palette.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        when (end) {
            RowEnd.Chevron -> Icon(painterResource(HomeIcons.chevron), null, Modifier.offset(x = 4.dp).size(18.dp), tint = palette.muted)
            is RowEnd.Expand -> {
                val turn by animateFloatAsState(if (end.open) 90f else 0f, tween(300), label = "expand")
                Icon(painterResource(HomeIcons.chevron), null, Modifier.offset(x = 4.dp).size(18.dp).graphicsLayer { rotationZ = turn }, tint = palette.muted)
            }
            RowEnd.External -> Icon(painterResource(SettingsIcons.external), null, Modifier.size(17.dp), tint = palette.muted)
            is RowEnd.Toggle -> SettingsSwitch(end.checked, enabled && end.enabled)
            RowEnd.None -> {}
        }
    }
}

/** Material 3's switch: an orange track and a white thumb when on (iOS's tint), a muted outline when off. */
@Composable
internal fun SettingsSwitch(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Switch(checked, null, modifier.clearAndSetSemantics { }, enabled = enabled, colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = palette.orange, checkedBorderColor = palette.orange,
        uncheckedThumbColor = palette.muted, uncheckedTrackColor = Color.Transparent,
        uncheckedBorderColor = palette.muted, disabledCheckedThumbColor = Color.White, disabledCheckedTrackColor = palette.orange.copy(alpha = 0.4f),
        disabledUncheckedThumbColor = palette.muted, disabledUncheckedTrackColor = Color.Transparent,
        disabledUncheckedBorderColor = palette.muted))
}

/** A row of a radio list (prototype `.opt`): Material 3's radio, the name and a small detail. */
@Composable
internal fun RadioRow(title: String, detail: String? = null, selected: Boolean, divider: Boolean = true, danger: Boolean = false,
                      modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth().heightIn(min = 54.dp)
        .drawBehind { if (divider) drawRect(palette.hairline, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        RadioButton(selected, null, Modifier.size(20.dp), enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = palette.foreground, unselectedColor = palette.muted))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = NoahTokens.Typography.body, color = if (danger) palette.failure else palette.foreground)
            detail?.let { Text(it, style = HomeType.small, color = palette.muted) }
        }
    }
}

/** A Material sheet in Settings' look: the title, one line, then [content]; [locked] can't be dismissed (deleting). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(onDismiss: () -> Unit, title: String, line: String? = null, locked: Boolean = false, modifier: Modifier = Modifier,
                           content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val palette = LocalPalette.current
    val currentLocked by rememberUpdatedState(locked)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !currentLocked || it != SheetValue.Hidden })
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    OnboardingSheet({ if (!locked) onDismiss() }, state) {
        // Scrolls rather than squeezing its actions to nothing when the keyboard leaves a short window (a book-posture fold).
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(title, Modifier.semantics { heading() }, style = NoahTokens.Typography.sheetTitle, color = palette.foreground)
            line?.let { Text(it, Modifier.padding(top = 6.dp), style = HomeType.line, color = palette.muted) }
            content(close)
        }
    }
}

/** A sheet's two actions (prototype `.shact`): Cancel and the action, sharing the width. */
@Composable
internal fun SheetActions(cancel: String, confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit, enabled: Boolean = true, danger: Boolean = false,
                          loading: Boolean = false, cancelEnabled: Boolean = !loading, confirmTag: String? = null, wrap: Boolean = false) {
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        SendPill(cancel, Modifier.weight(1f), enabled = cancelEnabled, outlined = true, wrap = wrap) { haptics.light(); onCancel() }
        SendPill(confirm, Modifier.weight(1f).then(if (confirmTag != null) Modifier.testTag(confirmTag) else Modifier), enabled = enabled,
            danger = danger, loading = loading, wrap = wrap) { if (danger) haptics.warning() else haptics.light(); onConfirm() }
    }
}

/** The status mark (prototype `stMark`): a ring with a check, a spinner, a red "!" or an icon. */
internal enum class MarkKind { ok, running, failed, idle }

@Composable
internal fun StatusMark(kind: MarkKind, icon: Int? = null, iconTint: Color? = null, ring: Color? = null, check: Float = 1f) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val spin = if (kind == MarkKind.running && !reduceMotion) rememberInfiniteTransition(label = "mark").animateFloat(0f, 360f,
        infiniteRepeatable(tween(1_000, easing = LinearEasing)), label = "spin").value else 0f
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width / 56f
            val color = ring ?: when (kind) { MarkKind.ok -> palette.orange; MarkKind.failed -> palette.failure; else -> palette.hairline }
            drawCircle(color, 26 * s, style = Stroke(2.5f * s))
            if (kind == MarkKind.running) drawArc(palette.foreground, spin - 90f, 90f, false, Offset(2 * s, 2 * s), Size(52 * s, 52 * s), style = Stroke(2.6f * s, cap = StrokeCap.Round))
            if (kind == MarkKind.ok && icon == null && check > 0f) {
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(18.5f * s, 28.8f * s); lineTo(24.9f * s, 35.2f * s); lineTo(37.9f * s, 21.8f * s) }
                drawPath(com.noahwallet.ui.onboarding.trimmed(path, check), palette.foreground, style = Stroke(2.6f * s, cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        if (kind == MarkKind.failed && icon == null) Text("!", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 22.sp), color = palette.failure)
        icon?.let { Icon(painterResource(it), null, Modifier.size(26.dp), tint = iconTint ?: palette.muted) }
    }
}

/** The status block at the top of Backup and Notifications: the mark, a title and one line. */
@Composable
internal fun StatusHeader(mark: @Composable () -> Unit, title: String, line: String?, failed: Boolean = false, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.padding(top = 14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        mark()
        Column {
            Text(title, style = SettingsType.status, color = if (failed) palette.failure else palette.foreground)
            line?.let { Text(it, Modifier.padding(top = 1.dp), style = HomeType.line, color = palette.muted) }
        }
    }
}

/** One fine-print line (prototype `.fine`). */
@Composable
internal fun FinePrint(text: String, modifier: Modifier = Modifier, failed: Boolean = false) {
    val palette = LocalPalette.current
    Text(text, modifier.padding(top = 18.dp), style = HomeType.small, color = if (failed) palette.failure else palette.muted)
}

/** A warning with its icon (prototype `.warn`). */
@Composable
internal fun Warning(text: String, icon: Int = SettingsIcons.shield, failed: Boolean = false, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (failed) palette.failure else palette.foreground)
        Text(text, style = HomeType.line, color = if (failed) palette.failure else palette.muted)
    }
}

/** A section label inside a page (prototype `.sec`). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Text(text, modifier.padding(top = 22.dp).semantics { heading() }, style = HomeType.header, color = palette.muted)
}

/** A field label (prototype `.flabel`). */
@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Text(text, modifier.padding(top = 20.dp), style = HomeType.header, color = palette.muted)
}

/**
 * Send's tonal field (prototype `.fld`): an orange outline while focused, red when [failed]; an optional [tag] (the
 * recipient's kind) at the end. [lines] above one makes it a text area; [mono] shows the whole value in monospace, wrapping.
 */
@Composable
internal fun SettingsField(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, label: String? = null,
                           failed: Boolean = false, enabled: Boolean = true, lines: Int = 1, tag: String? = null,
                           keyboard: KeyboardOptions = KeyboardOptions.Default, leading: Int? = null, contentDescription: String = label ?: placeholder,
                           mono: Boolean = false) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    label?.let { FieldLabel(it) }
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = if (lines > 1) 124.dp else 54.dp).tonal(shape, palette.soft)
        .then(if (failed) Modifier.border(1.5.dp, palette.failure, shape) else if (focused) Modifier.border(1.5.dp, palette.orange, shape) else Modifier)
        .padding(start = 16.dp, end = 8.dp, top = if (lines > 1) 14.dp else 0.dp, bottom = if (lines > 1) 14.dp else 0.dp),
        verticalAlignment = if (lines > 1) Alignment.Top else Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        leading?.let { Icon(painterResource(it), null, Modifier.size(20.dp), tint = palette.muted) }
        BasicTextField(value, onValue, Modifier.weight(1f).semantics { this.contentDescription = contentDescription }, enabled = enabled,
            textStyle = (if (mono) com.noahwallet.ui.activity.ActivityType.mono.copy(fontSize = 15.sp, lineHeight = 21.sp) else NoahTokens.Typography.body)
                .copy(color = palette.foreground), singleLine = lines == 1 && !mono, minLines = lines,
            cursorBrush = SolidColor(palette.orange), keyboardOptions = keyboard, interactionSource = interaction,
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = NoahTokens.Typography.body, color = palette.muted, maxLines = 1); inner() } })
        tag?.let { Text(it, Modifier.padding(end = 8.dp), style = HomeType.header, color = palette.muted, maxLines = 1) }
    }
}

/** A tonal row that opens a picker (prototype `.fld.row`). */
@Composable
internal fun PickerRow(text: String, modifier: Modifier = Modifier, icon: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 54.dp).tonal(RoundedCornerShape(14.dp), palette.soft)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 12.dp)
        .graphicsLayer { alpha = if (enabled) 1f else .4f },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon?.let { Icon(painterResource(it), null, Modifier.size(20.dp), tint = palette.muted) }
        Text(text, Modifier.weight(1f), style = NoahTokens.Typography.body, color = palette.foreground)
        Icon(painterResource(HomeIcons.chevron), null, Modifier.size(18.dp), tint = palette.muted)
    }
}

/**
 * A round avatar (prototype `.av`): [you] is the orange-ringed initial; otherwise a soft circle with an initial or icon.
 * [badge] is a red "!", [due] an orange dot, [pending] a dashed ring that turns.
 */
@Composable
internal fun SettingsAvatar(size: Dp, modifier: Modifier = Modifier, you: Boolean = false, initial: String? = null, icon: Int? = null,
                            badge: Boolean = false, due: Boolean = false, pending: Boolean = false) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val turn = if (pending && !reduceMotion) rememberInfiniteTransition(label = "pending").animateFloat(0f, 360f,
        infiniteRepeatable(tween(8_000, easing = LinearEasing)), label = "pending").value else 0f
    val large = size >= 60.dp
    Box(modifier.size(size)) {
        Box(Modifier.fillMaxSize().then(if (you) Modifier.border(2.dp, palette.orange, CircleShape) else Modifier.background(palette.soft, CircleShape)),
            contentAlignment = Alignment.Center) {
            val fontSize = when { size >= 84.dp -> 34.sp; large -> 24.sp; else -> 17.sp }
            if (initial != null) Text(initial.lowercase(), style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.SemiBold,
                fontSize = fontSize, lineHeight = fontSize), color = palette.foreground)
            else icon?.let { Icon(painterResource(it), null, Modifier.size(if (large) 27.dp else 20.dp), tint = palette.foreground) }
        }
        if (pending) Canvas(Modifier.requiredSize(size + if (large) 8.dp else 6.dp).align(Alignment.Center).graphicsLayer { rotationZ = turn }) {
            val width = (if (large) 2.dp else 1.5.dp).toPx()
            drawCircle(palette.muted, (this.size.minDimension - width) / 2, style = Stroke(width,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.5.dp.toPx(), 3.dp.toPx()))))
        }
        if (badge) Box(Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(if (large) 26.dp else 21.dp).background(palette.background, CircleShape)
            .padding(2.dp).background(palette.failure, CircleShape), contentAlignment = Alignment.Center) {
            val fontSize = if (large) 13.sp else 11.sp
            Text("!", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = fontSize), color = Color.White)
        }
        if (due) Box(Modifier.align(Alignment.TopEnd).offset(1.dp, (-1).dp).size(15.dp).background(palette.background, CircleShape)
            .padding(2.dp).background(palette.orange, CircleShape))
    }
}

/** The network badge next to your name off mainnet (Home's chip badge). */
@Composable
internal fun NetworkBadge(variant: String) {
    if (variant == "mainnet") return
    val palette = LocalPalette.current
    Text(tr(variant.uppercase()), Modifier.clip(CircleShape).background(palette.orangeTint).padding(horizontal = 7.dp, vertical = 2.dp),
        style = HomeType.badge, color = palette.orangeText)
}

/** The ⋯ menu's filter chip next to a title: names the filter; tapping clears it (Activity's chip). */
@Composable
internal fun FilterChip(text: String, onClear: () -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    val scale = remember(text) { Animatable(if (reduceMotion) 1f else .6f) }
    val alpha = remember(text) { Animatable(0f) }
    LaunchedEffect(text) {
        launch { alpha.animateTo(1f, if (reduceMotion) tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS) else tween(160)) }
        if (!reduceMotion) scale.animateTo(1f, NoahTokens.Motion.bouncy())
    }
    val clear = tr("Clear filter")
    Row(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }.height(30.dp).tonal(RoundedCornerShape(15.dp), palette.soft)
        .clickable(onClickLabel = clear, role = Role.Button) { haptics.selection(); onClear() }.padding(start = 12.dp, end = 8.dp).testTag("settings-filter-chip"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = ActivityType.chip, color = palette.foreground)
        Icon(painterResource(HomeIcons.close), clear, Modifier.size(14.dp), tint = palette.muted)
    }
}

/** A text link (prototype `.lnk`). */
@Composable
internal fun SettingsLink(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(text, modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp)
        .graphicsLayer { alpha = if (enabled) 1f else .4f }, style = ActivityType.link, color = palette.foreground)
}

/** A big total and the line under it (prototype `.dbig` at 40 sp and `.dfiat`). */
@Composable
internal fun BigTotal(amount: String, line: String?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier.padding(top = 6.dp)) {
        Text(amount, Modifier.semantics { contentDescription = amount }, style = SettingsType.total, color = palette.foreground, maxLines = 1)
        line?.let { Text(it, Modifier.padding(top = 3.dp), style = HomeType.line, color = palette.muted) }
    }
}


/** Onboarding's segmented control (prototype `.seg`): a soft track and a sliding pill on the snappy spring. */
@Composable
internal fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (T) -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    val dark = palette === NoahTokens.Dark
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val position by animateFloatAsState(index.toFloat(), if (reduceMotion) tween(0) else NoahTokens.Motion.snappy(), label = "segment")
    BoxWithConstraints(modifier.padding(top = 8.dp).fillMaxWidth().height(42.dp).tonal(CircleShape, palette.soft).padding(3.dp)) {
        val width = maxWidth / options.size
        Box(Modifier.offset(x = width * position).width(width).fillMaxHeight()
            .graphicsLayer { shadowElevation = 2.dp.toPx(); shape = CircleShape; clip = true }
            .background(if (dark) Color(0xFF3A3A3C) else palette.background, CircleShape))
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                val on = value == selected
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .selectable(on, enabled = enabled, role = Role.Tab) { if (!on) { haptics.selection(); onSelect(value) } },
                    contentAlignment = Alignment.Center) {
                    Text(label, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
                        color = if (on) palette.foreground else palette.muted, maxLines = 1)
                }
            }
        }
    }
}

/** A value with − and + (prototype `.step`). */
@Composable
internal fun Stepper(text: String, value: Long, range: LongRange, modifier: Modifier = Modifier, enabled: Boolean = true, onValue: (Long) -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    Row(modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = NoahTokens.Typography.body, color = palette.foreground)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, delta) in listOf(tr("Fewer") to -1, tr("More") to 1)) {
                val next = value + delta
                Box(Modifier.size(40.dp, 36.dp).tonal(RoundedCornerShape(18.dp), palette.soft)
                    .clickable(enabled = enabled && next in range, role = Role.Button, onClickLabel = label) { haptics.selection(); onValue(next) }
                    .graphicsLayer { alpha = if (enabled && next in range) 1f else .35f }.semantics { contentDescription = label },
                    contentAlignment = Alignment.Center) {
                    Text(if (delta < 0) "−" else "+", style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 20.sp), color = palette.foreground)
                }
            }
        }
    }
}
