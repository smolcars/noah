package com.noahwallet.widgets

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.noahwallet.MainActivity
import com.noahwallet.R
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The four layouts chosen by size (widgets.md, Android), and No wallet; [WidgetViews.plan] steps down to one row where two don't fit. */
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

    fun uri(variant: String, route: Route): Uri = "com.noahwallet.$variant://widget/${route.path}".toUri()

    private fun open(context: Context, variant: String, route: Route): PendingIntent = PendingIntent.getActivity(context, route.requestCode,
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri(variant, route))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * The slot's sea in dp, from the heights in its layout; null for the one-row layouts. The 2×2's status line, fiat and
     * label row grow with large text: [status], [fiat] and [labelRow] are their measured heights (dp, [plan]).
     */
    @Suppress("LongParameterList") // the slot, and the measured rows that move its sea
    fun frame(layout: WidgetLayout, content: WidgetContent?, widthDp: Float, heightDp: Float, status: Float? = null,
        labelRow: Float = 18f, lift: Float = 0f, fiat: Float? = null): WidgetSea.Frame? = when (layout) {
        WidgetLayout.small -> {
            // From the bottom: padding, the status line (Renew takes two lines here), fiat, then the balance.
            val lines = status ?: when (content?.status) { is WidgetContent.Status.Renew -> 39f; is WidgetContent.Status.Arriving -> 22f; null -> 0f }
            val balanceTop = heightDp - 16f - lines - (fiat ?: if (content?.fiat != null) 19f else 0f) - 34f
            // From the top: padding and the label row.
            val top = 15f + labelRow - 1f
            WidgetSea.Frame(0f, top, widthDp, heightDp - top, maxOf(26f, balanceTop - top - 4f), 26f, 16f)
        }
        WidgetLayout.medium -> {
            val width = (widthDp * .52f).roundToInt().toFloat()
            val actionsTop = heightDp - 16f - 40f
            // Below the label's own line ([lift]), if it has one: the boat never sails over it.
            val top = 34f + lift
            WidgetSea.Frame(widthDp - width, top, width, heightDp - top, maxOf(26f, actionsTop - 8f - top), 26f, 18f, fade = true)
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

    /** Each layout's balance box from its fixed dimensions, beside the medium [label]. */
    fun balanceBox(layout: WidgetLayout, widthDp: Float, label: Label? = null): Box = when (layout) {
        WidgetLayout.small -> Box(widthDp - 36f, 34f, 30f)
        // The balance column has what the label leaves, or the whole row under a label on its own line or left out.
        WidgetLayout.medium -> Box(widthDp - 36f - if (label == null || label.line || !label.shown) 0f else 14f + label.width, 38f, 34f, MEDIUM_FLOOR)
        // Padding, the longship and three 44 dp buttons with their gaps.
        WidgetLayout.wide -> Box(widthDp - 32f - 42f - 3 * 52f, 28f, 24f)
        WidgetLayout.narrow -> Box(widthDp - 32f - 30f, 28f, 24f)
        WidgetLayout.empty -> error("""No balance without a wallet""")
    }

    /** How far the medium label's text shrinks beside the balance before it takes a line of its own. */
    const val LABEL_FLOOR = .8f

    /**
     * The medium label: its width (dp), whether it keeps " · 30 days", its text's scale, whether it has a line of its own
     * above the balance, whether it shows at all, and how far its own line and the gap under it lower the rest (dp).
     */
    data class Label(val width: Float, val days: Boolean, val scale: Float = 1f, val line: Boolean = false, val shown: Boolean = true, val lift: Float = 0f)

    /**
     * The label's widths at full size (dp): the chip's padding and gap, the chip's text, the change and " · 30 days"; and
     * the height of a line of its own (dp, without the gap under it).
     */
    data class LabelParts(val fixed: Float, val chip: Float, val change: Float, val days: Float, val tall: Float)

    /**
     * Medium: the label takes the width it needs beside a [widthDp] widget and the balance column the rest, never less
     * than the balance's narrowest ([balance] dp, [WidgetType.minWidth]). Where the label doesn't fit, " · 30 days" goes,
     * then its text shrinks down to [LABEL_FLOOR]. Then it takes a line of its own above the balance, which has the whole
     * row, if the [spare] height (dp) above the status line and the Actions allows; otherwise the label is left out (the
     * wave still shows the trend). The balance, fiat, status line and Actions always show whole.
     */
    fun label(widthDp: Float, parts: LabelParts, balance: Float, spare: Float): Label {
        val row = widthDp - 36f
        val text = parts.chip + parts.change
        fun fit(room: Float, line: Boolean): Label? = when {
            parts.fixed + text + parts.days <= room -> Label(parts.fixed + text + parts.days, true, line = line)
            parts.fixed + text <= room -> Label(parts.fixed + text, false, line = line)
            // 2 dp to spare for each view's whole pixels.
            else -> ((room - parts.fixed - 2f) / text).takeIf { it >= LABEL_FLOOR }?.let { Label(room, false, it, line) }
        }
        fit(row - 14f - balance, false)?.let { return it }
        // Its own line and the 4 dp under it.
        if (parts.tall + 4f <= spare) return (fit(row, true) ?: Label(row, false, LABEL_FLOOR, true)).copy(lift = parts.tall + 4f)
        return Label(0f, false, shown = false)
    }

    /** Text as the widget's TextViews set it out, measured in dp at the system's font scale. */
    private class Type(val context: Context) {
        val metrics = context.resources.displayMetrics!!
        val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val semibold: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 600, false) else Typeface.DEFAULT_BOLD

        /** [sp] in whole pixels, as a TextView takes its size from XML, or [exact] as the widget sets it. */
        fun paint(typeface: Typeface, sp: Float, tabular: Boolean = false, exact: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            this.typeface = typeface; textSize = if (exact) px else (px + .5f).toInt().toFloat()
            if (tabular) fontFeatureSettings = "tnum"
        }

        fun wide(text: CharSequence?, paint: TextPaint): Float = if (text.isNullOrEmpty()) 0f else ceil(Layout.getDesiredWidth(text, paint)) / metrics.density

        /** Where [text] may break across lines: each piece must fit a line, or the line breaks inside it. */
        fun segments(text: CharSequence): List<String> {
            val breaks = java.text.BreakIterator.getLineInstance()
            val string = text.toString()
            breaks.setText(string)
            return generateSequence(breaks.first() to breaks.next()) { (_, end) -> end to breaks.next() }
                .takeWhile { (_, end) -> end != java.text.BreakIterator.DONE }.map { (start, end) -> string.substring(start, end).trimEnd() }.toList()
        }

        /**
         * [text] laid out [widthDp] wide (on one line if null) as its TextView draws it: simple breaks and no hyphens (the
         * builder's defaults, as the status views set them), the fallback fonts' line spacing, and font padding only where
         * the view keeps it ([pad]).
         */
        fun layout(text: CharSequence, paint: TextPaint, widthDp: Float? = null, pad: Boolean = false): StaticLayout {
            val width = widthDp?.let { (it * metrics.density).toInt() } ?: (ceil(Layout.getDesiredWidth(text, paint)).toInt() + 1)
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1)).setIncludePad(pad)
                .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }.build()
        }

        /** [text]'s lines and height (dp), laid out as [layout]. */
        fun lines(text: CharSequence?, paint: TextPaint, widthDp: Float? = null, pad: Boolean = false): Pair<Int, Float> {
            if (text.isNullOrEmpty()) return 0 to 0f
            return layout(text, paint, widthDp, pad).let { it.lineCount to it.height / metrics.density }
        }
    }

    /** The medium label for [content] [widthDp] wide, with [spare] dp of height for a line of its own. */
    private fun label(type: Type, content: WidgetContent, widthDp: Float, spare: Float): Label {
        // The chip with its padding and gap, the change at weight 600, then the days.
        val chip = type.paint(type.medium, 11f)
        val change = type.paint(type.semibold, 12f, tabular = true)
        val days = type.paint(Typeface.DEFAULT, 11.5f)
        fun tall(text: String?, paint: TextPaint) = type.lines(text, paint).second
        val parts = LabelParts(if (content.chip != null) 14f + 6f else 0f, type.wide(content.chip, chip), type.wide(content.change, change),
            type.wide(content.days, days),
            maxOf(18f, if (content.chip != null) tall(content.chip, chip) + 6f else 0f, tall(content.change, change), tall(content.days, days)))
        val density = type.metrics.density
        return label(widthDp, parts, WidgetType.minWidth(type.context, content.balance, content.satsSuffix, 34f, 38f, density, MEDIUM_FLOOR) / density, spare)
    }

    /** The 2×2's label row (dp): it grows with large text, at least 18 dp. */
    private fun labelRow(type: Type, content: WidgetContent): Float {
        fun tall(text: String?, paint: TextPaint) = type.lines(text, paint).second
        return maxOf(18f, if (content.chip != null) tall(content.chip, type.paint(type.medium, 11f)) + 6f else 0f,
            tall(content.change, type.paint(type.semibold, 12f, tabular = true)), tall(content.days, type.paint(Typeface.DEFAULT, 11.5f)))
    }

    /**
     * The status line as set out: its text size (sp, before the font scale), its height (dp, with the gap above it), each
     * part's lines, whether the medium layout puts the expiry on a line of its own, and whether it was [forced] to the
     * lines its room holds.
     */
    data class StatusFit(val sp: Float, val tall: Float, val lines: List<Int>, val split: Boolean = false, val forced: Boolean = false)

    /** Width (dp) left over when fitting text: each view in a row rounds to whole pixels, up to half a pixel each. */
    const val SPARE = 3f

    /** The status line goes down to this in two rows (sp, before the font scale). */
    const val STATUS_FLOOR = 11f

    /** One row's status, and the fiat everywhere, go down to this, as their autoSize did (sp, before the font scale). */
    const val LINE_FLOOR = 9f

    /**
     * The status line in a [widthDp] [layout] with [roomDp] of height for it: at the largest size from 13 sp (12 in one
     * row) down to its floor that fits each part on one line, else wrapped (medium: "Renew" and the expiry under it, as on
     * iOS) onto as many lines as the room holds, breaking only where the text may break. Every height is the real text's,
     * laid out as its TextView draws it; its row grows with it, so large text is never cut. Null where it doesn't fit,
     * unless [force]d: then at the floor, on the lines the room holds.
     */
    private class StatusLayout(val type: Type, val layout: WidgetLayout, status: WidgetContent.Status, widthDp: Float, val roomDp: Float) {
        val oneRow = layout == WidgetLayout.narrow || layout == WidgetLayout.wide
        val medium = layout == WidgetLayout.medium
        val renew = status as? WidgetContent.Status.Renew
        val title: CharSequence = when (status) {
            is WidgetContent.Status.Renew -> if (oneRow) status.expiry else bold(status.title)
            is WidgetContent.Status.Arriving -> status.text
        }

        // The text's room: the column, less the icon or dot and its gap.
        val row = when (layout) {
            WidgetLayout.medium -> widthDp - 36f - 48f
            WidgetLayout.small -> widthDp - 36f
            WidgetLayout.wide -> widthDp - 32f - 42f - 3 * 52f
            else -> widthDp - 32f - 30f
        }
        val icon = when {
            layout == WidgetLayout.narrow -> 0f
            renew != null -> if (oneRow) 16f else 19f
            else -> if (oneRow) 10f else 12f
        }
        val gap = if (oneRow) 1f else 5f
        val least = if (oneRow) 16f else 17f
        val floor = if (oneRow) LINE_FLOOR else STATUS_FLOOR
        val sizes = generateSequence(if (oneRow) 12f else 13f) { it - .5f }.takeWhile { it >= floor }.toList()

        // Each part's text and room: medium Renew on one line, or split as the 2×2 always has it.
        fun parts(split: Boolean): List<Pair<CharSequence, Float>> = when {
            renew == null || oneRow -> listOf(title to row - icon)
            medium && !split -> listOf(SpannableStringBuilder(title).append(" · ${renew.expiry}") to row - icon)
            else -> listOf(title to row - icon, renew.expiry to row)
        }

        fun paint(sp: Float) = type.paint(type.medium, sp, exact = true)
        fun laid(text: CharSequence, room: Float, sp: Float) = type.layout(text, paint(sp), room - SPARE)
        fun breaks(text: CharSequence, room: Float, sp: Float) = type.segments(text).all { type.wide(it, paint(sp)) <= room - SPARE }
        fun tall(parts: List<Pair<CharSequence, Float>>, sp: Float) =
            gap + parts.sumOf { (text, room) -> maxOf(least, laid(text, room, sp).height / type.metrics.density).toDouble() }.toFloat()
        fun oneLine(parts: List<Pair<CharSequence, Float>>, sp: Float) =
            parts.all { (text, room) -> laid(text, room, sp).lineCount == 1 } && tall(parts, sp) <= roomDp
        fun wrapped(parts: List<Pair<CharSequence, Float>>, sp: Float) = parts.all { (text, room) -> breaks(text, room, sp) } && tall(parts, sp) <= roomDp

        /** The status line as it fits, or null where it doesn't, unless [force]d. */
        fun fit(force: Boolean): StatusFit? {
            val one = if (medium && renew != null) sizes.firstOrNull { oneLine(parts(false), it) } else null
            val split = medium && renew != null && one == null
            val parts = parts(split)
            val sp = one ?: sizes.firstOrNull { oneLine(parts, it) } ?: sizes.firstOrNull { wrapped(parts, it) }
            if (sp != null) return StatusFit(sp, tall(parts, sp), parts.map { (text, room) -> laid(text, room, sp).lineCount }, split)
            if (!force) return null
            // The last resort, in one row: at the floor, the lines the room holds.
            val (text, room) = parts.first()
            val lines = laid(text, room, floor)
            val shown = (1..lines.lineCount).lastOrNull { gap + lines.getLineBottom(it - 1) / type.metrics.density <= roomDp } ?: 1
            return StatusFit(floor, gap + maxOf(least, lines.getLineBottom(shown - 1) / type.metrics.density), listOf(shown), forced = true)
        }
    }

    /** A line of text as set out: its size (sp, before the font scale) and its height (dp, with the gap above it). */
    data class Line(val sp: Float, val tall: Float)

    /** The fiat on one line [roomDp] wide: from 13 sp (12 in one row) down to [LINE_FLOOR], its row growing with the text. */
    private fun fiat(type: Type, layout: WidgetLayout, text: String, roomDp: Float): Line {
        val oneRow = layout == WidgetLayout.narrow || layout == WidgetLayout.wide
        fun paint(sp: Float) = type.paint(type.medium, sp, exact = true)
        val sp = generateSequence(if (oneRow) 12f else 13f) { it - .5f }.takeWhile { it >= LINE_FLOOR }
            .firstOrNull { type.wide(text, paint(it)) <= roomDp - SPARE } ?: LINE_FLOOR
        return Line(sp, (if (oneRow) 1f else 2f) + maxOf(if (oneRow) 16f else 17f, type.lines(text, paint(sp)).second))
    }

    /** The 2×2's room for its sea above the balance (dp, [frame]): the 2×2 is planned only where the boat never meets the digits. */
    const val SEA_ROOM = 29f

    /** The Actions' labels never go below this (sp, before the font scale); below it the buttons show their icons alone. */
    const val ACTION_FLOOR = 11f

    /**
     * The medium Actions' label size (sp, before the font scale) at which all three fit their 40 dp buttons, across and
     * down (their TextViews keep font padding); null for icons alone.
     */
    private fun actions(type: Type, content: WidgetContent, widthDp: Float): Float? {
        // Three buttons 8 dp apart, each with 6 dp of padding a side, the 16 dp icon and 6 dp before the label.
        val room = (widthDp - 36f - 16f) / 3 - 12f - 16f - 6f - SPARE
        return generateSequence(14f) { it - .5f }.takeWhile { it >= ACTION_FLOOR }.firstOrNull { sp ->
            val paint = type.paint(type.medium, sp, exact = true)
            listOf(content.receive, content.scan, content.send).all { type.wide(it, paint) <= room && type.lines(it, paint, pad = true).second <= 40f }
        }
    }

    private fun bold(text: String) = SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    /**
     * One size as set out: its layout, the status line, the medium label, the 2×2's label row (dp), the medium Actions'
     * label size (sp, [actions]; null for icons alone), and the fiat line.
     */
    data class Plan(val layout: WidgetLayout, val status: StatusFit? = null, val label: Label? = null, val labelRow: Float = 18f,
        val actions: Float? = null, val fiat: Line? = null)

    /**
     * The layout for a [widthDp] × [heightDp] slot: by size, but a two-row layout only where its content fits the height
     * at the system's font scale (padding, balance, fiat, the status line and the Actions or the label row), else the
     * one-row layout for its width; the 4×1 only where its column takes the balance (at its floor) and the status line,
     * else the 2×1, which gives them the whole width.
     */
    fun plan(context: Context, content: WidgetContent?, widthDp: Float, heightDp: Float): Plan {
        if (content == null) return Plan(WidgetLayout.empty)
        val planner = Planner(context, content, widthDp, heightDp)
        val first = WidgetLayout.of(widthDp, heightDp)
        return listOf(first, WidgetLayout.of(widthDp, 0f), WidgetLayout.narrow).distinct().firstNotNullOfOrNull { planner.plan(it) }
            ?: planner.plan(WidgetLayout.narrow, force = true)!!
    }

    /** One slot's layouts as [plan] sets each out: null where its content doesn't fit the height, unless [plan] is forced. */
    private class Planner(val context: Context, val content: WidgetContent, val widthDp: Float, val heightDp: Float) {
        val type = Type(context)

        fun plan(layout: WidgetLayout, force: Boolean = false): Plan? {
            val fiatText = fiatText(layout)
            val fiat = fiatText?.let { fiat(type, layout, it, column(layout)) }
            val row = if (layout == WidgetLayout.small) labelRow(type, content) else 18f
            val fixed = fixed(layout, fiat, row)
            val status = content.status?.let { StatusLayout(type, layout, it, widthDp, heightDp - fixed).fit(force) ?: return null }
            val needs = fixed + (status?.tall ?: 0f)
            if (needs > heightDp && !force) return null
            return when (layout) {
                WidgetLayout.medium -> medium(status, needs, fiatText)
                WidgetLayout.small -> Plan(layout, status, labelRow = row, fiat = fiat)
                // The 44 dp buttons, and the balance at its floor.
                WidgetLayout.wide -> if (heightDp < 44f || !wideFits()) null else Plan(layout, status, fiat = fiat)
                else -> Plan(layout, status, fiat = fiat)
            }
        }

        // One row shows the status, or else the fiat, under the balance.
        private fun fiatText(layout: WidgetLayout): String? {
            val oneRow = layout == WidgetLayout.narrow || layout == WidgetLayout.wide
            return content.fiat?.takeIf { !oneRow || content.status == null }
        }

        private fun column(layout: WidgetLayout) = when (layout) {
            WidgetLayout.medium, WidgetLayout.small -> widthDp - 36f
            else -> balanceBox(layout, widthDp).width
        }

        // Everything but the status line: padding, the label row, the balance, the fiat and the Actions.
        private fun fixed(layout: WidgetLayout, fiat: Line?, row: Float) = (fiat?.tall ?: 0f) + when (layout) {
            WidgetLayout.medium -> 17f + 38f + 40f + 16f
            // The sea's shallowest band (26 dp) and its 4 dp gap above the balance, less the 1 dp it shares with the label row.
            WidgetLayout.small -> 15f + row + 34f + 16f + SEA_ROOM
            else -> 28f
        }

        private fun medium(status: StatusFit?, needs: Float, fiatText: String?): Plan {
            val label = label(type, content, widthDp, heightDp - needs)
            // The fiat again for the column the label leaves: never taller than what was set aside.
            return Plan(WidgetLayout.medium, status, label, actions = actions(type, content, widthDp),
                fiat = fiatText?.let { fiat(type, WidgetLayout.medium, it, balanceBox(WidgetLayout.medium, widthDp, label).width) })
        }

        // The balance at its floor in the 4×1's column, with 1 dp to spare for the views' whole pixels.
        private fun wideFits(): Boolean {
            val density = type.metrics.density
            val narrowest = WidgetType.minWidth(context, content.balance, content.satsSuffix, 24f, 28f, density, .5f) / density
            return balanceBox(WidgetLayout.wide, widthDp).width >= narrowest + 1f
        }
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
        val plans = sizes.associateWith { plan(context, content, it.width, it.height) }
        fun box(size: SizeF) = plans.getValue(size).let { balanceBox(it.layout, size.width, it.label) }
        fun frame(size: SizeF) =
            plans.getValue(size).let {
                frame(it.layout, content, size.width, size.height, it.status?.tall ?: 0f, it.labelRow, it.label?.lift ?: 0f, it.fiat?.tall ?: 0f)
            }
        val balances = if (content == null) emptyMap() else plans.keys.map(::box).distinct()
            .associateWith { WidgetType.balance(context, content.balance, content.satsSuffix, it.sp, it.width, it.height, density, it.floor) }
        val frames = plans.keys.mapNotNull(::frame).distinct()
        fun bytes(scale: Float) = frames.sumOf { WidgetSea.pixels(it, scale).let { (w, h) -> w.toLong() * h * 4 } }
        val room = budget - balances.values.sumOf { it.allocationByteCount.toLong() }
        val scale = if (bytes(density) <= room) density
        else (density * sqrt(room.coerceAtLeast(0).toDouble() / bytes(density)) * .98).toFloat().coerceAtLeast(density / 5)
        val seas = frames.associateWith { WidgetSea.draw(content?.wave, it, scale) }
        val views = plans.mapValues { (size, plan) ->
            frame(size).let { views(context, variant, content, plan, it, it?.let(seas::getValue), content?.let { balances.getValue(box(size)) }, density) }
        }
        return Built(views, seas.values.map { it.bitmap } + balances.values)
    }

    /** One size alone, at full resolution. */
    @Suppress("LongParameterList") // one slot: its content and flat geometry inputs
    fun build(context: Context, variant: String, content: WidgetContent?, widthDp: Float, heightDp: Float, density: Float): RemoteViews =
        buildAll(context, variant, content, listOf(SizeF(widthDp, heightDp)), density, Long.MAX_VALUE).views.values.single()

    @Suppress("LongParameterList") // one slot's views from the parts every size shares
    private fun views(context: Context, variant: String, content: WidgetContent?, plan: Plan, frame: WidgetSea.Frame?,
        drawn: WidgetSea.Drawn?, balance: Bitmap?, density: Float): RemoteViews {
        val layout = plan.layout
        val label = plan.label
        val views = RemoteViews(context.packageName, layout.resource)
        views.setOnClickPendingIntent(android.R.id.background, open(context, variant, Route.home))
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
        plan.fiat?.let { views.setTextViewTextSize(R.id.widget_fiat, TypedValue.COMPLEX_UNIT_SP, it.sp) }
        status(views, layout, content.status, plan.status)
        labels(views, layout, content, label)
        buttons(context, views, variant, plan, content)
        return views
    }

    /** The chip and the change label, where the layout has them; [label] places medium's. */
    private fun labels(views: RemoteViews, layout: WidgetLayout, content: WidgetContent, label: Label?) {
        // The chip sits beside the change label, so only the layouts with a label have one.
        if (layout == WidgetLayout.small || layout == WidgetLayout.medium) {
            // Medium: beside the balance, on a line of its own above it, or left out.
            val line = label?.line == true
            if (layout == WidgetLayout.medium) {
                views.setViewVisibility(R.id.widget_label, if (line || label?.shown == false) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widget_label_line, if (line) View.VISIBLE else View.GONE)
            }
            val (chip, change, days) = if (line) Triple(R.id.widget_chip_line, R.id.widget_change_line, R.id.widget_change_days_line)
            else Triple(R.id.widget_chip, R.id.widget_change, R.id.widget_change_days)
            views.setTextViewText(chip, content.chip.orEmpty())
            views.setViewVisibility(chip, if (content.chip != null) View.VISIBLE else View.GONE)
            views.setTextViewText(change, content.change.orEmpty())
            views.setTextViewText(days, if (label?.days == false) "" else content.days.orEmpty())
            if (label != null && label.scale < 1f) for ((id, sp) in listOf(chip to 11f, change to 12f, days to 11.5f))
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp * label.scale)
        }
    }

    /** Receive, Scan and Send, where the layout has them. */
    private fun buttons(context: Context, views: RemoteViews, variant: String, plan: Plan, content: WidgetContent) {
        val layout = plan.layout
        if (layout == WidgetLayout.medium || layout == WidgetLayout.wide) {
            for ((id, route, label) in listOf(Triple(R.id.widget_receive, Route.receive, content.receive),
                Triple(R.id.widget_scan, Route.scan, content.scan), Triple(R.id.widget_send, Route.send, content.send))) {
                views.setOnClickPendingIntent(id, open(context, variant, route))
                views.setContentDescription(id, label)
            }
            if (layout == WidgetLayout.medium) buttonLabels(views, plan, content)
        }
    }

    /** Medium: labels at the size all three fit, or icons alone, the label still the button's description; never an ellipsis. */
    private fun buttonLabels(views: RemoteViews, plan: Plan, content: WidgetContent) {
        val labels = listOf(R.id.widget_receive_label to content.receive, R.id.widget_scan_label to content.scan,
            R.id.widget_send_label to content.send)
        for ((id, text) in labels) {
            views.setTextViewText(id, text)
            views.setViewVisibility(id, if (plan.actions != null) View.VISIBLE else View.GONE)
            plan.actions?.let { views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, it) }
        }
    }

    private fun status(views: RemoteViews, layout: WidgetLayout, status: WidgetContent.Status?, fit: StatusFit?) {
        views.setViewVisibility(R.id.widget_status, if (status != null) View.VISIBLE else View.GONE)
        val icons = layout != WidgetLayout.narrow
        if (icons) {
            views.setViewVisibility(R.id.widget_status_icon, if (status is WidgetContent.Status.Renew) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_status_dot, if (status is WidgetContent.Status.Arriving) View.VISIBLE else View.GONE)
        }
        val medium = layout == WidgetLayout.medium
        val detail = layout == WidgetLayout.small || medium
        // Medium: "Renew · Expires in 18 hours" on one line, or the expiry on a line of its own; small: always under it.
        val split = medium && fit?.split == true
        when (status) {
            is WidgetContent.Status.Renew -> if (detail) renew(views, status, medium, split)
            else views.setTextViewText(R.id.widget_status_title, status.expiry)
            is WidgetContent.Status.Arriving -> {
                views.setTextViewText(R.id.widget_status_title, status.text)
                if (detail) views.setViewVisibility(R.id.widget_status_detail, View.GONE)
                if (medium) views.setViewVisibility(R.id.widget_status_detail_line, View.GONE)
            }
            null -> {}
        }
        // Sized to fit (status): every line of the status at one size, each part on the lines it was measured to take.
        if (fit != null) size(views, fit, detail, medium)
    }

    /** Renew's title, then its expiry: beside it or on a line of its own on medium, under it on the 2×2. */
    private fun renew(views: RemoteViews, status: WidgetContent.Status.Renew, medium: Boolean, split: Boolean) {
        views.setTextViewText(R.id.widget_status_title, bold(status.title))
        if (medium) {
            views.setTextViewText(R.id.widget_status_detail, " · ${status.expiry}")
            views.setViewVisibility(R.id.widget_status_detail, if (split) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widget_status_detail_line, status.expiry)
            views.setViewVisibility(R.id.widget_status_detail_line, if (split) View.VISIBLE else View.GONE)
        } else {
            views.setTextViewText(R.id.widget_status_detail, status.expiry)
            views.setViewVisibility(R.id.widget_status_detail, View.VISIBLE)
        }
    }

    private fun size(views: RemoteViews, fit: StatusFit, detail: Boolean, medium: Boolean) {
        val ids = listOfNotNull(R.id.widget_status_title, R.id.widget_status_detail.takeIf { detail }, R.id.widget_status_detail_line.takeIf { medium })
        for (id in ids) views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, fit.sp)
        views.setInt(R.id.widget_status_title, "setMaxLines", fit.lines.first())
        fit.lines.getOrNull(1)?.let { views.setInt(if (medium) R.id.widget_status_detail_line else R.id.widget_status_detail, "setMaxLines", it) }
    }
}
