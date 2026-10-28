@file:Suppress("MatchingDeclarationName") // named for the StyledQR composable and its Home card, like iOS

package com.noahwallet.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.noahwallet.R
import com.noahwallet.ui.design.NoahTokens

/**
 * The styled QR (prototype `buildQR`): error correction H (M for long payloads), round dot modules, rounded finder eyes and
 * the longship in a white tile over a cleared centre. Ink and tile are fixed; the card is always white.
 */
internal class StyledQRModel(val size: Int, private val dark: BooleanArray) {
    val logo = (Math.round(size * .22f) or 1)
    val logoStart = (size - logo) / 2
    fun isDark(r: Int, c: Int) = r in 0 until size && c in 0 until size && dark[r * size + c]
    fun isEye(r: Int, c: Int) = (r < 7 && c < 7) || (r < 7 && c >= size - 7) || (r >= size - 7 && c < 7)
    fun isHole(r: Int, c: Int) = r >= logoStart - 1 && r < logoStart + logo + 1 && c >= logoStart - 1 && c < logoStart + logo + 1
    /** Modules drawn as dots: dark, outside the eyes and the logo's cleared square. */
    fun dot(r: Int, c: Int) = isDark(r, c) && !isEye(r, c) && !isHole(r, c)

    companion object {
        fun encode(content: String, correction: ErrorCorrectionLevel = ErrorCorrectionLevel.H): StyledQRModel? = runCatching {
            val matrix = Encoder.encode(content, correction).matrix
            StyledQRModel(matrix.width, BooleanArray(matrix.width * matrix.height) { matrix.get(it % matrix.width, it / matrix.width).toInt() == 1 })
        }.getOrNull()
    }
}

private val ink = NoahTokens.Light.qrInk

private fun roundRect(x: Float, y: Float, side: Float, radius: Float) = RoundRect(x, y, x + side, y + side, CornerRadius(radius))

/**
 * Draws [content]'s QR. It draws in once (modules spread from the centre, eyes and logo spin in) the first
 * time it appears; [reveal] false or Reduce Motion skips straight to (or fades to) the final code.
 */
@Composable
internal fun StyledQR(content: String, modifier: Modifier = Modifier, reveal: Boolean = true, reduceMotion: Boolean = false,
    correction: ErrorCorrectionLevel = ErrorCorrectionLevel.H) {
    val model = remember(content, correction) { StyledQRModel.encode(content, correction) } ?: return
    val logo = ImageBitmap.imageResource(R.drawable.qr_logo)
    val start = rememberSaveable(content) { if (reveal) android.os.SystemClock.elapsedRealtime() else 0L }
    val elapsed = rememberElapsed(start.takeIf { it > 0 }, 2_000)
    val n = model.size
    val modules = remember(model) { modulePath(model) }
    val ring = remember { Path().apply { fillType = PathFillType.EvenOdd; addRoundRect(roundRect(0f, 0f, 7f, 2.4f))
        addRoundRect(roundRect(1f, 1f, 5f, 1.5f)) } }
    val pupil = remember { Path().apply { addRoundRect(roundRect(2f, 2f, 3f, 1.1f)) } }
    val modulesIn = step(elapsed, 320, SpringCurve.soft, 1_000, reduceMotion)
    val logoIn = step(elapsed, 380, SpringCurve.bouncy, reduceMotion = reduceMotion)
    val eyesIn = List(3) { step(elapsed, 560L + it * 110, SpringCurve.bouncy, reduceMotion = reduceMotion) }
    Canvas(modifier) {
        val m = size.width / n
        val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, size.width * .72f * if (reduceMotion) 1f else modulesIn)) }
        clipPath(clip) {
            withTransform({ scale(m, m, Offset.Zero) }) { drawPath(modules, ink, alpha = if (reduceMotion) modulesIn else 1f) }
        }
        listOf(Offset(0f, 0f), Offset((n - 7) * m, 0f), Offset(0f, (n - 7) * m)).forEachIndexed { i, origin ->
            val p = eyesIn[i]
            withTransform({
                translate(origin.x, origin.y)
                scale(m, m, Offset.Zero)
                if (!reduceMotion) { rotate(-45f * (1 - p), Offset(3.5f, 3.5f)); scale(lerp(.4f, 1f, p), lerp(.4f, 1f, p), Offset(3.5f, 3.5f)) }
            }) {
                drawPath(ring, ink, alpha = p.coerceIn(0f, 1f)); drawPath(pupil, ink, alpha = p.coerceIn(0f, 1f))
            }
        }
        val x = model.logoStart * m
        val side = model.logo * m
        val pad = m * .6f
        withTransform({
            if (!reduceMotion) { rotate(-12f * (1 - logoIn), center); scale(lerp(.3f, 1f, logoIn), lerp(.3f, 1f, logoIn), center) }
        }) {
            val alpha = logoIn.coerceIn(0f, 1f)
            drawRoundRect(Color.White, Offset(x - pad, x - pad), Size(side + 2 * pad, side + 2 * pad), CornerRadius((side + 2 * pad) * .26f), alpha = alpha)
            clipPath(Path().apply { addRoundRect(roundRect(x, x, side, side * .23f)) }) {
                drawImage(logo, IntOffset.Zero, IntSize(logo.width, logo.height), IntOffset(x.toInt(), x.toInt()),
                    IntSize(side.toInt(), side.toInt()), alpha = alpha, filterQuality = FilterQuality.High)
            }
        }
    }
}

/** Every dark module as a dot, in module units. */
private fun modulePath(model: StyledQRModel) = Path().apply {
    val n = model.size
    for (r in 0 until n) for (c in 0 until n) if (model.dot(r, c)) addOval(androidx.compose.ui.geometry.Rect(Offset(c + .5f, r + .5f), .44f))
}
