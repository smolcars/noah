package com.noahwallet.onchain

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.send.StageArt
import com.noahwallet.ui.send.rememberBlockSlide
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The exit picture's rest pose for one bark state (exit-v1-notes.md, the mapping's last column): your branch from the
 * round down to your VTXO, the transaction being sent, which are mined and where, and the timelock. The picture only
 * moves when this changes; nothing here is timed.
 */
internal data class ExitPose(
    val n: Int, val phase: ExitStage.Phase, val attention: ExitStage.Attention? = null, val current: Int = 0,
    /** [current] sits in the next block (bark has it ready); [fee] its fee child is attached. */
    val dropped: Boolean = false, val fee: Boolean = false,
    /** Per transaction, root first: its block once mined, whether another wallet paid its fee, and its fee child. */
    val mined: List<Long?> = List(n) { null }, val theirs: List<Boolean> = List(n) { false }, val paid: List<Boolean> = List(n) { false },
    /** Per transaction: how many other branches leave it, and which of its outputs is yours (the faint stubs). */
    val branches: List<Int> = List(n) { 0 }, val ours: List<Int> = List(n) { 0 },
    val tip: Long = 0, val lockedFrom: Long? = null, val claimableHeight: Long? = null, val rate: Long? = null, val claimedBlock: Long? = null,
) {
    companion object {
        /** A round VTXO's path before anything is sent (the warning's and review's picture): four transactions, radix 4. */
        fun sample(n: Int = 4, tip: Long = 0) = ExitPose(n.coerceAtLeast(1), ExitStage.Phase.publish, branches = List(n) { if (it < n - 1) 3 else 0 },
            ours = List(n) { listOf(2, 2, 1).getOrElse(it) { 0 } }, tip = tip)

        fun of(exit: ExitModel, stage: ExitStage, tip: Long): ExitPose {
            val n = stage.total.coerceAtLeast(1)
            val txs = exit.details.transactions ?: (exit.historyDetails).lastOrNull { !it.transactions.isNullOrEmpty() }?.transactions.orEmpty()
            val done = stage.phase in setOf(ExitStage.Phase.wait, ExitStage.Phase.claimable, ExitStage.Phase.claiming, ExitStage.Phase.claimed)
            val mined = List(n) { i -> txs.getOrNull(i)?.status?.block?.height ?: if (done) stage.lockedFrom else null }
            val theirs = List(n) { i -> (txs.getOrNull(i)?.status?.origin ?: exit.packages.getOrNull(i)?.childOrigin).let { it != null && it != "wallet" } }
            val paid = List(n) { i -> txs.getOrNull(i)?.status?.child_txid != null || exit.packages.getOrNull(i)?.childTxid != null }
            val branches = List(n) { i -> if (i == n - 1) 0 else ((exit.packages.getOrNull(i)?.outputs ?: 0) - 2).coerceAtLeast(0) }
            val ours = List(n) { i -> (exit.packages.getOrNull(i + 1)?.spends ?: 0L).toInt().coerceIn(0, branches[i]) }
            val kind = stage.currentKind
            val parked = stage.attention == ExitStage.Attention.needsFunds
            return ExitPose(n, stage.phase, stage.attention, stage.current.coerceAtMost(n - 1),
                dropped = !parked && (kind == "awaiting-cpfp-broadcast" || kind == "awaiting-confirmation"), fee = kind == "awaiting-confirmation",
                mined = mined, theirs = theirs, paid = paid, branches = branches, ours = ours, tip = tip,
                lockedFrom = stage.lockedFrom ?: txs.mapNotNull { it.status.block?.height }.maxOrNull(),
                claimableHeight = stage.claimableHeight, rate = stage.rate, claimedBlock = stage.claimedBlock)
        }
    }
    val zoomed get() = phase in setOf(ExitStage.Phase.wait, ExitStage.Phase.claimable, ExitStage.Phase.claiming, ExitStage.Phase.claimed)
    val claiming get() = phase == ExitStage.Phase.claiming || phase == ExitStage.Phase.claimed
}

/** Each move's progress, 0 → 1 (descend counts mined transactions). At rest every value is its pose's target. */
internal data class PictureMotion(val lift: Float, val unroll: Float, val descend: Float, val drop: Float, val fee: Float, val bump: Float,
                                  val zoom: Float, val open: Float, val claim: Float, val fin: Float, val cancel: Float,
                                  val slide: Float = 1f, val pop: Float = 1f, val blink: Float = 1f, val ripple: Float = -1f) {
    companion object {
        fun rest(p: ExitPose) = PictureMotion(lift = 1f, unroll = if (p.phase == ExitStage.Phase.lift) 0f else 1f,
            descend = p.mined.count { it != null }.toFloat(), drop = if (p.dropped) 1f else 0f, fee = if (p.fee) 1f else 0f,
            bump = if (p.attention == ExitStage.Attention.feesRose) 1f else 0f, zoom = if (p.zoomed) 1f else 0f,
            open = if (p.phase in setOf(ExitStage.Phase.claimable, ExitStage.Phase.claiming, ExitStage.Phase.claimed)) 1f else 0f,
            claim = if (p.claiming) 1f else 0f, fin = if (p.phase == ExitStage.Phase.claimed) 1f else 0f,
            cancel = if (p.phase == ExitStage.Phase.canceled) 1f else 0f)
    }
}

/**
 * The exit in progress (exit.md, the hero): the Ark line with your coin and the server, your branch with faint stubs for
 * everyone else's, the block row with "next", and your on-chain wallet, which pays each fee. It animates between rest poses
 * with the house springs; under Reduce Motion every move is a 180 ms fade to the next rest pose. [balance] is the on-chain
 * wallet's label; [destination] a claim's address when it isn't this wallet.
 */
@Composable
internal fun ExitPicture(pose: ExitPose, description: String, modifier: Modifier = Modifier, balance: String? = null,
                         destination: String? = null, showArk: Boolean = true) {
    val reduceMotion = LocalReduceMotion.current
    if (reduceMotion) Crossfade(pose, modifier.semantics { contentDescription = description }, animationSpec = tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS), label = "exit") { p ->
        PictureCanvas(p, PictureMotion.rest(p), balance, destination, showArk, Modifier.fillMaxSize())
    } else {
        val motion = rememberPictureMotion(pose)
        PictureCanvas(pose, motion, balance, destination, showArk, modifier.semantics { contentDescription = description })
    }
}

@Composable
private fun rememberPictureMotion(p: ExitPose): PictureMotion {
    val rest = PictureMotion.rest(p)
    // Arriving on a page shows the move into its state once: lifting off Ark, or the path unrolling.
    val lift = remember { Animatable(if (p.phase == ExitStage.Phase.lift) 0f else 1f) }
    val unroll = remember { Animatable(if (p.phase == ExitStage.Phase.publish) 0f else rest.unroll) }
    val descend = remember { Animatable(rest.descend) }
    val drop = remember(p.current) { Animatable(if (p.current == 0 || !p.dropped) rest.drop else 0f) }
    val fee = remember(p.current) { Animatable(rest.fee) }
    val bump = remember(p.current) { Animatable(rest.bump) }
    val zoom = remember { Animatable(rest.zoom) }
    val open = remember { Animatable(rest.open) }
    val claim = remember { Animatable(rest.claim) }
    val fin = remember { Animatable(rest.fin) }
    val cancel = remember { Animatable(rest.cancel) }
    val unrollMillis = ((.3f * p.n + .45f) * 1000).roundToInt()
    LaunchedEffect(rest) {
        launch { lift.animateTo(rest.lift, NoahTokens.Motion.snappy()) }
        launch { unroll.animateTo(rest.unroll, if (rest.unroll > unroll.value) tween(unrollMillis, easing = LinearEasing) else NoahTokens.Motion.snappy()) }
        launch { descend.animateTo(rest.descend, NoahTokens.Motion.snappy()) }
        launch { drop.animateTo(rest.drop, NoahTokens.Motion.bouncy()) }
        launch { fee.animateTo(rest.fee, NoahTokens.Motion.soft()) }
        launch { bump.animateTo(rest.bump, NoahTokens.Motion.soft()) }
        launch { zoom.animateTo(rest.zoom, NoahTokens.Motion.snappy()) }
        launch { open.animateTo(rest.open, NoahTokens.Motion.bouncy()) }
        launch { claim.animateTo(rest.claim, NoahTokens.Motion.bouncy()) }
        launch { fin.animateTo(rest.fin, NoahTokens.Motion.snappy()) }
        launch { cancel.animateTo(rest.cancel, NoahTokens.Motion.soft()) }
    }
    val (_, slide, pop) = rememberBlockSlide(p.tip, false)
    // The "next" block blinks while it holds your transaction (650 ms each way); claimable rings ripple out.
    val clock = rememberInfiniteTransition(label = "exit")
    val beat by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(1_300, easing = LinearEasing)), label = "blink")
    val ripple by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2_200, easing = LinearEasing)), label = "ripple")
    return PictureMotion(lift.value, unroll.value, descend.value, drop.value, fee.value, bump.value, zoom.value, open.value, claim.value,
        fin.value, cancel.value, slide, pop, (.55f + .45f * cos(2 * PI * beat)).toFloat(), if (p.phase == ExitStage.Phase.claimable) ripple else -1f)
}

private fun clamp(x: Float, a: Float = 0f, b: Float = 1f) = x.coerceIn(a, b)
private fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u

/** Where a transaction is drawn: hanging in your branch, falling into "next", in "next", or in a mined block. */
private enum class Place { branch, fall, next, block, ark }
private class Node(val x: Float, val y: Float, val place: Place, val u: Float = 1f)

@Composable
private fun PictureCanvas(p: ExitPose, m: PictureMotion, balance: String?, destination: String?, showArk: Boolean, modifier: Modifier) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Text is measured in picture units: the canvas is scaled so one unit is one prototype pixel (dp).
    fun units(value: Float) = with(density) { (value / (density.density * fontScale)).sp }
    val grotesk = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium, fontSize = units(10.5f), fontFeatureSettings = "tnum")
    val words = TextStyle(fontWeight = FontWeight.Normal, fontSize = units(10.5f))
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val number = remember(locale) { java.text.NumberFormat.getIntegerInstance(locale) }
    val labels = mutableMapOf<String, TextLayoutResult>()
    fun text(value: String, style: TextStyle) = labels.getOrPut(value + "|" + style.hashCode()) { measurer.measure(value, style) }
    val round = tr("round"); val next = tr("next"); val ark = tr("Ark")
    val rateLabel = p.rate?.let { tr("{rate} sat/vB", it.toString()) }
    val span = p.claimableHeight?.let { end -> p.lockedFrom?.let { end - it } }
    val spanLabel = span?.let { trPlural("{n} blocks", it) }
    Canvas(modifier) {
        val k = density.density
        val w = size.width / k; val full = size.height / k; val h = min(full, 430f)
        // Too small to hold the path, the row and the bar (a pane mid-resize): draw nothing rather than overlap.
        if (w < 200f || h < 160f) return@Canvas
        translate(0f, (full - h) / 2 * k) { scale(k, k, Offset.Zero) {
            fun label(value: String, x: Float, y: Float, color: Color, alpha: Float = 1f, style: TextStyle = grotesk, align: Int = 0) {
                if (alpha <= 0f) return
                val t = text(value, style)
                val left = when (align) { -1 -> x; 1 -> x - t.size.width; else -> x - t.size.width / 2f }
                drawText(t, color = color, alpha = clamp(alpha), topLeft = Offset(left, y - t.size.height * .78f))
            }
            val n = p.n
            val s = clamp(w * .098f, 32f, 44f).roundToInt().toFloat(); val st = s + 12
            val yRowT = h - s - 30; val yRowC = yRowT + s / 2
            val nx = (w / 2).roundToInt().toFloat()
            val yRoot = yRowT - 38
            val top = clamp(h * .16f, 40f, 50f)
            val gap = if (n > 1) min(64f, (yRoot - top) / (n - 1)) else 0f
            val yUp = yRoot - (n - 1) * gap; val yArk = yUp + 14
            val rx = 8 + s / 2; val wx = w - 24
            val rC = clamp(w * .045f, 14f, 19f).roundToInt().toFloat(); val rN = 6f
            val lockY = (h * .38f).roundToInt().toFloat()
            val liftU = m.lift * (1 - m.cancel)
            val pre = m.claim > 0f
            val needsFunds = p.attention == ExitStage.Attention.needsFunds
            val rm = m.ripple < 0f && m.blink == 1f

            // Ark: a faint line, the server (greyed once you leave: an exit doesn't need it) and your coin.
            val arkA = (1 - m.zoom) * (if (pre) 0f else 1f)
            drawLine(palette.hairline, Offset(16f, yArk), Offset(w - 48, yArk), 1f, alpha = arkA * (1 - .45f * liftU))
            label(ark, 16f, yArk - 9, palette.muted, arkA, words.copy(fontSize = units(11f)), -1)
            if (showArk) {
                badge(Offset(w - 26, yArk), 15f, StageArt.server, palette.foreground, palette.hairline, palette.background, arkA * (1 - liftU * .62f), dashed = liftU > .5f, dots = true)
                if (liftU < 1) drawLine(palette.muted, Offset(w - 41, yArk), Offset(nx + rC + 4, yArk), 1f, alpha = (1 - liftU) * .5f * arkA)
            }

            // The block row, mempool.space style: the round's block, the latest three, then the blinking "next".
            val holds = (p.dropped && p.mined.getOrNull(p.current) == null && !pre) || p.phase == ExitStage.Phase.claiming
            val shift = (1 - m.slide) * st
            fun blockX(height: Long) = nx - (p.tip - height + 1) * st + shift
            val rowA = if (pre) m.claim else 1 - m.zoom
            fun fade(x: Float) = if (pre) clamp((x - s / 2 - 10) / (st * .8f)) else clamp((x - rx - s / 2 - 26) / st)
            if (rowA > 0f) {
                if (!pre) {
                    drawRoundRect(palette.foreground, Offset(rx - s / 2, yRowT), Size(s, s), CornerRadius(7f), alpha = rowA * .38f)
                    label(round, rx, yRowT + s + 14, palette.muted, rowA, words)
                    for (i in 0 until 3) drawCircle(palette.muted, 1.4f, Offset(rx + s / 2 + 9 + i * 6, yRowC), alpha = rowA)
                }
                // Without a tip (a sample path) the blocks are drawn unlabelled.
                val labelled = p.tip > 0
                for (i in -1..2) {
                    val height = p.tip - i
                    if (labelled && height < 0) continue
                    val x = blockX(height)
                    val a = fade(x) * rowA * if (i < 0) 1 - m.slide else 1f
                    if (a <= 0f) continue
                    val ours = height in p.mined || height == p.claimedBlock
                    val popScale = if (i == 0) 1 + .2f * (1 - m.pop) else 1f
                    scale(popScale, popScale, Offset(x, yRowC)) {
                        drawRoundRect(palette.foreground, Offset(x - s / 2, yRowT), Size(s, s), CornerRadius(7f), alpha = a * if (ours) 1f else .55f + .15f * (2 - i).coerceIn(0, 2))
                    }
                    if (labelled) label(number.format(height), x, yRowT + s + 14, if (ours && i == 0) palette.foreground else palette.muted, a)
                }
                val nextX = nx + shift
                drawRoundRect(palette.foreground, Offset(nextX - s / 2, yRowT), Size(s, s), CornerRadius(6f), alpha = rowA * (if (holds) m.blink else 1f),
                    style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
                label(next, nextX, yRowT + s + 14, palette.muted, rowA, words)
            }

            // Your branch: transactions not yet sent hang above "next"; the bottom one drops in, gets its fee, is mined.
            fun home(j: Int) = Offset(nx, yRoot - (j - m.descend) * gap)
            fun node(j: Int): Node {
                val minedAt = p.mined.getOrNull(j)
                if (minedAt != null) return Node(blockX(minedAt), yRowC, Place.block)
                val b = home(j)
                if (j == p.current && (p.dropped || m.drop > 0f)) {
                    val y = lerp(b.y, yRowC, m.drop)
                    return Node(nx + shift * m.drop, y, if (m.drop > .98f) Place.next else Place.fall, m.drop)
                }
                if (j == n - 1) return Node(b.x, lerp(lerp(yArk, b.y, liftU), yArk, m.cancel), Place.ark)
                return Node(b.x, b.y, Place.branch)
            }
            val unrollSeconds = .3f * n + .45f
            fun segment(k: Int) = clamp((m.unroll * unrollSeconds - k * .3f) / .3f)
            fun shown(j: Int) = clamp((m.unroll * unrollSeconds - ((n - 2 - j) * .3f + .25f)) / .35f) * if (m.unroll >= 1f) 1f else 1.15f - .15f * m.unroll
            val la = 1 - m.zoom; val ca = 1 - m.cancel * .65f
            if (!pre && m.zoom < 1f) {
                for (j in 0 until n - 1) {
                    val a = node(j); val b = node(j + 1)
                    if (b.place == Place.block || b.place == Place.next) continue
                    val u = segment(n - 2 - j)
                    if (u <= 0f) continue
                    val topEnd = Offset(b.x, b.y + if (j + 1 == n - 1) rC else rN)
                    val bottom = if (a.place == Place.branch || a.place == Place.fall) Offset(a.x, a.y - rN) else Offset(a.x, yRowT)
                    link(topEnd, bottom, u, palette.orange, 1.6f, la * ca)
                }
                val root = node(0)
                val tether = clamp((m.unroll * unrollSeconds - (n - 1) * .3f) / .45f)
                if (tether > 0f && root.place == Place.branch && n > 1) link(Offset(root.x, root.y + rN), Offset(rx, yRowT), tether, palette.orange, 1.4f, la * .9f * ca)
                // Everyone else's branches: faint stubs, only around your path.
                for (j in 0 until n - 1) {
                    val a = node(j)
                    if (a.place == Place.block || a.place == Place.next) continue
                    val sa = (if (a.place == Place.fall) 1 - clamp(a.u * 2) else 1f) * clamp(shown(j)) * la * ca
                    if (sa <= 0f) continue
                    for (b in 0..p.branches[j]) {
                        if (b == p.ours[j]) continue
                        val stub = Offset(a.x + (b - p.ours[j]) * min(22f, w * .06f), a.y - gap * .6f)
                        link(stub, Offset(a.x, a.y - rN), 1f, palette.foreground, 1f, .2f * sa)
                        drawCircle(palette.foreground, 2f, stub, alpha = .32f * sa)
                    }
                }
                for (j in 0 until n - 1) {
                    val a = node(j); val sh = shown(j)
                    if (a.place == Place.branch && sh <= 0f) continue
                    val alpha = la * ca
                    if (a.place == Place.block) drawCircle(palette.background, 4.6f, Offset(a.x - 4, a.y), alpha = alpha * fade(a.x))
                    else {
                        val r = rN * if (a.place == Place.branch) clamp(sh, 0f, 1.3f) else 1f
                        val c = Offset(a.x - if (a.place == Place.next) 4 else 0, a.y)
                        drawCircle(palette.background, r, c, alpha = alpha)
                        drawCircle(palette.foreground, r, c, alpha = alpha, style = Stroke(1.7f))
                    }
                    if (a.place != Place.branch) fee(j, a.x - 4, a.y, p, m, wx, yRowC, yRowT, palette, alpha * if (a.place == Place.block) fade(a.x) else 1f, rateLabel) { v, x, y, al ->
                        label(v, x, y, palette.foreground, al) }
                    if (needsFunds && j == p.current) drawCircle(palette.failure, rN + 6, Offset(a.x, a.y), alpha = (.6f + .4f * m.blink) * alpha,
                        style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
                }
                // Your coin: the last transaction's output.
                val c = node(n - 1)
                val inRow = c.place == Place.block || c.place == Place.next || c.place == Place.fall
                var cx = c.x; var cy = c.y
                var r = if (inRow) lerp(rC * 1.1f, s * .3f, clamp(c.u)) else rC * (1 + .1f * liftU)
                if (c.place == Place.block) { cx = lerp(cx, nx, m.zoom); cy = lerp(cy, lockY, m.zoom); r = lerp(s * .3f, rC * 2.3f, m.zoom) }
                coin(Offset(cx, cy), r, if (c.place == Place.block && m.zoom < .5f) palette.foreground else palette.soft, palette.orange, 1f)
                if (c.place == Place.block || c.place == Place.next) fee(n - 1, cx, cy, p, m, wx, yRowC, yRowT, palette, 1f, rateLabel, s * .32f, s * .27f) { v, x, y, al ->
                    label(v, x, y, palette.foreground, al) }
                if (needsFunds && p.current == n - 1) drawCircle(palette.failure, r + 6, Offset(cx, cy), alpha = .6f + .4f * m.blink,
                    style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
            }

            // The timelock: your coin, locked, and a bar counting the blocks until it can be claimed.
            val radius = rC * 2.3f
            if (m.zoom > 0f && m.claim < 1f && p.lockedFrom != null && p.claimableHeight != null) {
                val a = m.zoom * (1 - m.claim)
                if (m.zoom >= 1f && !pre) coin(Offset(nx, lockY), radius, palette.soft, palette.orange, a)
                if (m.open > 0f && !pre) {
                    if (!rm) for (i in 0 until 2) {
                        val q = ((m.ripple - i * .23f) % 1f + 1f) % 1f
                        drawCircle(palette.orange, radius + 4 + q * 30, Offset(nx, lockY), alpha = a * .55f * (1 - q), style = Stroke(1.5f))
                    } else drawCircle(palette.orange, radius + 7, Offset(nx, lockY), alpha = a * .35f * m.open, style = Stroke(1.5f))
                }
                if (!pre) StageArt.run { padlock(Offset(nx + radius * .72f, lockY + radius * .62f), a, clamp(m.open), palette.foreground, palette.background) }
                val bx0 = 24f; val bx1 = w - 24; val by = yRowC - 2; val bw = bx1 - bx0
                val span = (p.claimableHeight - p.lockedFrom).coerceAtLeast(1)
                val u = clamp((p.tip - p.lockedFrom).toFloat() / span)
                drawRoundRect(palette.soft, Offset(bx0, by), Size(bw, 6f), CornerRadius(3f), alpha = a)
                if (u > 0f) drawRoundRect(palette.foreground, Offset(bx0, by), Size(maxOf(6f, bw * u), 6f), CornerRadius(3f), alpha = a)
                for (t in 1 until 24) drawRect(palette.muted, Offset(bx0 + bw * t / 24 - .5f, by + 10), Size(1f, if (t % 6 == 0) 6f else 3f), alpha = a * .5f)
                if (u < 1f) {
                    val hx = bx0 + bw * u
                    drawCircle(palette.orange, 4.5f, Offset(hx, by + 3), alpha = a * m.blink)
                    label(number.format(p.tip), hx.coerceIn(bx0 + 22, maxOf(bx0 + 22, bx1 - 22)), by - 10, palette.foreground, a)
                }
                label(number.format(p.lockedFrom), bx0, by + 30, palette.muted, a, align = -1)
                label(number.format(p.claimableHeight), bx1, by + 30, if (u >= 1f) palette.foreground else palette.muted, a, align = 1)
                spanLabel?.let { label(it, (bx0 + bx1) / 2, by + 30, palette.muted, a, words.copy(fontSize = units(11f))) }
            }

            // The claim: your coin drops into the next block, then flies into the wallet it was claimed to.
            val fs = 1 + m.fin * .75f
            val wxAt = lerp(wx, nx, m.fin); val wyAt = lerp(yRowC, lockY - 4, m.fin)
            if (pre) {
                val cm = m.claim; val fu = m.fin
                var x = lerp(nx, nx + shift, cm); var y = lerp(lockY, yRowC, cm); var r = lerp(radius, s * .3f, clamp(cm))
                if (fu > 0f) { x = lerp(nx, wxAt, fu); y = lerp(yRowC, wyAt, fu) - StageArt.hump(fu) * 30; r = s * .3f * (1 - .4f * clamp(fu)) }
                if (fu < .97f) coin(Offset(x, y), r, palette.soft, palette.orange, 1 - clamp((fu - .8f) / .17f))
            }
            // Your on-chain wallet: every fee comes from it, and a claim to this phone lands in it.
            val walletA = if (needsFunds) 1f else maxOf(if (p.mined.any { it != null } || p.dropped) 1 - m.zoom else 0f, m.claim)
            if (walletA > 0f) {
                scale(fs, fs, Offset(wxAt, wyAt)) {
                    badge(Offset(wxAt, wyAt), 16f, StageArt.chain, palette.foreground, if (needsFunds) palette.failure else palette.hairline, palette.background, walletA)
                }
                val walletLabel = if (pre && destination != null) com.noahwallet.ui.activity.short(destination, 6, 4) else balance
                if (m.fin < .5f) walletLabel?.let { label(it, wx, yRowC + 31, if (needsFunds) palette.failure else palette.muted, walletA * (1 - m.fin * 2)) }
                if (needsFunds) {
                    drawCircle(palette.failure, 6f, Offset(wx + 12, yRowC - 12))
                    label("!", wx + 12, yRowC - 8.5f, Color.White, 1f, words.copy(fontWeight = FontWeight.Bold, fontSize = units(10f)))
                    drawLine(palette.failure, Offset(wx - 22, yRowC), Offset(nx + s / 2 + 8, yRowC), 1.2f, StrokeCap.Butt,
                        PathEffect.dashPathEffect(floatArrayOf(2f, 4f)), alpha = .55f)
                }
                if (m.fin > 0f) {
                    val ring = clamp((m.fin - .2f) / .8f)
                    drawArc(palette.orange, -90f, 360f * ring, false, Offset(wxAt - (16 * fs + 7), wyAt - (16 * fs + 7)),
                        Size(2 * (16 * fs + 7), 2 * (16 * fs + 7)), style = Stroke(2.5f))
                    val check = clamp((m.fin - .45f) / .55f)
                    if (check > 0f) scale(check, check, Offset(wxAt + 19 * fs, wyAt - 19 * fs)) {
                        val at = Offset(wxAt + 19 * fs, wyAt - 19 * fs)
                        drawCircle(palette.foreground, 10f, at)
                        val path = Path().apply { moveTo(at.x - 4.2f, at.y + .3f); lineTo(at.x - 1.2f, at.y + 3.2f); lineTo(at.x + 4.4f, at.y - 3f) }
                        drawPath(path, palette.background, style = Stroke(2.2f, cap = StrokeCap.Round))
                    }
                }
            }
        } }
    }
}

/** A cubic link from [p] to [c], vertical at both ends (prototype `link`), drawn up to [u]. */
private fun DrawScope.link(p: Offset, c: Offset, u: Float, color: Color, width: Float, alpha: Float) {
    if (u <= 0f || alpha <= 0f) return
    val my = (p.y + c.y) / 2
    fun at(t: Float): Offset {
        val a = 1 - t
        return Offset(a * a * a * p.x + 3 * a * a * t * p.x + 3 * a * t * t * c.x + t * t * t * c.x, a * a * a * p.y + 3 * a * a * t * my + 3 * a * t * t * my + t * t * t * c.y)
    }
    val path = Path().apply { for (i in 0..16) { val q = at(u * i / 16); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) } }
    drawPath(path, color, alpha = clamp(alpha), style = Stroke(width, cap = StrokeCap.Round))
}

/** A circle badge with an icon: the Ark server or your on-chain wallet (prototype `badge`). */
private fun DrawScope.badge(at: Offset, r: Float, icon: Path, ink: Color, ring: Color, fill: Color, alpha: Float, dashed: Boolean = false, dots: Boolean = false) {
    if (alpha <= 0f) return
    val a = clamp(alpha)
    drawCircle(fill, r, at, alpha = a)
    drawCircle(ring, r, at, alpha = a, style = Stroke(1.5f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3f, 3f)) else null))
    StageArt.run {
        icon(icon, at, r * 1.15f, ink, alpha = a)
        if (dots) icon(StageArt.serverDots, at, r * 1.15f, ink, width = 2.8f, alpha = a)
    }
}

/** Your coin: the orange-ringed VTXO from Send (prototype `coin`). */
private fun DrawScope.coin(at: Offset, r: Float, fill: Color, ring: Color, alpha: Float) {
    if (alpha <= 0f || r <= 0f) return
    drawCircle(fill, r, at, alpha = clamp(alpha))
    drawCircle(ring, r, at, alpha = clamp(alpha), style = Stroke(clamp(r * .19f, 2f, 4.2f)))
}

/**
 * A transaction's fee child: a dot that flies from your on-chain wallet and docks on its anchor (prototype `drawFee`).
 * Another wallet's child is hollow and costs you nothing; when fees rose, a bigger dot replaces the first.
 */
private fun DrawScope.fee(j: Int, ax: Float, ay: Float, p: ExitPose, m: PictureMotion, wx: Float, yRowC: Float, yRowT: Float,
                          palette: NoahTokens.Palette, alpha: Float, rate: String?, ox: Float = 11f, oy: Float = 6f, label: (String, Float, Float, Float) -> Unit) {
    if (!p.paid.getOrElse(j) { false } || alpha <= 0f) return
    val inBlock = p.mined.getOrNull(j) != null
    val current = j == p.current && !inBlock
    val dx = ax + ox; val dy = ay + oy
    if (p.theirs.getOrElse(j) { false }) {
        drawCircle(if (inBlock) palette.background else palette.muted, 3.6f, Offset(dx, dy), alpha = alpha * if (current) m.fee else 1f, style = Stroke(1.4f))
        return
    }
    fun fly(u: Float, r: Float, a: Float) {
        if (u <= 0f || a <= 0f) return
        val q = Offset(lerp(wx - 14, dx, u), lerp(yRowC - 6, dy, u) - StageArt.hump(u) * 26)
        if (u > .95f && !inBlock) drawLine(palette.foreground, Offset(ax + 3, ay + 2), Offset(dx, dy), 1f, alpha = .55f * a)
        drawCircle(if (inBlock) palette.background else palette.foreground, r, q, alpha = clamp(a))
    }
    if (current && m.bump > 0f) {
        fly(1f, 3.6f, alpha * (1 - m.bump))
        fly(m.bump, 4.8f, alpha)
        rate?.let { label(it, ax + 4, yRowT - 10, alpha * clamp(m.bump * 3)) }
    } else fly(if (current) m.fee else 1f, 3.6f, alpha)
}
