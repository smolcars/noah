package com.noahwallet.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sea (widgets.md, The wave): RemoteViews can't draw paths, so the wave and its fading fill are drawn here, in
 * Bitmap pixels for the slot. Only orange is drawn, so the bitmap suits light, dark and wallpaper colours alike; the
 * boat is a launcher-tinted ImageView placed at [Drawn.boatX], [Drawn.boatY].
 */
internal object WidgetSea {
    private const val ORANGE = 0xF7931A
    /** The longship mask's hull bottom, as a share of its height (rows 18–209 of 256 are the ship). */
    const val HULL = .82f
    /** The band's headroom over the wave's highest point, as a share of the boat's size (plus 2 dp). */
    const val HEADROOM = .76f

    /** The boat's top, [bs] tall, so its hull rests on the 2 dp stroke's upper edge at [line]: never through the hull. */
    fun boatTop(line: Float, bs: Float) = line - HULL * bs - 1

    /**
     * Where the sea sits in the widget, in dp: its box ([x], [y], [w], [h]), the wave's band height from its top, the
     * boat's size and its right-edge inset. [fade] fades the sea in from the left over its first third (medium).
     * [centred] puts the boat in the middle (No wallet); [calm] sets calm water's line within the sea.
     */
    data class Frame(val x: Float, val y: Float, val w: Float, val h: Float, val band: Float, val boat: Float,
        val inset: Float, val fade: Boolean = false, val centred: Boolean = false, val calm: Float? = null)

    /** The boat's top-left corner in the widget, dp. */
    data class Drawn(val bitmap: Bitmap, val boatX: Float, val boatY: Float)

    /** The line in the sea's own dp coordinates, and the y it ends at (today) — the boat rides there. */
    internal data class Line(val path: Path, val today: Float, val boatX: Float)

    internal fun line(values: List<Long>?, frame: Frame): Line {
        val w = frame.w; val band = frame.band; val bs = frame.boat; val inset = frame.inset
        val path = Path()
        val bx = if (frame.centred) (w - bs) / 2 else w - inset - bs
        val swell = values?.let { WidgetWave.swell(it.map(Long::toDouble)) }
        val low = swell?.min() ?: 0.0; val high = swell?.max() ?: 0.0
        if (swell == null || high - low < 1e-9) {
            // Calm water: a flat line with a gentle ripple, about 72% down the band unless the frame sets it.
            val y = frame.calm ?: (band * .72f).roundToInt().toFloat()
            path.moveTo(0f, y)
            var x = 3f
            while (x <= w + 3) { path.lineTo(x, y + 1.4f * sin(x / 7.0).toFloat()); x += 3 }
            return Line(path, y, bx)
        }
        // From the smoothed minimum (2 dp above the band's bottom) to the maximum (under the boat's headroom).
        val top = HEADROOM * bs + 2
        val xs = swell.indices.map { (it.toDouble() / swell.lastIndex) * (bx - 2) }
        val ys = swell.map { top + (1 - (it - low) / (high - low)) * (band - top - 2) }
        path.moveTo(xs[0].toFloat(), ys[0].toFloat())
        for (s in WidgetWave.monotone(xs, ys)) path.cubicTo(s.c1x.toFloat(), s.c1y.toFloat(), s.c2x.toFloat(), s.c2y.toFloat(), s.x.toFloat(), s.y.toFloat())
        val today = ys.last().toFloat()
        // Flat at today's level under the boat, to the right edge.
        path.lineTo(w, today)
        return Line(path, today, bx)
    }

    /** The bitmap's size for [frame] at [scale] px per dp. */
    fun pixels(frame: Frame, scale: Float): Pair<Int, Int> =
        (frame.w * scale).roundToInt().coerceAtLeast(1) to (frame.h * scale).roundToInt().coerceAtLeast(1)

    /**
     * Draws at [scale] px per dp (the display's density, or less to fit the launcher's budget: the ImageView stretches
     * it to the slot); the boat sails on the line at today, its hull on the stroke's upper edge.
     */
    fun draw(values: List<Long>?, frame: Frame, scale: Float): Drawn {
        val line = line(values, frame)
        val (width, height) = pixels(frame, scale)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.scale(width / frame.w, height / frame.h)
        val sea = Path(line.path).apply { lineTo(frame.w, frame.h); lineTo(0f, frame.h); close() }
        val bounds = RectF().also { sea.computeBounds(it, true) }
        canvas.drawPath(sea, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, bounds.top, 0f, frame.h, Color.argb(71, 0xF7, 0x93, 0x1A), Color.argb(0, 0xF7, 0x93, 0x1A), Shader.TileMode.CLAMP)
        })
        canvas.drawPath(line.path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = Color.rgb(ORANGE shr 16 and 0xFF, ORANGE shr 8 and 0xFF, ORANGE and 0xFF)
        })
        if (frame.fade) canvas.drawRect(0f, 0f, frame.w, frame.h, Paint().apply {
            shader = LinearGradient(0f, 0f, frame.w * .34f, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        return Drawn(bitmap, frame.x + line.boatX, frame.y + boatTop(line.today, frame.boat))
    }
}
