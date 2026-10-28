package com.noahwallet.widgets

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.StyleSpan
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.noahwallet.MainActivity
import com.noahwallet.R
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The four layouts chosen by size (widgets.md, Android), and No wallet. */
internal enum class WidgetLayout(val resource: Int) {
    narrow(R.layout.widget_noah_narrow), wide(R.layout.widget_noah_wide), small(R.layout.widget_noah),
    medium(R.layout.widget_noah_medium), empty(R.layout.widget_noah_empty);

    companion object {
        /**
         * By size in dp rather than cells (an Android 9 launcher's 4 columns are ~276 dp): one row takes Receive, Scan
         * and Send from 300 dp; two rows take the balance column, label, wave and capsules from 320 dp.
         */
        const val WIDE_ROW_DP = 300f
        const val MEDIUM_DP = 320f
        const val TALL_DP = 120f
        fun of(widthDp: Float, heightDp: Float): WidgetLayout = when {
            heightDp < TALL_DP -> if (widthDp < WIDE_ROW_DP) narrow else wide
            widthDp < MEDIUM_DP -> small
            else -> medium
        }
    }
}

/** Builds one slot's RemoteViews; drawing happens off the main thread (the provider's IO scope). */
internal object WidgetViews {
    /** The routes the widget opens (widgets.md, Opening the app from a widget), with distinct request codes. */
    enum class Route(val path: String, val requestCode: Int) { home("home", 4100), receive("receive", 4101), scan("scan", 4102), send("send", 4103) }

    fun uri(variant: String, route: Route): Uri = Uri.parse("com.noahwallet.$variant://widget/${route.path}")

    private fun open(context: Context, variant: String, route: Route): PendingIntent = PendingIntent.getActivity(context, route.requestCode,
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri(variant, route))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The slot's sea in dp, from the fixed heights in its layout; null for the one-row layouts. */
    fun frame(layout: WidgetLayout, content: WidgetContent?, widthDp: Float, heightDp: Float): WidgetSea.Frame? = when (layout) {
        WidgetLayout.small -> {
            // From the bottom: padding, the status line (Renew takes two lines here), fiat, then the balance.
            val status = when (content?.status) { is WidgetContent.Status.Renew -> 39f; is WidgetContent.Status.Arriving -> 22f; null -> 0f }
            val balanceTop = heightDp - 16f - status - (if (content?.fiat != null) 19f else 0f) - 34f
            WidgetSea.Frame(0f, 32f, widthDp, heightDp - 32f, maxOf(26f, balanceTop - 32f - 4f), 26f, 16f)
        }
        WidgetLayout.medium -> {
            val width = (widthDp * .52f).roundToInt().toFloat()
            val actionsTop = heightDp - 16f - 40f
            WidgetSea.Frame(widthDp - width, 34f, width, heightDp - 34f, maxOf(26f, actionsTop - 8f - 34f), 26f, 18f, fade = true)
        }
        WidgetLayout.empty -> {
            // The boat mid-widget on calm water; the sea starts just above the line, so only water is drawn.
            val line = (heightDp / 2 + (WidgetSea.HULL - .5f) * 44f + 1).roundToInt().toFloat()
            WidgetSea.Frame(0f, line - 6f, widthDp, heightDp - line + 6f, heightDp - line + 6f, 44f, 0f, centred = true, calm = 6f)
        }
        else -> null
    }

    /** The balance's box (dp), its full text size (sp) and how far it shrinks to fit the width ([WidgetType.balance]). */
    data class Box(val width: Float, val height: Float, val sp: Float, val floor: Float = .5f)

    /**
     * How far the medium balance shrinks beside the label, as a share of its full size: just under half, so a six-figure
     * change keeps " · 30 days" at 320 dp in every language and unit, network chip and all.
     */
    const val MEDIUM_FLOOR = .45f

    /** Each layout's balance box from its fixed dimensions; [label] is the medium label's width. */
    fun balanceBox(layout: WidgetLayout, widthDp: Float, label: Float = 0f): Box = when (layout) {
        WidgetLayout.small -> Box(widthDp - 36f, 34f, 30f)
        // The balance column has what the label leaves.
        WidgetLayout.medium -> Box(widthDp - 36f - 14f - label, 38f, 34f, MEDIUM_FLOOR)
        // Padding, the longship and three 44 dp buttons with their gaps.
        WidgetLayout.wide -> Box(widthDp - 32f - 42f - 3 * 52f, 28f, 24f)
        WidgetLayout.narrow -> Box(widthDp - 32f - 30f, 28f, 24f)
        WidgetLayout.empty -> error("""No balance without a wallet""")
    }

    /** The medium label's width (dp), and whether it keeps " · 30 days". */
    data class Label(val width: Float, val days: Boolean)

    /**
     * Medium: the label (chip, change and " · 30 days", [full] dp) takes the width it needs beside a [widthDp] widget and
     * the balance column the rest, so long as the balance ([balance] dp at full size) keeps [MEDIUM_FLOOR] of its size
     * there. Otherwise " · 30 days" goes ([short] dp), never the amount.
     */
    fun label(widthDp: Float, full: Float, short: Float, balance: Float): Label =
        if (widthDp - 36f - 14f - full >= balance * MEDIUM_FLOOR) Label(full, true) else Label(short, false)

    /** [label] for [content], measured as widget_noah_medium.xml sets the label and the balance. */
    fun label(context: Context, content: WidgetContent, widthDp: Float): Label {
        val metrics = context.resources.displayMetrics
        fun dp(text: String?, typeface: Typeface, sp: Float, tabular: Boolean = false): Float = if (text.isNullOrEmpty()) 0f else
            ceil(TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                // In whole pixels, as a TextView takes its size from XML.
                this.typeface = typeface; textSize = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics) + .5f).toInt().toFloat()
                if (tabular) fontFeatureSettings = "tnum"
            }.measureText(text)) / metrics.density
        // The chip with its padding and gap, the change at weight 600, then the days.
        val chip = content.chip?.let { dp(it, Typeface.create("sans-serif-medium", Typeface.NORMAL), 11f) + 14f + 6f } ?: 0f
        val semibold = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 600, false) else Typeface.DEFAULT_BOLD
        val short = chip + dp(content.change, semibold, 12f, tabular = true)
        return label(widthDp, short + dp(content.days, Typeface.DEFAULT, 11.5f), short,
            WidgetType.width(context, content.balance, content.satsSuffix, 34f) / metrics.density)
    }

    /**
     * The launcher refuses an update whose bitmaps exceed 1.5 × its display's pixels × 4 bytes (AppWidgetService), across
     * every size in the map, and fixes that display at boot. An unfolded Fold lists only its inner display, though it may
     * have booted closed, so this takes the smallest display listed but never more than [BOOT_DISPLAY_BOUND], with a fifth
     * to spare.
     */
    @Suppress("DEPRECATION")
    fun bitmapBudget(context: Context): Long = budget(context.getSystemService(android.hardware.display.DisplayManager::class.java)?.displays.orEmpty()
        .map { android.graphics.Point().also(it::getRealSize) }.filter { it.x > 0 && it.y > 0 }.map { it.x.toLong() * it.y }
        .ifEmpty { listOf(context.resources.displayMetrics.let { it.widthPixels.toLong() * it.heightPixels }) })

    /** A boot display no larger than 1080 × 1920: below a Pixel Fold's (1080 × 2092) and a Galaxy Fold's (904 × 2316) cover. */
    const val BOOT_DISPLAY_BOUND = 1080L * 1920

    fun budget(displayPixels: List<Long>): Long = 6 * minOf(displayPixels.minOrNull() ?: BOOT_DISPLAY_BOUND, BOOT_DISPLAY_BOUND) * 4 / 5

    /** One update's views by size, and every distinct bitmap they hold. */
    class Built(val views: Map<SizeF, RemoteViews>, val bitmaps: List<Bitmap>)

    /**
     * Every size's views, as one update. Identical seas and balances share a bitmap, and the seas' resolution drops evenly
     * (never below a fifth) until the whole map fits [budget].
     */
    @Suppress("LongParameterList") // every slot: its content, sizes and the bitmap budget
    fun buildAll(context: Context, variant: String, content: WidgetContent?, sizes: List<SizeF>, density: Float, budget: Long): Built {
        val layouts = sizes.associateWith { if (content == null) WidgetLayout.empty else WidgetLayout.of(it.width, it.height) }
        val labels = if (content == null) emptyMap()
        else layouts.filterValues { it == WidgetLayout.medium }.mapValues { (size, _) -> label(context, content, size.width) }
        fun box(size: SizeF) = balanceBox(layouts.getValue(size), size.width, labels[size]?.width ?: 0f)
        val balances = if (content == null) emptyMap() else layouts.keys.map(::box).distinct()
            .associateWith { WidgetType.balance(context, content.balance, content.satsSuffix, it.sp, it.width, it.height, density, it.floor) }
        val frames = layouts.mapNotNull { (size, layout) -> frame(layout, content, size.width, size.height) }.distinct()
        fun bytes(scale: Float) = frames.sumOf { WidgetSea.pixels(it, scale).let { (w, h) -> w.toLong() * h * 4 } }
        val room = budget - balances.values.sumOf { it.allocationByteCount.toLong() }
        val scale = if (bytes(density) <= room) density
        else (density * sqrt(room.coerceAtLeast(0).toDouble() / bytes(density)) * .98).toFloat().coerceAtLeast(density / 5)
        val seas = frames.associateWith { WidgetSea.draw(content?.wave, it, scale) }
        val views = layouts.mapValues { (size, layout) ->
            views(context, variant, content, layout, size, frame(layout, content, size.width, size.height)?.let(seas::getValue),
                content?.let { balances.getValue(box(size)) }, labels[size], density)
        }
        return Built(views, seas.values.map { it.bitmap } + balances.values)
    }

    /** One size alone, at full resolution. */
    @Suppress("LongParameterList") // one slot: its content and flat geometry inputs
    fun build(context: Context, variant: String, content: WidgetContent?, widthDp: Float, heightDp: Float, density: Float): RemoteViews =
        buildAll(context, variant, content, listOf(SizeF(widthDp, heightDp)), density, Long.MAX_VALUE).views.values.single()

    @Suppress("LongParameterList") // one slot's views from the parts every size shares
    private fun views(context: Context, variant: String, content: WidgetContent?, layout: WidgetLayout, size: SizeF,
        drawn: WidgetSea.Drawn?, balance: Bitmap?, label: Label?, density: Float): RemoteViews {
        val views = RemoteViews(context.packageName, layout.resource)
        views.setOnClickPendingIntent(android.R.id.background, open(context, variant, Route.home))
        val frame = frame(layout, content, size.width, size.height)
        if (frame != null && drawn != null) {
            views.setImageViewBitmap(R.id.widget_sea, drawn.bitmap)
            views.setViewPadding(R.id.widget_sea_box, (frame.x * density).roundToInt(), (frame.y * density).roundToInt(), 0, 0)
            views.setViewPadding(R.id.widget_boat_box, (drawn.boatX * density).roundToInt(), (drawn.boatY * density).roundToInt(), 0, 0)
            views.setViewVisibility(R.id.widget_boat, View.VISIBLE)
        }
        if (content == null) return views
        views.setContentDescription(android.R.id.background, content.description)

        views.setImageViewBitmap(R.id.widget_balance, balance)
        views.setContentDescription(R.id.widget_balance, content.balance)
        val oneRow = layout == WidgetLayout.narrow || layout == WidgetLayout.wide
        // One row shows one line: the status, or else the fiat.
        val fiat = content.fiat?.takeIf { !oneRow || content.status == null }
        views.setTextViewText(R.id.widget_fiat, fiat.orEmpty())
        views.setViewVisibility(R.id.widget_fiat, if (fiat != null) View.VISIBLE else View.GONE)
        status(views, layout, content.status)
        labels(views, layout, content, label)
        actions(context, views, variant, layout, content)
        return views
    }

    /** The chip and the change label, where the layout has them; [label] says whether medium keeps " · 30 days". */
    private fun labels(views: RemoteViews, layout: WidgetLayout, content: WidgetContent, label: Label?) {
        // The chip sits beside the change label, so only the layouts with a label have one.
        if (layout == WidgetLayout.small || layout == WidgetLayout.medium) {
            views.setTextViewText(R.id.widget_chip, content.chip.orEmpty())
            views.setViewVisibility(R.id.widget_chip, if (content.chip != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_change, content.change.orEmpty())
            views.setTextViewText(R.id.widget_change_days, if (label?.days == false) "" else content.days.orEmpty())
        }
    }

    /** Receive, Scan and Send, where the layout has them. */
    private fun actions(context: Context, views: RemoteViews, variant: String, layout: WidgetLayout, content: WidgetContent) {
        if (layout == WidgetLayout.medium || layout == WidgetLayout.wide) {
            for ((id, route, label) in listOf(Triple(R.id.widget_receive, Route.receive, content.receive),
                Triple(R.id.widget_scan, Route.scan, content.scan), Triple(R.id.widget_send, Route.send, content.send))) {
                views.setOnClickPendingIntent(id, open(context, variant, route))
                views.setContentDescription(id, label)
            }
            if (layout == WidgetLayout.medium) {
                views.setTextViewText(R.id.widget_receive_label, content.receive)
                views.setTextViewText(R.id.widget_scan_label, content.scan)
                views.setTextViewText(R.id.widget_send_label, content.send)
            }
        }
    }

    private fun status(views: RemoteViews, layout: WidgetLayout, status: WidgetContent.Status?) {
        views.setViewVisibility(R.id.widget_status, if (status != null) View.VISIBLE else View.GONE)
        val icons = layout != WidgetLayout.narrow
        if (icons) {
            views.setViewVisibility(R.id.widget_status_icon, if (status is WidgetContent.Status.Renew) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_status_dot, if (status is WidgetContent.Status.Arriving) View.VISIBLE else View.GONE)
        }
        val detail = layout == WidgetLayout.small || layout == WidgetLayout.medium
        when (status) {
            is WidgetContent.Status.Renew -> if (detail) {
                // Small: Renew, then the expiry below; medium: one line, "Renew · Expires in 18 hours".
                views.setTextViewText(R.id.widget_status_title, SpannableString(status.title).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                })
                views.setTextViewText(R.id.widget_status_detail, if (layout == WidgetLayout.medium) " · ${status.expiry}" else status.expiry)
                views.setViewVisibility(R.id.widget_status_detail, View.VISIBLE)
            } else views.setTextViewText(R.id.widget_status_title, status.expiry)
            is WidgetContent.Status.Arriving -> {
                views.setTextViewText(R.id.widget_status_title, status.text)
                if (detail) views.setViewVisibility(R.id.widget_status_detail, View.GONE)
            }
            null -> {}
        }
    }
}
