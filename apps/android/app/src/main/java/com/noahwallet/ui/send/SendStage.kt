package com.noahwallet.ui.send

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.SpringCurve
import com.noahwallet.ui.home.step
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.send.StageArt.badge
import com.noahwallet.ui.send.StageArt.coin
import com.noahwallet.ui.send.StageArt.icon
import com.noahwallet.ui.send.StageArt.line
import com.noahwallet.ui.send.StageArt.padlock
import com.noahwallet.ui.send.BlockRow.blockStrip
import kotlin.math.min

/** Who the payment goes to on the stage and in the titles: an initial for people and addresses, a rail glyph otherwise. */
data class SendWho(val name: String, val initial: String? = null, val glyph: Glyph? = null) {
    enum class Glyph { ark, lightning, chain, request }
}

/**
 * The sending stage (prototype `startSend` and its rails) in the prototype's 320 × 230 units. Every element is
 * a function of the phases [SendTimeline] has started by [elapsed], so the picture can never run ahead of the
 * wallet, and a pose change or recreation redraws the same frame.
 */
@Composable
internal fun SendStage(motion: SendMotion, elapsed: Long, who: SendWho, tip: Long?, confirmedAt: Long?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val nextLabel = com.noahwallet.localization.tr("next")
    val started = SendTimeline.started(motion, elapsed, reduceMotion)
    val (shownTip, slide, pop) = rememberBlockSlide(tip, reduceMotion)
    fun units(value: Float) = with(density) { (value / (density.density * fontScale)).sp }
    val display = NoahTokens.Display
    val glyph = { r: Float -> measurer.measure("₿", TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = units(r * 1.05f))) }
    val payGlyph = remember(density) { glyph(30f) }
    val refundGlyph = remember(density) { measurer.measure("₿", TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = units(30f))) }
    val initial = remember(who.initial, density) { who.initial?.let { measurer.measure(it, TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = units(20f))) } }
    val labelStyle = TextStyle(fontFamily = display, fontWeight = FontWeight.Medium, fontSize = units(11.5f))
    val next = remember(nextLabel, density) { measurer.measure(nextLabel, labelStyle) }
    val format = remember(locale) { java.text.NumberFormat.getIntegerInstance(locale) }

    Canvas(modifier.clearAndSetSemantics { }) {
        fun startOf(phase: SendPhase) = started.firstOrNull { it.phase == phase }?.start
        fun local(phase: SendPhase) = startOf(phase)?.let { elapsed - it }
        fun p(phase: SendPhase, delay: Long = 0, curve: SpringCurve = SpringCurve.snappy, duration: Long? = null): Float =
            local(phase)?.let { step(it, delay, curve, duration, reduceMotion) } ?: 0f
        fun has(phase: SendPhase) = startOf(phase) != null
        // Under Reduce Motion, travel jumps to its end and only opacity fades.
        fun travel(value: Float) = if (reduceMotion) (if (value > 0f) 1f else 0f) else value

        val k = min(size.width / 320f, size.height / 230f)
        translate((size.width - 320f * k) / 2 - 20f * k, (size.height - 230f * k) / 2 - 10f * k) { scale(k, k, Offset.Zero) {
            val stageAlpha = 1 - p(SendPhase.stop, curve = SpringCurve.soft).coerceIn(0f, 1f)
            val c = Offset(150f, 118f)
            val d = Offset(300f, 118f)
            val pay = Offset(c.x + 40, c.y)
            val sv = Offset(pay.x + 56, pay.y - 74)
            val route = motion.route

            // Block strip (on-chain): the three latest blocks and the next one, from the wallet's chain source.
            if (route.chain && shownTip != null) {
                val stripIn = p(SendPhase.appear, 900, SpringCurve.soft).coerceIn(0f, 1f) * stageAlpha
                if (stripIn > 0f) {
                    val waitingFor = local(SendPhase.waiting)
                    val dotIn = when {
                        confirmedAt != null && has(SendPhase.drop) -> 1f
                        has(SendPhase.drop) -> p(SendPhase.drop, 250, SpringCurve.soft).let { if (it >= .999f || reduceMotion) it else 0f }
                        else -> 0f
                    }
                    blockStrip(86f, 176f, 38f, shownTip, confirmedAt, dotIn, slide, pop,
                        BlockRow.blink(waitingFor ?: 0, waitingFor != null && confirmedAt == null, reduceMotion), palette,
                        { height -> measurer.measure(format.format(height), labelStyle) }, next, stripIn)
                }
            }

            // The recipient: slides in, and at the finale glides to the centre while the orange ring draws.
            val destIn = p(SendPhase.appear, 80).coerceIn(0f, 1f)
            val finale = travel(p(SendPhase.finale, 120))
            val shake = local(SendPhase.fizzle)?.takeIf { !reduceMotion }?.let { StageArt.shake(it / 300f, floatArrayOf(-6f, 5f, 0f)) } ?: 0f
            val destAlpha = destIn * StageArt.lerp(1f, .3f, p(SendPhase.back, curve = SpringCurve.soft).coerceIn(0f, 1f)) * stageAlpha
            val destAt = Offset(d.x + 20 * (1 - travel(destIn)) - 120 * finale + shake, d.y)
            val destScale = StageArt.lerp(1f, 1.5f, finale)
            if (destAlpha > 0f) scale(destScale, destScale, destAt) {
                drawCircle(palette.soft, 26f, destAt, alpha = destAlpha)
                if (initial != null) drawText(initial, palette.foreground, destAt, destAlpha)
                else who.glyph?.let { icon(glyphPath(it), destAt, 22f, palette.foreground, alpha = destAlpha) }
                val ring = local(SendPhase.finale)?.let { t ->
                    if (reduceMotion) step(t, 0, SpringCurve.soft, reduceMotion = true) else SpringCurve.soft.over((t - 260) / 620f)
                } ?: 0f
                if (ring > 0f) drawArc(palette.orange, -90f, 360f * ring.coerceIn(0f, 1f), false, Offset(destAt.x - 33, destAt.y - 33),
                    androidx.compose.ui.geometry.Size(66f, 66f), alpha = if (reduceMotion) ring.coerceIn(0f, 1f) * destAlpha else destAlpha, style = Stroke(2f))
                val check = p(SendPhase.finale, 520, SpringCurve.bouncy)
                if (check > 0f) {
                    val at = Offset(destAt.x + 19, destAt.y - 19)
                    val sc = if (reduceMotion) 1f else check.coerceAtLeast(0f)
                    scale(sc, sc, at) {
                        drawCircle(palette.foreground, 10f, at, alpha = check.coerceIn(0f, 1f) * destAlpha)
                        val tick = Path().apply { moveTo(at.x - 4.5f, at.y); relativeLineTo(3f, 3f); relativeLineTo(5.5f, -6f) }
                        drawPath(tick, palette.background, alpha = check.coerceIn(0f, 1f) * destAlpha, style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }

            // Your coin, then the split: the payment moves right, your change rolls back into your balance.
            val mineIn = p(SendPhase.appear, curve = SpringCurve.bouncy)
            val split = p(SendPhase.split)
            coin(c, 44f, StageArt.Coin.mine, mineIn.coerceIn(0f, 1f) * (1 - split.coerceIn(0f, 1f)) * stageAlpha,
                if (reduceMotion) 1f else StageArt.lerp(.7f, 1f, mineIn) * StageArt.lerp(1f, .82f, split), palette)
            val change = p(SendPhase.split, curve = SpringCurve.bouncy)
            val rollBack = p(SendPhase.split, 630, SpringCurve.soft)
            val changeAt = StageArt.lerp(StageArt.lerp(c, Offset(c.x - 62, c.y + 34), travel(change)), Offset(c.x - 140, c.y + 96), travel(rollBack))
            coin(changeAt, 20f, StageArt.Coin.mine, change.coerceIn(0f, 1f) * (1 - rollBack.coerceIn(0f, 1f)) * stageAlpha,
                if (reduceMotion) 1f else StageArt.lerp(.5f, 1f, change) * StageArt.lerp(1f, .6f, rollBack), palette)

            // The payment piece.
            val payIn = p(SendPhase.split, curve = SpringCurve.bouncy)
            var payAt = StageArt.lerp(c, pay, travel(payIn))
            var payScale = if (reduceMotion) 1f else StageArt.lerp(.5f, 1f, payIn)
            var payAlpha = payIn.coerceIn(0f, 1f)
            when (route) {
                SendRoute.ark -> {
                    val go = p(SendPhase.deliver)
                    payAt = StageArt.lerp(payAt, d, travel(go)); payScale *= StageArt.lerp(1f, .35f, travel(go)); payAlpha *= 1 - go.coerceIn(0f, 1f)
                }
                SendRoute.lightning -> {
                    val inServer = p(SendPhase.paid)
                    payAt = StageArt.lerp(payAt, sv, travel(inServer)); payScale *= StageArt.lerp(1f, .3f, travel(inServer)); payAlpha *= 1 - inServer.coerceIn(0f, 1f)
                    val back = p(SendPhase.back)
                    payScale *= StageArt.lerp(1f, .6f, travel(back)); payAlpha *= 1 - back.coerceIn(0f, 1f)
                }
                SendRoute.chainArk -> {
                    val inServer = p(SendPhase.drop)
                    payAt = StageArt.lerp(payAt, sv, travel(inServer)); payScale *= StageArt.lerp(1f, .3f, travel(inServer)); payAlpha *= 1 - inServer.coerceIn(0f, 1f)
                }
                SendRoute.chainWallet -> {
                    val gone = p(SendPhase.drop)
                    payScale *= StageArt.lerp(1f, .3f, travel(gone)); payAlpha *= 1 - gone.coerceIn(0f, 1f)
                }
            }
            coin(payAt, 30f, StageArt.Coin.pay, payAlpha * stageAlpha, payScale, palette, payGlyph)

            when (route) {
                SendRoute.ark -> {
                    // You and the Ark server each draw a signature onto the payment, then it's delivered.
                    val fade = 1 - p(SendPhase.deliver).coerceIn(0f, 1f)
                    val me = p(SendPhase.sign, curve = SpringCurve.bouncy)
                    val server = p(SendPhase.sign, 110, SpringCurve.bouncy)
                    val meAt = Offset(pay.x - 70, pay.y - 72)
                    val serverAt = Offset(pay.x + 70, pay.y - 72)
                    badge(true, meAt, me.coerceIn(0f, 1f) * fade * stageAlpha, if (reduceMotion) 1f else StageArt.lerp(.4f, 1f, me), palette)
                    badge(false, serverAt, server.coerceIn(0f, 1f) * fade * stageAlpha, if (reduceMotion) 1f else StageArt.lerp(.4f, 1f, server), palette)
                    local(SendPhase.sign)?.let { t ->
                        fun drawn(delay: Long) = if (reduceMotion) step(t, 420 + delay, SpringCurve.snappy, reduceMotion = true)
                            else ((t - 420 - delay) / 320f).coerceIn(0f, 1f).let { 1 - (1 - it) * (1 - it) }
                        line(Offset(pay.x - 58, pay.y - 58), Offset(pay.x - 17, pay.y - 24), palette.orange, drawn(0), fade * stageAlpha)
                        line(Offset(pay.x + 58, pay.y - 58), Offset(pay.x + 17, pay.y - 24), palette.foreground, drawn(160), fade * stageAlpha)
                    }
                    val seal = p(SendPhase.sign, 900, SpringCurve.soft)
                    if (seal > 0f && seal < 1f && !reduceMotion) drawCircle(palette.foreground, 30f * StageArt.lerp(1f, 1.7f, seal), pay, alpha = (1 - seal) * stageAlpha, style = Stroke(2f))
                    val ring = p(SendPhase.sign, 900).coerceIn(0f, 1f)
                    if (ring > 0f) drawCircle(palette.foreground, 34f, pay, alpha = .5f * ring * fade * stageAlpha, style = Stroke(1.5f))
                }
                SendRoute.lightning -> {
                    val lockAt = Offset(pay.x, pay.y - 32)
                    val lockIn = p(SendPhase.lock, curve = SpringCurve.bouncy)
                    val lockOut = maxOf(p(SendPhase.paid), p(SendPhase.back)).coerceIn(0f, 1f)
                    val open = if (has(SendPhase.signBack)) p(SendPhase.signBack, curve = SpringCurve.bouncy) else p(SendPhase.key, 900, SpringCurve.bouncy)
                    val lockScale = if (reduceMotion) 1f else StageArt.lerp(.6f, 1f, lockIn)
                    translate(0f, if (reduceMotion) 0f else -10f * (1 - lockIn)) {
                        scale(lockScale, lockScale, lockAt) { padlock(lockAt, lockIn.coerceIn(0f, 1f) * (1 - lockOut) * stageAlpha, if (reduceMotion) open.coerceIn(0f, 1f) else open, palette.foreground, palette.background) }
                    }
                    // Ark holds it while Lightning pays: the server and the dotted tether.
                    val serverIn = p(SendPhase.hold, curve = SpringCurve.bouncy)
                    val serverAlpha = serverIn.coerceIn(0f, 1f) *
                        (1 - p(SendPhase.paid, 600, SpringCurve.soft).coerceIn(0f, 1f)) *
                        StageArt.lerp(1f, .35f, p(SendPhase.signBack, curve = SpringCurve.soft).coerceIn(0f, 1f)) *
                        (1 - p(SendPhase.back, curve = SpringCurve.soft).coerceIn(0f, 1f))
                    val pulse = if (reduceMotion) 1f else 1 + .15f * StageArt.hump(p(SendPhase.paid, 300, SpringCurve.bouncy))
                    badge(false, sv, serverAlpha * stageAlpha, (if (reduceMotion) 1f else StageArt.lerp(.4f, 1f, serverIn)) * pulse, palette)
                    val tether = local(SendPhase.hold)?.let { ((it - 150) / 300f).coerceIn(0f, 1f) } ?: 0f
                    val tetherOut = maxOf(p(SendPhase.paid), p(SendPhase.signBack)).coerceIn(0f, 1f)
                    line(Offset(pay.x + 14, pay.y - 40), Offset(sv.x - 13, sv.y + 12), palette.foreground, tether, (1 - tetherOut) * stageAlpha, dashed = true)
                    // The bolt runs from the server to the recipient, looping while Lightning is still paying.
                    val bolt = Path().apply { moveTo(sv.x + 15, sv.y + 8); lineTo(sv.x + 34, sv.y + 26); lineTo(sv.x + 26, sv.y + 31); lineTo(d.x - 17, d.y - 20) }
                    val payStart = startOf(SendPhase.pay)
                    val fizzle = local(SendPhase.fizzle)
                    if (fizzle != null) {
                        val f = (fizzle / 420f).coerceIn(0f, 1f)
                        if (f < 1f) drawPath(bolt, lerp(palette.orange, palette.muted, f), alpha = (1 - f) * stageAlpha,
                            style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    } else if (payStart != null && !has(SendPhase.paid)) {
                        val until = startOf(SendPhase.key) ?: Long.MAX_VALUE
                        val t = elapsed - payStart
                        // The first zap at the phase's start, then one a second while it waits.
                        val (zap, length) = when {
                            t < 700 -> 0L to 480f
                            else -> (700 + (t - 700) / 1_000 * 1_000) to 768f
                        }
                        val into = (t - zap).toFloat()
                        if (payStart + zap < until && into in 0f..length && !reduceMotion) {
                            val q = StageArt.cubic(.4f, 0f, .2f, 1f).transform(into / length)
                            drawPath(StageArt.segment(bolt, -34 + 174 * q, 174 * q), palette.orange, alpha = stageAlpha,
                                style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        } else if (reduceMotion && payStart + zap < until) drawPath(bolt, palette.orange, alpha = .6f * stageAlpha,
                            style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    // Their receipt (the preimage) travels back through Ark as a key and opens the lock. Success only.
                    local(SendPhase.key)?.let { t ->
                        if (t < (if (reduceMotion) 405 else 900)) {
                            val q = if (reduceMotion) 1f else StageArt.cubic(.35f, 0f, .25f, 1f).transform(t / 900f)
                            val from = Offset(d.x - 8, d.y - 34)
                            val at = if (q < .5f) StageArt.lerp(from, sv, q / .5f) else StageArt.lerp(sv, Offset(pay.x, pay.y - 30), (q - .5f) / .5f)
                            val alpha = if (reduceMotion) (t / 180f).coerceIn(0f, 1f) else (q / .5f).coerceIn(0f, 1f)
                            icon(StageArt.key, at, 24f, palette.orange, 2.4f, alpha * stageAlpha)
                        }
                    }
                    // The refund: Ark signs a fresh coin back to you.
                    val back = p(SendPhase.back, curve = SpringCurve.soft, duration = 900)
                    if (back > 0f) coin(Offset(pay.x + (180 - pay.x) * travel(back), pay.y), 30f, StageArt.Coin.refund, back.coerceIn(0f, 1f) * stageAlpha,
                        if (reduceMotion) 1.45f else StageArt.lerp(.6f, 1.45f, back), palette, refundGlyph)
                }
                SendRoute.chainArk, SendRoute.chainWallet -> {
                    if (route == SendRoute.chainArk) {
                        val serverIn = p(SendPhase.swap, curve = SpringCurve.bouncy)
                        val serverOut = p(SendPhase.waiting, curve = SpringCurve.soft).coerceIn(0f, 1f)
                        badge(false, sv, serverIn.coerceIn(0f, 1f) * (1 - serverOut) * stageAlpha, if (reduceMotion) 1f else StageArt.lerp(.4f, 1f, serverIn), palette)
                        val tether = local(SendPhase.swap)?.let { ((it - 150) / 300f).coerceIn(0f, 1f) } ?: 0f
                        line(Offset(pay.x + 14, pay.y - 26), Offset(sv.x - 13, sv.y + 12), palette.foreground, tether, (1 - p(SendPhase.drop).coerceIn(0f, 1f)) * stageAlpha, dashed = true)
                    }
                    // The transaction travels into the next block, where the strip holds it.
                    local(SendPhase.drop)?.let { t ->
                        val q = step(t, 250, SpringCurve.soft, reduceMotion = reduceMotion)
                        if (shownTip != null && q > 0f && q < .999f && !reduceMotion) {
                            val from = if (route == SendRoute.chainArk) sv else pay
                            drawCircle(palette.orange, 6f, StageArt.lerp(from, Offset(86f + 3 * 50f + 19f, 176f + 19f), q), alpha = q.coerceIn(0f, 1f) * stageAlpha)
                        }
                    }
                }
            }
        } }
    }
}

private fun glyphPath(glyph: SendWho.Glyph): Path = when (glyph) {
    SendWho.Glyph.ark -> StageArt.ark
    SendWho.Glyph.lightning -> StageArt.lightning
    SendWho.Glyph.chain -> StageArt.chain
    SendWho.Glyph.request -> StageArt.request
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawText(text: androidx.compose.ui.text.TextLayoutResult, color: Color, center: Offset, alpha: Float) =
    drawText(text, color = color, alpha = alpha.coerceIn(0f, 1f), topLeft = Offset(center.x - text.size.width / 2f, center.y - text.size.height / 2f))

/** The phase line's words for [SendTimeline]'s current phase; null hides it. [height]: the block it waits for or confirmed in. */
internal data class PhaseLine(val source: String, val argument: String? = null, val done: Boolean = false, val suffix: Pair<Long, Long>? = null)

internal object SendPhaseLines {
    fun of(motion: SendMotion, elapsed: Long, reduceMotion: Boolean, amount: String, tip: Long?, confirmedAt: Long?, needed: Long, format: (Long) -> String): PhaseLine? {
        val started = SendTimeline.started(motion, elapsed, reduceMotion)
        if (SendTimeline.stillPaying(motion, elapsed, reduceMotion)) return PhaseLine("Still paying. This can take a few minutes.")
        return when (started.lastOrNull()?.phase) {
            null, SendPhase.appear, SendPhase.stop -> null
            SendPhase.split -> PhaseLine("Splitting your coin")
            SendPhase.sign -> PhaseLine("Signing with Ark")
            SendPhase.deliver -> PhaseLine("Delivering")
            SendPhase.finale -> PhaseLine(if (motion.route == SendRoute.lightning) "Paid" else "Delivered", done = true)
            SendPhase.lock -> PhaseLine("Locking {amount}", amount)
            SendPhase.hold -> PhaseLine("Ark holds it while Lightning pays")
            SendPhase.pay -> PhaseLine("Paying over Lightning")
            SendPhase.key -> PhaseLine("Their receipt unlocks it")
            SendPhase.paid -> PhaseLine("Paid", done = true)
            SendPhase.fizzle -> PhaseLine("That payment didn’t go through")
            SendPhase.signBack -> PhaseLine("Ark signs it back to you")
            SendPhase.back -> PhaseLine("Back in your wallet", done = true)
            SendPhase.swap, SendPhase.drop -> if (motion.route == SendRoute.chainArk) PhaseLine("Swapping with Ark") else PhaseLine("Splitting your coin")
            SendPhase.waiting -> when {
                tip == null -> null
                confirmedAt == null -> PhaseLine("Waiting for block {height}", format(tip + 1))
                else -> {
                    val confirmations = BlockRow.confirmations(tip, confirmedAt)
                    PhaseLine("Confirmed in block {height}", format(confirmedAt), true, (confirmations to needed).takeIf { confirmations < needed })
                }
            }
        }
    }
}
