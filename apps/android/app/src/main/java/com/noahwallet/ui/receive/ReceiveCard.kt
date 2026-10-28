package com.noahwallet.ui.receive

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahwallet.localization.tr
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeIcons
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.home.Pill
import com.noahwallet.ui.home.SpringCurve
import com.noahwallet.ui.home.StyledQR
import com.noahwallet.ui.home.step
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.send.BlockRow
import com.noahwallet.ui.send.BlockRow.blockStrip
import com.noahwallet.ui.send.SendIcons
import com.noahwallet.ui.send.SendType
import com.noahwallet.ui.send.StageArt
import com.noahwallet.ui.send.StageArt.badge
import com.noahwallet.ui.send.StageArt.icon
import com.noahwallet.ui.send.StageArt.padlock
import com.noahwallet.ui.send.rememberBlockSlide
import kotlinx.coroutines.isActive

/**
 * The arrival sequences (prototype `arrive`), in ms after the payment was seen: the server delivers an Ark coin;
 * Lightning hits the server, which hands you a locked coin your key opens; on-chain waits in the next block.
 * Then the card flips to the amount and back. Reduce Motion paces it as the prototype does (× 0.45).
 */
internal object ReceiveTimeline {
    private fun pace(ms: Long, reduceMotion: Boolean) = if (reduceMotion) (ms * .45f).toLong() else ms
    fun flipAt(rail: ReceiveArrival.Rail, reduceMotion: Boolean) = pace(when (rail) {
        ReceiveArrival.Rail.ark -> 1_250L; ReceiveArrival.Rail.lightning -> 2_760L; ReceiveArrival.Rail.onchain -> 900L
    }, reduceMotion)
    fun backAt(rail: ReceiveArrival.Rail, reduceMotion: Boolean) = flipAt(rail, reduceMotion) + pace(2_900, reduceMotion)
    /** When the card is itself again and (for Ark and Lightning) the request can be renewed. */
    fun end(rail: ReceiveArrival.Rail, reduceMotion: Boolean) = backAt(rail, reduceMotion) + if (reduceMotion) 200 else 700

    /** The line under the card. On-chain hands over to the block row once its wait begins. */
    fun phase(rail: ReceiveArrival.Rail, t: Long, reduceMotion: Boolean): String? {
        fun at(ms: Long) = t >= pace(ms, reduceMotion)
        return when (rail) {
            ReceiveArrival.Rail.ark -> null
            ReceiveArrival.Rail.lightning -> when {
                at(4_360) -> null
                at(2_500) -> "Received"
                at(1_650) -> "Claiming with your key"
                at(850) -> "Ark holds it for you"
                else -> "Incoming over Lightning"
            }
            ReceiveArrival.Rail.onchain -> if (at(900)) null else "Seen on the network"
        }
    }

    /** Haptics between [since] and [now]: light on the delivery and the unlock, success on the flip. True is success. */
    fun haptics(rail: ReceiveArrival.Rail, since: Long, now: Long, reduceMotion: Boolean): List<Boolean> {
        val moments = when (rail) {
            ReceiveArrival.Rail.ark -> listOf(760L to false)
            ReceiveArrival.Rail.lightning -> listOf(850L to false, 2_070L to false)
            ReceiveArrival.Rail.onchain -> emptyList()
        }.map { (at, success) -> pace(at, reduceMotion) to success } + (flipAt(rail, reduceMotion) to true)
        return moments.filter { (at, _) -> at > since && at <= now }.sortedBy { it.first }.map { it.second }
    }

    /** The card's turn (degrees) at [t]: bouncy to the back, a hold, snappy home. */
    fun turn(rail: ReceiveArrival.Rail, t: Long, reduceMotion: Boolean): Float {
        val flip = flipAt(rail, reduceMotion)
        val back = backAt(rail, reduceMotion)
        if (reduceMotion) return if (t >= flip && t < back) 180f else 0f
        return when {
            t < flip -> 0f
            t < back -> 180f * step(t - flip, 0, SpringCurve.bouncy)
            else -> 180f + 180f * step(t - back, 0, SpringCurve.snappy)
        }
    }
}

/** The receive card's front: what the QR encodes and how it reads. */
internal data class CardFace(val payload: String?, val title: String, val caption: String, val band: Pair<String, String?>?, val unavailable: Boolean)

/**
 * The Receive card: always white, the styled QR of the shown format, its name and a caption, the inverted
 * amount band, the orbiting listening light, the arrival overlay and the flip to "+₿X".
 */
@Composable
internal fun ReceiveCard(face: CardFace, arrival: ReceiveArrival?, amount: String?, listening: Boolean, tilt: Float, width: Dp, qrSide: Dp,
    onProfile: () -> Unit, onTap: (() -> Unit)?, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val elapsed by rememberArrivalClock(arrival, reduceMotion)
    val turn = cardTurn(arrival, elapsed, reduceMotion)
    val backFade = backFade(arrival, elapsed, reduceMotion)
    val showBack = if (reduceMotion) backFade > 0f else turn % 360f in 90f..270f
    ArrivalHaptics(arrival, elapsed, reduceMotion)
    val density = LocalDensity.current
    Box(modifier.width(width), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxWidth().graphicsLayer {
            rotationY = (turn + tilt) % 360f
            cameraDistance = 1400 * density.density
        }) {
            CardSurface(onTap.takeIf { !showBack }) {
                // The front stays in layout (it sizes the card); the back draws over it, turned to face you.
                CardFront(face, qrSide, onProfile, Modifier.graphicsLayer { alpha = if (reduceMotion) 1 - backFade else if (showBack) 0f else 1f })
                if (showBack && arrival != null) Back(arrival, amount ?: "", elapsed - ReceiveTimeline.flipAt(arrival.rail, reduceMotion),
                    Modifier.matchParentSize().graphicsLayer { if (reduceMotion) alpha = backFade else rotationY = 180f })
            }
            if (arrival != null && arrival.rail != ReceiveArrival.Rail.onchain && !showBack) ArrivalOverlay(arrival.rail, elapsed, qrSide,
                Modifier.matchParentSize())
        }
        if (listening && !reduceMotion && played(arrival, elapsed)) ListeningLight(Modifier.matchParentSize())
    }
}

/** The white card itself: its shadow (none in dark), border and, while it faces you, the formats tap. */
@Composable
private fun CardSurface(onTap: (() -> Unit)?, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth()
        .shadow(if (palette === NoahTokens.Dark) 0.dp else 14.dp, shape, ambientColor = Color.Black.copy(alpha = .08f),
            spotColor = Color.Black.copy(alpha = .08f))
        .clip(shape).background(NoahTokens.Light.qrCard).border(1.dp, Color.Black.copy(alpha = .07f), shape)
        .then(if (onTap != null) Modifier.clickable(role = Role.Button, onClickLabel = tr("Ways to pay you"), onClick = onTap) else Modifier)
        .testTag("receive-card"), content = content)
}

/** Ticks each frame while an arrival plays, from its start until its end. */
@Composable
private fun rememberArrivalClock(arrival: ReceiveArrival?, reduceMotion: Boolean): State<Long> =
    produceState(arrival?.let { SystemClock.elapsedRealtime() - it.startedAt } ?: Long.MAX_VALUE / 4, arrival?.startedAt) {
        val started = arrival?.startedAt ?: return@produceState
        while (isActive) {
            withFrameMillis { }
            value = SystemClock.elapsedRealtime() - started
            if (value > ReceiveTimeline.end(arrival.rail, reduceMotion) + 200) break
        }
    }

/** The card's turn to its back and round again while an arrival plays; none under Reduce Motion. */
private fun cardTurn(arrival: ReceiveArrival?, elapsed: Long, reduceMotion: Boolean): Float =
    if (reduceMotion) 0f else arrival?.let { ReceiveTimeline.turn(it.rail, elapsed, false) } ?: 0f

/** Listening again once an arrival has played (an on-chain one stays for its block row). */
private fun played(arrival: ReceiveArrival?, elapsed: Long) = arrival == null || elapsed > ReceiveTimeline.end(arrival.rail, false)

/** Reduce Motion: no turn; the back fades in for 180 ms and out again. */
private fun backFade(arrival: ReceiveArrival?, elapsed: Long, reduceMotion: Boolean): Float {
    if (!reduceMotion || arrival == null) return 0f
    val flip = ReceiveTimeline.flipAt(arrival.rail, true)
    val back = ReceiveTimeline.backAt(arrival.rail, true)
    return if (elapsed < back) ((elapsed - flip) / 180f).coerceIn(0f, 1f) else (1 - (elapsed - back) / 180f).coerceIn(0f, 1f)
}

/** The prototype's arrival haptics; restored with the screen so a pose change never repeats one. */
@Composable
private fun ArrivalHaptics(arrival: ReceiveArrival?, elapsed: Long, reduceMotion: Boolean) {
    val haptics = com.noahwallet.ui.onboarding.rememberHaptics()
    var hapticsAt by androidx.compose.runtime.saveable.rememberSaveable(arrival?.startedAt) { mutableLongStateOf(if (elapsed > 300) elapsed else -1) }
    LaunchedEffect(arrival?.startedAt, elapsed / 100) {
        val rail = arrival?.rail ?: return@LaunchedEffect
        for (kind in ReceiveTimeline.haptics(rail, hapticsAt, elapsed, reduceMotion)) if (kind) haptics.success() else haptics.light()
        hapticsAt = elapsed
    }
}

/** The card's front: the QR, its name and caption (a spinner while there's no payload, or setup's prompt), then the band. */
@Composable
private fun CardFront(face: CardFace, qrSide: Dp, onProfile: () -> Unit, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val ink = NoahTokens.Light.qrInk
    Column(Modifier.fillMaxWidth().then(modifier)
        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = if (face.band != null) 0.dp else 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            face.unavailable -> Column(Modifier.fillMaxWidth().height(qrSide + 50.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                Text(tr("Lightning address unavailable"), style = NoahTokens.Typography.sheetTitle, color = ink)
                Text(tr("Finish setting up your Lightning address before sharing your QR."), style = HomeType.line, color = Color(0xFF6B6B6B))
                Pill(tr("Open Profile"), height = 44.dp, container = ink, content = Color.White, onClick = onProfile)
            }
            face.payload == null -> Box(Modifier.size(qrSide), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = ink, strokeWidth = 2.5.dp)
            }
            else -> {
                Box(Modifier.size(qrSide).semantics { contentDescription = face.title }.testTag("receive-qr")) {
                    StyledQR(face.payload, Modifier.fillMaxSize(), reveal = true, reduceMotion = reduceMotion,
                        correction = ReceiveFormats.correction(face.payload))
                }
                Text(face.title, Modifier.padding(top = 12.dp), style = HomeType.qrName, color = ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(face.caption, Modifier.padding(top = 2.dp), style = HomeType.small, color = Color(0xFF6B6B6B), textAlign = TextAlign.Center)
            }
        }
        face.band?.let { (sats, fiat) -> Band(sats, fiat) }
    }
}

/** The inverted band (prototype `.band`): the amount and its fiat value, growing in from the card's foot. */
@Composable
private fun Band(sats: String, fiat: String?) {
    val reduceMotion = LocalReduceMotion.current
    val rise = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, if (reduceMotion) tween(180) else NoahTokens.Motion.bouncy()) }
    Row(Modifier.padding(top = 14.dp).layout { measurable, constraints ->
        // Bleed to the card's edges (the column pads 22 dp each side).
        val bleed = 22.dp.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * bleed, maxWidth = constraints.maxWidth + 2 * bleed))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
    }.graphicsLayer { alpha = rise.value.coerceIn(0f, 1f); if (!reduceMotion) translationY = 30 * (1 - rise.value) * density }
        .background(NoahTokens.Light.qrInk).padding(horizontal = 22.dp, vertical = 14.dp).testTag("receive-band"),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(sats, style = SendType.summary.copy(fontSize = 24.sp, lineHeight = 24.sp), color = Color.White, maxLines = 1)
        fiat?.let { Text(it, style = HomeType.line.copy(fontSize = 14.sp), color = Color(0xFFBDBDBD), maxLines = 1) }
    }
}

/** The back: the orange ring and check, "+₿X" and the rail (prototype `.back`). */
@Composable
private fun Back(arrival: ReceiveArrival, amount: String, t: Long, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val ink = NoahTokens.Light.qrInk
    val ring = if (reduceMotion) 1f else step(t, 250, SpringCurve.soft, 650)
    val check = if (reduceMotion) 1f else step(t, 650, SpringCurve.soft, 380)
    Column(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Canvas(Modifier.size(76.dp)) {
            val unit = size.width / 84f
            drawArc(NoahTokens.Light.orange, -90f, 360f * ring.coerceIn(0f, 1f), false, Offset(2 * unit, 2 * unit), Size(80 * unit, 80 * unit),
                style = Stroke(2.5f * unit))
            val path = Path().apply { moveTo(27 * unit, 43 * unit); relativeLineTo(10 * unit, 10 * unit); relativeLineTo(21 * unit, -22 * unit) }
            drawPath(StageArt.segment(path, 0f, PathMeasure().apply { setPath(path, false) }.length * check.coerceIn(0f, 1f)), ink,
                style = Stroke(3.2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Text("+$amount", style = SendType.big.copy(fontSize = 44.sp, lineHeight = 46.sp), color = ink, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(when (arrival.rail) { ReceiveArrival.Rail.ark -> SendIcons.ark; ReceiveArrival.Rail.lightning -> HomeIcons.lightning
                ReceiveArrival.Rail.onchain -> HomeIcons.chain }),
                null, Modifier.size(16.dp), tint = Color(0xFF6B6B6B))
            Text(when (arrival.rail) {
                ReceiveArrival.Rail.ark -> tr("via Ark · just now")
                ReceiveArrival.Rail.lightning -> tr("via Lightning · just now")
                ReceiveArrival.Rail.onchain -> tr("On-chain · waiting for a block")
            }, style = SendType.phase, color = Color(0xFF6B6B6B))
        }
    }
}

/**
 * The arrival over the card (prototype `arrive`): the Ark server above it delivers a coin into the QR's centre
 * (Ark), or holds a Lightning payment locked to your secret until your key, rising from below, opens it.
 */
@Composable
private fun ArrivalOverlay(rail: ReceiveArrival.Rail, t: Long, qrSide: Dp, modifier: Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val glyph = remember(density) { measurer.measure("₿",
        TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.SemiBold, fontSize = with(density) { (27f / (density.density * fontScale)).sp })) }
    Canvas(modifier) {
        val k = density.density
        scale(k, k, Offset.Zero) {
            with(ArrivalFrame(rail, t, reduceMotion, size.width / k, qrSide)) {
                val serverIn = at(0, curve = SpringCurve.bouncy)
                val serverOut = when (rail) { ReceiveArrival.Rail.ark -> at(380, 250, SpringCurve.soft); else -> at(2_500, curve = SpringCurve.soft) }
                    .coerceIn(0f, 1f)
                badge(false, Offset(cx, sv), serverIn.coerceIn(0f, 1f) * (1 - serverOut), if (reduceMotion) 1f else StageArt.lerp(.4f, 1f, serverIn), palette)
                coin(glyph)
                when (rail) {
                    ReceiveArrival.Rail.ark -> ripple()
                    ReceiveArrival.Rail.lightning -> { bolt(); key() }
                    ReceiveArrival.Rail.onchain -> Unit
                }
            }
        }
    }
}

/** One frame of the arrival over a [qrSide] card [w] wide, [t] ms in: phase progress and the overlay's fixed points. */
private class ArrivalFrame(val rail: ReceiveArrival.Rail, val t: Long, val reduceMotion: Boolean, val w: Float, qrSide: Dp) {
    val cx = w / 2
    val cy = 22f + qrSide.value / 2
    val sv = -34f
    private val ink = NoahTokens.Light.qrInk

    fun at(ms: Long, delay: Long = 0, curve: SpringCurve = SpringCurve.snappy, duration: Long? = null): Float {
        val start = if (reduceMotion) (ms * .45f).toLong() else ms
        return if (t < start) 0f else step(t - start, delay, curve, duration, reduceMotion)
    }
    fun travel(v: Float) = if (reduceMotion) (if (v > 0f) 1f else 0f) else v

    /** The coin dropping from the server into the QR's centre; a Lightning one carries its padlock. */
    fun DrawScope.coin(glyph: TextLayoutResult) {
        val drop = at(if (rail == ReceiveArrival.Rail.ark) 380 else 850, curve = SpringCurve.bouncy)
        val coinOut = at(if (rail == ReceiveArrival.Rail.ark) 1_250 else 2_760).coerceIn(0f, 1f)
        val coinY = StageArt.lerp(sv, cy, travel(drop))
        val coinScale = if (reduceMotion) 1f else StageArt.lerp(.4f, 1f, drop)
        val coinAlpha = drop.coerceIn(0f, 1f) * (1 - coinOut)
        if (coinAlpha <= 0f) return
        scale(coinScale, coinScale, Offset(cx, coinY)) {
            drawCircle(ink, 26f, Offset(cx, coinY), alpha = coinAlpha)
            drawText(glyph, Color.White, Offset(cx - glyph.size.width / 2f, coinY - glyph.size.height / 2f), alpha = coinAlpha)
            if (rail == ReceiveArrival.Rail.lightning) {
                val open = at(1_650, 420, SpringCurve.bouncy)
                val lockOut = at(2_500).coerceIn(0f, 1f)
                padlock(Offset(cx, coinY - 25), coinAlpha * (1 - lockOut), open, ink, Color.White)
            }
        }
    }

    fun DrawScope.ripple() {
        val ripple = at(760, curve = SpringCurve.soft)
        if (ripple > 0f && ripple < 1f) drawCircle(NoahTokens.Light.orange, 28f * StageArt.lerp(1f, 2.6f, ripple), Offset(cx, cy),
            alpha = 1 - ripple, style = Stroke(2.5f))
    }

    /** The bolt hits the server. */
    fun DrawScope.bolt() {
        val start = if (reduceMotion) 112L else 250L
        if (reduceMotion || t < start || t >= start + 620) return
        val q = StageArt.cubic(.4f, 0f, .2f, 1f).transform((t - start) / 620f)
        val bolt = Path().apply { moveTo(w + 40, sv - 60); lineTo(cx + 44, sv - 14); lineTo(cx + 52, sv - 2); lineTo(cx + 16, sv) }
        drawPath(StageArt.segment(bolt, 480 * q - 240, 480 * q), NoahTokens.Light.orange,
            style = Stroke(3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /** Your key rises from below and opens the lock. */
    fun DrawScope.key() {
        val keyStart = if (reduceMotion) (1_650 * .45f).toLong() else 1_650L
        if (t < keyStart || t >= keyStart + (if (reduceMotion) 190 else 420)) return
        val rise = travel(at(1_650))
        icon(StageArt.key, Offset(cx, cy + 170 - 200 * rise), 24f, NoahTokens.Light.orange, 2.4f, at(1_650).coerceIn(0f, 1f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawText(text: androidx.compose.ui.text.TextLayoutResult, color: Color, topLeft: Offset,
    alpha: Float) =
    drawText(text, color = color, topLeft = topLeft, alpha = alpha.coerceIn(0f, 1f))

/** A thin orange segment slowly circling the card while it can be paid (7 s a lap). */
@Composable
private fun ListeningLight(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "listening")
    val lap by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "lap")
    val orange = LocalPalette.current.orange
    Canvas(modifier) {
        val inset = -3.dp.toPx() + 1.25.dp.toPx()
        val path = Path().apply { addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(31.dp.toPx()))) }
        val length = PathMeasure().apply { setPath(path, false) }.length
        val segment = 46.dp.toPx()
        val from = lap * length
        drawPath(StageArt.segment(path, from, from + segment), orange, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        if (from + segment > length) drawPath(StageArt.segment(path, 0f, from + segment - length), orange, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The block row under the card for an on-chain arrival: your transaction in the next block, then its block. */
@Composable
internal fun ReceiveBlockRow(arrival: ReceiveArrival?, tip: Long?, confirmedAt: Long?, width: Dp, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val format = remember(locale) { java.text.NumberFormat.getIntegerInstance(locale) }
    val nextLabel = tr("next")
    val (shownTip, slide, pop) = rememberBlockSlide(tip, reduceMotion)
    val elapsed by rememberBlockRowClock(arrival, confirmedAt, reduceMotion)
    val visible = arrival?.rail == ReceiveArrival.Rail.onchain && shownTip != null
    ConfirmedHaptic(arrival, confirmedAt)
    val fade = if (visible) step(elapsed, 0, SpringCurve.soft, reduceMotion = reduceMotion).coerceIn(0f, 1f) else 0f
    val style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium,
        fontSize = with(density) { (10.5f / (density.density * fontScale)).sp })
    val next = remember(nextLabel, density) { measurer.measure(nextLabel, style) }
    Canvas(modifier.width(width).height(58.dp).clearAndSetSemantics { }) {
        if (!visible) return@Canvas
        val k = size.width / 300f
        scale(k, k, Offset.Zero) {
            val dot = if (reduceMotion) 1f else step(elapsed, 200, SpringCurve.bouncy).coerceIn(0f, 1f)
            val blink = BlockRow.blink(elapsed - 900, elapsed > 900 && confirmedAt == null, reduceMotion)
            val frame = BlockRow.Frame(shownTip, confirmedAt, dot, slide, pop, blink, fade)
            blockStrip(Offset(72f, 6f), 30f, frame, palette, BlockRow.Labels({ height -> measurer.measure(format.format(height), style) }, next))
        }
    }
}

/** Ticks while the row moves or the next block blinks; still once the transaction is in its block. */
@Composable
private fun rememberBlockRowClock(arrival: ReceiveArrival?, confirmedAt: Long?, reduceMotion: Boolean): State<Long> =
    produceState(0L, arrival?.startedAt, confirmedAt != null, reduceMotion) {
        val started = arrival?.startedAt ?: return@produceState
        while (isActive) {
            withFrameMillis { }
            value = SystemClock.elapsedRealtime() - started
            if (value > 1_500 && (confirmedAt != null || reduceMotion)) break
        }
    }

/** A light tap when the arrival's block is mined while you watch. */
@Composable
private fun ConfirmedHaptic(arrival: ReceiveArrival?, confirmedAt: Long?) {
    val haptics = com.noahwallet.ui.onboarding.rememberHaptics()
    var wasConfirmed by remember(arrival?.startedAt) { mutableStateOf(confirmedAt != null) }
    LaunchedEffect(confirmedAt) { if (confirmedAt != null && !wasConfirmed) { wasConfirmed = true; haptics.light() } }
}
