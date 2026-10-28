package com.noahwallet.onchain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.localization.tr
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.services.wallet.WalletBackgroundActivity
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.WalletAlertDialog
import com.noahwallet.ui.WalletModalBottomSheet
import com.noahwallet.ui.readableWidth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop

fun onchainReady(app: AppModel) = onchainWallet(app, ExitRules::ready)
private fun onchainWallet(app: AppModel, rule: (Boolean, Boolean, Boolean, Boolean) -> Boolean): Boolean {
    val persisted = app.settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
    return rule(persisted?.isInitialized != false, app.session.isOpen,
        app.isWalletSuspended || persisted?.isWalletSuspended == true, WalletBackgroundActivity.isRunning)
}
/** Waits while the wallet is only briefly not ready ([ExitRules.settling]), as after every app switch; true once it's ready. */
internal suspend fun onchainSettle(app: AppModel): Boolean {
    // shortcut: checks every half second for up to 30 s; a flow of the session's state would replace it.
    repeat(60) {
        if (!onchainWallet(app, ExitRules::settling)) return onchainReady(app)
        delay(500)
    }
    return onchainReady(app)
}
internal fun onchainDependencies(app: AppModel) =
    onchainDependencies(app.session, app.services.lifecycle, AutoBoardFunding.stored(app.settings), ExitClaimRecords.stored(app.settings),
        ExitSeen.stored(app.settings), settle = { onchainSettle(app) }, shared = SharedOverview.shared) { onchainReady(app) }

/** The model's calls on [session], each mutation under [lifecycle]'s gate; a device test runs them on a fake wallet. */
@Suppress("LongParameterList") // wiring: one argument per dependency
internal fun onchainDependencies(session: com.noahwallet.services.wallet.WalletSession, lifecycle: com.noahwallet.services.wallet.WalletLifecycle,
    funding: AutoBoardFunding, claims: ExitClaimRecords, seen: ExitSeen, settle: suspend () -> Boolean = { false },
    shared: SharedOverview? = null, ready: () -> Boolean) = OnchainModel.Dependencies(
    ready = ready, settle = settle, overview = session::onchainOverview,
    quote = { action, ids, amount, destination -> session.quoteOnchain(action, ids, amount, destination) },
    execute = { quote -> lifecycle.withWalletMutation {
        if (!ready()) fail(OnchainFailure.unavailable)
        // A started or canceled exit begins a new run: its old proof and tips must not carry over, even if this caller is gone.
        session.executeOnchain(quote, keepOnchain = { funding.keep(quote.walletID, it) }, recordClaim = claims::add,
            newRun = { ExitFunding.shared.forget(it); seen.forget(it) }, shortOfFunds = ExitFunding.shared::reported)
    } },
    attempts = { withContext(Dispatchers.IO) { session.onchainJournal.loadAll() } },
    acknowledge = { id -> lifecycle.withWalletMutation {
        if (!ready()) fail(OnchainFailure.unavailable)
        withContext(Dispatchers.IO) { session.onchainJournal.acknowledge(id) }
    } },
    check = session::checkOnchainAttempt,
    override = { id -> lifecycle.withWalletMutation {
        if (!ready()) fail(OnchainFailure.unavailable)
        withContext(Dispatchers.IO) { session.onchainJournal.overrideUncertain(id) }
    } },
    detail = session::exitDetail, refreshReceipts = session::refreshOnchainReceiptFees,
    claimAddress = session::exitDepositAddress, claimOwnership = session::exitClaimDestinationIsMine, discard = session::discardBoardReview,
    executeRenewal = { quote -> lifecycle.withWalletMutation {
        if (!ready()) fail(OnchainFailure.unavailable)
        session.executeOnchain(quote, renewal = true)
    } },
    coin = session::vtxoDetail, shared = shared,
)

@Composable
internal fun rememberOnchainModel(app: AppModel, detailID: String? = null): OnchainModel =
    remember(app, detailID) { OnchainModel(onchainDependencies(app), detailID) }

/**
 * The model's lifecycle on a page: quotes drop when Noah pauses or the wallet changes, a prepared board expires, and the
 * overview reads while the page is started (at once when the wallet reports a change, else every minute) and stops
 * while the wallet is suspended.
 */
@Composable
internal fun OnchainEffects(app: AppModel, model: OnchainModel, reloadOnChange: Boolean = false) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) model.invalidate() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.invalidate() }
    }
    // Settings' pages read the wallet again at once rather than showing nothing until the next periodic read.
    // One that reads again at once keeps what it shows until that read replaces it (it waits out a reopen) or finds the wallet gone.
    LaunchedEffect(model, app.session) { app.session.walletChanges.collect {
        if (reloadOnChange && !app.isWalletSuspended) { model.invalidate(); model.load() } else model.unavailable()
    } }
    LaunchedEffect(model.quote) {
        val held = model.quote ?: return@LaunchedEffect
        if (held.preparedBoard != null) {
            delay((BoardPrepared.lifetimeMillis - held.elapsed()).coerceAtLeast(0))
            if (model.quote == held) model.invalidate()
        }
    }
    LaunchedEffect(model, app.isWalletSuspended) {
        if (app.isWalletSuspended) { model.unavailable(); return@LaunchedEffect }
        // A finished renew or refresh, a sync, new funds or Check the chain: bark's change events reload history,
        // and the balance refresh after them bumps its revision.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.keepLoaded { app.history.revision to app.summaryRevision } }
    }
}

/** Reads the overview again each time [changes] moves (not for its first value), unless busy or a review is open. */
internal suspend fun OnchainModel.reloadOnChanges(changes: () -> Any) =
    snapshotFlow(changes).drop(1).collect { if (!busy && quote == null) load() }

/** Reads now, on every move of [changes] (watched from before that read, so one during it isn't missed) and each minute. */
internal suspend fun OnchainModel.keepLoaded(changes: () -> Any): Unit = coroutineScope {
    launch(start = CoroutineStart.UNDISPATCHED) { reloadOnChanges(changes) }
    load()
    while (isActive) { delay(60_000); if (!busy && quote == null) load() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnchainScreen(app: AppModel, model: OnchainModel, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    OnchainEffects(app, model)
    PullToRefreshBox(model.loading, onRefresh = { model.invalidate(); scope.launch { model.load() } }) {
        Column(Modifier.fillMaxSize().readableWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (app.isWalletSuspended) Text(tr("Wallet suspended. Resume your wallet in Settings."))
            else content()
            OnchainStatus(app, model)
        }
    }
    model.quote?.let { quote ->
        OnchainReview(app, quote, onDismiss = model::invalidate, confirm = { scope.launch { model.confirm(); app.refresh() } })
    }
}

@Composable
internal fun AmountRow(app: AppModel, title: String, sats: Long) {
    ListItem(headlineContent = { Text(tr(title)) }, supportingContent = { Text(tr(amountText(app, sats))) })
}
internal fun amountText(app: AppModel, sats: Long) = BitcoinAmount.format(sats, app.amountUnit, java.util.Locale.forLanguageTag(app.localeIdentifier))

@Composable
internal fun DetailRow(title: String, value: String) {
    Text(tr(title), style = MaterialTheme.typography.labelLarge)
    SelectionContainer { Text(tr(value)) }
}
internal fun copy(context: Context, value: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Noah", value))
}
@Composable
internal fun TransactionRow(txid: String) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    DetailRow("Transaction ID", txid)
    TextButton({ copy(context, txid) }) { Text(tr("Copy transaction ID")) }
    if (BuildConfig.APP_VARIANT != "regtest") {
        TextButton({ uri.openUri("https://mempool.space/" + (if (BuildConfig.APP_VARIANT == "signet") "signet/" else "") + "tx/$txid") }) {
            Text(tr("View in explorer"))
        }
    }
}

@Composable
internal fun OnchainStatus(app: AppModel, model: OnchainModel) {
    val scope = rememberCoroutineScope()
    var overrideID by remember { mutableStateOf<String?>(null) }
    if (model.loading || model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    model.attempts.forEach { attempt -> AttemptStatus(app, model, attempt, scope) { overrideID = it } }
    overrideID?.let { id -> OverrideDialog({ overrideID = null }) { overrideID = null; scope.launch { model.overrideUncertain(id) } } }
    if (model.estimateFallback != null) {
        Text(tr("Fee estimate unavailable"))
        TextButton(model::continueWithoutEstimate, enabled = !model.busy) { Text(tr("Continue without estimate")) }
    }
    model.error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
    model.message?.let { Text(tr(it)) }
}

/** One saved attempt: what it reviewed, its receipt or why it's uncertain, and what can be done next. */
@Composable
private fun AttemptStatus(app: AppModel, model: OnchainModel, attempt: OnchainOperationJournal.Attempt, scope: kotlinx.coroutines.CoroutineScope,
    override: (String) -> Unit) {
    HorizontalDivider()
    Text(tr(if (attempt.overrideAudit != null) "Overridden attempt" else if (attempt.receipt == null) "Check operation status" else "Submitted"),
        style = MaterialTheme.typography.titleLarge)
    Text(tr(attempt.quote.action.title))
    AmountRow(app, "Reviewed amount", attempt.quote.amount)
    Text(tr(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(attempt.quote.createdAt))))
    Text(tr(attempt.receipt?.message ?: OnchainFailure.uncertain.text))
    attempt.overrideAudit?.let { audit ->
        Text(tr("Repeat protection overridden") + ": " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(audit.overriddenAt)))
        Text(tr(audit.statusMessage)); Text(tr(audit.title)); Text(tr(audit.warning)); Text(tr(audit.confirmation))
    }
    attempt.receipt?.let { ReceiptRows(app, attempt, it) }
    (attempt.receipt?.txid ?: attempt.txid)?.let { TransactionRow(it) }
    AttemptActions(app, model, attempt, scope, override)
}

@Composable
private fun ReceiptRows(app: AppModel, attempt: OnchainOperationJournal.Attempt, receipt: OnchainReceipt) {
    receipt.chainStatus?.let { Text(tr(it.text)) }
    receipt.statusError?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
    receipt.onchainFee?.let { AmountRow(app, "Actual onchain fee", it) }
    if (attempt.quote.action == OnchainAction.board) {
        val amounts = receipt.boardAmounts(attempt.quote)
        AmountRow(app, "Ark fee", checkNotNull(attempt.quote.fee))
        AmountRow(app, "Ark receives", amounts.arkReceives)
        AmountRow(app, "Total leaving onchain wallet", amounts.totalLeaving)
    }
}

/** Done and Refresh for a settled receipt; otherwise Check status and, unless locked, the override. */
@Composable
private fun AttemptActions(app: AppModel, model: OnchainModel, attempt: OnchainOperationJournal.Attempt, scope: kotlinx.coroutines.CoroutineScope,
    override: (String) -> Unit) {
    val enabled = !model.busy && !app.isWalletSuspended
    if (attempt.receipt != null && !attempt.receipt.broadcastRetryPending) {
        TextButton({ scope.launch { model.acknowledge(attempt.id) } }, enabled = enabled) { Text(tr("Done")) }
        if (attempt.receipt.txid != null) TextButton({ scope.launch { model.checkStatus(attempt.id) } },
            enabled = enabled) { Text(tr("Refresh network status")) }
    }
    else {
        Text(tr("Noah checks again automatically as your wallet syncs."))
        TextButton({ scope.launch { model.checkStatus(attempt.id) } }, enabled = enabled) { Text(tr("Check status")) }
        // An uncertain board stays locked until Check status settles it, so it has no override.
        if (attempt.overridable()) TextButton({ override(attempt.id) },
            enabled = enabled) { Text(tr("Override repeat protection…"), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun OverrideDialog(dismiss: () -> Unit, confirm: () -> Unit) {
    WalletAlertDialog(onDismissRequest = dismiss,
        title = { Text(tr(OnchainOperationJournal.overrideTitle)) }, text = { Text(tr(OnchainOperationJournal.overrideWarning)) },
        dismissButton = { TextButton(dismiss) { Text(tr("Keep attempt")) } },
        confirmButton = { TextButton(confirm) { Text(tr(OnchainOperationJournal.overrideConfirmation)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnchainReview(app: AppModel, quote: OnchainQuote, onDismiss: () -> Unit, confirm: () -> Unit,
    cancelTitle: String = "Cancel", cancel: () -> Unit = onDismiss) {
    var current by remember(quote) { mutableStateOf(quote.current()) }
    LaunchedEffect(quote) { while (isActive && current) { delay(250); current = quote.current() } }
    WalletModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(if (quote.feesUnknown) "Continue without estimate?" else quote.action.title), style = MaterialTheme.typography.headlineSmall)
            ReviewAmounts(app, quote)
            ReviewDetails(app, quote)
            ReviewWarnings(quote)
            if (!current) Text(tr(OnchainFailure.changed.text), color = MaterialTheme.colorScheme.error)
            Button(confirm,
                enabled = current && !app.isWalletSuspended) {
                val unestimated = if (quote.action == OnchainAction.claim) "Broadcast without estimate" else "Start Selected Exit"
                Text(tr(if (quote.feesUnknown) unestimated else quote.action.title))
            }
            TextButton(cancel) { Text(tr(cancelTitle)) }
        }
    }
}
@Composable
private fun ReviewAmounts(app: AppModel, quote: OnchainQuote) {
    AmountRow(app, if (quote.action == OnchainAction.board) "Amount boarded" else "Amount selected", quote.amount)
    if (quote.ids.isNotEmpty()) Text(tr("VTXOs selected") + ": " + quote.ids.size)
    if (quote.action != OnchainAction.cancel && quote.fee != null && quote.chainFee != null) {
        AmountRow(app, when (quote.action) { OnchainAction.board -> "Ark fee"; OnchainAction.refresh -> "Refresh fee"; else -> "Estimated claim fee" },
            quote.fee)
        if (quote.action in setOf(OnchainAction.board, OnchainAction.startExit, OnchainAction.progress))
            AmountRow(app, if (quote.action == OnchainAction.board) "Onchain fee" else "Estimated broadcast fees", quote.chainFee)
        AmountRow(app, if (quote.action == OnchainAction.board) "Ark receives" else "Amount after fee", quote.net)
    }
}

@Composable
private fun ReviewDetails(app: AppModel, quote: OnchainQuote) {
    quote.preparedBoard?.let { prepared ->
        AmountRow(app, "Total leaving onchain wallet", quote.totalLeaving)
        DetailRow("Signed transaction size", "${prepared.vsize} vB")
        DetailRow("Funding inputs", "${prepared.inputs.size}")
    }
    if (quote.automatic) AmountRow(app, "Onchain reserve left", quote.confirmed - quote.amount - checkNotNull(quote.chainFee))
    quote.destination?.let { DetailRow("Destination", it) }
    quote.externalDestinationWarning?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ReviewWarnings(quote: OnchainQuote) {
    if (quote.feesUnknown) {
        Text(tr("Fee estimate unavailable. Fees and amount to receive are unknown. Continue without an estimate only if you accept this uncertainty."),
            color = MaterialTheme.colorScheme.error)
        if (quote.action == OnchainAction.startExit) Text(tr("Broadcast funding status is unknown; confirmed onchain funds are required."))
    }
    Text(reviewText(quote))
    if (quote.action in setOf(OnchainAction.startExit, OnchainAction.progress, OnchainAction.claim)) {
        if (quote.fee != null && quote.chainFee != null) ExitRules.warning(quote.amount, quote.fee, quote.totalFee,
            quote.action == OnchainAction.claim)?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
        @Suppress("MaxLineLength") // one translation source string
        if (!quote.feesUnknown && !quote.fundable) Text(tr("Additional confirmed onchain funds required. You are starting tracking anyway; fund the wallet before progressing."))
    }
}

/** The review's explanation; MAX adds its own sentence, translated separately. */
@Composable
internal fun reviewText(quote: OnchainQuote): String = tr(reviewExplanation(quote)) +
    if (quote.action == OnchainAction.board && quote.boardAll) " " + tr("MAX uses confirmed funds only and deducts the onchain fee from that balance.") else ""
@Suppress("MaxLineLength") // translation source strings
internal fun reviewExplanation(quote: OnchainQuote): String = when (quote.action) {
    OnchainAction.board -> "Move onchain bitcoin into Ark using this exact signed funding transaction. The boarding amount includes the Ark fee; the onchain fee is paid in addition. Funds become spendable after the board completes. This review expires after two minutes."
    OnchainAction.refresh -> "This refreshes the selected VTXOs in a delegated Ark round."
    OnchainAction.startExit -> if (quote.feesUnknown) "Once started, Noah progresses the exit on its own, including in the background when the system lets it, broadcasting and fee-bumping exit transactions and paying fees from your onchain balance. Only the claim fee is deducted from the recovered value. Cancellation is possible only until the final exit transaction is broadcast." else "Once started, Noah progresses the exit on its own, including in the background when the system lets it, broadcasting and fee-bumping exit transactions and paying fees from your onchain balance. The fees shown are estimates and may change. Only the claim fee is deducted from the recovered value. Cancellation is possible only until the final exit transaction is broadcast."
    OnchainAction.progress -> "This may broadcast or fee-bump Bitcoin transactions required by the emergency exit process. Fees are paid from your onchain balance. Cancellation is possible only before the final exit transaction is broadcast."
    OnchainAction.claim -> "This broadcasts the claim transaction. Earlier broadcast fees are not deducted again."
    OnchainAction.cancel -> "Cancel this exit before its final transaction is broadcast. The VTXO remains spendable. The wallet will recheck whether cancellation is still possible."
}
