package com.noahwallet.ui.send

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.noahwallet.R
import com.noahwallet.localization.tr
import com.noahwallet.ui.WalletAlertDialog
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeIcons
import com.noahwallet.ui.home.tonal
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalPose
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.onboarding.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Send and Receive type (prototype sizes): Roboto for words, Space Grotesk for amounts and keys. */
internal object SendType {
    private val display = NoahTokens.Display
    private val box = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None)
    private val trim = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)
    val amount = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 68.sp, lineHeight = 68.sp, letterSpacing = (-0.035).em, lineHeightStyle = trim)
    val sheetAmount = amount.copy(fontSize = 54.sp, lineHeight = 54.sp)
    val key = TextStyle(fontFamily = display, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 30.sp, lineHeightStyle = trim)
    val summary = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em, lineHeightStyle = trim)
    val big = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-0.025).em, lineHeightStyle = box)
    val title = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val strong = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val value = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val number = TextStyle(fontFamily = display, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum", lineHeightStyle = box)
    val line = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, lineHeightStyle = box)
    val phase = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, lineHeightStyle = box)
    val small = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, lineHeightStyle = box)
    val section = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, lineHeightStyle = box)
    val chip = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, lineHeightStyle = box)
    val tag = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, lineHeightStyle = box)
    val mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, lineHeightStyle = box)
    val initial = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 18.sp, lineHeightStyle = trim)
    val pill = NoahTokens.Typography.button
}

internal object SendIcons {
    val back = R.drawable.ic_sr_back
    val copy = R.drawable.ic_sr_copy
    val swap = R.drawable.ic_sr_swap
    val delete = R.drawable.ic_sr_delete
    val ark = R.drawable.ic_sr_ark
    val request = R.drawable.ic_sr_request
    fun of(glyph: SendWho.Glyph) = when (glyph) {
        SendWho.Glyph.ark -> ark
        SendWho.Glyph.lightning -> HomeIcons.lightning
        SendWho.Glyph.chain -> HomeIcons.chain
        SendWho.Glyph.request -> request
    }
}

/** A small tonal chip (Max, Paste, Add a note). */
@Composable
internal fun SendChip(title: String, modifier: Modifier = Modifier, container: Color? = null, enabled: Boolean = true, height: Dp = 32.dp, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(modifier.height(height).tonal(RoundedCornerShape(height / 2), container ?: palette.soft)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp)
        .graphicsLayer { alpha = if (enabled) 1f else .4f }, contentAlignment = Alignment.Center) {
        Text(title, style = SendType.chip, color = palette.foreground, maxLines = 1)
    }
}

/**
 * The typed amount, big and centred: grey "₿0" when empty, the newest digit fading in from grey, and a
 * shake when it's more than you can send.
 */
@Composable
internal fun AmountFigure(text: String, empty: Boolean, shake: Float, style: TextStyle = SendType.amount, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    var previous by remember { mutableStateOf(text) }
    val fresh = remember { Animatable(1f) }
    LaunchedEffect(text) {
        val grew = text.length > previous.length && !empty
        previous = text
        if (grew && !reduceMotion) { fresh.snapTo(0f); fresh.animateTo(1f, NoahTokens.Motion.snappy()) } else fresh.snapTo(1f)
    }
    Row(modifier.graphicsLayer { translationX = shake * density }.semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.Bottom) {
        if (empty) Text(text, style = style, color = palette.muted, maxLines = 1)
        else {
            Text(text.dropLast(1), style = style, color = palette.foreground, maxLines = 1)
            val f = fresh.value
            Text(text.takeLast(1), Modifier.graphicsLayer { alpha = StageArt.lerp(.4f, 1f, f.coerceIn(0f, 1f)); translationY = 6 * (1 - f) * density },
                style = style, color = androidx.compose.ui.graphics.lerp(palette.muted, palette.foreground, f.coerceIn(0f, 1f)), maxLines = 1)
        }
    }
}

/** The shake the prototype plays on an amount over your balance (380 ms, ease-out). */
@Composable
internal fun rememberAmountShake(): Animatable<Float, AnimationVector1D> = remember { Animatable(0f) }
internal suspend fun Animatable<Float, AnimationVector1D>.shakeAmount() {
    for (offset in listOf(-10f, 9f, -6f, 3f, 0f)) animateTo(offset, tween(76, easing = FastOutSlowInEasing))
}

/**
 * The keypad: Space Grotesk digits, a key that grows briefly when pressed, a blank (sats) or "." (fiat)
 * bottom-left key and delete. [onKey] receives "0"–"9", "." or null for delete.
 */
@Composable
internal fun SendKeypad(decimal: Boolean, enabled: Boolean, modifier: Modifier = Modifier, keyHeight: Dp = 60.dp, onKey: (String?) -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", if (decimal) "." else "", "0", null)
    Column(modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key -> Key(key, enabled && key != "", Modifier.weight(1f).height(keyHeight)) { onKey(key) } }
            }
        }
    }
}

@Composable
private fun Key(key: String?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val grow = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val label = if (key == null) tr("Delete digit") else key
    Box(modifier.clip(RoundedCornerShape(30.dp)).background(if (pressed && enabled) palette.soft else Color.Transparent)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label) {
            haptics.selection()
            if (!reduceMotion) scope.launch { grow.snapTo(1f); grow.animateTo(1.28f, tween(70, easing = LinearOutSlowInEasing)); grow.animateTo(1f, tween(150, easing = FastOutSlowInEasing)) }
            onClick()
        }.testTag("send-key-${key ?: "⌫"}").semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { scaleX = grow.value; scaleY = grow.value }) {
            if (key == null) Icon(painterResource(SendIcons.delete), null, Modifier.size(26.dp), tint = palette.foreground)
            else if (key.isNotEmpty()) Text(key, style = SendType.key, color = palette.foreground)
        }
    }
}

/** An avatar: the recipient's initial in Space Grotesk, or a rail glyph. */
@Composable
internal fun WhoAvatar(who: SendWho, size: Dp = 44.dp, textStyle: TextStyle = SendType.initial) {
    val palette = LocalPalette.current
    Box(Modifier.size(size).background(palette.soft, CircleShape), contentAlignment = Alignment.Center) {
        when {
            who.initial != null -> Text(who.initial, style = textStyle, color = palette.foreground)
            who.glyph != null -> Icon(painterResource(SendIcons.of(who.glyph)), null, Modifier.size(size * 20 / 44), tint = palette.foreground)
        }
    }
}

/** The "To" field: a tonal pill with the label, the text and a Paste chip; the caret is orange. */
@Composable
internal fun ToField(value: String, onValue: (String) -> Unit, enabled: Boolean, onPaste: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).tonal(RoundedCornerShape(26.dp), palette.soft).padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("To"), style = SendType.body.copy(fontWeight = FontWeight.Medium), color = palette.muted)
        BasicTextField(value, onValue, Modifier.weight(1f).padding(vertical = 14.dp).testTag("send-recipient"), enabled = enabled,
            textStyle = SendType.body.copy(color = palette.foreground), maxLines = 3,
            cursorBrush = SolidColor(palette.orange),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onDone() }),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text(tr("Name, address or invoice"), style = SendType.body, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() }
            })
        SendChip(tr("Paste"), Modifier.testTag("send-paste"), container = palette.background, enabled = enabled, onClick = onPaste)
    }
}

/** A result or recent row (64 dp): avatar, name and its type. */
@Composable
internal fun WhoRow(who: SendWho, subtitle: String, divider: Boolean, enabled: Boolean = true, busy: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth().height(64.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { }
        .drawBehind { if (divider) drawRect(palette.hairline, Offset(58.dp.toPx(), size.height - 1.dp.toPx()), Size(size.width - 58.dp.toPx(), 1.dp.toPx())) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        WhoAvatar(who)
        Column(Modifier.weight(1f)) {
            Text(who.name, style = SendType.body, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = SendType.small, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = palette.muted, strokeWidth = 2.dp)
    }
}

/** A rail card on Method (or a balance card on Balance): glyph, name, one line and an optional "Best" badge. */
@Composable
internal fun RailCard(icon: Int?, name: String, line: String, best: Boolean, enabled: Boolean, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !LocalReduceMotion.current) .98f else 1f, NoahTokens.Motion.tap(), label = "rail")
    Row(modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .4f }
        .tonal(RoundedCornerShape(22.dp), palette.soft)
        .then(if (selected) Modifier.border(1.5.dp, palette.foreground, RoundedCornerShape(22.dp)) else Modifier)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)
        .semantics(mergeDescendants = true) { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (icon != null) Box(Modifier.size(40.dp).background(palette.background, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = palette.foreground)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = SendType.strong, color = palette.foreground)
            Text(line, style = SendType.small, color = palette.muted)
        }
        if (best) Text(tr("Best"), Modifier.background(palette.orangeTint, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp), style = SendType.tag, color = palette.orangeText)
    }
}

/**
 * A review or result row (48 dp): muted label, value on the right; amounts in Space Grotesk. A [wraps] row too long for
 * one line puts its label above its value and both wrap rather than ellipsize (Review's rows, as on iOS).
 */
@Composable
internal fun ValueRow(label: String, value: String, number: Boolean = false, icon: Int? = null, divider: Boolean = true, copy: (() -> Unit)? = null, wraps: Boolean = false, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val row = modifier.fillMaxWidth().heightIn(min = 48.dp)
        .drawBehind { if (divider) drawRect(palette.hairline, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
        .semantics(mergeDescendants = true) { }
    val copyButton = @Composable {
        val copyLabel = tr("Copy")
        if (copy != null) Box(Modifier.padding(start = 8.dp).size(36.dp).tonal(CircleShape, palette.soft)
            .clickable(role = Role.Button, onClickLabel = copyLabel, onClick = copy).semantics { contentDescription = copyLabel }, contentAlignment = Alignment.Center) {
            Icon(painterResource(SendIcons.copy), null, Modifier.size(18.dp), tint = palette.foreground)
        }
    }
    if (wraps) LineOrStack(row, label = { Text(label, style = SendType.body, color = palette.muted) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.padding(end = 6.dp).size(16.dp), tint = palette.foreground)
            Text(value, Modifier.weight(1f, fill = false), style = if (number) SendType.number else SendType.value, color = palette.foreground)
            copyButton()
        }
    } else Row(row, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 12.dp), style = SendType.body, color = palette.muted, maxLines = 1)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.padding(end = 6.dp).size(16.dp), tint = palette.foreground)
            Text(value, Modifier.weight(1f, fill = false), style = if (number) SendType.number else SendType.value, color = palette.foreground,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
        }
        copyButton()
    }
}

/** [label] and [value] side by side (value at the end) when they fit on one line, else the label above the value. */
@Composable
private fun LineOrStack(modifier: Modifier, label: @Composable () -> Unit, value: @Composable () -> Unit) =
    Layout(listOf(label, value), modifier) { (labels, values), constraints ->
        val title = labels.single()
        val content = values.single()
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        if (title.maxIntrinsicWidth(Constraints.Infinity) + gap + content.maxIntrinsicWidth(Constraints.Infinity) <= width) {
            val t = title.measure(Constraints(maxWidth = width))
            val v = content.measure(Constraints(maxWidth = width - t.width - gap))
            val height = maxOf(constraints.minHeight, t.height, v.height)
            layout(width, height) { t.place(0, (height - t.height) / 2); v.place(width - v.width, (height - v.height) / 2) }
        } else {
            val padding = 10.dp.roundToPx()
            val spacing = 2.dp.roundToPx()
            val t = title.measure(Constraints(maxWidth = width))
            val v = content.measure(Constraints(maxWidth = width))
            val height = maxOf(constraints.minHeight, padding + t.height + spacing + v.height + padding)
            layout(width, height) { t.place(0, padding); v.place(0, padding + t.height + spacing) }
        }
    }

/** How long the finger stays down before a hold sends. */
internal const val HoldMillis = 800L

/**
 * Hold to send: holding 0.8 s traces an orange stroke around the pill (your signature), with ticks at
 * 25/50/75 % and a heavy one on completion; letting go early, a second finger or a pose change rewinds it. [onConfirm] runs
 * once per gesture.
 * TalkBack and Switch Access get a "Send ₿X" action that asks for confirmation instead of holding.
 */
@Composable
internal fun HoldToSend(amount: String, enabled: Boolean, sending: Boolean, modifier: Modifier = Modifier, onConfirm: () -> Unit) =
    HoldToConfirm(tr("Hold to send"), tr("Sending"), tr("Send {amount}", amount), tr("Send"), enabled, sending, modifier, "send-hold", onConfirm)

/** Send's hold with other words ([hold], [busyLabel]); [action] names the accessibility action and its confirmation, [confirm] its button. */
@Composable
internal fun HoldToConfirm(hold: String, busyLabel: String, action: String, confirm: String, enabled: Boolean, sending: Boolean,
                           modifier: Modifier = Modifier, tag: String, onConfirm: () -> Unit) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val progress = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    val currentConfirm by rememberUpdatedState(onConfirm)
    val currentEnabled by rememberUpdatedState(enabled)
    val label = if (sending) busyLabel else hold
    Box(modifier.fillMaxWidth().height(58.dp)
        .graphicsLayer { scaleX = press.value; scaleY = press.value; alpha = if (enabled || sending) 1f else .4f }
        .drawWithContent {
            drawContent()
            val p = progress.value
            if (p > 0f) {
                val inset = 2.dp.toPx()
                val path = pill(-inset, -inset, size.width + inset, size.height + inset)
                val length = PathMeasure().apply { setPath(path, false) }.length
                drawPath(StageArt.segment(path, 0f, length * p), palette.orange, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        .background(palette.foreground, RoundedCornerShape(29.dp))
        .testTag(tag)
        // A pose change moves the pill under the finger, so it restarts the gesture: hold again in the new pose.
        .pointerInput(LocalPose.current) {
            awaitEachGesture {
                val finger = awaitFirstDown(requireUnconsumed = false).id
                if (!currentEnabled) return@awaitEachGesture
                var fired = false
                val hold = scope.launch {
                    launch { if (!reduceMotion) press.animateTo(.97f, NoahTokens.Motion.tap()) }
                    val ticks = launch {
                        delay(200); haptics.selection(); delay(200); haptics.light(); delay(200); haptics.medium()
                    }
                    // Every hold is a full 0.8 s, even one that starts while the last one rewinds. The stroke follows
                    // frame time rather than an animation, so it still traces when animations are removed.
                    progress.snapTo(0f)
                    val stroke = launch {
                        val start = withFrameMillis { it }
                        while (true) progress.snapTo(((withFrameMillis { it } - start) / HoldMillis.toFloat()).coerceAtMost(1f))
                    }
                    // Sending waits on the unscaled clock: "Remove animations" finishes animations at once but can't shorten this.
                    delay(HoldMillis)
                    stroke.cancel(); ticks.cancel()
                    progress.snapTo(1f)
                    if (currentEnabled) { fired = true; haptics.heavy(); currentConfirm() }
                }
                try { holdUntilReleased(finger) } finally {
                    if (!fired && hold.isActive) {
                        hold.cancel()
                        scope.launch { launch { press.animateTo(1f, NoahTokens.Motion.snappy()) }; progress.animateTo(0f, tween(260, easing = LinearOutSlowInEasing)) }
                    } else scope.launch { press.animateTo(1f, NoahTokens.Motion.snappy()) }
                }
            }
        }
        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = label
            if (!enabled) disabled()
            onClick(label = action) { if (currentEnabled) confirming = true; currentEnabled }
            customActions = listOf(CustomAccessibilityAction(action) { if (currentEnabled) confirming = true; currentEnabled })
        }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.text.BasicText(label, Modifier.padding(horizontal = 16.dp), style = SendType.pill.copy(color = palette.background),
            maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = SendType.pill.fontSize))
    }
    LaunchedEffect(sending) { if (!sending && progress.value >= 1f) progress.snapTo(0f) }
    if (confirming) WalletAlertDialog(onDismissRequest = { confirming = false },
        title = { Text(action) },
        confirmButton = { TextButton({ confirming = false; if (currentEnabled) { haptics.heavy(); currentConfirm() } }, Modifier.testTag("$tag-confirm")) { Text(confirm) } },
        dismissButton = { TextButton({ confirming = false }) { Text(tr("Cancel")) } })
}

/**
 * Waits while the finger that started a hold keeps holding: it ends when that finger lifts, is consumed or leaves the
 * pill, or when a second finger lands, so overlapping fingers can't add up to one 0.8 s hold. As Compose's own
 * waitForUpOrCancellation does, it also looks at the Final pass, where a parent's consumption shows: a scroll that
 * drags the pill along under the finger ends the hold even though the finger never leaves it.
 */
private suspend fun AwaitPointerEventScope.holdUntilReleased(finger: PointerId) {
    while (true) {
        val changes = awaitPointerEvent().changes
        if (changes.any { it.id != finger && it.pressed }) return
        val change = changes.firstOrNull { it.id == finger } ?: return
        if (!change.pressed || change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding)) return
        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return
    }
}

/** A stadium from the top-left end of its top edge, clockwise, as the prototype's SVG rect strokes it. */
private fun pill(left: Float, top: Float, right: Float, bottom: Float): Path {
    val r = (bottom - top) / 2
    return Path().apply {
        moveTo(left + r, top); lineTo(right - r, top)
        arcTo(androidx.compose.ui.geometry.Rect(right - 2 * r, top, right, bottom), -90f, 180f, false)
        lineTo(left + r, bottom)
        arcTo(androidx.compose.ui.geometry.Rect(left, top, left + 2 * r, bottom), 90f, 180f, false)
        close()
    }
}

/**
 * A title that cross-fades when it changes (prototype `setTitle`): the old one blurs out in 140 ms, the new
 * one rises in with the bouncy spring.
 */
@Composable
internal fun SwapTitle(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    var shown by remember { mutableStateOf(text) }
    val out = remember { Animatable(0f) }
    val rise = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text == shown) return@LaunchedEffect
        if (reduceMotion) { out.animateTo(1f, tween(90)); shown = text; out.animateTo(0f, tween(90)); return@LaunchedEffect }
        out.animateTo(1f, tween(140)); shown = text; out.snapTo(0f); rise.snapTo(0f); rise.animateTo(1f, NoahTokens.Motion.bouncy())
    }
    Text(shown, modifier.graphicsLayer {
        alpha = (1 - out.value) * rise.value.coerceIn(0f, 1f)
        if (!reduceMotion) translationY = 6 * (1 - rise.value) * density
    }.then(if (Build.VERSION.SDK_INT >= 31 && out.value > .01f && !reduceMotion) Modifier.blur((4 * out.value).dp, BlurredEdgeTreatment.Unbounded) else Modifier)
        .semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = style, color = palette.foreground)
}

/** The muted line under the stage: fades and rises in on each change; done phases read in the foreground. */
@Composable
internal fun PhaseText(text: String?, done: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val enter = remember { Animatable(1f) }
    LaunchedEffect(text) { if (text != null) { enter.snapTo(0f); enter.animateTo(1f, if (reduceMotion) tween(180) else NoahTokens.Motion.snappy()) } }
    Box(modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
        if (text != null) Text(text, Modifier.graphicsLayer {
            alpha = enter.value.coerceIn(0f, 1f); if (!reduceMotion) translationY = 6 * (1 - enter.value) * density
        }.semantics { liveRegion = LiveRegionMode.Polite }, style = SendType.phase, color = if (done) palette.foreground else palette.muted,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A black pill (prototype `.pill`: 52 dp, 17 sp; 48 dp and 16 sp under the Receive card) or the outlined
 * variant. Long labels (and longer languages) shrink to fit rather than clip.
 */
@Composable
internal fun SendPill(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false, height: Dp = 52.dp,
                       danger: Boolean = false, loading: Boolean = false, icon: Int? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !LocalReduceMotion.current) .97f else 1f, NoahTokens.Motion.tap(), label = "pill")
    val shape = RoundedCornerShape(height / 2)
    val style = if (height < 52.dp) SendType.pill.copy(fontSize = 16.sp, lineHeight = 20.sp) else SendType.pill
    // Red only while it can act (prototype `.pill.bad:disabled`): a disabled destructive pill looks like any other.
    val red = danger && enabled
    val ink = if (outlined) palette.foreground else if (red) Color.White else palette.background
    Box(modifier.height(height).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled || loading) 1f else .28f }
        .then(if (outlined) Modifier.border(1.5.dp, palette.hairline, shape).clip(shape) else Modifier.tonal(shape, if (red) palette.failure else palette.foreground))
        .clickable(interaction, indication = androidx.compose.material3.ripple(), enabled = enabled && !loading, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = ink, strokeWidth = 2.dp)
            else icon?.let { Icon(painterResource(it), null, Modifier.size(20.dp), tint = ink) }
            androidx.compose.foundation.text.BasicText(title, style = style.copy(color = ink, textAlign = TextAlign.Center),
                maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize))
        }
    }
}

