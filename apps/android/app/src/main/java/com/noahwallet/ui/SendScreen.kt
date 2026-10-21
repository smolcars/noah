package com.noahwallet.ui

import com.noahwallet.ui.WalletAlertDialog
import com.noahwallet.ui.WalletModalBottomSheet

import com.noahwallet.localization.tr
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.client.V0LnAddressSuggestionsClient
import com.noahwallet.api.generated.client.V0PricesClient
import com.noahwallet.api.generated.models.LightningAddressSuggestionsPayload
import com.noahwallet.send.*
import com.noahwallet.services.server.LightningIdentity
import com.noahwallet.services.server.value
import kotlinx.coroutines.*
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(app: AppModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val presentation = viewModel<SendPresentation>(key = "send-presentation") { SendPresentation(SendModel(SendRuntime.shared(context).controller, BuildConfig.APP_VARIANT, app.session::resolveSendRecipient, app.session::quoteSend, app.session::discardSend)) }
    val model = presentation.model
    val controller = model.controller
    val scope = presentation.viewModelScope
    val activity = context as android.app.Activity
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var scanner by rememberSaveable { mutableStateOf(false) }
    var outcomeSheet by rememberSaveable { mutableStateOf(true) }
    fun leaveReview() { model.discardReview(); model.stage = if (model.isMax) SendStage.source else SendStage.recipient }
    fun done() { scope.launch { controller.acknowledge(); if (!controller.locked) { model.reset(); app.refresh(); onDone() } } }
    LaunchedEffect(Unit) {
        model.fiatCurrency = app.preferredCurrency
        app.summary?.let { model.arkBalance = it.balance.ark.spendable.toLong(); model.onchainBalance = it.balance.onchain.confirmed.toLong() }
        runCatching { app.session.pendingSendHashes() }.getOrNull()?.let { model.pendingLightningHashes = it }
        controller.checkStatus()
    }
    LaunchedEffect(app.summary) {
        app.summary?.let { model.arkBalance = it.balance.ark.spendable.toLong(); model.onchainBalance = it.balance.onchain.confirmed.toLong() }
    }
    LaunchedEffect(controller.attempt?.receipt?.txid, app.isWalletSuspended) {
        if (controller.attempt?.receipt?.txid == null || app.isWalletSuspended) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { controller.checkStatus(); delay(10_000) }
        }
    }
    LaunchedEffect(app.preferredCurrency) {
        model.fiatCurrency = app.preferredCurrency
        model.btcPrice = runCatching { V0PricesClient(app.services.client).fiatPrices(JsonObject(emptyMap())).value().rates[app.preferredCurrency] }.getOrNull()?.takeIf { it.isFinite() && it > 0 }
    }
    LaunchedEffect(controller.state, app.repeatPayment) {
        app.repeatPayment?.let { if (!controller.locked) { model.prefill(it, app.preferredCurrency); app.repeatPayment = null } }
    }
    LaunchedEffect(controller.state, app.scannedDestination) {
        app.scannedDestination?.let { if (!controller.locked) {
            app.scannedDestination = null; model.reset(); model.importRecipient(it)
        } }
    }
    LaunchedEffect(model.destination) {
        val candidate = model.destination
        coroutineScope {
            launch { model.previewRecipient() }
            launch {
                val query = SendRecipientService.suggestionQuery(candidate, LightningIdentity.domain(BuildConfig.APP_VARIANT)) ?: return@launch
                delay(300)
                val result = runCatching { V0LnAddressSuggestionsClient(app.services.client).lnAddressSuggestions(LightningAddressSuggestionsPayload(query)).value().suggestions }.getOrDefault(emptyList())
                if (model.destination == candidate && isActive) model.suggestions = result
            }
        }
    }
    var previousStage by rememberSaveable { mutableStateOf(model.stage.name) }
    var previousOutcome by rememberSaveable { mutableStateOf(controller.state.name) }
    LaunchedEffect(model.stage, controller.state) {
        if (previousStage != model.stage.name || previousOutcome != controller.state.name) focus.clearFocus()
        previousStage = model.stage.name; previousOutcome = controller.state.name
    }
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && !activity.isChangingConfigurations && !controller.locked) model.discardReview()
            if (event == Lifecycle.Event.ON_STOP && !activity.isChangingConfigurations) scanner = false
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!activity.isChangingConfigurations) presentation.leave()
        }
    }
    BackHandler(model.showReview && !controller.locked) { leaveReview() }
    if (scanner) {
        SendScanner(onClose = { scanner = false }) { value -> scanner = false; scope.launch { model.importRecipient(value) } }
        return
    }
    if (controller.locked) {
        if (controller.state == SendController.State.succeeded && outcomeSheet) {
            WalletModalBottomSheet(onDismissRequest = { outcomeSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                SendOutcome(controller, ::done)
            }
        } else SendOutcome(controller, ::done)
        return
    }
    Column(Modifier.fillMaxSize().readableWidth().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { focus.clearFocus(); model.reset() }, enabled = !model.busy, modifier = Modifier.testTag("send-start-over")) { Text(tr("Start over")) }
            TextButton(onClick = { focus.clearFocus(); scanner = true }, enabled = !model.busy) { Text(tr("Scan QR")) }
        }
        Column(Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (model.stage) {
                SendStage.amount -> {
                    Text(tr("How much?"), style = MaterialTheme.typography.headlineLarge)
                    Text(tr(model.amount.ifEmpty { "0" }), style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("send-amount").semantics { contentDescription = "Amount ${model.amount.ifEmpty { "0" }}" })
                    TextButton(onClick = model::toggleCurrency, enabled = model.btcPrice != null && !model.busy && model.parsed.isAmountEditable, modifier = Modifier.testTag("send-currency-toggle")) { Text(tr(if (model.useFiat) model.fiatCurrency else "sats")) }
                    Text(tr("${model.arkBalance} sats available in Ark"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    app.summary?.balance?.let { balance ->
                        balance.pendingRoundMessage("${balance.ark.pendingInRound} sats")?.let {
                            Text(tr(it), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!model.parsed.isAmountEditable) Text(tr("Amount set by payment request"))
                    AmountKeypad(model.amount, model.useFiat, !model.busy && model.parsed.isAmountEditable) { model.amount = it }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = model::maxSend, enabled = !model.busy && model.parsed.isAmountEditable && (model.arkBalance > 0 || model.onchainBalance > 0), modifier = Modifier.testTag("send-max")) { Text(tr("MAX")) }
                        TextButton(onClick = model::recipientFirst, enabled = !model.busy) { Text(tr("Choose recipient first")) }
                    }
                    PasteRecipient { value -> scope.launch { model.importRecipient(value) } }
                }
                SendStage.recipient -> {
                    Text(tr("Who are you paying?"), style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(model.destination, model::setRecipient, label = { Text(tr("Address, invoice, or payment request")) },
                        enabled = !model.busy, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("send-recipient"))
                    PasteRecipient { value -> focus.clearFocus(); scope.launch { model.importRecipient(value) } }
                    model.matchingRoute?.let { route ->
                        Text(tr("Recipient verified"), color = MaterialTheme.colorScheme.primary)
                        if (route.commentAllowed > 0) {
                            OutlinedTextField(model.comment, { model.comment = it }, label = { Text(tr("Add a note")) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
                            Text(tr("${model.comment.length}/${route.commentAllowed}"), style = MaterialTheme.typography.bodySmall)
                            if (model.comment.isNotEmpty() && route.arkAddress != null) Text(tr("Adding a note will send this payment over Lightning."), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    model.suggestions.forEach { suggestion -> TextButton(onClick = { model.setRecipient(suggestion) }) { Text(tr(suggestion)) } }
                }
                SendStage.method -> {
                    Text(tr("Choose payment method"), style = MaterialTheme.typography.headlineMedium)
                    model.rails.forEach { rail ->
                        SendChoice(rail.label, model.progress.selectedRail == rail, model.availability[rail.name] == true && !model.busy) { model.progress = model.progress.copy(selectedRail = rail, railConfirmed = true) }
                    }
                }
                SendStage.source -> {
                    Text(tr("Choose balance"), style = MaterialTheme.typography.headlineMedium)
                    SendSource.entries.forEach { source ->
                        SendChoice("${source.label} · ${if (source == SendSource.offchain) model.arkBalance else model.onchainBalance} sats", model.source == source, source in model.sources && !model.busy) { model.source = source }
                    }
                }
                SendStage.review -> Text(tr("Review payment"), style = MaterialTheme.typography.headlineMedium)
            }
            model.error?.let { Text(model.destinationError?.localized(androidx.compose.ui.platform.LocalContext.current) ?: tr(it), color = MaterialTheme.colorScheme.error) }
            controller.message?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.padding(16.dp)) { NoahButton(if (model.busy || model.resolvingRecipient) "Preparing…" else "Continue", !model.busy && !model.resolvingRecipient, modifier = Modifier.testTag("send-continue")) { focus.clearFocus(); scope.launch { model.continueStage() } } }
    }
    val quote = model.quote; val payment = model.payment
    if (model.showReview && quote != null && payment != null) {
        LaunchedEffect(quote) {
            quote.prepared?.let { prepared ->
                delay(maxOf(0, prepared.createdAt + SendPrepared.lifetimeMillis - System.currentTimeMillis()))
                if (!controller.locked && model.quote == quote) { leaveReview(); model.error = "This review expired. Review the payment again." }
            }
        }
        WalletModalBottomSheet(onDismissRequest = { if (!controller.locked) leaveReview() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SendConfirmation(payment, quote, controller,
                confirm = { focus.clearFocus(); controller.submit(payment, quote); model.showReview = false }, cancel = ::leaveReview)
        }
    }
}


@Composable internal fun SendConfirmation(payment: SendPayment, quote: SendQuote, controller: SendController, confirm: () -> Unit, cancel: () -> Unit) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(tr("Confirm payment"), style = MaterialTheme.typography.headlineMedium)
                SelectionContainer { Text(payment.destination, style = MaterialTheme.typography.bodySmall) }
                Text(tr(if (!quote.vtxoIDs.isNullOrEmpty()) "Recipient receives about ${quote.amount} sats" else "${quote.amount} sats"), style = MaterialTheme.typography.headlineLarge)
                if (!quote.vtxoIDs.isNullOrEmpty()) Text(tr("The exact amount depends on the network fee when sent. The total is exactly the reviewed coins."), style = MaterialTheme.typography.bodySmall)
                ReviewRow(if (quote.approximate) "Estimated fee" else "Fee", "${quote.fee} sats")
                ReviewRow(if (quote.approximate && quote.vtxoIDs.isNullOrEmpty()) "Estimated total" else "Total", "${quote.total} sats")
                quote.prepared?.let { ReviewRow("Transaction size", "${it.vsize} vB · ${it.inputCount} ${if (it.inputCount == 1) "input" else "inputs"}") }
                quote.feeRate?.let { ReviewRow("Fee rate", "$it sat/vB") }
                payment.source?.let { ReviewRow("From", it.label) }
                if (payment.comment.isNotEmpty()) ReviewRow("Note", payment.comment)
                quote.checkpoint?.let { Text(tr("An existing Lightning payment will be checked. No new payment will be created.")) }
                if (quote.approximate) Text(tr("The fee is an estimate. The final fee may change before submission."), style = MaterialTheme.typography.bodySmall)
                NoahButton("Confirm", !controller.locked, modifier = Modifier.testTag("send-confirm"), onClick = confirm)
                NoahButton("Back", !controller.locked, secondary = true, modifier = Modifier.testTag("send-review-back"), onClick = cancel)
            }
}

@Composable private fun PasteRecipient(onPaste: (String) -> Unit) {
    val context = LocalContext.current
    TextButton(modifier = Modifier.testTag("send-paste"), onClick = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let(onPaste)
    }) { Text(tr("Paste")) }
}
@Composable private fun SendChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled) {
        RadioButton(selected, onClick = null, enabled = enabled); Spacer(Modifier.width(12.dp)); Text(tr(label), Modifier.weight(1f))
    }
}
@Composable internal fun AmountKeypad(amount: String, decimal: Boolean, enabled: Boolean, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (decimal) "." else "", "0", null)).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    TextButton(onClick = { onChange(keypadInput(amount, key, decimal)) }, enabled = enabled && key != "", modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("send-key-${key ?: "⌫"}")) {
                        Text(if (key == null) tr("Delete") else tr(key), style = if (key == null) MaterialTheme.typography.labelMedium else MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
    }
}
internal fun keypadInput(amount: String, key: String?, decimal: Boolean): String = when {
    key == null -> amount.dropLast(1)
    key == "." -> if (decimal && '.' !in amount) amount.ifEmpty { "0" } + "." else amount
    key.length == 1 && key[0] in '0'..'9' && amount.length < 18 -> if (amount == "0") key else amount + key
    else -> amount
}
@Composable private fun ReviewRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr(label), Modifier.weight(1f)); Text(tr(value), Modifier.weight(1f)) }
}
@Composable internal fun SendOutcome(controller: SendController, onDone: () -> Unit) {
    val scope = rememberCoroutineScope(); var overrideStep by remember { mutableIntStateOf(0) }
    val receipt = controller.attempt?.alreadyPaid ?: controller.attempt?.receipt
    NoahForm(when (controller.state) {
        SendController.State.submitting -> "Sending…"
        SendController.State.alreadyPaid -> "Invoice already paid"
        SendController.State.failed -> "Payment not sent"
        SendController.State.succeeded -> if (receipt?.status == SendReceipt.Status.confirmed) "Payment confirmed" else if (receipt?.txid != null) "Payment submitted" else "Payment sent"
        SendController.State.storageBlocked -> "Payment status unavailable"
        else -> "Payment may have been sent"
    }) {
        if (controller.state == SendController.State.submitting) CircularProgressIndicator()
        receipt?.let {
            Text(tr(if (it.amountIsPending) "Recipient amount pending" else "${it.amount} sats"), style = MaterialTheme.typography.displaySmall)
            if (it.txid != null) ReviewRow("Status", when (it.status) { SendReceipt.Status.confirmed -> "Confirmed"; SendReceipt.Status.unlocated -> "Unable to locate"; SendReceipt.Status.conflicted -> "Conflicted"; SendReceipt.Status.submitted -> "Submitted" })
            it.fee?.let { fee -> ReviewRow("Actual fee", "$fee sats") }
            it.txid?.let { txid -> SelectionContainer { Text(tr(txid), style = MaterialTheme.typography.bodySmall) } }
            it.conflictDescription?.let { description -> Text(tr(description)) }
            if (it.broadcastRetryPending) Text(tr(SendReceipt.broadcastPendingMessage))
            it.chainStatus?.let { status -> Text(tr(status.text)) }
            if (it.txid != null && it.chainStatus == null && !it.broadcastRetryPending)
                Text(tr(SendReceipt.submittedMessage))
        }
        controller.attempt?.existingLightning?.let { Text(tr(it.message)); ReviewRow("Existing fee", "${it.fee} sats") }
        controller.message?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
        if (controller.state == SendController.State.uncertain) Text(tr(SendReceipt.uncertainMessage))
        if (controller.state in listOf(SendController.State.uncertain, SendController.State.storageBlocked) || receipt?.txid != null) NoahButton(if (controller.checking) "Checking…" else if (controller.state == SendController.State.storageBlocked) "Retry" else "Check status", !controller.checking) { scope.launch { controller.checkStatus() } }
        if (controller.state in listOf(SendController.State.succeeded, SendController.State.alreadyPaid, SendController.State.failed)) NoahButton("Done", receipt?.broadcastRetryPending != true, onClick = onDone)
        if (controller.canOverride) TextButton(onClick = { overrideStep = 1 }) { Text(tr("Start a new payment after checking history"), color = MaterialTheme.colorScheme.error) }
    }
    if (overrideStep > 0) WalletAlertDialog(onDismissRequest = { overrideStep = 0 }, title = { Text(tr(if (overrideStep == 1) "Have you checked?" else SendOverrideAudit.titleText)) },
        text = { Text(tr(if (overrideStep == 1) "Check your history and confirm with the recipient. This payment may already have been sent." else controller.overrideWarning)) },
        confirmButton = { TextButton(onClick = { if (overrideStep == 1) overrideStep = 2 else { scope.launch { controller.startNewPaymentAfterHistoryCheck(true); overrideStep = 0 } } }) { Text(tr(if (overrideStep == 1) "I checked history" else SendOverrideAudit.confirmationText)) } },
        dismissButton = { TextButton(onClick = { overrideStep = 0 }) { Text(tr("Keep checking")) } })
}

/** Configuration recreation retains only presentation; SendRuntime still owns every submission. */
internal class SendPresentation(val model: SendModel) : ViewModel() {
    fun leave() { if (!model.controller.locked) model.reset() }
    override fun onCleared() { if (!model.controller.locked) model.discardReview() }
}
