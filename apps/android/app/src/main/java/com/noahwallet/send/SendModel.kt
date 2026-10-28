package com.noahwallet.send

import androidx.compose.runtime.*
import com.noahwallet.formatting.FiatCurrency
import com.noahwallet.history.RepeatPayment
import kotlinx.coroutines.*

@Suppress("LongParameterList") // dependency seams: tests inject recipient, quote, discard and offer fakes
class SendModel(val controller: SendController, private val network: String,
    private val resolveRecipient: suspend (String) -> SendRecipientRoute,
    private val quotePayment: suspend (SendPayment) -> SendQuote,
    private val discardQuote: suspend (SendQuote) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** The amount a BOLT12 offer fixes, or null; the quote checks it again. */
    private val offerAmount: suspend (String) -> Long? = { null }) {
    private var amountText by mutableStateOf("")
    /** A valid amount clears Next's zero-amount error at once; any other error stays. */
    var amount: String
        get() = amountText
        set(value) { amountText = value; if (errorSource == zeroAmount && sats in validSats) errorSource = null }
    var destination by mutableStateOf("")
        private set
    private var recipientRevision = 0
    private var reviewGeneration = 0
    var comment by mutableStateOf("")
    var useFiat by mutableStateOf(false)
    var btcPrice by mutableStateOf<Double?>(null)
    var fiatCurrency by mutableStateOf("USD")
    var stage by mutableStateOf(SendStage.amount)
    var progress by mutableStateOf(SendProgress())
    var parsed by mutableStateOf(SendDestination())
    var route by mutableStateOf<SendRecipientRoute?>(null)
    var source by mutableStateOf<SendSource?>(null)
    var pendingLightningHashes by mutableStateOf<Set<String>>(emptySet())
    var arkBalance by mutableLongStateOf(0)
    var onchainBalance by mutableLongStateOf(0)
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var destinationError by mutableStateOf<SendDestinationError?>(null)
    private var errorSource by mutableStateOf<String?>(null)
    var error: String?
        get() = destinationError?.message ?: errorSource
        set(value) { destinationError = null; errorSource = value }
    var busy by mutableStateOf(false)
    var resolvingRecipient by mutableStateOf(false)
    var quote by mutableStateOf<SendQuote?>(null); private set
    var payment by mutableStateOf<SendPayment?>(null); private set
    var showReview by mutableStateOf(false)
    val isMax get() = progress.entry == "max"
    val sats: Long get() = if (isMax) SendFlow.maxBalance(source, onchainBalance, arkBalance)
    else if (useFiat) btcPrice?.let { FiatCurrency.fiatToSats(amount.toDoubleOrNull() ?: Double.NaN, it) } ?: 0 else amount.toLongOrNull() ?: 0
    val matchingRoute get() = route?.takeIf { parsed.destinationType == "lnurl" && it.destination == SendDestination.cleaned(destination).lowercase() }
    val rails get() = if (parsed.destinationType == "lnurl" && matchingRoute?.arkAddress != null && comment.isEmpty()) listOf(SendRail.ark,
        SendRail.lightning) else SendFlow.rails(parsed.destinationType, parsed.bip321)
    val availability: Map<String, Boolean> get() {
        val invoice = parsed.bip321?.get("lightningInvoice") ?: destination
        val pending = PaymentEncoding.invoice(invoice)?.hash in pendingLightningHashes
        return mapOf("ark" to (sats > 0 && arkBalance >= sats),
            "lightning" to (sats > 0 && (arkBalance >= sats || pending || parsed.destinationType == "lnurl")),
            "onchain" to (isMax || (sats > 0 && (arkBalance >= sats || onchainBalance >= sats))))
    }
    val sources get() = SendSource.entries.filter {
        val balance = if (it == SendSource.offchain) arkBalance else onchainBalance
        if (isMax) balance > 0 else sats > 0 && balance >= sats
    }
    fun setRecipient(value: String) {
        destination = value; recipientRevision++
        if (parsed.amount != null) amount = ""
        parsed = SendDestination(); route = null; comment = ""; suggestions = emptyList(); error = null; resolvingRecipient = false
        progress = progress.copy(recipientConfirmed = false, railConfirmed = false, selectedRail = null, rails = emptyList(), selectedRailAvailable = null,
            amountConfirmed = progress.amountConfirmed && amount.isNotEmpty(), sourceConfirmed = isMax && progress.sourceConfirmed)
        if (!isMax) source = null
        discardReview(); payment = null
    }
    fun discardReview() {
        reviewGeneration++; quote?.let { old -> scope.launch { runCatching { discardQuote(old) } } }; quote = null; showReview = false
    }
    fun maxSend() {
        if (controller.locked) return
        reset(); progress = SendProgress(entry = "max"); stage = SendStage.source
    }
    fun recipientFirst() { progress = progress.copy(entry = "recipient-first"); stage = SendStage.recipient }
    /** Scan, paste and recents. [stay]: Amount's Paste keeps you on Amount until the recipient is accepted, so one it can't accept shows today's error there.
     *  From Amount only a request's own amount skips the keypad; a typed one waits for Next (send-receive.md, Amount). */
    suspend fun importRecipient(value: String, stay: Boolean = false) {
        setRecipient(value)
        val keypad = stage == SendStage.amount
        if (keypad) progress = progress.copy(entry = if (sats > 0) "amount-first" else "recipient-first", amountConfirmed = false)
        if (!stay) stage = SendStage.recipient
        acceptRecipient(keypad)
    }
    suspend fun previewRecipient() {
        val revision = recipientRevision; val candidate = destination
        if (SendDestination.parse(candidate, network).destinationType != "lnurl") return
        parsed = SendDestination.parse(candidate, network)
        resolvingRecipient = true
        val resolved = runCatching { resolveRecipient(SendDestination.cleaned(candidate).lowercase()) }.getOrNull()
        if (revision != recipientRevision || !currentCoroutineContext().isActive) return
        route = resolved; resolvingRecipient = false
    }
    suspend fun acceptRecipient(keypad: Boolean = false) {
        if (busy || controller.locked) return
        busy = true; error = null
        val revision = recipientRevision; val reviewAtStart = reviewGeneration
        val unchanged = { revision == recipientRevision && reviewAtStart == reviewGeneration }
        try {
            if (!recipientAccepted(keypad, unchanged)) return
        } catch (_: Exception) {
            if (revision == recipientRevision) {
                error = "Unable to resolve this recipient. Please check the address."
                destinationError = parsed.error
            }
            return
        } finally { busy = false }
        if (unchanged()) advance()
    }
    /** A payment request's own amount replaces the typed one. */
    private fun applyRequestAmount() = parsed.amount?.let { fixed ->
        requireSend(fixed <= 2_100_000_000_000_000L.toBigDecimal() && runCatching { fixed.longValueExact() }.isSuccess)
        amount = fixed.longValueExact().toString(); useFiat = false; progress = progress.copy(amountConfirmed = true)
    }
    /** False when MAX can't pay this recipient or the recipient or review changed while resolving it. */
    private suspend fun recipientAccepted(keypad: Boolean, unchanged: () -> Boolean): Boolean {
        parsed = SendDestination.parse(destination, network)
        requireSend(parsed.destinationType != null && parsed.error == null)
        if (isMax && (!SendFlow.canMax(parsed.destinationType, parsed.bip321) || parsed.amount != null)) {
            error = "MAX requires an on-chain address without a fixed amount."; return false
        }
        if (parsed.destinationType == "offer") {
            val fixed = offerAmount(SendDestination.cleaned(destination))
            if (!unchanged()) return false
            // An offer that fixes its amount behaves like an invoice with one.
            fixed?.let { parsed = parsed.copy(amount = it.toBigDecimal(), isAmountEditable = false) }
        }
        applyRequestAmount()
        if (parsed.destinationType == "lnurl" && matchingRoute == null) {
            val resolved = resolveRecipient(SendDestination.cleaned(destination).lowercase())
            if (!unchanged()) return false
            route = resolved; requireSend(matchingRoute != null)
        }
        progress = progress.copy(recipientConfirmed = true, amountConfirmed = progress.amountConfirmed || (sats > 0 && !keypad),
            selectedRail = if (isMax) SendRail.onchain else SendFlow.recommend(rails, availability))
        return true
    }
    suspend fun continueStage() {
        if (busy || controller.locked) return
        error = null
        if (stage == SendStage.recipient) { acceptRecipient(); return }
        if (confirmStage()) advance()
    }
    /** Confirms the current stage's choice; false, with an error, when it can't pay. */
    private fun confirmStage(): Boolean {
        when (stage) {
            SendStage.amount -> {
                if (sats !in validSats) { error = zeroAmount; return false }
                progress = progress.copy(amountConfirmed = true)
            }
            SendStage.method -> {
                if (availability[progress.selectedRail?.name] != true) { error = "Insufficient funds for this payment method."; return false }
                progress = progress.copy(railConfirmed = true)
            }
            SendStage.source -> {
                if (source !in sources || source == null) { error = "Choose a balance with sufficient funds."; return false }
                progress = progress.copy(sourceConfirmed = true, selectedRail = if (isMax) SendRail.onchain else progress.selectedRail)
            }
            SendStage.recipient, SendStage.review -> Unit
        }
        return true
    }
    private suspend fun advance() {
        if (source !in sources) { source = null; progress = progress.copy(sourceConfirmed = false) }
        if (sources.size == 1 && source == null) source = sources.first()
        val selected = if (!progress.railConfirmed && !isMax) SendFlow.recommend(rails, availability) else progress.selectedRail
        progress = progress.copy(rails = rails, sourceOptions = sources, selectedRail = selected, selectedRailAvailable = availability[selected?.name])
        stage = progress.next
        if (stage == SendStage.review) review()
    }
    suspend fun review() {
        discardReview(); busy = true; val generation = reviewGeneration
        try {
            val rail = progress.selectedRail ?: throw SendFailure(SendFailure.Reason.invalid)
            val payment = reviewedPayment(rail)
            val reviewed = quotePayment(payment)
            if (generation != reviewGeneration || !currentCoroutineContext().isActive) { withContext(NonCancellable) { discardQuote(reviewed) }; return }
            quote = reviewed; this.payment = payment; showReview = true
        } catch (failure: SendFailure) {
            reviewFailed(generation, failure.message)
        } catch (_: Exception) {
            reviewFailed(generation, null)
        } finally { busy = false }
    }
    private suspend fun reviewFailed(generation: Int, message: String?) {
        if (generation == reviewGeneration && currentCoroutineContext().isActive) {
            error = message ?: "Unable to prepare this payment. Check the destination and available funds."; stage = SendStage.recipient
        }
    }
    /** The payment the chosen rail sends: a payment request's rail-specific target, or a Lightning address's Ark one. */
    private fun reviewedPayment(rail: SendRail): SendPayment {
        var (method, target) = railTarget(rail)
        if (method == "lnurl") {
            val route = matchingRoute ?: throw SendFailure(SendFailure.Reason.invalid)
            val msat = Math.multiplyExact(sats, 1000); requireSend(msat in route.minMsat..route.maxMsat && comment.length <= route.commentAllowed)
            if (rail == SendRail.ark && comment.isEmpty() && route.arkAddress != null) { method = "ark"; target = route.arkAddress }
        }
        if (method == "lightning") requireInvoiceAmount(target)
        val payment = SendPayment(target, method, sats, if (rail == SendRail.onchain) source else null, isMax, comment, repeatMetadata())
        requireSend(!isMax || (method == "onchain" && source != null))
        return payment
    }
    private fun requireInvoiceAmount(invoice: String) {
        PaymentEncoding.invoice(invoice)?.sats?.takeIf { it.signum() > 0 }?.let { requireSend(it.compareTo(sats.toBigDecimal()) == 0) }
    }
    /** What Repeat needs to send to a Lightning address again. */
    private fun repeatMetadata() = if (parsed.destinationType == "lnurl" && SendDestination.isLightningAddress(destination)) {
        SendPayment.metadata(destination, comment, amount, if (useFiat) fiatCurrency else null)
    } else null
    private fun railTarget(rail: SendRail): Pair<String, String> {
        var method = parsed.destinationType ?: ""; var target = SendDestination.cleaned(destination)
        if (method == "lnurl") target = target.lowercase()
        parsed.bip321?.let { request -> method = SendFlow.method(rail, request) ?: throw SendFailure(SendFailure.Reason.invalid)
            target = request.getValue(mapOf("ark" to "arkAddress", "lightning" to "lightningInvoice", "offer" to "offer",
                "onchain" to "onchainAddress").getValue(method)) }
        return method to target
    }
    fun reset() {
        if (controller.locked) return
        discardReview(); amount = ""; setRecipient(""); comment = ""; useFiat = false; progress = SendProgress(); source = null; stage = SendStage.amount
        payment = null; error = null
    }
    fun prefill(repeat: RepeatPayment, currency: String) {
        if (controller.locked) return
        reset(); setRecipient(repeat.destination)
        val prefill = repeat.prefill(currency)
        amount = prefill.amountInput; useFiat = prefill.amountMode == "FIAT"; comment = repeat.comment
        progress = SendProgress(entry = "recipient-first"); stage = SendStage.recipient
    }
    fun toggleCurrency() {
        val price = btcPrice ?: return; val value = sats
        amount = if (useFiat) value.toString() else FiatCurrency.satsToFiat(value, price, fiatCurrency); useFiat = !useFiat
    }
    private companion object {
        const val zeroAmount = "Enter an amount greater than zero."
        val validSats = 1..2_100_000_000_000_000L
    }
}
