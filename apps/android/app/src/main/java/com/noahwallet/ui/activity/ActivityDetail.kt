package com.noahwallet.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahwallet.R
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.FiatCurrency
import com.noahwallet.history.HistoryMetadata
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.*
import com.noahwallet.ui.onboarding.*
import com.noahwallet.ui.send.BlockRow
import com.noahwallet.ui.send.BlockRow.blockStrip
import com.noahwallet.ui.send.SendIcons
import com.noahwallet.ui.send.rememberBlockSlide
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val detailNav = 56.dp

/**
 * One payment: the header (avatar, amount, status, value at the time, the block row while it confirms), the rows,
 * the collapsed Details, Share and Pay again. On phones it opens out of its row ([shared]); wide, it fills the pane.
 * [closable] puts × in the bar instead of Back (wide panes and Home's sheet).
 */
@Composable
internal fun ActivityDetail(ui: ActivityUi, row: ActivityRules.Row, actions: ActivityActions, state: ActivityState, toasts: HomeToasts,
                            wide: Boolean, shared: Shared?, closable: Boolean = wide, close: () -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val record = row.record
    val scroll = rememberScrollState()
    val pay = record.canRepeat
    val receipt = receiptText(ui, row)
    val opened = remember(row.id) { SystemClock.elapsedRealtime() }
    val elapsed = rememberElapsed(opened, 1_000)
    Box(Modifier.fillMaxSize().shared("row-${row.id}", shared, container = true).background(palette.background).testTag("activity-detail")) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .padding(start = if (wide) 32.dp else 24.dp, end = if (wide) 32.dp else 24.dp, top = if (closable) 18.dp else detailNav + 8.dp,
                    bottom = if (pay) 150.dp else 60.dp)) {
                Header(ui, row, wide, shared, elapsed)
                Column(Modifier.padding(top = 24.dp).enter(4, wide, shared, elapsed)) {
                    MainRows(ui, row, actions, toasts)
                }
                DetailsSection(ui, row, actions, state, toasts, scroll, Modifier.enter(5, wide, shared, elapsed))
            }
        }
        // Content fades under the bar once it scrolls.
        val edge by animateFloatAsState(if (scroll.value > 2) 1f else 0f, tween(200), label = "edge")
        Box(Modifier.fillMaxWidth().height(detailNav).graphicsLayer { alpha = edge }
            .background(Brush.verticalGradient(0f to palette.background, .78f to palette.background, 1f to palette.background.copy(alpha = 0f))))
        Row(Modifier.fillMaxWidth().height(detailNav).padding(horizontal = 4.dp).enter(0, wide, shared, elapsed), verticalAlignment = Alignment.CenterVertically) {
            if (!closable) BarButton(R.drawable.ic_activity_back, tr("Back"), Modifier.testTag("activity-detail-back")) { haptics.light(); close() }
            Spacer(Modifier.weight(1f))
            BarButton(R.drawable.ic_activity_share, tr("Share"), Modifier.testTag("activity-detail-share")) { haptics.light(); actions.share(receipt) }
            if (closable) BarButton(HomeIcons.close, tr("Close"), Modifier.testTag("activity-detail-close")) { haptics.light(); close() }
        }
        if (pay) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(0f to palette.background.copy(alpha = 0f), .46f to palette.background))
            .padding(start = if (wide) 32.dp else 24.dp, end = 24.dp, top = 48.dp, bottom = if (wide) 24.dp else 28.dp).enter(6, wide, shared, elapsed)) {
            Pill(tr("Pay again"), Modifier.fillMaxWidth().testTag("activity-pay-again"), height = 56.dp, style = ActivityType.pill) {
                haptics.light(); record.repeatPayment?.let(actions.repeat)
            }
        }
        HomeToast(toasts.current, Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
    }
}

/** The detail's sections rise in after the shared elements (prototype `openRow`); wide, they rise as a row is chosen. */
@Composable
private fun Modifier.enter(index: Int, wide: Boolean, shared: Shared?, elapsed: Long): Modifier {
    val reduceMotion = LocalReduceMotion.current
    if (wide) return rise(elapsed, 50L + index * 22, 8f, reduceMotion)
    if (shared == null) return this
    val rise = with(LocalDensity.current) { 14.dp.roundToPx() }
    return with(shared.visibility) {
        this@enter.animateEnterExit(
            enter = fadeIn(tween(260, delayMillis = 90 + index * 40)) + slideInVertically(spring(.85f, 438.6491f)) { rise },
            exit = fadeOut(tween(120)))
    }
}

@Composable
private fun Header(ui: ActivityUi, row: ActivityRules.Row, wide: Boolean, shared: Shared?, elapsed: Long) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val record = row.record
    val home = row.home
    val struck = ActivityRules.struck(home)
    // Wide, the avatar pops in (bouncy) as a row is chosen.
    val pop = remember(row.id) { Animatable(if (wide && !reduceMotion) .6f else 1f) }
    LaunchedEffect(row.id) { if (pop.value < 1f) pop.animateTo(1f, NoahTokens.Motion.bouncy()) }
    Column(Modifier.padding(top = 6.dp)) {
        RowAvatar(home, new = false, pop = false, large = true, modifier = Modifier.shared("avatar-${row.id}", shared)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - .6f) / .4f).coerceIn(0f, 1f) })
        val amount = rowAmount(home, ui.hidden, ui.unit, ui.locale)
        val color = if (struck) palette.muted else palette.foreground
        val style = ActivityType.big.copy(textDecoration = if (struck) TextDecoration.LineThrough else null, color = color)
        Box(Modifier.padding(top = 18.dp).fillMaxWidth().shared("amount-${row.id}", shared).semantics(mergeDescendants = true) { contentDescription = amount }
            .testTag("activity-detail-amount")) {
            if (wide && !struck && !ui.hidden && !reduceMotion) RollingText(amount, style, color, rollFromZero = true)
            else BasicText(amount, style = style, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 46.sp))
        }
        val (status, tone) = ActivityRules.status(row)
        Row(Modifier.padding(top = 12.dp).enter(1, wide, shared, elapsed).testTag("activity-detail-status"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tone == ActivityRules.Tone.pending) PulseDot(palette.orange)
            Text(statusText(status, record),
                style = ActivityType.status, color = when (tone) {
                    ActivityRules.Tone.failed -> palette.failure; ActivityRules.Tone.muted -> palette.muted; else -> palette.foreground
                })
        }
        fiatLine(ui, row)?.let { Text(it, Modifier.padding(top = 3.dp).enter(2, wide, shared, elapsed), style = HomeType.line, color = palette.muted) }
        blockWait(ui, record, home)?.let { (tip, confirmedAt, needed) ->
            Column(Modifier.enter(3, wide, shared, elapsed)) { ActivityBlockRow(tip, confirmedAt, needed, ui.locale) }
        }
    }
}

/** A status line's words: a renewal that is pending, failed or canceled says so without counting coins. */
@Composable
internal fun statusText(status: String, record: HistoryTransaction) =
    if (status == ActivityRules.renewed) trPlural(status, record.inputVtxos.orEmpty().size.coerceAtLeast(1)) else tr(status)

/** "$X at the time" for settled rows, today's value for today's and pending rows; none when failed or unpriced. */
@Composable
private fun fiatLine(ui: ActivityUi, row: ActivityRules.Row): String? {
    val home = row.home
    if (ActivityRules.struck(home)) return null
    if (ui.hidden) return "••••"
    val date = home.date
    val today = date != null && HomeDays.day(date, ui.now) == HomeDays.Day.Today
    val currency = ui.currency.takeIf { it in FiatCurrency.supported } ?: return null
    val amount = home.amount ?: return null
    return if (!home.pending && date != null && !today) row.record.btcPrice?.takeIf { it.isFinite() && it > 0 }?.let {
        tr("{amount} at the time", FiatCurrency.display(amount, it, currency, ui.locale))
    } else ui.price?.takeIf { it.isFinite() && it > 0 }?.let { FiatCurrency.display(amount, it, currency, ui.locale) }
}

/**
 * The block row's data while an on-chain payment waits: the wallet's tip, its block once mined and the confirmations
 * it needs. Only from today's chain status (Activity reads it as the payment opens); none without a txid or a tip.
 */
private fun blockWait(ui: ActivityUi, record: HistoryTransaction, home: HomeRow): Triple<Long, Long?, Long>? {
    if (!home.pending) return null
    val tip = ui.tip ?: return null
    return when {
        record.source == "onchain-wallet" -> Triple(tip, record.confirmationHeight?.toLong(), 1L)
        record.movementKind == "send-onchain" || record.movementKind == "offboard" -> {
            val txid = HistoryMetadata.parse(record.metadataJson ?: "").offboardTxid ?: return null
            Triple(tip, ui.confirmedAt[txid], ui.chainNeeded)
        }
        else -> null
    }
}

/** Send's mempool-style block row: the next block holding your dot, then "Confirmed in block N · 1 of 2". */
@Composable
internal fun ActivityBlockRow(tip: Long, confirmedAt: Long?, needed: Long, locale: java.util.Locale) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val format = remember(locale) { java.text.NumberFormat.getIntegerInstance(locale) }
    val shownTip = maxOf(tip, confirmedAt ?: tip)
    val (slidTip, slide, pop) = rememberBlockSlide(shownTip, reduceMotion)
    val started = remember { SystemClock.elapsedRealtime() }
    val elapsed by produceState(0L, confirmedAt != null, reduceMotion) {
        while (isActive && confirmedAt == null && !reduceMotion) { withFrameMillis { }; value = SystemClock.elapsedRealtime() - started }
    }
    val style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium, fontSize = with(density) { (11f / (density.density * fontScale)).sp })
    val next = tr("next")
    val nextLabel = remember(next, style) { measurer.measure(next, style) }
    Canvas(Modifier.padding(top = 20.dp).size(188.dp, 62.dp).testTag("activity-block-row").clearAndSetSemantics { }) {
        val tipShown = slidTip ?: return@Canvas
        scale(density.density, density.density, Offset.Zero) {
            blockStrip(0f, 0f, 38f, tipShown, confirmedAt, 1f, slide, pop, BlockRow.blink(elapsed, confirmedAt == null, reduceMotion), palette,
                { height -> measurer.measure(format.format(height), style) }, nextLabel)
        }
    }
    val confirmations = BlockRow.confirmations(shownTip, confirmedAt)
    val line = if (confirmedAt == null) tr("Waiting for block {height}", format.format(shownTip + 1))
        else tr("Confirmed in block {height}", format.format(confirmedAt)) + if (needed > 1) " · " + tr("{n} of {total}", format.format(confirmations.coerceAtMost(needed)), format.format(needed)) else ""
    Text(line, Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = HomeType.line.copy(fontWeight = FontWeight.Medium), color = palette.muted)
}

/**
 * A detail row: label on the left, value (with copy, the explorer or a chevron) on the right. Settings adds an orange
 * [dot] or verified [ok] mark before the value and a [failed] value in red.
 */
@Composable
internal fun DetailRow(label: String, value: String, kind: ValueKind = ValueKind.text, copy: String? = null, explorer: String? = null,
                      glyph: Int? = null, small: Boolean = false, toasts: HomeToasts, onExplorer: (String) -> Unit = {}, last: Boolean = false,
                      dot: Boolean = false, ok: Boolean = false, failed: Boolean = false, onClick: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    val style = when (kind) {
        ValueKind.text -> ActivityType.value; ValueKind.number -> ActivityType.number; ValueKind.mono -> ActivityType.mono
    }.let { if (small) it.copy(fontSize = if (kind == ValueKind.mono) 13.5.sp else 15.sp) else it }
    Row(Modifier.fillMaxWidth().heightIn(min = if (small) 44.dp else 48.dp)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .drawBehind { if (!last) drawRect(palette.hairline, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
        .semantics(mergeDescendants = onClick != null) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(tr(label), style = ActivityType.label.let { if (small) it.copy(fontSize = 15.sp) else it }, color = palette.muted, maxLines = 1)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            glyph?.let { Icon(painterResource(it), null, Modifier.size(16.dp), tint = palette.foreground) }
            if (dot) Box(Modifier.size(8.dp).background(palette.orange, CircleShape))
            SelectionContainer(Modifier.weight(1f, fill = false)) {
                Text(value, style = style, color = if (failed) palette.failure else palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            }
            if (ok) Box(Modifier.size(16.dp).background(palette.orange, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_activity_check), null, Modifier.size(11.dp), tint = androidx.compose.ui.graphics.Color.White)
            }
            copy?.let { CopyIcon(label, it, toasts) }
            explorer?.let { url ->
                val view = tr("View in explorer")
                Box(Modifier.offset(x = 4.dp).size(30.dp).clip(CircleShape).clickable(onClickLabel = view, role = Role.Button) { onExplorer(url) }
                    .semantics { contentDescription = view }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_activity_external), null, Modifier.size(17.dp), tint = palette.muted)
                }
            }
            if (onClick != null) Icon(painterResource(HomeIcons.chevron), null, Modifier.size(18.dp), tint = palette.muted)
        }
    }
}

/** A long technical value under its label, in full, so it can still be selected; copy beside the label. */
@Composable
internal fun DetailBlock(label: String, value: String, toasts: HomeToasts, copy: Boolean = true) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().drawBehind { drawRect(palette.hairline, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
        .padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(label), Modifier.weight(1f), style = ActivityType.label.copy(fontSize = 15.sp), color = palette.muted)
            if (copy) CopyIcon(label, value, toasts)
        }
        SelectionContainer { Text(value, Modifier.padding(top = 4.dp), style = ActivityType.mono.copy(fontSize = 13.5.sp), color = palette.foreground) }
    }
}

internal enum class ValueKind { text, number, mono }

/** Copy: the icon turns into a check, with a "Copied" toast and a success haptic. */
@Composable
internal fun CopyIcon(label: String, value: String, toasts: HomeToasts) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val context = LocalContext.current
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_200); copied = false } }
    val description = tr("Copy {label}", tr(label))
    val done = tr("Copied")
    Box(Modifier.offset(x = 4.dp).size(30.dp).clip(CircleShape).clickable(onClickLabel = description, role = Role.Button) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        haptics.success(); toasts.show(done); copied = true
    }.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(painterResource(if (copied) R.drawable.ic_activity_check else SendIcons.copy), null, Modifier.size(17.dp), tint = palette.muted)
    }
}

/** Middle-shortened identifiers (prototype `short`). */
internal fun short(value: String, head: Int = 8, tail: Int = 6) = if (value.length > head + tail + 1) value.take(head) + "…" + value.takeLast(tail) else value

private val railGlyph = mapOf(ActivityRules.Rail.ark to SendIcons.ark, ActivityRules.Rail.round to SendIcons.ark,
    ActivityRules.Rail.lightning to HomeIcons.lightning, ActivityRules.Rail.offer to HomeIcons.lightning, ActivityRules.Rail.chain to HomeIcons.chain)

/** The rows table, each only when it applies (activity.md, Detail). */
@Composable
private fun MainRows(ui: ActivityUi, row: ActivityRules.Row, actions: ActivityActions, toasts: HomeToasts) {
    val record = row.record
    val home = row.home
    fun money(sats: Long) = if (ui.hidden) "₿••••" else BitcoinAmount.format(sats, ui.unit, ui.locale)
    val noBrowser = tr("No browser available to open the explorer.")
    val explore: (String) -> Unit = { if (!actions.explorer(it)) toasts.show(noBrowser) }
    val rows = mutableListOf<@Composable (Boolean) -> Unit>()
    ActivityRules.recipient(record)?.let { to ->
        rows += { last -> if (ActivityRules.readable(to)) DetailRow("To", to, copy = to, toasts = toasts, last = last)
            else DetailRow("To", short(to, 10, 6), ValueKind.mono, copy = to, toasts = toasts, last = last) }
    }
    val rail = ActivityRules.rail(record)
    rows += { last -> DetailRow("Via", tr(rail.label), glyph = railGlyph.getValue(rail), toasts = toasts, last = last) }
    for (line in ActivityRules.fees(record, home)) rows += { last ->
        when (val value = line.value) {
            is ActivityRules.FeeValue.Sats -> DetailRow(line.label, money(value.sats), ValueKind.number, toasts = toasts, last = last)
            is ActivityRules.FeeValue.UpTo -> DetailRow(line.label, tr("Up to {amount}", money(value.sats)), ValueKind.number, toasts = toasts, last = last)
            ActivityRules.FeeValue.Free -> DetailRow(line.label, tr("Free"), toasts = toasts, last = last)
            ActivityRules.FeeValue.None -> DetailRow(line.label, tr("None"), toasts = toasts, last = last)
        }
    }
    ActivityRules.total(record, home)?.let { total -> rows += { last -> DetailRow("Total", money(total), ValueKind.number, toasts = toasts, last = last) } }
    // A confirmed payment whose block time isn't known yet has no date; Confirmed names its block.
    if (home.date != null || record.hasConfirmation != true) {
        val date = longDate(home.date, ui)
        rows += { last -> DetailRow("Date", date, toasts = toasts, last = last) }
    }
    record.confirmationHeight?.takeIf { record.hasConfirmation == true }?.let { height ->
        rows += { last -> DetailRow("Confirmed", tr("Block {height}", java.text.NumberFormat.getIntegerInstance(ui.locale).format(height.toLong())), toasts = toasts, last = last) }
    }
    record.repeatPayment?.takeIf { it.amountMode == "FIAT" && it.fiatCurrency != null }?.let { repeat ->
        repeat.amountInput.toDoubleOrNull()?.let { value ->
            rows += { last -> DetailRow("Entered as", if (ui.hidden) "••••" else FiatCurrency.format(value, repeat.fiatCurrency!!, ui.locale), toasts = toasts, last = last) }
        }
    }
    val note = record.repeatPayment?.comment?.takeIf { it.isNotEmpty() } ?: record.description
    note?.let { rows += { last -> DetailRow("Note", it, toasts = toasts, last = last) } }
    record.recurring?.takeIf { record.direction == "outgoing" }?.let { recurring ->
        rows += { last -> DetailRow("Repeats", tr(recurring.interval.description), toasts = toasts, last = last) { actions.recurring(recurring.scheduleID) } }
    }
    if (record.movementKind == "exit") rows += { last -> DetailRow("Progress", tr("Exit details"), toasts = toasts, last = last) { actions.exits(record.exitedVtxos.orEmpty()) } }
    record.exitFeeFor?.let { id -> rows += { last -> DetailRow("Progress", tr("Exit details"), toasts = toasts, last = last) { actions.exits(listOf(id)) } } }
    ActivityRules.proof(record)?.let { proof -> rows += { last -> DetailRow("Proof of payment", short(proof), ValueKind.mono, copy = proof, toasts = toasts, last = last) } }
    ActivityRules.chainTxid(record)?.let { txid ->
        val url = ActivityRules.explorerURL(record, ui.variant)
        rows += { last -> DetailRow("Transaction ID", short(txid), ValueKind.mono, copy = txid, explorer = url, toasts = toasts, onExplorer = explore, last = last) }
    }
    rows.forEachIndexed { i, content -> content(i == rows.lastIndex) }
}

@Composable
private fun longDate(date: java.util.Date?, ui: ActivityUi): String {
    if (date == null) return tr("Unconfirmed")
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, ui.locale).format(date)
    val day = when (HomeDays.day(date, ui.now)) {
        HomeDays.Day.Today -> tr("Today"); HomeDays.Day.Yesterday -> tr("Yesterday")
        else -> java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(ui.locale, "MMMdyyyy"), ui.locale).format(date)
    }
    return tr("{day} at {time}", day, time)
}

/** Everything today's technical section shows, collapsed; it opens with the soft spring. */
@Composable
private fun DetailsSection(ui: ActivityUi, row: ActivityRules.Row, actions: ActivityActions, state: ActivityState, toasts: HomeToasts,
                           scroll: androidx.compose.foundation.ScrollState, modifier: Modifier) {
    Details(state.details, { state.details = it }, scroll, modifier) { TechnicalRows(ui, row.record, actions, toasts) }
}

/** Collapsed "Details": the row turns its chevron and the rows open with the soft spring, scrolling them into view. */
@Composable
internal fun Details(open: Boolean, onOpen: (Boolean) -> Unit, scroll: androidx.compose.foundation.ScrollState, modifier: Modifier = Modifier,
                     content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val turn by animateFloatAsState(if (open) 90f else 0f, if (reduceMotion) tween(0) else tween(300), label = "details")
    Column(modifier.padding(top = 18.dp)) {
        val expanded = tr("Expanded"); val collapsed = tr("Collapsed")
        Row(Modifier.fillMaxWidth().height(48.dp).drawBehind {
            drawRect(palette.hairline, Offset.Zero, Size(size.width, 1.dp.toPx()))
            drawRect(palette.hairline, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
        }.clickable(role = Role.Button) {
            haptics.selection(); onOpen(!open)
            if (!open) scope.launch { delay(120); scroll.animateScrollTo(scroll.value + with(density) { 220.dp.roundToPx() }) }
        }.semantics { stateDescription = if (open) expanded else collapsed }.testTag("activity-details-toggle"), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Details"), Modifier.weight(1f), style = ActivityType.value, color = palette.foreground)
            Icon(painterResource(HomeIcons.chevron), null, Modifier.size(18.dp).graphicsLayer { rotationZ = turn }, tint = palette.muted)
        }
        AnimatedVisibility(open, enter = if (reduceMotion) fadeIn(tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS))
            else expandVertically(spring(1f, 157.9137f)) + fadeIn(spring(1f, 157.9137f)),
            exit = if (reduceMotion) fadeOut(tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) else shrinkVertically(spring(1f, 157.9137f)) + fadeOut(spring(1f, 157.9137f))) {
            Column(Modifier.testTag("activity-details"), content = content)
        }
    }
}

@Composable
private fun TechnicalRows(ui: ActivityUi, record: HistoryTransaction, actions: ActivityActions, toasts: HomeToasts) {
    val palette = LocalPalette.current
    fun money(sats: Long) = if (ui.hidden) "₿••••" else BitcoinAmount.format(sats, ui.unit, ui.locale)
    fun signed(sats: Long) = if (ui.hidden) "₿••••" else (if (sats > 0) "+" else if (sats < 0) "−" else "") + BitcoinAmount.format(kotlin.math.abs(sats), ui.unit, ui.locale)
    @Composable fun section(title: String) = Text(tr(title), Modifier.padding(top = 18.dp, bottom = 2.dp), style = HomeType.header, color = palette.muted)
    record.paymentHash?.let { DetailBlock("Payment hash", it, toasts) }
    record.preimage?.takeIf { record.direction == "incoming" }?.let { DetailBlock("Preimage", it, toasts) }
    (record.receivedOn?.singleOrNull()?.destination?.takeIf { it.isNotEmpty() && record.direction == "incoming" } ?: record.invoice?.takeIf { record.direction == "incoming" })
        ?.let { DetailBlock("Invoice", it, toasts) }
    record.offer?.let { DetailBlock("Offer", it, toasts) }
    DetailBlock("Payment ID", record.id, toasts)
    if (record.source == "onchain-wallet" || record.balanceChangeSat != null) {
        DetailRow("Chain status", tr(if (record.hasConfirmation == true) "Confirmed" else "Unconfirmed"), small = true, toasts = toasts)
        record.balanceChangeSat?.let { DetailRow("Balance change", signed(it), ValueKind.number, small = true, toasts = toasts) }
        record.confirmationHeight?.let { DetailRow("Block height", java.text.NumberFormat.getIntegerInstance(ui.locale).format(it.toLong()), small = true, toasts = toasts) }
        record.confirmationHash?.let { DetailBlock("Block hash", it, toasts) }
        record.txHex?.let { DetailBlock("Raw transaction", it, toasts) }
    }
    record.movementKindLabel?.let { DetailRow("Movement type", tr(it), small = true, toasts = toasts) }
    record.movementId?.let { DetailRow("Movement ID", it.toString(), copy = it.toString(), small = true, toasts = toasts) }
    record.subsystemName?.let { DetailRow("Subsystem", "$it (${record.subsystemKind.orEmpty()})", small = true, toasts = toasts) }
    record.intendedBalanceSat?.let { DetailRow("Intended change", signed(it), ValueKind.number, small = true, toasts = toasts) }
    record.effectiveBalanceSat?.let { DetailRow("Effective change", signed(it), ValueKind.number, small = true, toasts = toasts) }
    // As in React Native: the anchor only when it isn't the transaction already shown.
    record.chainAnchor?.takeIf { it.substringBefore(':') != record.txid?.substringBefore(':') }?.let { DetailBlock("Chain anchor", it, toasts) }
    listOf("Sent to" to record.sentTo, "Received on" to record.receivedOn).forEach { (label, values) ->
        if (!values.isNullOrEmpty()) DetailBlock(label, values.joinToString("\n") { "${it.destination} · ${money(it.amount_sat)}" }, toasts, copy = values.size == 1)
    }
    listOf("Spent coins" to record.inputVtxos, "New coins" to record.outputVtxos, "Exited coins" to record.exitedVtxos).forEach { (title, coins) ->
        if (coins.isNullOrEmpty()) return@forEach
        section(title)
        coins.forEachIndexed { i, coin ->
            DetailRow(tr("Coin {n}", (i + 1).toString()), short(coin, 10, 8), ValueKind.mono, small = true, toasts = toasts) { actions.coin(coin) }
        }
    }
    record.metadataJson?.takeIf { it.isNotEmpty() && it != "{}" }?.let { section("Metadata"); DetailBlock("JSON", it, toasts) }
}

/** The plain-text receipt Share sends (see [ActivityRules.receipt]). */
@Composable
private fun receiptText(ui: ActivityUi, row: ActivityRules.Row): String {
    val context = LocalContext.current
    val amount = row.home.amount?.let { row.home.sign + BitcoinAmount.format(it, ui.unit, ui.locale) }
    val date = longDate(row.home.date, ui).takeIf { row.home.date != null || row.record.hasConfirmation != true }
    val resources = androidx.compose.ui.platform.LocalResources.current
    return remember(row, amount, date, resources) {
        ActivityRules.receipt(row, amount, date, ActivityRules.chainTxid(row.record)) { source, arguments ->
            if (source == ActivityRules.renewed)
                com.noahwallet.localization.L10n.plural(context, source, (arguments.firstOrNull() as? String)?.toIntOrNull() ?: 1)
            else com.noahwallet.localization.L10n.text(context, source, *arguments)
        }
    }
}
