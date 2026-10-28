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
 * and wallpaper colours still resolve in the launcher. Tabular digits; it shrinks to fit, down to half size.
 */
internal object WidgetType {
    private fun font(context: Context, id: Int, fallback: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, fallback)

    /** [widthDp] × [heightDp] is the balance's box; the text sits on its bottom, left aligned, like the layout's text. */
    @Suppress("LongParameterList") // a drawing primitive: the text, its size and the box it fits
    fun balance(context: Context, text: String, satsSuffix: Boolean, sizeSp: Float, widthDp: Float, heightDp: Float, density: Float): Bitmap {
        val main = if (satsSuffix && text.endsWith(" sats")) text.dropLast(5) else text
        val suffix = text.removePrefix(main)
        val full = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
        fun paint(size: Float, medium: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (medium) font(context, R.font.space_grotesk_medium, Typeface.NORMAL) else font(context, R.font.space_grotesk_semibold, Typeface.BOLD)
            textSize = size; color = Color.WHITE; fontFeatureSettings = "tnum"
            // "sats" after the number: about half size, Medium, no tightening.
            letterSpacing = if (medium) 0f else -.03f
        }
        fun width(size: Float) = paint(size, false).measureText(main) + if (suffix.isEmpty()) 0f else paint(size * .52f, true).measureText(suffix)
        val available = widthDp * density
        val size = (full * minOf(1f, available / width(full).coerceAtLeast(1f))).coerceAtLeast(full / 2)
        val height = (heightDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(ceil(width(size)).toInt().coerceAtLeast(1), height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val numbers = paint(size, false)
        // The line's bottom on the box's bottom, as a bottom-gravity TextView without font padding sets it.
        val baseline = height - numbers.fontMetrics.descent
        canvas.drawText(main, 0f, baseline, numbers)
        if (suffix.isNotEmpty()) canvas.drawText(suffix, numbers.measureText(main), baseline, paint(size * .52f, true))
        return bitmap
    }
}
