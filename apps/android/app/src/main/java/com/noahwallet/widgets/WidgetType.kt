package com.noahwallet.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.noahwallet.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The balance in Space Grotesk. Launchers inflate RemoteViews in a restricted context that never loads an app's fonts,
 * so the figures are drawn here as a white mask on an ImageView the launcher tints with the text colour: light, dark
 * and wallpaper colours still resolve in the launcher. Tabular digits; it shrinks to fit.
 */
internal object WidgetType {
    private fun font(context: Context, id: Int, fallback: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, fallback)

    /**
     * [widthDp] × [heightDp] is the balance's box; the text sits on its bottom, left aligned, like the layout's text. It
     * shrinks to fit the width down to [floor] of its size (half, unless the layout allows less), and to fit the height
     * however large the system text is.
     */
    @Suppress("LongParameterList") // a drawing primitive: the text, its size and the box it fits
    fun balance(context: Context, text: String, satsSuffix: Boolean, sizeSp: Float, widthDp: Float, heightDp: Float, density: Float,
        floor: Float = .5f): Bitmap =
        balancePx(context, text, satsSuffix, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics),
            widthDp, heightDp, density, floor)

    /** The balance's width in pixels at [sizeSp] (the system's font scale applied), unshrunk. */
    fun width(context: Context, text: String, satsSuffix: Boolean, sizeSp: Float): Float =
        measure(context, text, satsSuffix, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics))

    private fun parts(text: String, satsSuffix: Boolean): Pair<String, String> =
        (if (satsSuffix && text.endsWith(" sats")) text.dropLast(5) else text).let { it to text.removePrefix(it) }

    private fun paint(context: Context, size: Float, medium: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (medium) font(context, R.font.space_grotesk_medium, Typeface.NORMAL) else font(context, R.font.space_grotesk_semibold, Typeface.BOLD)
        textSize = size; color = Color.WHITE; fontFeatureSettings = "tnum"
        // "sats" after the number: about half size, Medium, no tightening.
        letterSpacing = if (medium) 0f else -.03f
    }

    private fun measure(context: Context, text: String, satsSuffix: Boolean, size: Float): Float {
        val (main, suffix) = parts(text, satsSuffix)
        return paint(context, size, false).measureText(main) + if (suffix.isEmpty()) 0f else paint(context, size * .52f, true).measureText(suffix)
    }

    /** [full]: the full text size in pixels, with the system's font scale applied. */
    @Suppress("LongParameterList") // a drawing primitive: the text, its size and the box it fits
    internal fun balancePx(context: Context, text: String, satsSuffix: Boolean, full: Float, widthDp: Float, heightDp: Float, density: Float,
        floor: Float = .5f): Bitmap {
        val (main, suffix) = parts(text, satsSuffix)
        fun width(size: Float) = measure(context, text, satsSuffix, size)
        val available = widthDp * density
        val height = (heightDp * density).roundToInt().coerceAtLeast(1)
        val byWidth = (full * minOf(1f, available / width(full).coerceAtLeast(1f))).coerceAtLeast(full * floor)
        // Large system text: the figures' ink above the baseline and the line's descent below it must fit the box too,
        // whatever the width allows (they scale with the size).
        val ink = android.graphics.Rect().also { paint(context, full, false).getTextBounds(main, 0, main.length, it) }
        val tall = (-ink.top + paint(context, full, false).fontMetrics.descent).coerceAtLeast(1f)
        val size = minOf(byWidth, full * (height - 1) / tall)
        val bitmap = Bitmap.createBitmap(ceil(width(size)).toInt().coerceAtLeast(1), height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val numbers = paint(context, size, false)
        // The line's bottom on the box's bottom, as a bottom-gravity TextView without font padding sets it.
        val baseline = height - numbers.fontMetrics.descent
        canvas.drawText(main, 0f, baseline, numbers)
        if (suffix.isNotEmpty()) canvas.drawText(suffix, numbers.measureText(main), baseline, paint(context, size * .52f, true))
        return bitmap
    }
}
