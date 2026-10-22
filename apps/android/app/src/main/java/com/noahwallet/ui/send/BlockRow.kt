package com.noahwallet.ui.send

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.noahwallet.ui.design.NoahTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.launch

/**
 * The block row, mempool.space style: the three most recent blocks (darker towards the newest, heights
 * under them), then a dashed "next" block holding your transaction as an orange dot. Everything comes from
 * the wallet's own chain source: [tip] is its height, [confirmedAt] the transaction's block once mined.
 */
internal object BlockRow {
    /** Confirmations so far: 0 while unconfirmed, then tip − height + 1. */
    fun confirmations(tip: Long, confirmedAt: Long?): Long = confirmedAt?.let { (tip - it + 1).coerceAtLeast(1) } ?: 0

    /**
     * One frame of the strip. [slide] runs 0 → 1 after the tip moves up one block: the row slides one block
     * along, the oldest fades and a new dashed block appears. [pop] is the newly mined block's bounce.
     */
    fun DrawScope.blockStrip(x0: Float, y: Float, size: Float, tip: Long, confirmedAt: Long?, dot: Float, slide: Float, pop: Float,
                             blink: Float, palette: NoahTokens.Palette, label: (Long) -> TextLayoutResult, next: TextLayoutResult, alpha: Float = 1f) {
        val gap = size + 12
        val s = slide.coerceIn(0f, 1f)
        val shift = gap * (1 - s)
        fun x(slot: Int) = x0 + slot * gap + shift
        // Mined blocks, the one that just left on the left during a slide.
        for (slot in -1..2) {
            val height = tip - 2 + slot
            val settled = if (slot < 0) 0f else .55f + .15f * slot
            val before = if (slot == 2) 1f else .55f + .15f * (slot + 1)
            val shade = StageArt.lerp(before, settled, s) * alpha
            if (shade <= 0f || height < 0) continue
            val scale = if (slot == 2) StageArt.lerp(1.22f, 1f, pop) else 1f
            val left = x(slot)
            scale(scale, scale, Offset(left + size / 2, y + size / 2)) {
                drawRoundRect(palette.foreground, Offset(left, y), Size(size, size), CornerRadius(7f), alpha = shade)
            }
            val text = label(height)
            drawText(text, color = if (slot == 2 && s < 1f) palette.foreground else palette.muted, alpha = (if (slot < 0) 1 - s else 1f) * alpha,
                topLeft = Offset(left + size / 2 - text.size.width / 2f, y + size + 14 - text.size.height / 2f))
            if (confirmedAt == height && dot > 0f) drawCircle(palette.background, 6f, Offset(left + size / 2, y + size / 2), alpha = dot.coerceIn(0f, 1f) * alpha)
        }
        // The next block, waiting for your transaction.
        val left = x(3)
        val appear = if (shift > 0f) s else 1f
        drawRoundRect(palette.foreground, Offset(left, y), Size(size, size), CornerRadius(6f), alpha = appear * blink * alpha,
            style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
        drawText(next, color = palette.muted, alpha = appear * alpha, topLeft = Offset(left + size / 2 - next.size.width / 2f, y + size + 14 - next.size.height / 2f))
        if (confirmedAt == null && dot > 0f) drawCircle(palette.orange, 6f, Offset(left + size / 2, y + size / 2), alpha = dot.coerceIn(0f, 1f) * alpha)
    }

    /** The waiting block's slow blink (prototype: 650 ms each way); still under Reduce Motion. */
    fun blink(elapsed: Long, on: Boolean, reduceMotion: Boolean): Float =
        if (!on || reduceMotion) 1f else (.65f + .35f * cos(2 * PI * elapsed / 1_300.0)).toFloat()
}

/**
 * The tip the row shows and its slide after the tip moves up by one; larger jumps (or the first value)
 * just appear. Returns the shown tip, the slide and the newly mined block's pop, both 0 → 1.
 */
@Composable
internal fun rememberBlockSlide(tip: Long?, reduceMotion: Boolean): Triple<Long?, Float, Float> {
    var shown by remember { mutableStateOf(tip) }
    val slide = remember { Animatable(1f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(tip) {
        val previous = shown
        shown = tip
        if (tip == null || previous == null || tip != previous + 1) { slide.snapTo(1f); pop.snapTo(1f); return@LaunchedEffect }
        if (reduceMotion) { slide.snapTo(1f); pop.snapTo(1f); return@LaunchedEffect }
        slide.snapTo(0f); pop.snapTo(0f)
        kotlinx.coroutines.coroutineScope {
            launch { pop.animateTo(1f, NoahTokens.Motion.bouncy()) }
            launch { slide.animateTo(1f, NoahTokens.Motion.snappy()) }
        }
    }
    return Triple(shown, slide.value, pop.value)
}
