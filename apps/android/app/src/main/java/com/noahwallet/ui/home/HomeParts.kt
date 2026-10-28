package com.noahwallet.ui.home

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.settings.meAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Home's type: Roboto for text, Space Grotesk for amounts (prototype sizes). */
internal object HomeType {
    private val display = NoahTokens.Display
    private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
    // CSS line boxes: a single line is as tall as its line height (prototype `font: 600 15px/20px`), and
    // a fallback glyph such as ₿ can't make one line taller than another.
    private val box = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    val chip = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, lineHeightStyle = box)
    val chipInitial = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 14.sp, lineHeightStyle = trim)
    val badge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, lineHeightStyle = box)
    val mini = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.02).em)
    val balance = NoahTokens.Typography.balance.copy(lineHeight = 50.sp, lineHeightStyle = trim)
    val line = NoahTokens.Typography.sub.copy(lineHeightStyle = box)
    val strong = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, lineHeightStyle = box)
    val small = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, lineHeightStyle = box)
    val header = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, lineHeightStyle = box)
    val button = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp, lineHeightStyle = box)
    val qrName = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val listTitle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, lineHeightStyle = box)
    val rowTitle = NoahTokens.Typography.body.copy(lineHeightStyle = box)
    val amount = TextStyle(fontFamily = display, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum",
        lineHeightStyle = box)
    val avatar = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 17.sp, lineHeightStyle = trim)
    val total = NoahTokens.Typography.amount.copy(lineHeight = 36.sp, lineHeightStyle = trim)
    val got = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em,
        lineHeightStyle = trim)
    val toast = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, lineHeightStyle = box)
}

/** A flat tonal surface: Android's answer to the prototype's Liquid Glass. */
internal fun Modifier.tonal(shape: androidx.compose.ui.graphics.Shape, color: Color) = clip(shape).background(color)

@Composable
internal fun CircleButton(icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(modifier.size(40.dp).tonal(CircleShape, palette.soft).clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(21.dp), tint = palette.foreground)
    }
}

/** Leading: you. An orange-ringed initial and your address's local part; a network badge off mainnet. */
@Composable
internal fun NameChip(address: String?, variant: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val local = address?.substringBefore('@')?.takeIf { it.isNotEmpty() }
    val label = tr("Profile")
    Row(Modifier.height(40.dp).tonal(RoundedCornerShape(20.dp), palette.soft)
        .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
        .padding(start = 5.dp, end = if (local != null || variant != "mainnet") 14.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(30.dp).meAvatar().border(1.5.dp, palette.orange, CircleShape), contentAlignment = Alignment.Center) {
            if (local != null) Text(local.take(1).lowercase(), style = HomeType.chipInitial, color = palette.foreground)
            else Icon(painterResource(HomeIcons.person), null, Modifier.size(18.dp), tint = palette.foreground)
        }
        // Measured after the ring and badge, so a narrow bar shortens the name rather than dropping the badge.
        if (local != null) Text(local, style = HomeType.chip, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 160.dp))
        if (variant != "mainnet") Text(tr(variant.uppercase()), Modifier.padding(start = 2.dp).clip(CircleShape).background(palette.orangeTint)
            .padding(horizontal = 7.dp, vertical = 2.dp), style = HomeType.badge, color = palette.orangeText)
    }
}

/**
 * Digits roll to their value (snappy, 1.7× its length, 45 ms apart, with a light blur); other characters
 * stay put. Columns are keyed from the right so the units digit stays the units digit as the number grows.
 */
@Composable
internal fun RollingText(text: String, style: TextStyle, color: Color, rollFromZero: Boolean, modifier: Modifier = Modifier, startDelay: Long = 0) {
    val reduceMotion = LocalReduceMotion.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = remember(style, density) { (0..9).map { with(density) { measurer.measure("$it", style).size.width.toDp() } } }
    val lineHeight = with(density) { style.lineHeight.toDp() }
    val digitIndex = text.mapIndexedNotNull { index, char -> index.takeIf { char.isDigit() } }
    val fade = remember { Animatable(1f) }
    var mounted by remember { mutableStateOf(false) }
    SideEffect { mounted = true }
    LaunchedEffect(text) {
        if (reduceMotion && rollFromZero) { fade.snapTo(0f); fade.animateTo(1f, tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) }
    }
    Row(modifier.graphicsLayer { alpha = fade.value }, verticalAlignment = Alignment.Bottom) {
        text.forEachIndexed { index, char ->
            key(text.length - index) {
                // One line box for every character so they share a baseline; separators' descenders may overflow it.
                if (!char.isDigit()) Text(char.toString(), Modifier.height(lineHeight).testTag("home-balance-char"), style = style, color = color,
                    overflow = TextOverflow.Visible)
                else RollingDigit(char.digitToInt(), style, color, widths, lineHeight,
                    rollFromZero = (rollFromZero || mounted) && !reduceMotion, wait = startDelay + digitIndex.indexOf(index) * 45L)
            }
        }
    }
}

/** One digit's column rolling to [target] after [delay], from 0 when [rollFromZero]; it blurs a little while it moves. */
@Composable
private fun RollingDigit(target: Int, style: TextStyle, color: Color, widths: List<Dp>, lineHeight: Dp, rollFromZero: Boolean, wait: Long) {
    val reduceMotion = LocalReduceMotion.current
    val position = remember { Animatable(if (rollFromZero) 0f else target.toFloat()) }
    var from by remember { mutableFloatStateOf(position.value) }
    LaunchedEffect(target) {
        if (position.value == target.toFloat()) return@LaunchedEffect
        if (reduceMotion) { position.snapTo(target.toFloat()); return@LaunchedEffect }
        from = position.value
        delay(wait)
        position.animateTo(target.toFloat(), spring(dampingRatio = .85f, stiffness = 438.6491f / (1.7f * 1.7f)))
    }
    val travelled = if (target.toFloat() == from) 1f else ((position.value - from) / (target - from)).coerceIn(0f, 1f)
    val blur = 2.5f * 4 * travelled * (1 - travelled)
    Box(Modifier.width(widths[target]).height(lineHeight).clipToBounds().testTag("home-balance-char")
        .then(if (Build.VERSION.SDK_INT >= 31 && blur > .05f) Modifier.blur(blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier)) {
        // Unbounded: the ten-digit column is taller than the one-line window it rolls through.
        Column(Modifier.align(Alignment.TopCenter).wrapContentHeight(Alignment.Top, unbounded = true).requiredWidth(widths.max())
            .offset { IntOffset(0, -(position.value * lineHeight.toPx()).roundToInt()) }) {
            for (digit in 0..9) Text("$digit", Modifier.height(lineHeight).fillMaxWidth(), style = style, color = color,
                textAlign = TextAlign.Center)
        }
    }
}

/** The pulsing dot of the status line; still under Reduce Motion. */
@Composable
internal fun PulseDot(color: Color) {
    val reduceMotion = LocalReduceMotion.current
    val pulse = if (reduceMotion) 0f else rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse").value
    val wave = if (pulse < .5f) pulse * 2 else (1 - pulse) * 2
    val eased = FastOutSlowInEasing.transform(wave)
    Box(Modifier.size(7.dp).graphicsLayer { alpha = 1 - .75f * eased; scaleX = 1 - .3f * eased; scaleY = 1 - .3f * eased }.background(color, CircleShape))
}

@Composable
internal fun StatusLine(text: String, failed: Boolean, dot: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, emphasis: String? = null) {
    val palette = LocalPalette.current
    // No onClick: plain text, not a button.
    Row(if (onClick != null) modifier.clickable(role = Role.Button, onClick = onClick) else modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dot) PulseDot(palette.muted)
        Text(text + (emphasis?.let { " $it" } ?: ""), style = HomeType.line, color = if (failed) palette.failure else palette.foreground)
    }
}

/**
 * The top bar's quiet wallet activity, in a fixed slot so nothing moves: a turning glyph while a background task or
 * backup runs ([running] names it for TalkBack), a green check for about 2 s once a backup completes, else nothing.
 */
@Composable
internal fun ActivityGlyph(running: String?, backedUpAt: Long?) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(backedUpAt) {
        if (backedUpAt == null) { checked = false; return@LaunchedEffect }
        checked = true
        try { kotlinx.coroutines.delay(2_000) } finally { checked = false }
    }
    val fade = tween<Float>(if (reduceMotion) 180 else 250)
    Box(Modifier.size(26.dp, 44.dp), contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedVisibility(!checked && running != null,
            enter = androidx.compose.animation.fadeIn(fade), exit = androidx.compose.animation.fadeOut(fade)) {
            val turn by rememberInfiniteTransition(label = "activity").animateFloat(0f, 360f,
                infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "turn")
            val label = tr(running ?: "")
            Icon(painterResource(HomeIcons.sync), null, Modifier.size(20.dp).graphicsLayer { rotationZ = if (reduceMotion) 0f else turn }
                .semantics { contentDescription = label }, tint = palette.muted)
        }
        androidx.compose.animation.AnimatedVisibility(checked,
            enter = androidx.compose.animation.fadeIn(fade) + if (reduceMotion) androidx.compose.animation.EnterTransition.None
            else androidx.compose.animation.scaleIn(spring(.5f, 400f), initialScale = .3f),
            exit = androidx.compose.animation.fadeOut(fade)) {
            val label = tr("Backup completed")
            // Today's success green (Send's ✓, the widget).
            Box(Modifier.size(20.dp).background(Color(0xFF22C55E), CircleShape).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                Icon(painterResource(HomeIcons.check), null, Modifier.size(14.dp), tint = Color.White)
            }
        }
    }
}

/** One nudge card: icon, two lines, an action pill and (for dismissible nudges) ×. */
@Composable
internal fun NudgeCard(icon: Int, title: String, subtitle: String?, action: String?, dismissLabel: String?,
    onAction: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth().tonal(RoundedCornerShape(22.dp), palette.soft).padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = palette.foreground)
        Column(Modifier.weight(1f)) {
            Text(title, style = if (subtitle == null) HomeType.line else HomeType.strong, color = palette.foreground)
            subtitle?.let { Text(it, style = HomeType.small, color = palette.muted) }
        }
        action?.let { Pill(it, enabled = enabled, onClick = onAction) }
        dismissLabel?.let { label ->
            Box(Modifier.padding(start = 0.dp).offset(x = (-4).dp).size(28.dp).clip(CircleShape).clickable(onClickLabel = label, role = Role.Button,
                onClick = onDismiss)
                .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                Icon(painterResource(HomeIcons.close), null, Modifier.size(14.dp), tint = palette.muted)
            }
        }
    }
}

/** Black pill (white in dark); compact height for nudges. */
@Composable
internal fun Pill(title: String, modifier: Modifier = Modifier, height: Dp = 36.dp, outlined: Boolean = false, enabled: Boolean = true,
    style: TextStyle = HomeType.strong, container: Color? = null, content: Color? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !LocalReduceMotion.current) .95f else 1f, NoahTokens.Motion.tap(), label = "pill")
    val shape = RoundedCornerShape(height / 2)
    Box(modifier.height(height).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .4f }
        .then(if (outlined) Modifier.border(1.5.dp, palette.hairline, shape).clip(shape) else Modifier.tonal(shape, container ?: palette.foreground))
        .clickable(interaction, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(title, style = style, color = content ?: if (outlined) palette.foreground else palette.background, maxLines = 1)
    }
}

/**
 * A 42 dp avatar (60 dp [large], Activity's detail): an initial for people and addresses, otherwise a rail glyph;
 * pending, failed and new marks. [fading] shrinks the new dot away (soft, prototype `fadeDot`), then [onSeen].
 */
@Composable
internal fun RowAvatar(row: HomeRow, new: Boolean, pop: Boolean, modifier: Modifier = Modifier, large: Boolean = false, fading: Boolean = false,
    onSeen: () -> Unit = {}) {
    val side = if (large) 60.dp else 42.dp
    Box(modifier.size(side)) {
        AvatarFace(row.avatar, large)
        if (row.pending) PendingRing(side, large, Modifier.align(Alignment.Center))
        if (row.failed) FailedMark(large, Modifier.align(Alignment.BottomEnd))
        if (new) NewDot(pop, fading, onSeen, Modifier.align(Alignment.TopEnd))
    }
}

/** The soft circle with an initial for people and addresses, otherwise a rail glyph. */
@Composable
private fun AvatarFace(avatar: HomeRow.Avatar, large: Boolean) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxSize().background(palette.soft, CircleShape), contentAlignment = Alignment.Center) {
        val icon = when (avatar) {
            is HomeRow.Avatar.Initial -> null.also {
                Text(avatar.letter, style = if (large) HomeType.avatar.copy(fontSize = 24.sp, lineHeight = 24.sp) else HomeType.avatar,
                    color = palette.foreground)
            }
            HomeRow.Avatar.Lightning -> HomeIcons.lightning
            HomeRow.Avatar.Chain -> HomeIcons.chain
            HomeRow.Avatar.Received -> HomeIcons.receive
            HomeRow.Avatar.Sent -> HomeIcons.send
            HomeRow.Avatar.Recurring -> HomeIcons.repeat
            HomeRow.Avatar.Renewal -> HomeIcons.renew
            HomeRow.Avatar.Exit -> com.noahwallet.ui.settings.SettingsIcons.exit
        }
        icon?.let { Icon(painterResource(it), null, Modifier.size(if (large) 27.dp else 20.dp), tint = palette.foreground) }
    }
}

/** A pending row's dashed ring, turning slowly (still under Reduce Motion). */
@Composable
private fun PendingRing(side: Dp, large: Boolean, modifier: Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val turn = if (!reduceMotion) rememberInfiniteTransition(label = "pending").animateFloat(0f, 360f,
        infiniteRepeatable(tween(8_000, easing = LinearEasing)), label = "pending").value else 0f
    Canvas(modifier.requiredSize(side + if (large) 8.dp else 6.dp).graphicsLayer { rotationZ = turn }) {
        val width = (if (large) 2.dp else 1.5.dp).toPx()
        drawCircle(palette.muted, (size.minDimension - width) / 2, style = Stroke(width,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.5.dp.toPx(), 3.dp.toPx()))))
    }
}

@Composable
private fun FailedMark(large: Boolean, modifier: Modifier) {
    val palette = LocalPalette.current
    Box(modifier.offset(if (large) 4.dp else 5.dp, if (large) 4.dp else 5.dp)
        .size(if (large) 26.dp else 21.dp).background(palette.background, CircleShape)
        .padding(2.dp).background(palette.failure, CircleShape), contentAlignment = Alignment.Center) {
        val size = if (large) 13.sp else 11.sp
        Text("!", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size), color = Color.White)
    }
}

/** The new row's orange dot: pops in with [pop]; [fading] grows it a little, shrinks it away, then [onSeen]. */
@Composable
private fun NewDot(pop: Boolean, fading: Boolean, onSeen: () -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val scale = remember { Animatable(if (pop && !reduceMotion) 0f else 1f) }
    val gone = remember { Animatable(0f) }
    LaunchedEffect(Unit) { if (scale.value < 1f) { delay(120); scale.animateTo(1f, NoahTokens.Motion.bouncy()) } }
    LaunchedEffect(fading) {
        if (!fading) return@LaunchedEffect
        if (reduceMotion) gone.animateTo(1f, tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS))
        else gone.animateTo(1f, tween((SpringCurve.soft.settle * 1_400).toInt(), easing = LinearEasing))
        onSeen()
    }
    Box(modifier.offset(3.dp, (-3).dp).size(15.dp).graphicsLayer {
        // Grows a little, then shrinks to nothing (prototype keyframes 1 → 1.35 at 25 % → 0).
        val g = gone.value
        val shrink = if (reduceMotion) 1f else if (g < .25f) lerp(1f, 1.35f, SpringCurve.soft.over(g / .25f)) else lerp(1.35f, 0f,
            SpringCurve.soft.over((g - .25f) / .75f))
        scaleX = scale.value * shrink; scaleY = scale.value * shrink
        alpha = if (reduceMotion) 1 - g else if (g < .25f) 1f else 1 - (g - .25f) / .75f
    }.background(palette.background, CircleShape).padding(2.dp).background(palette.orange, CircleShape))
}

/** Kind · time, or the row's state; "Failed" and "Canceled" read in the failure colour. */
@Composable
internal fun rowSubtitle(row: HomeRow, locale: java.util.Locale, now: java.util.Date): Pair<String, Boolean> {
    val time = row.date?.let { date ->
        when (HomeDays.day(date, now)) {
            HomeDays.Day.Today, HomeDays.Day.Yesterday -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale).format(date)
            else -> dayLabel(date, locale, now)
        }
    }
    val kind = row.kind?.let { tr(it) }
    val state = row.state?.let { tr(it) }
    return when {
        row.refunded -> tr("Refunded · back in your wallet") to true
        row.failed || row.canceled -> (state!! + if (row.unchanged) " " + tr("· nothing left your wallet") else "") to true
        state != null -> listOfNotNull(state, kind).joinToString(" · ") to false
        else -> listOfNotNull(kind, time).joinToString(" · ") to false
    }
}

internal fun dayLabel(date: java.util.Date, locale: java.util.Locale, now: java.util.Date): String {
    val year = java.util.Calendar.getInstance().apply { time = date }.get(java.util.Calendar.YEAR)
    val thisYear = java.util.Calendar.getInstance().apply { time = now }.get(java.util.Calendar.YEAR)
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (year == thisYear) "MMMd" else "MMMdyyyy")
    return java.text.SimpleDateFormat(pattern, locale).format(date)
}

/** The row's signed amount, or dots while amounts are hidden. */
internal fun rowAmount(row: HomeRow, hidden: Boolean, unit: com.noahwallet.formatting.BitcoinAmountUnit, locale: java.util.Locale) =
    if (hidden) "••••" else row.amount?.let { row.sign + BitcoinAmount.format(it, unit, locale) }.orEmpty()

/**
 * One Recent/Activity row (66 dp): avatar, title, subtitle and a monochrome amount. Activity adds its [selected]
 * highlight, the new dot [fading] away once seen, and modifiers that carry the avatar, name and amount into the detail.
 */
@Composable
internal fun ActivityRow(row: HomeRow, hidden: Boolean, unit: com.noahwallet.formatting.BitcoinAmountUnit, locale: java.util.Locale, now: java.util.Date,
    new: Boolean, pop: Boolean, divider: Boolean, modifier: Modifier = Modifier, selected: Boolean = false,
    fading: Boolean = false, onSeen: () -> Unit = {}, avatarModifier: Modifier = Modifier, titleModifier: Modifier = Modifier,
    amountModifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val (subtitle, failed) = rowSubtitle(row, locale, now)
    val amount = rowAmount(row, hidden, unit, locale)
    Row(modifier.fillMaxWidth().height(66.dp)
        .drawBehind {
            // The selected row's soft highlight reaches 12 dp past the text (prototype `.rw.sel::before`).
            if (selected) drawRoundRect(palette.soft, Offset(-12.dp.toPx(), 3.dp.toPx()),
                androidx.compose.ui.geometry.Size(size.width + 24.dp.toPx(), size.height - 6.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
            if (divider) drawRect(palette.hairline, Offset(56.dp.toPx(), size.height - 1.dp.toPx()),
                androidx.compose.ui.geometry.Size(size.width - 56.dp.toPx(), 1.dp.toPx()))
        }.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { if (selected) this.selected = true },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        RowAvatar(row, new, pop, fading = fading, onSeen = onSeen, modifier = avatarModifier)
        Column(Modifier.weight(1f)) {
            Text(tr(row.title), titleModifier, style = HomeType.rowTitle, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = HomeType.small, color = if (failed) palette.failure else palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(amount, amountModifier, style = HomeType.amount.copy(textDecoration = if (row.failed || row.canceled) TextDecoration.LineThrough else null),
            color = if (row.pending || row.failed || row.canceled) palette.muted else palette.foreground, maxLines = 1)
    }
}

/** The transient pill under the top bar (prototype `toast`): rises in, holds, fades out. */
@Composable
internal fun HomeToast(message: HomeToastMessage?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(1f) }
    var shown by remember { mutableStateOf<HomeToastMessage?>(null) }
    LaunchedEffect(message?.id) {
        if (message == null) return@LaunchedEffect
        shown = message
        progress.snapTo(0f)
        progress.animateTo(1f, tween(message.millis.toInt(), easing = LinearEasing))
        shown = null
    }
    val current = shown ?: return
    val text = message?.takeIf { it.id == current.id }?.text ?: current.text
    val p = progress.value
    val inEnd = (340f / current.millis).coerceAtMost(.2f)
    val outStart = 1 - (306f / current.millis).coerceAtMost(.18f)
    val alpha = when { p < inEnd -> p / inEnd; p > outStart -> (1 - p) / (1 - outStart); else -> 1f }
    val rise = when { reduceMotion -> 0f; p < inEnd -> 8 * (1 - SpringCurve.snappy.over(p / inEnd)); p > outStart -> -4 * (p - outStart) / (1 - outStart)
        else -> 0f }
    val scale = if (reduceMotion || p >= inEnd) 1f else lerp(.9f, 1f, SpringCurve.snappy.over(p / inEnd))
    Box(modifier.graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f); translationY = rise * density; scaleX = scale; scaleY = scale }
        .height(34.dp).background(palette.foreground, RoundedCornerShape(17.dp)).padding(horizontal = 16.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
        Text(text, style = HomeType.toast, color = palette.background, maxLines = 1)
    }
}

/** [tag] names a toast that [HomeToasts.retext] may update in place; a retext keeps [id], so the toast keeps its time. */
data class HomeToastMessage(val text: String, val millis: Long = 1_700, val id: Long = System.nanoTime(), val tag: String? = null)

/** Rise-in entrance on Home's first appearance (prototype `enter()`), driven by [elapsed] since it started. */
internal fun Modifier.rise(elapsed: Long, delay: Long, distance: Float, reduceMotion: Boolean): Modifier = graphicsLayer {
    val p = step(elapsed, delay, SpringCurve.snappy, reduceMotion = reduceMotion)
    alpha = p.coerceIn(0f, 1f)
    if (!reduceMotion) translationY = (1 - p) * distance * density
}

/** Collapses its content's height by [fraction] (0 = full, 1 = gone), for nudge and card folds. */
internal fun Modifier.collapse(fraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = (placeable.height * (1 - fraction)).roundToInt().coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, 0) }
}

@Composable
internal fun renewTitle(renew: HomeRules.Renew, unit: com.noahwallet.formatting.BitcoinAmountUnit, locale: java.util.Locale, hidden: Boolean) =
    tr("Renew {amount}", if (hidden) "••••" else BitcoinAmount.format(renew.sats, unit, locale))

@Composable
internal fun renewSubtitle(renew: HomeRules.Renew): String =
    if (renew.hours < 1) tr("Expires in under an hour") else trPlural("Expires in {n} hours", renew.hours.toInt().coerceAtMost(Int.MAX_VALUE))
