package com.noahwallet.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.ui.send.*
import kotlinx.coroutines.*
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.serialization.json.JsonObject

/**
 * Send (docs/native-rewrite/design/send-receive.md): today's SendModel and SendController behind the
 * redesigned steps. [tabBar] reports when the phone's tab bar hides (every step after Amount, back on Done).
 */
@Composable
fun SendScreen(app: AppModel, tabBar: (Boolean) -> Unit = {}, onDone: () -> Unit) {
    val context = LocalContext.current
    val presentation = viewModel<SendPresentation>(key = "send-presentation") { SendPresentation(SendModel(SendRuntime.shared(context).controller, BuildConfig.APP_VARIANT, app.session::resolveSendRecipient, app.session::quoteSend, app.session::discardSend, offerAmount = app.session::sendOfferAmount)) }
    val model = presentation.model
    val controller = model.controller
    val scope = presentation.viewModelScope
    val activity = context as android.app.Activity
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var scanner by rememberSaveable { mutableStateOf(false) }
    fun leaveReview() { model.discardReview(); model.stage = if (model.isMax) SendStage.source else SendStage.recipient }
    fun done() { scope.launch { controller.acknowledge(); if (!controller.locked) { presentation.motion = null; model.reset(); app.refresh() } } }
    // Today's automatic refresh: the status check (which reconciles a transaction and retries a pending broadcast) and,
    // for an on-chain payment, the block row's tip. Entering Send and the loop below share its 30 s pacing.
    suspend fun refresh() {
        if (controller.locked && controller.attempt?.payment?.method == "onchain")
            runCatching { app.session.chainTip() }.getOrNull()?.let { presentation.tip = it }
        controller.checkStatus()
    }
    LaunchedEffect(Unit) {
        model.fiatCurrency = app.preferredCurrency
        app.summary?.let { model.arkBalance = it.balance.ark.spendable.toLong(); model.onchainBalance = it.balance.onchain.confirmed.toLong() }
        runCatching { app.session.pendingSendHashes() }.getOrNull()?.let { model.pendingLightningHashes = it }
        // Within 30 s of the last automatic refresh this waits for the next one instead of skipping it, so an uncertain
        // or storage-blocked payment (with or without a transaction) is always looked at again.
        presentation.refresh.whenDue(::refresh)
    }
    LaunchedEffect(app.summary) {
        app.summary?.let { model.arkBalance = it.balance.ark.spendable.toLong(); model.onchainBalance = it.balance.onchain.confirmed.toLong() }
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
            // Send's own scanner would cover the request it now shows.
            app.scannedDestination = null; scanner = false; model.reset(); model.importRecipient(it)
        } }
    }
    LaunchedEffect(model.destination) {
        val candidate = model.destination
        coroutineScope {
            launch { model.previewRecipient() }
            launch {
                val query = SendRecipientService.suggestionQuery(candidate, LightningIdentity.domain(BuildConfig.APP_VARIANT)) ?: return@launch
                delay(200)
                val result = runCatching { V0LnAddressSuggestionsClient(app.services.client).lnAddressSuggestions(LightningAddressSuggestionsPayload(query)).value().suggestions }.getOrDefault(emptyList())
                if (model.destination == candidate && isActive) model.suggestions = SendRecipientService.withoutOwnAddress(result, app.server.state.lightningAddress)
            }
        }
    }
    var previousStage by rememberSaveable { mutableStateOf(model.stage.name) }
    var previousOutcome by rememberSaveable { mutableStateOf(controller.state.name) }
    LaunchedEffect(model.stage, controller.state) {
        if (previousStage != model.stage.name || previousOutcome != controller.state.name) focus.clearFocus()
        previousStage = model.stage.name; previousOutcome = controller.state.name
    }
    // The invoice note: the amount you had typed before a recipient set its own.
    LaunchedEffect(model.amount, model.stage) { if (model.stage == SendStage.amount) presentation.typedBefore = model.sats }
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
    val quote = model.quote
    if (model.showReview && quote != null && !controller.locked) LaunchedEffect(quote) {
        quote.prepared?.let { prepared ->
            delay(maxOf(0, SendPrepared.lifetimeMillis - prepared.elapsed()))
            if (!controller.locked && model.quote == quote) { leaveReview(); model.error = "This review expired. Review the payment again." }
        }
    }

    val refund = rememberSendProgress(presentation, app.session::movements)
    // While Send is visible with a transaction, the automatic refresh repeats, at most every 30 s across entering Send,
    // leaving the tab and recreating the activity (the pacing lives in the presentation). Check status stays immediate.
    val txid = controller.attempt?.receipt?.txid
    val onchain = controller.locked && controller.attempt?.payment?.method == "onchain"
    LaunchedEffect(txid, onchain, app.isWalletSuspended) {
        if (txid == null && !onchain || app.isWalletSuspended) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The block row's first tip doesn't wait: there's none to refresh yet.
            if (onchain && presentation.tip == null) runCatching { app.session.chainTip() }.getOrNull()?.let { presentation.tip = it }
            while (isActive) presentation.refresh.whenDue(::refresh)
        }
    }
    // Today's estimates for the method cards; the review's quote stays the authority.
    var estimates by remember { mutableStateOf<Map<SendRail, Long>>(emptyMap()) }
    LaunchedEffect(model.stage, model.sats, model.destination) {
        if (model.stage != SendStage.method) return@LaunchedEffect
        val sats = model.sats.takeIf { it > 0 } ?: return@LaunchedEffect
        val request = model.parsed.bip321
        estimates = buildMap {
            for (rail in model.rails) {
                val destination = if (rail == SendRail.onchain) request?.get("onchainAddress") ?: SendDestination.cleaned(model.destination) else null
                if (rail == SendRail.onchain && model.arkBalance < sats) continue
                runCatching { app.session.estimateSendFee(rail, sats, destination) }.getOrNull()?.let { put(rail, it) }
            }
        }
    }
    val hideBar = model.stage != SendStage.amount && !controller.locked || controller.locked &&
        controller.state != SendController.State.storageBlocked && !(controller.state == SendController.State.uncertain && controller.attempt?.existingLightning == null)
    SideEffect { tabBar(hideBar && !scanner) }
    DisposableEffect(Unit) { onDispose { tabBar(false) } }
    BackHandler(!controller.locked && !model.busy && (model.stage != SendStage.amount || model.progress.recipientConfirmed)) { model.back() }
    // Preparing or showing a review: an OS link can't replace it.
    ReviewOpen(app.openReviews, model.stage == SendStage.review)
    if (scanner) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().avoidHinge()) {
            SendScanner(onClose = { scanner = false }) { value -> scanner = false; scope.launch { model.importRecipient(value) } }
        }
        return
    }
    val locale = java.util.Locale.forLanguageTag(app.localeIdentifier)
    val address = app.server.state.lightningAddress
    val env = SendEnv(app.amountUnit, locale, BuildConfig.APP_VARIANT, address?.substringAfter('@', "")?.takeIf { it.isNotEmpty() },
        history = app.history.transactions,
        pendingRound = app.summary?.balance?.let { balance -> balance.pendingRoundMessage(com.noahwallet.formatting.BitcoinAmount.format(balance.ark.pendingInRound.toLong(), app.amountUnit, locale)) },
        tip = presentation.tip, chainNeeded = WalletConfiguration.base(BuildConfig.APP_VARIANT).roundTxRequiredConfirmations.toLong(),
        refund = refund, estimates = estimates, variant = BuildConfig.APP_VARIANT)
    val actions = SendActions(scan = { focus.clearFocus(); scanner = true }, done = ::done, leave = onDone,
        checkStatus = { scope.launch { controller.checkStatus() } }, startOver = { presentation.motion = null; model.reset() })
    com.noahwallet.ui.onboarding.OnboardingFrame(null, splitAtWindowCentre = true) {
        SendContent(presentation, env, actions)
    }
}

internal fun keypadInput(amount: String, key: String?, decimal: Boolean): String = when {
    key == null -> amount.dropLast(1)
    key == "." -> if (decimal && '.' !in amount) amount.ifEmpty { "0" } + "." else amount
    key.length == 1 && key[0] in '0'..'9' && amount.length < 18 -> if (amount == "0") key else amount + key
    else -> amount
}

/**
 * The motion follows the controller: each milestone is recorded when the wallet actually reaches it. Returns what a
 * failed Lightning send's records show came back, null until they've been read.
 */
@Composable
internal fun rememberSendProgress(presentation: SendPresentation, movements: suspend () -> List<io.github.niteshbalusu11.bark.Movement>): SendRefund? {
    val controller = presentation.model.controller
    var refund by remember(controller.attempt?.startedAt) { mutableStateOf<SendRefund?>(null) }
    LaunchedEffect(controller.state, controller.attempt?.startedAt) {
        val attempt = controller.attempt
        refund = when {
            controller.state != SendController.State.failed -> null
            attempt?.payment?.method !in SendProgressRules.lightningMethods -> SendRefund.none
            else -> runCatching { SendProgressRules.refund(controller.state, attempt, movements()) }.getOrDefault(SendRefund.none)
        }
    }
    // One State across recompositions: the refund state above is replaced whenever the attempt changes.
    val currentRefund by rememberUpdatedState(refund)
    LaunchedEffect(presentation) {
        snapshotFlow { listOf(controller.state, controller.attempt, presentation.submitting, currentRefund) }.collect {
            val motion = presentation.motion ?: return@collect
            if (controller.state == SendController.State.ready && !presentation.submitting) { presentation.motion = null; return@collect }
            SendProgressRules.milestone(controller.state, controller.attempt, !presentation.submitting, currentRefund)?.let { milestone ->
                presentation.motion = motion.reach(milestone, SystemClock.elapsedRealtime() - motion.startedAt)
            }
        }
    }
    return refund
}

/** Configuration recreation retains only presentation; SendRuntime still owns every submission. */
internal class SendPresentation(val model: SendModel) : ViewModel() {
    /** The motion this screen's hold started; kept across recreation and pose changes so it resumes in place. */
    var motion by mutableStateOf<SendMotion?>(null)
    /** True until the submission's job returns: until then an uncertain state is still being reconciled. */
    var submitting by mutableStateOf(false)
    /** The motion time whose haptics have already played, so a recreation never repeats them. */
    var hapticsAt = 0L
    /** The amount typed before a recipient (an invoice) set its own, for the review's note. */
    var typedBefore: Long? = null
    /** Send's automatic refresh pacing and the block row's last tip, kept across leaving the tab and recreation. */
    val refresh = SendRefresh()
    var tip by mutableStateOf<Long?>(null)

    /** Holding to send confirms exactly as today's confirm button did: once, for the reviewed quote only. */
    fun confirm(payment: SendPayment, quote: SendQuote) {
        val controller = model.controller
        if (controller.locked || !model.showReview || model.quote !== quote || model.payment !== payment) return
        val job = controller.submit(payment, quote) ?: return
        model.showReview = false
        motion = SendMotion(SendRoute.of(payment), SystemClock.elapsedRealtime())
        hapticsAt = 0L
        submitting = true
        viewModelScope.launch { try { job.await() } catch (_: Exception) { } finally { submitting = false } }
    }
    fun leave() { if (!model.controller.locked) model.reset() }
    override fun onCleared() { if (!model.controller.locked) model.discardReview() }
}
