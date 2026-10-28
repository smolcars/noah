package com.noahwallet.ui.send

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.noahwallet.ui.design.NoahTokens
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

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
    data class Frame(val tip: Long, val confirmedAt: Long?, val dot: Float, val slide: Float, val pop: Float, val blink: Float, val alpha: Float = 1f)

    /** The strip's measured words: each block's [height] label and the waiting block's [next]. */
    class Labels(val height: (Long) -> TextLayoutResult, val next: TextLayoutResult)

    /** The strip of [size]-unit blocks from [at], the block row's top left. */
    fun DrawScope.blockStrip(at: Offset, size: Float, frame: Frame, palette: NoahTokens.Palette, labels: Labels) {
        val gap = size + 12
        val s = frame.slide.coerceIn(0f, 1f)
        val shift = gap * (1 - s)
        fun x(slot: Int) = at.x + slot * gap + shift
        // Mined blocks, the one that just left on the left during a slide.
        for (slot in -1..2) minedBlock(Rect(Offset(x(slot), at.y), Size(size, size)), slot, frame, palette, labels)
        // The next block, waiting for your transaction.
        val left = x(3)
        val y = at.y
        val alpha = frame.alpha
        val appear = if (shift > 0f) s else 1f
        drawRoundRect(palette.foreground, Offset(left, y), Size(size, size), CornerRadius(6f), alpha = appear * frame.blink * alpha,
            style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
        drawText(labels.next, color = palette.muted, alpha = appear * alpha,
            topLeft = Offset(left + size / 2 - labels.next.size.width / 2f, y + size + 14 - labels.next.size.height / 2f))
        if (frame.confirmedAt == null && frame.dot > 0f) {
            drawCircle(palette.orange, 6f, Offset(left + size / 2, y + size / 2), alpha = frame.dot.coerceIn(0f, 1f) * alpha)
        }
    }

    /** The mined block in [slot] (−1 is the one leaving during a slide, 2 the newest) at [box], its height and any confirmed dot. */
    private fun DrawScope.minedBlock(box: Rect, slot: Int, frame: Frame, palette: NoahTokens.Palette, labels: Labels) {
        val s = frame.slide.coerceIn(0f, 1f)
        val height = frame.tip - 2 + slot
        val settled = if (slot < 0) 0f else .55f + .15f * slot
        val before = if (slot == 2) 1f else .55f + .15f * (slot + 1)
        val shade = StageArt.lerp(before, settled, s) * frame.alpha
        if (shade <= 0f || height < 0) return
        val scale = if (slot == 2) StageArt.lerp(1.22f, 1f, frame.pop) else 1f
        val left = box.left
        val y = box.top
        val size = box.width
        scale(scale, scale, Offset(left + size / 2, y + size / 2)) {
            drawRoundRect(palette.foreground, Offset(left, y), Size(size, size), CornerRadius(7f), alpha = shade)
        }
        val text = labels.height(height)
        drawText(text, color = if (slot == 2 && s < 1f) palette.foreground else palette.muted, alpha = (if (slot < 0) 1 - s else 1f) * frame.alpha,
            topLeft = Offset(left + size / 2 - text.size.width / 2f, y + size + 14 - text.size.height / 2f))
        if (frame.confirmedAt == height && frame.dot > 0f) {
            drawCircle(palette.background, 6f, Offset(left + size / 2, y + size / 2), alpha = frame.dot.coerceIn(0f, 1f) * frame.alpha)
        }
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
