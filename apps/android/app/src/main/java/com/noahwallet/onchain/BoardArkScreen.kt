package com.noahwallet.onchain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
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
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Snapshots the selection when opening the sheet so a refresh pruning stale coins cannot silently widen or shift its scope.
    var sheetSelected by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var sheetTotal by rememberSaveable { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    LaunchedEffect(app.isWalletSuspended) { loadBoardingTerms(app, { minimum = it }) { confirmations = it } }
    OnchainEffects(app, model, reloadOnChange = true)
    fun money(sats: Long) = shownAmount(app, locale, sats)
    val overview = model.overview
    val utxos = overview?.utxos.orEmpty()
    val chosen = activeSelection(autoBoard, selected, utxos)
    LaunchedEffect(chosen) { if (chosen != selected) selected = chosen }
    val chosenTotal = utxos.filter { it.id in chosen }.sumOf { it.amount }
    // A submitted board until it has the confirmations Ark needs: Send's block row follows it.
    val boarding = model.attempts.firstOrNull { boardingUnsettled(it, overview, confirmations) }
    val movable = canMoveToArk(chosen, overview, model, app)
    val footLabel = if (chosen.isEmpty()) tr("Move to Ark") else trPlural("Move {n} coins to Ark", chosen.size)
    SettingsFrame(com.noahwallet.localization.tr("On-chain"), nav.back, foot = moveFoot(boarding == null, movable, footLabel) {
        model.invalidate(); sheetSelected = chosen; sheetTotal = chosenTotal
    }) {
        if (app.isWalletSuspended) { FinePrint(tr("Wallet suspended. Resume your wallet in Settings.")); return@SettingsFrame }
        OnchainTotal(model, boarding, confirmations, locale, ::money)
        AutoBoardRows(app, autoBoard, minimum, ::money) { enabled ->
            autoBoard = enabled
            if (enabled) { selected = emptyList(); model.invalidate() }
        }
        if (!autoBoard && boarding == null && utxos.isNotEmpty()) {
            OnchainUtxoSection(utxos, chosen.toSet(), locale, enabled = !model.busy && !app.isWalletSuspended, money = ::money) { utxo ->
                haptics.selection()
                selected = toggleSelected(selected, utxo.id)
                model.invalidate()
            }
        }
        if (autoBoard) FinePrint(tr("Turn off Move automatically to choose individual on-chain coins to move."))
        FinePrint(boardingNote(confirmations))
        OnchainStatus(app, model)
    }
    sheetSelected?.let { snapshotted ->
        BoardSheet(app, model, overview?.confirmed ?: 0, snapshotted, sheetTotal, ::money, scope, onMoved = { selected = emptyList() }) {
            sheetSelected = null; model.invalidate()
        }
    }
}

private fun activeSelection(autoBoard: Boolean, selected: List<String>, utxos: List<OnchainUtxoModel>): List<String> =
    if (autoBoard) emptyList() else BoardUtxoSelection.prune(selected.toSet(), utxos).let { valid -> selected.filter { it in valid } }

private fun canMoveToArk(chosen: List<String>, overview: OnchainOverview?, model: OnchainModel, app: AppModel) =
    (chosen.isNotEmpty() || (overview?.confirmed ?: 0) > 0) && !model.busy && !app.isWalletSuspended

private fun toggleSelected(selected: List<String>, id: String) = if (id in selected) selected - id else selected + id

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
private fun moveFoot(shown: Boolean, enabled: Boolean, label: String, open: () -> Unit): (@Composable RowScope.() -> Unit)? =
    if (!shown) null else ({ FootPill(label, Modifier.testTag("board-open"), enabled = enabled, onClick = open) })

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

/** Selectable on-chain UTXOs shown while Move automatically is off; unconfirmed coins cannot be picked. */
@Composable
private fun OnchainUtxoSection(utxos: List<OnchainUtxoModel>, selected: Set<String>, locale: Locale, enabled: Boolean,
    money: (Long) -> String, toggle: (OnchainUtxoModel) -> Unit) {
    val palette = LocalPalette.current
    Text(tr("On-chain coins"), Modifier.padding(top = 18.dp, bottom = 2.dp).semantics { heading() }, style = HomeType.header, color = palette.muted)
    utxos.forEachIndexed { index, utxo ->
        OnchainUtxoRow(utxo, utxo.id in selected, locale, divider = index < utxos.lastIndex, enabled = enabled, money = money) { toggle(utxo) }
    }
    @Suppress("MaxLineLength") // one translation source string
    val privacyNote = tr("Select one or more confirmed coins to move them on their own. Moving one coin at a time avoids linking your other on-chain coins, but each move pays its own network fee.")
    FinePrint(privacyNote)
}

@Composable
private fun OnchainUtxoRow(utxo: OnchainUtxoModel, checked: Boolean, locale: Locale, divider: Boolean, enabled: Boolean,
    money: (Long) -> String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shortPoint = "${utxo.txid.take(6)}…:${utxo.vout}"
    val subtitle = utxo.confirmationHeight?.let {
        tr("Block {height} · {id}", java.text.NumberFormat.getIntegerInstance(locale).format(it), shortPoint)
    } ?: shortPoint
    Row(Modifier.fillMaxWidth().height(66.dp)
        .drawBehind { if (divider) drawRect(palette.hairline, Offset(56.dp.toPx(), size.height - 1.dp.toPx()), Size(size.width - 56.dp.toPx(), 1.dp.toPx())) }
        .clickable(enabled = enabled && utxo.selectable, role = Role.Checkbox, onClick = onClick)
        .semantics(mergeDescendants = true) { toggleableState = ToggleableState(checked) }
        .graphicsLayer { alpha = if (utxo.selectable) 1f else .4f }.testTag("onchain-utxo-${utxo.txid.take(8)}-${utxo.vout}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            Checkbox(checked, null, enabled = utxo.selectable,
                colors = CheckboxDefaults.colors(checkedColor = palette.foreground, checkmarkColor = palette.background,
                    uncheckedColor = palette.muted, disabledUncheckedColor = palette.hairline))
        }
        Column(Modifier.weight(1f)) {
            Text(if (utxo.confirmed) tr("Confirmed") else tr("Unconfirmed"), style = HomeType.rowTitle,
                color = if (utxo.confirmed) palette.foreground else palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = HomeType.small, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(money(utxo.amount), style = HomeType.amount, color = if (utxo.confirmed) palette.foreground else palette.muted, maxLines = 1)
    }
}

/**
 * Move to Ark: the amount (in sats) and Max, then today's review rows — Ark fee, network fee, what arrives and the total
 * leaving on-chain — and an explicit Move. Changing the amount drops the review. When [selected] is non-empty, the board
 * spends only those confirmed UTXOs and starts on Max (complete coin(s), no change output).
 */
@Composable
private fun BoardSheet(app: AppModel, model: OnchainModel, confirmed: Long, selected: List<String>, selectedTotal: Long,
    money: (Long) -> String, scope: kotlinx.coroutines.CoroutineScope, onMoved: () -> Unit, dismiss: () -> Unit) {
    val haptics = rememberHaptics()
    var amount by rememberSaveable(selected) { mutableStateOf(if (selected.isNotEmpty()) "MAX" else "") }
    val quote = model.quote?.takeIf { it.action == OnchainAction.board }
    var current by remember(quote) { mutableStateOf(quote?.current() == true) }
    LaunchedEffect(quote) { while (quote != null && current) { kotlinx.coroutines.delay(250); current = quote.current() } }
    val max = amount == "MAX"
    val sats = amount.toLongOrNull()
    val available = if (selected.isEmpty()) confirmed else selectedTotal
    val line = if (selected.isEmpty()) tr("From your on-chain balance.") else trPlural("From {n} selected coins.", selected.size)
    SettingsSheet(dismiss, tr("Move to Ark"), line, modifier = Modifier.testTag("board-sheet")) { close ->
        BoardAmountRow(app, model, amount, money(quote?.amount ?: available), available > 0) { amount = it }
        if (quote != null) BoardReviewRows(quote, current, money)
        model.error?.let { FinePrint(tr(it), failed = true) }
        SheetActions(tr("Cancel"), if (quote == null) tr("Review") else tr("Move"), onCancel = close, loading = model.busy, confirmTag = "board-review",
            enabled = !model.busy && !app.isWalletSuspended && boardSubmittable(quote, max, sats, current), onConfirm = {
                // The page's scope, as today: closing the sheet must never cancel a submission.
                val requested = if (max) 0 else sats ?: -1
                scope.launch {
                    if (quote == null) model.review(OnchainAction.board, ids = selected, amount = requested)
                    else confirmBoardSheet(app, model) { haptics.success(); onMoved(); close() }
                }
            })
    }
}

private suspend fun confirmBoardSheet(app: AppModel, model: OnchainModel, done: () -> Unit) {
    model.confirm()
    app.refresh()
    if (model.error == null) done()
}

/** Review needs Max or an amount; Move needs a review that's still current. */
private fun boardSubmittable(quote: OnchainQuote?, max: Boolean, sats: Long?, current: Boolean) =
    if (quote == null) max || (sats ?: 0) > 0 else current

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
    val prepared = quote.preparedBoard
    Column(Modifier.padding(top = 6.dp)) {
        DetailRow(tr("Ark fee"), money(checkNotNull(quote.fee)), ValueKind.number, toasts = toasts)
        DetailRow(tr("Network fee"), money(checkNotNull(quote.chainFee)), ValueKind.number, toasts = toasts)
        DetailRow(tr("Arrives in Ark"), money(quote.net), ValueKind.number, toasts = toasts)
        DetailRow(tr("Total"), money(quote.totalLeaving), ValueKind.number, toasts = toasts, last = prepared == null)
        if (prepared != null) DetailRow(tr("Funding inputs"), prepared.inputs.size.toString(), ValueKind.number, toasts = toasts, last = true)
        FinePrint(reviewText(quote))
        if (!current) FinePrint(tr(OnchainFailure.changed.text), failed = true)
    }
}
