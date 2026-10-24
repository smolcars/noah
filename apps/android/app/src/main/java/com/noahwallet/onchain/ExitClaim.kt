package com.noahwallet.onchain

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.currentStateAsState
import com.noahwallet.BuildConfig
import com.noahwallet.localization.tr
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.*
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalPose
import com.noahwallet.ui.onboarding.OnboardingPose
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.send.HoldToConfirm
import com.noahwallet.ui.send.SendChip
import com.noahwallet.ui.send.clipboardText
import com.noahwallet.ui.settings.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Claim (prototype `SCR.claim`): every exit that's ready, in one transaction, as today. "To" defaults to another wallet: an
 * on-chain address for this network, checked by today's parser, with Paste and Scan; Claim stays off until it's valid.
 * This phone's on-chain wallet records the claimed outputs as handled, so auto-board never moves them back to Ark.
 * bark's estimate gives the claim fee (taken from the amount); hold to claim submits once.
 */
@Composable
internal fun ExitClaimPage(app: AppModel, nav: SettingsNav) {
    val model = rememberOnchainModel(app)
    OnchainEffects(app, model, reloadOnChange = true)
    ExitClaimScreen(model, nav, app.session::exitDepositAddress, app.isWalletSuspended, { money(app, it) }) { OnchainStatus(app, model) }
}

/** The claim page on [model] (destination, review and hold), apart from [AppModel] so a device test drives it. */
@Composable
internal fun ExitClaimScreen(model: OnchainModel, nav: SettingsNav, phoneAddress: suspend () -> String, suspended: Boolean, money: (Long) -> String,
                             status: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var to by rememberSaveable { mutableStateOf(ClaimTo.other) }
    var typed by rememberSaveable { mutableStateOf("") }
    var phone by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf(false) }
    val exits = model.overview?.exits.orEmpty().filter { it.claimable }
    val ids = exits.map { it.id }
    val address = ExitClaim.address(typed, BuildConfig.APP_VARIANT)
    val destination = ExitClaim.destination(to, typed, phone, BuildConfig.APP_VARIANT)
    LaunchedEffect(to) {
        if (to == ClaimTo.phone && phone == null) try { phone = phoneAddress() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { phoneError = true }
    }
    // Today's claim review for this destination: read-only, fresh for 30 s, asked again when the destination changes and when
    // the page comes back (pausing drops it), so Claim never stays off.
    val resumed = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(destination, ids, resumed) {
        model.invalidate()
        if (destination == null || ids.isEmpty() || !resumed) return@LaunchedEffect
        pending = true
        try {
            delay(300)
            // A review still running (from before a pause, say) would drop this one: wait for it; its answer is already stale.
            snapshotFlow { model.busy }.first { !it }
            model.review(OnchainAction.claim, ids, destination = destination)
        } finally { pending = false }
    }
    ExitClaimContent(exits, model.overview?.tip ?: 0, model.quote?.takeIf { it.destination == destination }, model.estimateFallback?.takeIf { it.destination == destination },
        to, typed, address, phone, phoneError, model.busy, suspended, money, nav.back, reviewing = pending,
        choose = { to = it }, type = { typed = it }, reviewAgain = { destination?.let { scope.launch { model.review(OnchainAction.claim, ids, destination = it) } } },
        withoutEstimate = model::continueWithoutEstimate,
        claim = {
            if (!model.busy && model.quote != null) {
                nav.hold(true)
                scope.launch {
                    try { model.confirm() } finally { nav.hold(false) }
                    // Submitted: back to the exit at once (it shows Claiming); the wallet's own reads catch up.
                    if (model.message != null && model.error == null && model.estimateFallback == null) { haptics.success(); nav.pop() } else haptics.error()
                }
            }
        }, status = status)
}

@Composable
internal fun ExitClaimContent(exits: List<ExitModel>, tip: Long, quote: OnchainQuote?, fallback: OnchainQuote?, to: ClaimTo, typed: String, address: String?,
                              phone: String?, phoneError: Boolean, busy: Boolean, suspended: Boolean, money: (Long) -> String, back: (() -> Unit)?,
                              choose: (ClaimTo) -> Unit, type: (String) -> Unit, reviewAgain: () -> Unit, withoutEstimate: () -> Unit, claim: () -> Unit,
                              reviewing: Boolean = false, status: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    val toasts = LocalSettingsToasts.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var scanning by remember { mutableStateOf(false) }
    var current by remember(quote) { mutableStateOf(quote?.current() ?: false) }
    LaunchedEffect(quote) { while (isActive && current) { delay(250); current = quote?.current() ?: false } }
    val total = exits.sumOf { it.amount }
    val known = quote?.takeIf { it.fee != null }
    val destination = if (to == ClaimTo.phone) phone else address
    val wide = LocalPose.current !is OnboardingPose.Compact
    ExitFrame(tr("Claim"), back, hero = true, pictureLeads = true, foot = {
        when {
            fallback != null && quote == null -> FootPill(tr("Continue without estimate"), Modifier.testTag("exit-without-estimate"), enabled = !busy, onClick = withoutEstimate)
            // An expired review, or none while idle (a changed fee or failed submit dropped it): ask again rather than leave Claim off.
            (quote != null && !current) || (quote == null && destination != null && exits.isNotEmpty() && !busy && !reviewing) ->
                FootPill(tr("Review again"), Modifier.testTag("exit-review-again"), enabled = !busy && !suspended, onClick = reviewAgain)
            else -> HoldToConfirm(known?.let { tr("Hold to claim {amount}", money(it.net)) } ?: tr("Hold to claim"), tr("Claiming"),
                known?.let { tr("Claim {amount}", money(it.net)) } ?: tr("Claim"), tr("Claim"),
                enabled = current && ExitClaim.ready(destination, quote) && !busy && !suspended, sending = busy, modifier = Modifier.weight(1f),
                tag = "exit-hold-claim", onConfirm = claim)
        }
    }, leading = {
        ExitBig(tr("Claim {amount}", money(total)), Modifier.padding(top = 6.dp).testTag("exit-claim-amount"))
        exits.mapNotNull { it.details.claimable_since?.height }.minOrNull()?.let {
            Text(tr("Unlocked at block {height}", number(it)), Modifier.padding(top = 3.dp), style = HomeType.line, color = palette.muted)
        }
        val first = exits.firstOrNull()
        if (wide && first != null) ExitPicture(ExitPose.of(first, ExitStages.of(first, tip), tip), tr("Ready to claim"), exitPicture().padding(top = 14.dp))
    }) {
        if (exits.isEmpty()) { FinePrint(tr("No exits are ready to claim.")); status(); return@ExitFrame }
        SectionLabel(tr("To"))
        // Fixed while a review runs: a review asked meanwhile would be dropped, leaving Claim off.
        RadioRow(tr("Another wallet"), selected = to == ClaimTo.other, modifier = Modifier.testTag("exit-to-other"), enabled = !busy) { haptics.selection(); choose(ClaimTo.other) }
        if (to == ClaimTo.other) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    SettingsField(typed, type, tr("Bitcoin address"), Modifier.testTag("exit-claim-address"), failed = typed.isNotBlank() && address == null,
                        enabled = !busy, keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SendChip(tr("Paste"), Modifier.testTag("exit-claim-paste"), enabled = !busy) { context.clipboardText()?.trim()?.let(type) }
                SendChip(tr("Scan"), Modifier.testTag("exit-claim-scan"), enabled = !busy) { scanning = true }
            }
            if (typed.isNotBlank() && address == null) FinePrint(tr("Enter an on-chain Bitcoin address for this network."), failed = true)
            else FinePrint(tr("Check the address. This can’t be undone."))
        }
        RadioRow(tr("This phone’s on-chain wallet"), detail = phone?.let { short(it, 8, 4) }, selected = to == ClaimTo.phone, divider = false,
            modifier = Modifier.testTag("exit-to-phone"), enabled = !busy) { haptics.selection(); choose(ClaimTo.phone) }
        if (to == ClaimTo.phone) FinePrint(if (phoneError) tr("Couldn't load a Noah wallet address. Try again when the wallet is ready.")
            else tr("Noah won’t move these back to Ark."), failed = phoneError)
        Column(Modifier.padding(top = 18.dp)) {
            if (exits.size > 1) DetailRow(tr("Exits"), exits.size.toString(), ValueKind.number, toasts = toasts)
            DetailRow(tr("Claim fee"), known?.fee?.let { "~" + money(it) } ?: if (quote != null || fallback != null) tr("Unknown") else "—", ValueKind.number, toasts = toasts, last = true)
            TotalRow(tr("You get"), known?.let { "~" + money(it.net) } ?: "—")
        }
        if (known != null) ExitRules.warning(known.amount, checkNotNull(known.fee), known.totalFee, claimOnly = true)?.let { Warning(tr(it), failed = true) }
        if ((quote ?: fallback)?.feesUnknown == true) Warning(tr("Fee estimate unavailable. Fees and amount to receive are unknown. Continue without an estimate only if you accept this uncertainty."), failed = true)
        if (quote != null && !current) Warning(tr(OnchainFailure.changed.text), failed = true)
        FinePrint(tr("Every exit that’s ready goes in this one transaction. Earlier network fees aren’t taken again."))
        status()
    }
    if (scanning) Box(Modifier.fillMaxSize().background(palette.background).testTag("exit-claim-scanner")) {
        com.noahwallet.ui.SendScanner(onClose = { scanning = false }) { value -> scanning = false; type(value.trim()) }
    }
}
