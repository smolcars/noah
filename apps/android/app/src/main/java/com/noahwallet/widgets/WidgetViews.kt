package com.noahwallet.widgets

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import com.noahwallet.MainActivity
import com.noahwallet.R
import kotlin.math.roundToInt

/** The four layouts chosen by size (widgets.md, Android), and No wallet. */
internal enum class WidgetLayout(val resource: Int) {
    narrow(R.layout.widget_noah_narrow), wide(R.layout.widget_noah_wide), small(R.layout.widget_noah),
    medium(R.layout.widget_noah_medium), empty(R.layout.widget_noah_empty);

    companion object {
        /** Wide enough for Receive, Scan and Send beside the balance; tall enough for the wave. */
        const val WIDE_DP = 250f
        const val TALL_DP = 120f
        fun of(widthDp: Float, heightDp: Float): WidgetLayout = when {
            heightDp < TALL_DP -> if (widthDp < WIDE_DP) narrow else wide
            widthDp < WIDE_DP -> small
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
        WidgetLayout.empty -> WidgetSea.Frame(0f, 0f, widthDp, heightDp, heightDp, 44f, 0f, centred = true)
        else -> null
    }

    /** The balance's box (width and height, dp) and full text size (sp), from each layout's fixed dimensions. */
    fun balanceBox(layout: WidgetLayout, widthDp: Float): Triple<Float, Float, Float> = when (layout) {
        WidgetLayout.small -> Triple(widthDp - 36f, 34f, 30f)
        WidgetLayout.medium -> Triple(widthDp - 36f - 14f - 150f, 38f, 34f)
        // Padding, the longship and three 44 dp buttons with their gaps.
        WidgetLayout.wide -> Triple(widthDp - 32f - 42f - 3 * 52f, 28f, 24f)
        WidgetLayout.narrow -> Triple(widthDp - 32f - 30f, 28f, 24f)
        WidgetLayout.empty -> error("No balance without a wallet")
    }

    @Suppress("LongParameterList") // one slot: its content and flat geometry inputs
    fun build(context: Context, variant: String, content: WidgetContent?, widthDp: Float, heightDp: Float, density: Float): RemoteViews {
        val layout = if (content == null) WidgetLayout.empty else WidgetLayout.of(widthDp, heightDp)
        val views = RemoteViews(context.packageName, layout.resource)
        views.setOnClickPendingIntent(android.R.id.background, open(context, variant, Route.home))
        frame(layout, content, widthDp, heightDp)?.let { frame ->
            val drawn = WidgetSea.draw(content?.wave, frame, density)
            views.setImageViewBitmap(R.id.widget_sea, drawn.bitmap)
            views.setViewPadding(R.id.widget_sea_box, (frame.x * density).roundToInt(), (frame.y * density).roundToInt(), 0, 0)
            views.setViewPadding(R.id.widget_boat_box, (drawn.boatX * density).roundToInt(), (drawn.boatY * density).roundToInt(), 0, 0)
            views.setViewVisibility(R.id.widget_boat, View.VISIBLE)
        }
        if (content == null) return views
        views.setContentDescription(android.R.id.background, content.description)

        val (boxWidth, boxHeight, size) = balanceBox(layout, widthDp)
        views.setImageViewBitmap(R.id.widget_balance, WidgetType.balance(context, content.balance, content.satsSuffix, size, boxWidth, boxHeight, density))
        views.setContentDescription(R.id.widget_balance, content.balance)
        val oneRow = layout == WidgetLayout.narrow || layout == WidgetLayout.wide
        // One row shows one line: the status, or else the fiat.
        val fiat = content.fiat?.takeIf { !oneRow || content.status == null }
        views.setTextViewText(R.id.widget_fiat, fiat.orEmpty())
        views.setViewVisibility(R.id.widget_fiat, if (fiat != null) View.VISIBLE else View.GONE)
        status(views, layout, content.status)
        labels(views, layout, content)
        actions(context, views, variant, layout, content)
        return views
    }

    /** The chip and the change label, where the layout has them. */
    private fun labels(views: RemoteViews, layout: WidgetLayout, content: WidgetContent) {
        // The chip sits beside the change label, so only the layouts with a label have one.
        if (layout == WidgetLayout.small || layout == WidgetLayout.medium) {
            views.setTextViewText(R.id.widget_chip, content.chip.orEmpty())
            views.setViewVisibility(R.id.widget_chip, if (content.chip != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_change, content.change.orEmpty())
            views.setTextViewText(R.id.widget_change_days, content.days.orEmpty())
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
