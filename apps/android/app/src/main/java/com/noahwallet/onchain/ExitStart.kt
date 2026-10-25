package com.noahwallet.onchain

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
                             status: @Composable () -> Unit) {
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
        money, nav.back, tip = model.overview?.tip ?: 0, reviewAgain = { scope.launch { model.review(OnchainAction.startExit, ids) } },
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
        }, status = status)
}

@Composable
internal fun ExitStartContent(quote: OnchainQuote?, fallback: OnchainQuote?, confirmed: Long?, busy: Boolean, suspended: Boolean, delta: Long?,
                              money: (Long) -> String, back: (() -> Unit)?, reviewAgain: () -> Unit, withoutEstimate: () -> Unit, start: () -> Unit,
                              tip: Long = 0, status: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    val toasts = LocalSettingsToasts.current
    val review = quote ?: fallback
    var current by remember(quote) { mutableStateOf(quote?.current() ?: false) }
    LaunchedEffect(quote) { while (isActive && current) { delay(250); current = quote?.current() ?: false } }
    val known = quote?.takeIf { it.fee != null && it.chainFee != null }
    val wide = com.noahwallet.ui.onboarding.LocalPose.current !is com.noahwallet.ui.onboarding.OnboardingPose.Compact
    ExitFrame(tr("Review"), back, hero = true, pictureLeads = true, foot = {
        when {
            quote != null && current -> HoldToConfirm(tr("Hold to start exit"), tr("Starting"), tr("Start exit"), tr("Start exit"),
                enabled = !busy && !suspended, sending = busy, modifier = Modifier.weight(1f), tag = "exit-hold-start", onConfirm = start)
            fallback != null -> FootPill(tr("Continue without estimate"), Modifier.testTag("exit-without-estimate"), enabled = !busy, onClick = withoutEstimate)
            else -> FootPill(tr("Review again"), Modifier.testTag("exit-review-again"), enabled = !busy && !suspended, loading = busy, onClick = reviewAgain)
        }
    }, leading = {
        ExitBig(review?.let { tr("Exit {amount}", money(it.amount)) } ?: tr("Exit"), Modifier.padding(top = 6.dp).testTag("exit-start-amount"))
        review?.let { r ->
            val count = trPlural("{n} VTXOs", r.ids.size)
            Text(listOfNotNull(count, known?.transactions?.let { trPlural("{n} transactions", it) }, tr("about a day")).joinToString(" · "),
                Modifier.padding(top = 3.dp), style = HomeType.line, color = palette.muted)
        }
        if (wide) ExitPicture(ExitPose.sample(known?.transactions?.takeIf { review?.ids?.size == 1 }?.toInt() ?: 4, tip), tr("Your VTXO’s path to the chain"),
            exitPicture().padding(top = 14.dp))
    }) {
        Column(Modifier.padding(top = 18.dp)) {
            DetailRow(tr("Network fees"), known?.chainFee?.let { "~" + money(it) } ?: tr("Unknown"), ValueKind.number, toasts = toasts)
            confirmed?.let { DetailRow(tr("Paid from"), tr("On-chain · {amount}", money(it)), toasts = toasts) }
            DetailRow(tr("Claim fee"), known?.fee?.let { "~" + money(it) } ?: tr("Unknown"), ValueKind.number, toasts = toasts)
            known?.feeRate?.let { DetailRow(tr("Fee rate"), tr("{rate} sat/vB", it.toString()), toasts = toasts) }
            DetailRow(tr("Timelock"), delta?.let { trPlural("{n} blocks", it) } ?: tr("Unknown"), toasts = toasts, last = true)
            TotalRow(tr("You get"), known?.fee?.let { "~" + money(ExitRules.received(known.amount, it)) } ?: tr("Unknown"))
        }
        if (known != null && !known.fundable) Warning(tr("Not enough on-chain bitcoin for the fees. The exit starts, then waits until you add about {amount}.",
            money(checkNotNull(known.chainFee) - known.confirmed)), failed = true)
        if (known != null) ExitRules.warning(known.amount, checkNotNull(known.fee), known.totalFee, claimOnly = false)?.let { Warning(tr(it), failed = true) }
        if (review?.feesUnknown == true) {
            Warning(tr("Fee estimate unavailable. Fees and amount to receive are unknown. Continue without an estimate only if you accept this uncertainty."), failed = true)
            Warning(tr("Broadcast funding status is unknown; confirmed onchain funds are required."), failed = true)
        }
        if (quote != null && !current) Warning(tr(OnchainFailure.changed.text), failed = true)
        FinePrint(tr("Noah then sends and fee-bumps the exit’s transactions on its own, including in the background when the system lets it, paying from your on-chain bitcoin. Fees are estimates. Only the claim fee comes out of the amount. You can cancel until the last transaction is sent."))
        status()
    }
}
