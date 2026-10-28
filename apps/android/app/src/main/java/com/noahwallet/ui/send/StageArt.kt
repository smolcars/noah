package com.noahwallet.ui.send

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.PI
import kotlin.math.sin

/**
 * The motion's marks in the prototype's 24-unit icon paths and stage units: your coin (orange ring), the
 * payment (solid ₿ coin), you and the Ark server as badges, the padlock, the key (the preimage) and the
 * block strip. Callers scale the canvas so one unit is one prototype pixel.
 */
internal object StageArt {
    // Paths start with a no-op "m0 0" so the copy extractor never mistakes them for words.
    private fun parse(vararg d: String) = Path().apply { d.forEach { addPath(PathParser().parsePathString(it).toPath()) } }
    private fun rounded(x: Float, y: Float, w: Float, h: Float, r: Float) = Path().apply { addRoundRect(RoundRect(x, y, x + w, y + h, CornerRadius(r))) }

    val person = parse("m0 0M4.5 20.5c.8-4 3.9-6 7.5-6s6.7 2 7.5 6").apply { addOval(Rect(Offset(12f, 8f), 4f)) }
    val server = rounded(4f, 4f, 16f, 6.5f, 2f).apply { addPath(rounded(4f, 13.5f, 16f, 6.5f, 2f)) }
    val serverDots = parse("m0 0M8 7.25h.01M8 16.75h.01")
    val key = parse("m0 0M10.4 13.1 19 4.5M15.5 8l2.5 2.5M13.4 10.1l2 2").apply { addOval(Rect(Offset(7.5f, 16f), 4f)) }
    val ark = parse("m0 0M3 15h18l-2.6 4.5H5.6z", "m0 0M12 4v11M12 5.5l6 7.5h-6")
    val lightning = parse("m0 0M13 3 5 13.5h6L10 21l8-10.5h-6z")
    val chain = parse("m0 0M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val request = rounded(4f, 4f, 6f, 6f, 1.5f).apply {
        addPath(rounded(14f, 4f, 6f, 6f, 1.5f)); addPath(rounded(4f, 14f, 6f, 6f, 1.5f)); addPath(parse("m0 0M14 14h2v2M20 14v6h-6"))
    }

    /** A 24-unit icon drawn [side] units wide, centred on [center]. */
    @Suppress("LongParameterList") // a DrawScope primitive like drawCircle: geometry and paint are its parameters
    fun DrawScope.icon(path: Path, center: Offset, side: Float, color: Color, width: Float = 1.8f, alpha: Float = 1f) {
        translate(center.x - side / 2, center.y - side / 2) {
            scale(side / 24f, side / 24f, Offset.Zero) {
                drawPath(path, color, alpha = alpha.coerceIn(0f, 1f), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }

    /** You (orange) or the Ark server: a circle badge with its icon (prototype `badge`). */
    fun DrawScope.badge(me: Boolean, center: Offset, alpha: Float, scale: Float, palette: com.noahwallet.ui.design.NoahTokens.Palette) {
        if (alpha <= 0f) return
        val r = 20f
        scale(scale, scale, center) {
            drawCircle(palette.background, r, center, alpha = alpha.coerceIn(0f, 1f))
            drawCircle(if (me) palette.orange else palette.hairline, r, center, alpha = alpha.coerceIn(0f, 1f), style = Stroke(if (me) 2f else 1.5f))
            val stroke = if (me) palette.orange else palette.foreground
            icon(if (me) person else server, center, r * 1.15f, stroke, alpha = alpha)
            if (!me) icon(serverDots, center, r * 1.15f, stroke, width = 2.8f, alpha = alpha)
        }
    }

    /** Your coin (soft fill, orange ring), the refund (the same, thinner ring) or the payment (solid, with ₿). */
    @Suppress("LongParameterList") // a DrawScope primitive like drawCircle: geometry and paint are its parameters
    fun DrawScope.coin(center: Offset, r: Float, kind: Coin, alpha: Float, scale: Float, palette: com.noahwallet.ui.design.NoahTokens.Palette,
        glyph: TextLayoutResult? = null) {
        if (alpha <= 0f) return
        val a = alpha.coerceIn(0f, 1f)
        scale(scale, scale, center) {
            when (kind) {
                Coin.mine, Coin.refund -> {
                    drawCircle(palette.soft, r, center, alpha = a)
                    drawCircle(palette.orange, r, center, alpha = a, style = Stroke(if (kind == Coin.mine) 3f else 2.5f))
                }
                Coin.pay -> drawCircle(palette.foreground, r, center, alpha = a)
            }
            glyph?.let { drawText(it, color = if (kind == Coin.pay) palette.background else palette.foreground, alpha = a,
                topLeft = Offset(center.x - it.size.width / 2f, center.y - it.size.height / 2f)) }
        }
    }
    enum class Coin { mine, pay, refund }

    /** The padlock (prototype `padlock`); [open] lifts and turns the shackle. */
    fun DrawScope.padlock(at: Offset, alpha: Float, open: Float, ink: Color, fill: Color) {
        if (alpha <= 0f) return
        val a = alpha.coerceIn(0f, 1f)
        val shackle = Path().apply {
            moveTo(at.x - 8, at.y - 1); relativeLineTo(0f, -7f)
            arcTo(Rect(at.x - 8, at.y - 16, at.x + 8, at.y), 180f, 180f, false); relativeLineTo(0f, 7f)
        }
        translate(0f, -7f * open) {
            rotate(18f * open, Offset(at.x, at.y - 8)) { drawPath(shackle, ink, alpha = a, style = Stroke(3f, cap = StrokeCap.Round)) }
        }
        drawRoundRect(fill, Offset(at.x - 12, at.y - 2), Size(24f, 18f), CornerRadius(5f), alpha = a)
        drawRoundRect(ink, Offset(at.x - 12, at.y - 2), Size(24f, 18f), CornerRadius(5f), alpha = a, style = Stroke(2.5f))
        drawCircle(ink, 2.4f, Offset(at.x, at.y + 7), alpha = a)
    }

    /** A stroke drawn from [from] towards [to] up to [fraction]; dashed lines fade in instead. */
    @Suppress("LongParameterList") // a DrawScope primitive like drawLine: geometry and paint are its parameters
    fun DrawScope.line(from: Offset, to: Offset, color: Color, fraction: Float, alpha: Float = 1f, dashed: Boolean = false) {
        if (alpha <= 0f || fraction <= 0f) return
        val end = if (dashed) to else Offset(from.x + (to.x - from.x) * fraction.coerceIn(0f, 1f), from.y + (to.y - from.y) * fraction.coerceIn(0f, 1f))
        drawLine(color, from, end, 2f, StrokeCap.Round, if (dashed) PathEffect.dashPathEffect(floatArrayOf(3f, 5f)) else null,
            alpha = (if (dashed) fraction else alpha).coerceIn(0f, 1f) * if (dashed) alpha.coerceIn(0f, 1f) else 1f)
    }

    /** The part of [path] between [from] and [to] (in its own length units), for travelling dashes and drawn rings. */
    fun segment(path: Path, from: Float, to: Float): Path {
        val measure = PathMeasure().apply { setPath(path, false) }
        val out = Path()
        val start = from.coerceIn(0f, measure.length)
        val end = to.coerceIn(0f, measure.length)
        if (end > start) measure.getSegment(start, end, out, true)
        return out
    }

    fun cubic(x1: Float, y1: Float, x2: Float, y2: Float): androidx.compose.animation.core.Easing = androidx.compose.animation.core.CubicBezierEasing(x1, y1,
        x2, y2)
    fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** A hump 0 → 1 → 0, for a pulse whose keyframes return to rest. */
    fun hump(t: Float) = sin(PI * t.coerceIn(0f, 1f)).toFloat()
    fun shake(t: Float, offsets: FloatArray): Float {
        if (t <= 0f || t >= 1f) return 0f
        val position = t * offsets.size
        val index = position.toInt()
        val from = if (index == 0) 0f else offsets[index - 1]
        val to = offsets[index]
        return lerp(from, to, position - index)
    }
}
