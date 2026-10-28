package com.noahwallet.onchain

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.*
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.send.HoldToConfirm
import com.noahwallet.ui.settings.*
import kotlinx.coroutines.launch

/**
 * Start (prototype `SCR.confirm`): bark's fresh estimate for the chosen VTXOs (today's review, never older than 30 s), today's
 * explanation as one paragraph, then hold to start (Decision 2). Holding submits once; Back waits for it. A started exit
 * leaves for its progress, or the list when several started.
 */
@Composable
internal fun ExitStartPage(app: AppModel, nav: SettingsNav, ids: List<String>) {
    val model = rememberOnchainModel(app)
    OnchainEffects(app, model, reloadOnChange = true)
    ExitStartScreen(model, nav, ids, app.isWalletSuspended, rememberExitDelta(app), { money(app, it) }) { OnchainStatus(app, model) }
}

/** The start page on [model] (its review, the hold and where it leaves to), apart from [AppModel] so a device test drives it. */
@Composable
internal fun ExitStartScreen(model: OnchainModel, nav: SettingsNav, ids: List<String>, suspended: Boolean, delta: Long?, money: (Long) -> String,
    content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var started by remember { mutableStateOf(false) }
    // The review itself: read-only. Pausing, a wallet change or 30 s drop it; Review again asks bark afresh.
    LaunchedEffect(ids) { if (model.quote == null) model.review(OnchainAction.startExit, ids) }
    // Already started (it was submitted before the window was recreated, say): show its progress, never start it again.
    LaunchedEffect(model.overview) {
        val live = model.overview?.exits.orEmpty().filter { it.id in ids && it.state !in ExitTimeline.terminal }
        if (!started && !model.busy && live.size == ids.size) nav.replace(if (ids.size == 1) ExitPage(ids.single()) else ExitsPage)
    }
    ExitStartContent(model.quote, model.estimateFallback, model.overview?.confirmed, model.busy, suspended, delta,
        money, nav.back, depth = model.overview?.vtxos.orEmpty().filter { it.id in ids }.maxOfOrNull { it.exitDepth.toInt() },
        reviewAgain = { scope.launch { model.review(OnchainAction.startExit, ids) } },
        withoutEstimate = model::continueWithoutEstimate,
        start = {
            if (!model.busy && model.quote != null) {
                nav.hold(true)
                scope.launch {
                    try { model.confirm() } finally { nav.hold(false) }
                    if (model.message != null && model.error == null && model.estimateFallback == null) {
                        started = true; haptics.success()
                        nav.replace(if (ids.size == 1) ExitPage(ids.single()) else ExitsPage)
                    } else haptics.error()
                }
            }
        }, content = content)
}

@Composable
internal fun ExitStartContent(quote: OnchainQuote?, fallback: OnchainQuote?, confirmed: Long?, busy: Boolean, suspended: Boolean, delta: Long?,
    money: (Long) -> String, back: (() -> Unit)?, reviewAgain: () -> Unit, withoutEstimate: () -> Unit, start: () -> Unit,
    depth: Int? = null, content: @Composable () -> Unit = {}) {
    val review = quote ?: fallback
    val current = rememberCurrent(quote)
    val known = quote?.takeIf { it.fee != null && it.chainFee != null }
    ExitFrame(tr("Review"), back, hero = true, pictureLeads = true, foot = {
        when {
            quote != null && current -> HoldToConfirm(tr("Hold to start exit"), tr("Starting"), tr("Start exit"), tr("Start exit"),
                enabled = !busy && !suspended, sending = busy, modifier = Modifier.weight(1f), tag = "exit-hold-start", wrap = true, onConfirm = start)
            fallback != null -> FootPill(tr("Continue without estimate"), Modifier.testTag("exit-without-estimate"), wrap = true, enabled = !busy,
                onClick = withoutEstimate)
            else -> FootPill(tr("Review again"), Modifier.testTag("exit-review-again"), wrap = true, enabled = !busy && !suspended, loading = busy,
                onClick = reviewAgain)
        }
    }, leading = { StartLeading(review, known, money, depth, delta) }) {
        StartTotals(known, confirmed, delta, money)
        StartWarnings(review, known, quote != null && !current, money)
        @Suppress("MaxLineLength") // one translation source string
        FinePrint(tr("Noah then sends and fee-bumps the exit’s transactions on its own, including in the background when the system lets it, paying from your on-chain bitcoin. Fees are estimates. Only the claim fee comes out of the amount. You can cancel until the last transaction is sent."))
        content()
    }
}

/** The amount exiting, its VTXOs and transactions, and its path: the picture on wide screens, the one-line path on phones. */
@Composable
private fun ColumnScope.StartLeading(review: OnchainQuote?, known: OnchainQuote?, money: (Long) -> String, depth: Int?, delta: Long?) {
    val palette = LocalPalette.current
    val wide = com.noahwallet.ui.onboarding.LocalPose.current !is com.noahwallet.ui.onboarding.OnboardingPose.Compact
    ExitBig(review?.let { tr("Exit {amount}", money(it.amount)) } ?: tr("Exit"), Modifier.padding(top = 6.dp).testTag("exit-start-amount"))
    review?.let { r ->
        val count = trPlural("{n} VTXOs", r.ids.size)
        Text(listOfNotNull(count, known?.transactions?.let { trPlural("{n} transactions", it) }, tr("about a day")).joinToString(" · "),
            Modifier.padding(top = 3.dp), style = HomeType.line, color = palette.muted)
    }
    // One branch, the deepest selected VTXO's (a batch's total isn't one path); a round's usual four until it's known.
    val path = depth ?: known?.transactions?.takeIf { review?.ids?.size == 1 }?.toInt() ?: 4
    if (wide) ExitPicture(ExitScene.sample(path), tr("Your VTXO’s path to the chain"), exitPicture().padding(top = 14.dp))
    else if (review != null) ExitPath(path, review.ids.size > 1, known?.transactions?.toInt(), delta)
}

@Composable
private fun StartTotals(known: OnchainQuote?, confirmed: Long?, delta: Long?, money: (Long) -> String) {
    Column(Modifier.padding(top = 18.dp)) {
        ExitValueRow(tr("Network fees"), known?.chainFee?.let { "~" + money(it) } ?: tr("Unknown"), kind = ValueKind.number)
        confirmed?.let { ExitValueRow(tr("Paid from"), tr("On-chain · {amount}", money(it))) }
        ExitValueRow(tr("Claim fee"), known?.fee?.let { "~" + money(it) } ?: tr("Unknown"), kind = ValueKind.number)
        known?.feeRate?.let { ExitValueRow(tr("Fee rate"), tr("{rate} sat/vB", it.toString())) }
        ExitValueRow(tr("Timelock"), delta?.let { trPlural("{n} blocks", it) } ?: tr("Unknown"), last = true)
        TotalRow(tr("You get"), known?.fee?.let { "~" + money(ExitRules.received(known.amount, it)) } ?: tr("Unknown"))
    }
}

@Composable
private fun StartWarnings(review: OnchainQuote?, known: OnchainQuote?, changed: Boolean, money: (Long) -> String) {
    if (known != null && !known.fundable) Warning(tr("Not enough on-chain bitcoin for the fees. The exit starts, then waits until you add about {amount}.",
        money(checkNotNull(known.chainFee) - known.confirmed)), failed = true)
    if (known != null) ExitRules.warning(known.amount, checkNotNull(known.fee), known.totalFee, claimOnly = false)?.let { Warning(tr(it), failed = true) }
    if (review?.feesUnknown == true) {
        Warning(tr("Fee estimate unavailable. Fees and amount to receive are unknown. Continue without an estimate only if you accept this uncertainty."),
            failed = true)
        Warning(tr("Broadcast funding status is unknown; confirmed onchain funds are required."), failed = true)
    }
    if (changed) Warning(tr(OnchainFailure.changed.text), failed = true)
}

/**
 * Review's one-line path on phones (exit-v2-notes.md, Screens): the round's block and your transactions, your coin, the
 * timelock's dial, then the claim to its destination, with a caption under each third. [path] is the deepest selected
 * VTXO's transactions, drawn 8 at most; [batch] several VTXOs, whose shared ones are sent once. The captions give only
 * what's known: the estimate's [count] and the server's [delta].
 */
@Composable
private fun ExitPath(path: Int, batch: Boolean, count: Int?, delta: Long?) {
    val palette = LocalPalette.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val local = androidx.compose.ui.platform.LocalDensity.current
    val styles = remember(local) { TreeStyles(local) }
    val number = java.text.NumberFormat.getIntegerInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
    androidx.compose.foundation.Canvas(Modifier.padding(top = 18.dp).fillMaxWidth().height(58.dp).clearAndSetSemantics { }) {
        scale(density, density, Offset.Zero) {
            val w = size.width / density; val y = size.height / density / 2 - 4; val x0 = 10f; val x1 = w - 14; val cx = w / 2
            drawRoundRect(palette.foreground, Offset(x0, y - 8), Size(16f, 16f), CornerRadius(4f))
            // A long path folds to the stops that keep their spacing ("+N" for the rest), as the picture's does.
            val stops = ExitRail.stops(path, x0 + 30, w * .36f - x0 - 34, cx - 21,
                { "+" + number.format(it) }) { styles.width(measurer, it, ExitRail.moreSize, true) }
            var px = x0 + 16
            for (stop in stops) {
                val x = stop.x
                treeLink(Offset(px, y), Offset(x - stop.half + .3f, y), 0f, 1f, palette.orange, 2f, 1f, horizontal = true)
                px = x + stop.half - .3f
                when {
                    stop.coin -> treeCoin(Offset(x, y), ExitRail.coinR, palette.soft, palette.orange)
                    stop.more != null -> {
                        val text = measurer.measure("+" + number.format(stop.more), styles.style(ExitRail.moreSize, true)); val half = stop.width / 2
                        drawRoundRect(palette.background, Offset(x - half, y - 5), Size(2 * half, 10f), CornerRadius(5f))
                        drawRoundRect(palette.orange, Offset(x - half, y - 5), Size(2 * half, 10f), CornerRadius(5f), style = Stroke(1.4f))
                        drawText(text, color = palette.orange, topLeft = Offset(x - text.size.width / 2f, y - text.size.height / 2f))
                    }
                    else -> { drawCircle(palette.background, 5f, Offset(x, y)); drawCircle(palette.orange, 5f, Offset(x, y), style = Stroke(1.6f)) }
                }
            }
            treeLink(Offset(px, y), Offset(cx - 17, y), 0f, 1f, palette.hairline, 1.5f, 1f, floatArrayOf(3f, 3f), horizontal = true)
            drawCircle(palette.hairline, 13f, Offset(cx, y), style = Stroke(2f))
            exitPathTicks(cx, y, delta ?: 144, palette.muted)
            treePadlock(Offset(cx, y - 1), 12f, 0f, 1f, palette.foreground, palette.background)
            treeLink(Offset(cx + 21, y), Offset(x1 - 36, y), 0f, 1f, palette.hairline, 1.5f, 1f, floatArrayOf(3f, 3f), horizontal = true)
            exitWalletBadge(Offset(x1 - 20, y), palette)
        }
    }
    ExitPathCaptions(count, batch, delta)
}

/** The timelock dial's ticks around ([cx], [y]), the major ones longer. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.exitPathTicks(cx: Float, y: Float, delta: Long, color: androidx.compose.ui.graphics.Color) {
    val ticks = ExitDial.ticks(delta)
    for (h in 0 until ticks) {
        val an = -kotlin.math.PI / 2 + h * 2 * kotlin.math.PI / ticks; val r1 = if (ExitDial.major(delta, h)) 19.5f else 17.5f
        drawLine(color, Offset(cx + kotlin.math.cos(an).toFloat() * 16, y + kotlin.math.sin(an).toFloat() * 16),
            Offset(cx + kotlin.math.cos(an).toFloat() * r1, y + kotlin.math.sin(an).toFloat() * r1), 1f, alpha = .5f)
    }
}

@Composable
private fun ExitPathCaptions(count: Int?, batch: Boolean, delta: Long?) {
    val palette = LocalPalette.current
    // Three columns, one per third of the path; at large text they stack, each caption whole.
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
    val captions = listOf(count?.let { trPlural("{n} transactions", it) } to if (batch) tr("shared ones once") else tr("one block each"),
        delta?.let { trPlural("{n} blocks", it) } to tr("about a day"), tr("Claim") to tr("to another wallet"))
    @Composable fun caption(title: String?, line: String, align: androidx.compose.ui.text.style.TextAlign, modifier: Modifier) = Column(modifier) {
        title?.let { Text(it, Modifier.fillMaxWidth(), style = HomeType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = palette.foreground, textAlign = align) }
        Text(line, Modifier.fillMaxWidth(), style = HomeType.small, color = palette.muted, textAlign = align)
    }
    if (stacked) Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((title, line) in captions) caption(title, line, androidx.compose.ui.text.style.TextAlign.Start, Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val aligns = listOf(androidx.compose.ui.text.style.TextAlign.Start, androidx.compose.ui.text.style.TextAlign.Center,
            androidx.compose.ui.text.style.TextAlign.End)
        captions.forEachIndexed { i, (title, line) -> caption(title, line, aligns[i], Modifier.weight(1f)) }
    }
}
