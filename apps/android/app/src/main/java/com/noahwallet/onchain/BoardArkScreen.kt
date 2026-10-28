package com.noahwallet.onchain

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.localization.tr
import com.noahwallet.services.storage.SettingsPreferences
import com.noahwallet.services.wallet.ChainStatus
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.ActivityBlockRow
import com.noahwallet.ui.activity.DetailRow
import com.noahwallet.ui.activity.ValueKind
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * On-chain (prototype `SCR.onchain`): your confirmed on-chain balance, "Move automatically" (auto-board, with its
 * threshold) and the smallest move; "Move to Ark" opens today's boarding review in a sheet. While a board confirms,
 * Send's block row follows it. Receipts are today's.
 */
@Composable
internal fun OnchainPage(app: AppModel, nav: SettingsNav) {
    val model = rememberOnchainModel(app)
    val locale = Locale.forLanguageTag(app.localeIdentifier)
    var minimum by remember { mutableStateOf<Long?>(null) }
    var confirmations by remember { mutableStateOf<Long?>(null) }
    var autoBoard by remember { mutableStateOf(autoBoardEnabled(app)) }
    var moving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(app.isWalletSuspended) { loadBoardingTerms(app, { minimum = it }) { confirmations = it } }
    OnchainEffects(app, model, reloadOnChange = true)
    fun money(sats: Long) = shownAmount(app, locale, sats)
    val overview = model.overview
    // A submitted board until it has the confirmations Ark needs: Send's block row follows it.
    val boarding = model.attempts.firstOrNull { boardingUnsettled(it, overview, confirmations) }
    val movable = (overview?.confirmed ?: 0) > 0 && !model.busy && !app.isWalletSuspended
    SettingsFrame(com.noahwallet.localization.tr("On-chain"), nav.back, foot = moveFoot(boarding == null, movable) { model.invalidate(); moving = true }) {
        if (app.isWalletSuspended) { FinePrint(tr("Wallet suspended. Resume your wallet in Settings.")); return@SettingsFrame }
        OnchainTotal(model, boarding, confirmations, locale, ::money)
        AutoBoardRows(app, autoBoard, minimum, ::money) { autoBoard = it }
        FinePrint(boardingNote(confirmations))
        OnchainStatus(app, model)
    }
    if (moving) BoardSheet(app, model, overview?.confirmed ?: 0, ::money, scope) { moving = false; model.invalidate() }
}

/** The confirmed total, what's boarding, and the block row while a board waits for its confirmations. */
@Composable
private fun OnchainTotal(model: OnchainModel, boarding: OnchainOperationJournal.Attempt?, confirmations: Long?, locale: Locale,
    money: (Long) -> String) {
    val overview = model.overview
    BigTotal(overview?.let { money(it.confirmed) } ?: "—", totalLine(boarding?.let { money(it.quote.amount) }, overview == null && model.loading))
    if (boarding != null && overview != null) ActivityBlockRow(overview.tip, boarding.receipt?.chainStatus?.height, confirmations ?: 1, locale)
}

/** Ark's boarding minimum, then the confirmations a board needs, each as soon as it's read. */
private suspend fun loadBoardingTerms(app: AppModel, minimum: (Long?) -> Unit, confirmations: (Long?) -> Unit) {
    if (!onchainReady(app)) return
    minimum(runCatching { app.session.boardingMinimum() }.getOrNull())
    confirmations(runCatching { withContext(Dispatchers.IO) { app.session.arkInfo()?.requiredBoardConfirmations?.toLong() } }.getOrNull())
}

/** Move to Ark, unless a board is still on its way. */
private fun moveFoot(shown: Boolean, enabled: Boolean, open: () -> Unit): (@Composable RowScope.() -> Unit)? =
    if (!shown) null else ({ FootPill(tr("Move to Ark"), Modifier.testTag("board-open"), enabled = enabled, onClick = open) })

private fun shownAmount(app: AppModel, locale: Locale, sats: Long) =
    if (app.hidden) BitcoinAmount.format(0, app.amountUnit, locale).replace("0", "••••") else BitcoinAmount.format(sats, app.amountUnit, locale)

/** Under the total: what's boarding, Loading, or Confirmed. */
@Composable
private fun totalLine(boarding: String?, loading: Boolean) = when {
    boarding != null -> tr("Moving {amount} to Ark", boarding)
    loading -> tr("Loading…")
    else -> tr("Confirmed")
}

@Composable
private fun boardingNote(confirmations: Long?) =
    tr("Bitcoin in Ark moves instantly and costs less to send.") + (confirmations?.let { " " + tr("It needs {n} confirmations to arrive.",
        it.toString()) } ?: "")

private fun autoBoardEnabled(app: AppModel) =
    SettingsPreferences.transaction(app.settings.string("transaction-storage"))["isAutoBoardingEnabled"]?.jsonPrimitive?.booleanOrNull != false

/** A submitted board until it has the confirmations Ark needs. */
private fun boardingUnsettled(attempt: OnchainOperationJournal.Attempt, overview: OnchainOverview?, confirmations: Long?): Boolean {
    val status = attempt.receipt?.chainStatus
    val submitted = attempt.quote.action == OnchainAction.board && attempt.receipt?.txid != null && attempt.overrideAudit == null
    val short = overview != null && status?.height != null && overview.tip - status.height + 1 < (confirmations ?: 1)
    return submitted && (status?.kind != ChainStatus.Kind.confirmed || short)
}

@Composable
private fun AutoBoardRows(app: AppModel, autoBoard: Boolean, minimum: Long?, money: (Long) -> String, saved: (Boolean) -> Unit) {
    SettingsGroup(rows = listOfNotNull(
        { d -> SettingsRow(tr("Move automatically"), SettingsIcons.refresh, divider = d, modifier = Modifier.testTag("settings-auto-board"),
            sub = minimum?.let { tr("Asks when you have {amount} or more", money(BoardingRules.threshold(it))) } ?: tr("Auto-board threshold unavailable"),
            end = RowEnd.Toggle(autoBoard, !app.busy) { value ->
                app.action("Couldn't save auto-board preference.") {
                    app.settings.set(SettingsPreferences.autoBoard(app.settings.string("transaction-storage"), value), "transaction-storage")
                    saved(value)
                }
            }) },
        if (minimum != null) ({ d -> SettingsRow(tr("Smallest move"), SettingsIcons.ark, value = money(minimum), numeric = true, end = RowEnd.None,
            divider = d) }) else null,
    ))
}

/**
 * Move to Ark: the amount (in sats) and Max, then today's review rows — Ark fee, network fee, what arrives and the total
 * leaving on-chain — and an explicit Move. Changing the amount drops the review.
 */
@Composable
private fun BoardSheet(app: AppModel, model: OnchainModel, confirmed: Long, money: (Long) -> String, scope: kotlinx.coroutines.CoroutineScope,
    dismiss: () -> Unit) {
    val haptics = rememberHaptics()
    var amount by rememberSaveable { mutableStateOf("") }
    val quote = model.quote?.takeIf { it.action == OnchainAction.board }
    var current by remember(quote) { mutableStateOf(quote?.current() == true) }
    LaunchedEffect(quote) { while (quote != null && current) { kotlinx.coroutines.delay(250); current = quote.current() } }
    val max = amount == "MAX"
    val sats = amount.toLongOrNull()
    SettingsSheet(dismiss, tr("Move to Ark"), tr("From your on-chain balance."), modifier = Modifier.testTag("board-sheet")) { close ->
        BoardAmountRow(app, model, amount, money(quote?.amount ?: confirmed), confirmed > 0) { amount = it }
        if (quote != null) BoardReviewRows(quote, current, money)
        model.error?.let { FinePrint(tr(it), failed = true) }
        SheetActions(tr("Cancel"), if (quote == null) tr("Review") else tr("Move"), onCancel = close, loading = model.busy, confirmTag = "board-review",
            enabled = !model.busy && !app.isWalletSuspended && boardSubmittable(quote, max, sats, current), onConfirm = {
                // The page's scope, as today: closing the sheet must never cancel a submission.
                scope.launch { reviewOrMove(app, model, quote, if (max) 0 else sats ?: -1) { haptics.success(); close() } }
            })
    }
}

/** Review needs Max or an amount; Move needs a review that's still current. */
private fun boardSubmittable(quote: OnchainQuote?, max: Boolean, sats: Long?, current: Boolean) =
    if (quote == null) max || (sats ?: 0) > 0 else current

/** Reviews [amount] (0 for Max) without a review; otherwise moves it, and [moved] once it's moved. */
private suspend fun reviewOrMove(app: AppModel, model: OnchainModel, quote: OnchainQuote?, amount: Long, moved: () -> Unit) {
    if (quote == null) model.review(OnchainAction.board, amount = amount)
    else { model.confirm(); app.refresh(); if (model.error == null) moved() }
}

/** The amount being moved, in sats, or Max with the amount Max moves. */
@Composable
private fun BoardAmountRow(app: AppModel, model: OnchainModel, amount: String, maxAmount: String, hasConfirmed: Boolean, update: (String) -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val max = amount == "MAX"
    val bip177 = app.amountUnit == BitcoinAmountUnit.bip177
    Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val label = tr("Amount in sats")
        if (max) Text(maxAmount, Modifier.weight(1f), style = SettingsType.total, color = palette.foreground, maxLines = 1)
        else Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            if (bip177) Text("₿", style = SettingsType.total, color = if (amount.isEmpty()) palette.muted else palette.foreground)
            BasicTextField(amount, { typed -> update(typed.filter(Char::isDigit).take(16)); model.invalidate() },
                Modifier.weight(1f).semantics { contentDescription = label }.testTag("board-amount"), enabled = !model.busy,
                textStyle = SettingsType.total.copy(color = palette.foreground), singleLine = true, cursorBrush = SolidColor(palette.orange),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                decorationBox = { inner ->
                    Box {
                        if (amount.isEmpty()) Text("0", style = SettingsType.total, color = palette.muted)
                        inner()
                    }
                })
            if (!bip177) Text(" " + tr("sats"), Modifier.padding(bottom = 6.dp), style = HomeType.line, color = palette.muted)
        }
        com.noahwallet.ui.home.Pill(tr("Max"), Modifier.testTag("board-max"), outlined = !max, enabled = !model.busy && hasConfirmed) {
            haptics.selection(); update(if (max) "" else "MAX"); model.invalidate()
        }
    }
}

@Composable
private fun BoardReviewRows(quote: OnchainQuote, current: Boolean, money: (Long) -> String) {
    val toasts = LocalSettingsToasts.current
    Column(Modifier.padding(top = 6.dp)) {
        DetailRow(tr("Ark fee"), money(checkNotNull(quote.fee)), ValueKind.number, toasts = toasts)
        DetailRow(tr("Network fee"), money(checkNotNull(quote.chainFee)), ValueKind.number, toasts = toasts)
        DetailRow(tr("Arrives in Ark"), money(quote.net), ValueKind.number, toasts = toasts)
        DetailRow(tr("Total"), money(quote.totalLeaving), ValueKind.number, toasts = toasts, last = true)
        FinePrint(reviewText(quote))
        if (!current) FinePrint(tr(OnchainFailure.changed.text), failed = true)
    }
}
