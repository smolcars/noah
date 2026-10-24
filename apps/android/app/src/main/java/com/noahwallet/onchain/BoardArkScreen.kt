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
    var autoBoard by remember { mutableStateOf(SettingsPreferences.transaction(app.settings.string("transaction-storage"))["isAutoBoardingEnabled"]?.jsonPrimitive?.booleanOrNull != false) }
    var moving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(app.isWalletSuspended) {
        if (!onchainReady(app)) return@LaunchedEffect
        minimum = runCatching { app.session.boardingMinimum() }.getOrNull()
        confirmations = runCatching { withContext(Dispatchers.IO) { app.session.arkInfo()?.requiredBoardConfirmations?.toLong() } }.getOrNull()
    }
    OnchainEffects(app, model, reloadOnChange = true)
    fun money(sats: Long) = if (app.hidden) BitcoinAmount.format(0, app.amountUnit, locale).replace("0", "••••") else BitcoinAmount.format(sats, app.amountUnit, locale)
    val overview = model.overview
    // A submitted board until it has the confirmations Ark needs: Send's block row follows it.
    val boarding = model.attempts.firstOrNull { attempt ->
        val status = attempt.receipt?.chainStatus
        attempt.quote.action == OnchainAction.board && attempt.receipt?.txid != null && attempt.overrideAudit == null &&
            (status?.kind != ChainStatus.Kind.confirmed || overview != null && status.height != null && overview.tip - status.height + 1 < (confirmations ?: 1))
    }
    SettingsFrame(com.noahwallet.localization.tr("On-chain"), nav.back, foot = if (boarding != null) null else ({
        FootPill(tr("Move to Ark"), Modifier.testTag("board-open"), enabled = (overview?.confirmed ?: 0) > 0 && !model.busy && !app.isWalletSuspended) {
            model.invalidate(); moving = true
        }
    })) {
        if (app.isWalletSuspended) { FinePrint(tr("Wallet suspended. Resume your wallet in Settings.")); return@SettingsFrame }
        BigTotal(overview?.let { money(it.confirmed) } ?: "—", when {
            boarding != null -> tr("Moving {amount} to Ark", money(boarding.quote.amount))
            overview == null && model.loading -> tr("Loading…")
            else -> tr("Confirmed")
        })
        if (boarding != null && overview != null) ActivityBlockRow(overview.tip, boarding.receipt?.chainStatus?.height, confirmations ?: 1, locale)
        SettingsGroup(rows = listOfNotNull(
            { d -> SettingsRow(tr("Move automatically"), SettingsIcons.refresh, divider = d, modifier = Modifier.testTag("settings-auto-board"),
                sub = minimum?.let { tr("Asks when you have {amount} or more", money(BoardingRules.threshold(it))) } ?: tr("Auto-board threshold unavailable"),
                end = RowEnd.Toggle(autoBoard, !app.busy) { value ->
                    app.action("Couldn't save auto-board preference.") {
                        app.settings.set(SettingsPreferences.autoBoard(app.settings.string("transaction-storage"), value), "transaction-storage")
                        autoBoard = value
                    }
                }) },
            if (minimum != null) ({ d -> SettingsRow(tr("Smallest move"), SettingsIcons.ark, value = money(minimum!!), numeric = true, end = RowEnd.None, divider = d) }) else null,
        ))
        FinePrint(tr("Bitcoin in Ark moves instantly and costs less to send.") + (confirmations?.let { " " + tr("It needs {n} confirmations to arrive.", it.toString()) } ?: ""))
        OnchainStatus(app, model)
    }
    if (moving) BoardSheet(app, model, overview?.confirmed ?: 0, ::money, scope) { moving = false; model.invalidate() }
}

/**
 * Move to Ark: the amount (in sats) and Max, then today's review rows — Ark fee, network fee, what arrives and the total
 * leaving on-chain — and an explicit Move. Changing the amount drops the review.
 */
@Composable
private fun BoardSheet(app: AppModel, model: OnchainModel, confirmed: Long, money: (Long) -> String, scope: kotlinx.coroutines.CoroutineScope, dismiss: () -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    var amount by rememberSaveable { mutableStateOf("") }
    val quote = model.quote?.takeIf { it.action == OnchainAction.board }
    var current by remember(quote) { mutableStateOf(quote?.current() == true) }
    LaunchedEffect(quote) { while (quote != null && current) { kotlinx.coroutines.delay(250); current = quote.current() } }
    val max = amount == "MAX"
    val sats = amount.toLongOrNull()
    val bip177 = app.amountUnit == BitcoinAmountUnit.bip177
    SettingsSheet(dismiss, tr("Move to Ark"), tr("From your on-chain balance."), modifier = Modifier.testTag("board-sheet")) { close ->
        Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val label = tr("Amount in sats")
            if (max) Text(money(quote?.amount ?: confirmed), Modifier.weight(1f), style = SettingsType.total, color = palette.foreground, maxLines = 1)
            else Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                if (bip177) Text("₿", style = SettingsType.total, color = if (amount.isEmpty()) palette.muted else palette.foreground)
                BasicTextField(amount, { typed -> amount = typed.filter(Char::isDigit).take(16); model.invalidate() },
                    Modifier.weight(1f).semantics { contentDescription = label }.testTag("board-amount"), enabled = !model.busy,
                    textStyle = SettingsType.total.copy(color = palette.foreground), singleLine = true, cursorBrush = SolidColor(palette.orange),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    decorationBox = { inner -> Box { if (amount.isEmpty()) Text("0", style = SettingsType.total, color = palette.muted); inner() } })
                if (!bip177) Text(" " + tr("sats"), Modifier.padding(bottom = 6.dp), style = HomeType.line, color = palette.muted)
            }
            com.noahwallet.ui.home.Pill(tr("Max"), Modifier.testTag("board-max"), outlined = !max, enabled = !model.busy && confirmed > 0) {
                haptics.selection(); amount = if (max) "" else "MAX"; model.invalidate()
            }
        }
        if (quote != null) Column(Modifier.padding(top = 6.dp)) {
            DetailRow(tr("Ark fee"), money(checkNotNull(quote.fee)), ValueKind.number, toasts = toasts)
            DetailRow(tr("Network fee"), money(checkNotNull(quote.chainFee)), ValueKind.number, toasts = toasts)
            DetailRow(tr("Arrives in Ark"), money(quote.net), ValueKind.number, toasts = toasts)
            DetailRow(tr("Total"), money(quote.totalLeaving), ValueKind.number, toasts = toasts, last = true)
            FinePrint(reviewText(quote))
            if (!current) FinePrint(tr(OnchainFailure.changed.text), failed = true)
        }
        model.error?.let { FinePrint(tr(it), failed = true) }
        SheetActions(tr("Cancel"), if (quote == null) tr("Review") else tr("Move"), onCancel = close, loading = model.busy, confirmTag = "board-review",
            enabled = !model.busy && !app.isWalletSuspended && if (quote == null) max || (sats ?: 0) > 0 else current, onConfirm = {
                // The page's scope, as today: closing the sheet must never cancel a submission.
                scope.launch {
                    if (quote == null) model.review(OnchainAction.board, amount = if (max) 0 else sats ?: -1)
                    else { model.confirm(); app.refresh(); if (model.error == null) { haptics.success(); close() } }
                }
            })
    }
}
