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
    content: @Composable () -> Unit) {
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
    LaunchedEffect(to) { if (to == ClaimTo.phone && phone == null) loadPhone(phoneAddress, { phone = it }) { phoneError = true } }
    // Today's claim review for this destination: read-only, fresh for 30 s, asked again when the destination changes and when
    // the page comes back (pausing drops it), so Claim never stays off.
    val resumed = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(destination, ids, resumed) {
        model.invalidate()
        if (destination == null || ids.isEmpty() || !resumed) return@LaunchedEffect
        pending = true
        try { reviewClaim(model, ids, destination) } finally { pending = false }
    }
    ExitClaimContent(exits, model.overview?.tip ?: 0, model.quote?.takeIf { it.destination == destination },
        model.estimateFallback?.takeIf { it.destination == destination },
        to, typed, address, phone, phoneError, model.busy, suspended, money, nav.back, reviewing = pending,
        choose = { to = it }, type = { typed = it }, reviewAgain = { destination?.let { scope.launch { model.review(OnchainAction.claim, ids,
            destination = it) } } },
        withoutEstimate = model::continueWithoutEstimate,
        claim = { claimExits(model, nav, scope, haptics) }, content = content)
}

private suspend fun loadPhone(phoneAddress: suspend () -> String, loaded: (String) -> Unit, failed: () -> Unit) {
    try { loaded(phoneAddress()) }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { failed() }
}

private suspend fun reviewClaim(model: OnchainModel, ids: List<String>, destination: String) {
    delay(300)
    // A review still running (from before a pause, say) would drop this one: wait for it; its answer is already stale.
    snapshotFlow { model.busy }.first { !it }
    model.review(OnchainAction.claim, ids, destination = destination)
}

private fun claimExits(model: OnchainModel, nav: SettingsNav, scope: kotlinx.coroutines.CoroutineScope, haptics: com.noahwallet.ui.onboarding.Haptics) {
    if (model.busy || model.quote == null) return
    nav.hold(true)
    scope.launch {
        try { model.confirm() } finally { nav.hold(false) }
        // Submitted: back to the exit at once (it shows Claiming); the wallet's own reads catch up.
        if (model.message != null && model.error == null && model.estimateFallback == null) { haptics.success(); nav.pop() } else haptics.error()
    }
}

@Composable
internal fun ExitClaimContent(exits: List<ExitModel>, tip: Long, quote: OnchainQuote?, fallback: OnchainQuote?, to: ClaimTo, typed: String, address: String?,
    phone: String?, phoneError: Boolean, busy: Boolean, suspended: Boolean, money: (Long) -> String, back: (() -> Unit)?,
    choose: (ClaimTo) -> Unit, type: (String) -> Unit, reviewAgain: () -> Unit, withoutEstimate: () -> Unit, claim: () -> Unit,
    reviewing: Boolean = false, content: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    var scanning by remember { mutableStateOf(false) }
    val current = rememberCurrent(quote)
    val total = exits.sumOf { it.amount }
    val known = quote?.takeIf { it.fee != null }
    val destination = if (to == ClaimTo.phone) phone else address
    val wide = LocalPose.current !is OnboardingPose.Compact
    val step = claimStep(quote, fallback, current, destination != null && exits.isNotEmpty() && !busy && !reviewing)
    val claimable = current && ExitClaim.ready(destination, quote) && !busy && !suspended
    ExitFrame(tr("Claim"), back, hero = true, pictureLeads = true, foot = {
        ClaimFoot(step, known, money, busy, suspended, claimable, withoutEstimate, reviewAgain, claim)
    }, leading = { ClaimLeading(exits, tip, total, wide, money) }) {
        if (exits.isEmpty()) { FinePrint(tr("No exits are ready to claim.")); content(); return@ExitFrame }
        SectionLabel(tr("To"))
        ClaimDestinations(to, typed, address, phone, phoneError, busy, choose, type) { scanning = true }
        ClaimTotals(exits.size, known, quote != null || fallback != null, money)
        ClaimWarnings(known, quote, fallback, current)
        FinePrint(tr("Every exit that’s ready goes in this one transaction. Earlier network fees aren’t taken again."))
        content()
    }
    if (scanning) Box(Modifier.fillMaxSize().background(palette.background).testTag("exit-claim-scanner")) {
        com.noahwallet.ui.SendScanner(onClose = { scanning = false }) { value -> scanning = false; type(value.trim()) }
    }
}

private enum class ClaimStep { withoutEstimate, reviewAgain, hold }

private fun claimStep(quote: OnchainQuote?, fallback: OnchainQuote?, current: Boolean, idle: Boolean) = when {
    fallback != null && quote == null -> ClaimStep.withoutEstimate
    // An expired review, or none while idle (a changed fee or failed submit dropped it): ask again rather than leave Claim off.
    (quote != null && !current) || (quote == null && idle) -> ClaimStep.reviewAgain
    else -> ClaimStep.hold
}

/** Continue without an estimate, Review again, or the hold that claims. */
@Composable
private fun RowScope.ClaimFoot(step: ClaimStep, known: OnchainQuote?, money: (Long) -> String, busy: Boolean, suspended: Boolean, claimable: Boolean,
    withoutEstimate: () -> Unit, reviewAgain: () -> Unit, claim: () -> Unit) {
    when (step) {
        ClaimStep.withoutEstimate -> FootPill(tr("Continue without estimate"), Modifier.testTag("exit-without-estimate"), wrap = true,
            enabled = !busy, onClick = withoutEstimate)
        ClaimStep.reviewAgain -> FootPill(tr("Review again"), Modifier.testTag("exit-review-again"), wrap = true, enabled = !busy && !suspended,
            onClick = reviewAgain)
        ClaimStep.hold -> ClaimHold(known, money, claimable, busy, claim)
    }
}

/** Another wallet (with its address) or this phone's on-chain wallet. */
@Composable
private fun ClaimDestinations(to: ClaimTo, typed: String, address: String?, phone: String?, phoneError: Boolean, busy: Boolean,
    choose: (ClaimTo) -> Unit, type: (String) -> Unit, scan: () -> Unit) {
    val haptics = rememberHaptics()
    // Fixed while a review runs: a review asked meanwhile would be dropped, leaving Claim off.
    RadioRow(tr("Another wallet"), selected = to == ClaimTo.other, modifier = Modifier.testTag("exit-to-other"),
        enabled = !busy) { haptics.selection(); choose(ClaimTo.other) }
    if (to == ClaimTo.other) ClaimAddress(typed, address, busy, type, scan)
    RadioRow(tr("This phone’s on-chain wallet"), detail = phone?.let { short(it, 8, 4) }, selected = to == ClaimTo.phone, divider = false,
        modifier = Modifier.testTag("exit-to-phone"), enabled = !busy) { haptics.selection(); choose(ClaimTo.phone) }
    if (to == ClaimTo.phone) FinePrint(if (phoneError) tr("Couldn't load a Noah wallet address. Try again when the wallet is ready.")
    else tr("Noah won’t move these back to Ark."), failed = phoneError)
}

@Composable
private fun ClaimWarnings(known: OnchainQuote?, quote: OnchainQuote?, fallback: OnchainQuote?, current: Boolean) {
    if (known != null) ExitRules.warning(known.amount, checkNotNull(known.fee), known.totalFee, claimOnly = true)?.let { Warning(tr(it), failed = true) }
    @Suppress("MaxLineLength") // one translation source string
    if ((quote ?: fallback)?.feesUnknown == true) Warning(tr("Fee estimate unavailable. Fees and amount to receive are unknown. Continue without an estimate only if you accept this uncertainty."), failed = true)
    if (quote != null && !current) Warning(tr(OnchainFailure.changed.text), failed = true)
}

@Composable
private fun RowScope.ClaimHold(known: OnchainQuote?, money: (Long) -> String, enabled: Boolean, busy: Boolean, claim: () -> Unit) {
    HoldToConfirm(known?.let { tr("Hold to claim {amount}", money(it.net)) } ?: tr("Hold to claim"), tr("Claiming"),
        known?.let { tr("Claim {amount}", money(it.net)) } ?: tr("Claim"), tr("Claim"),
        enabled = enabled, sending = busy, modifier = Modifier.weight(1f), tag = "exit-hold-claim", wrap = true, onConfirm = claim)
}

/** The amount claimed, the block it unlocked at and, on wide screens, the first exit's picture. */
@Composable
private fun ColumnScope.ClaimLeading(exits: List<ExitModel>, tip: Long, total: Long, wide: Boolean, money: (Long) -> String) {
    val palette = LocalPalette.current
    ExitBig(tr("Claim {amount}", money(total)), Modifier.padding(top = 6.dp).testTag("exit-claim-amount"))
    exits.mapNotNull { it.details.claimable_since?.height }.minOrNull()?.let {
        Text(tr("Unlocked at block {height}", number(it)), Modifier.padding(top = 3.dp), style = HomeType.line, color = palette.muted)
    }
    val first = exits.firstOrNull()
    if (wide && first != null) ExitPicture(ExitScene.of(first, ExitStages.of(first, tip), tip), tr("Ready to claim"), exitPicture().padding(top = 14.dp),
        money)
}

/** Another wallet's address: typed, pasted or scanned. */
@Composable
private fun ClaimAddress(typed: String, address: String?, busy: Boolean, type: (String) -> Unit, scan: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            SettingsField(typed, type, tr("Bitcoin address"), Modifier.testTag("exit-claim-address"), failed = typed.isNotBlank() && address == null,
                enabled = !busy, keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false), mono = true)
        }
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SendChip(tr("Paste"), Modifier.testTag("exit-claim-paste"), enabled = !busy) { context.clipboardText()?.trim()?.let(type) }
        SendChip(tr("Scan"), Modifier.testTag("exit-claim-scan"), enabled = !busy, onClick = scan)
    }
    if (typed.isNotBlank() && address == null) FinePrint(tr("Enter an on-chain Bitcoin address for this network."), failed = true)
    else FinePrint(tr("Check the address. This can’t be undone."))
}

@Composable
private fun ClaimTotals(exits: Int, known: OnchainQuote?, reviewed: Boolean, money: (Long) -> String) {
    Column(Modifier.padding(top = 18.dp)) {
        if (exits > 1) ExitValueRow(tr("Exits"), exits.toString(), kind = ValueKind.number)
        ExitValueRow(tr("Claim fee"), known?.fee?.let { "~" + money(it) } ?: if (reviewed) tr("Unknown") else "—",
            kind = ValueKind.number, last = true)
        TotalRow(tr("You get"), known?.let { "~" + money(it.net) } ?: "—")
    }
}
