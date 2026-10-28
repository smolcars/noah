package com.noahwallet.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.noahwallet.R
import com.noahwallet.api.generated.client.V0PricesClient
import com.noahwallet.formatting.*
import com.noahwallet.localization.tr
import com.noahwallet.services.server.value
import com.noahwallet.services.wallet.*
import com.noahwallet.ui.receive.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val requestSaver = Saver<ReceiveRequest?, String>(
    save = { it?.let { request -> Json.encodeToString(request) } ?: "" },
    restore = { it.takeIf(String::isNotEmpty)?.let { value -> Json.decodeFromString<ReceiveRequest>(value) } },
)
private val recordsSaver = Saver<ReceiveRecords?, String>(
    save = { it?.let { records -> Json.encodeToString(records) } ?: "" },
    restore = { it.takeIf(String::isNotEmpty)?.let { value -> Json.decodeFromString<ReceiveRecords>(value) } },
)
private val arrivalSaver = Saver<ReceiveArrival?, String>(
    save = { it?.let { arrival -> Json.encodeToString(arrival) } ?: "" },
    restore = { it.takeIf(String::isNotEmpty)?.let { value -> Json.decodeFromString<ReceiveArrival>(value) } },
)

/**
 * Receive (docs/native-rewrite/design/send-receive.md): your Lightning address by default, today's request,
 * invoice creation and completion subscription behind the redesigned card, and arrivals from the wallet's own
 * records while the screen is visible.
 */
@Composable
fun ReceiveScreen(app: AppModel, onProfile: () -> Unit = {}) {
    val requestState = rememberSaveable(stateSaver = requestSaver) { mutableStateOf<ReceiveRequest?>(null) }
    val formatState = rememberSaveable { mutableStateOf(ReceiveFormat.lightningAddress) }
    val arrivalState = rememberSaveable(stateSaver = arrivalSaver) { mutableStateOf<ReceiveArrival?>(null) }
    val state = remember(requestState, formatState, arrivalState) { ReceiveScreenState(requestState, formatState, arrivalState) }
    val request by state.request
    val arrival by state.arrival
    // The on-chain arrival whose confirmation the block row follows, after its presentation has ended.
    val chainState = rememberSaveable(stateSaver = arrivalSaver) { mutableStateOf<ReceiveArrival?>(null) }
    // What Receive has taken from the wallet's records, and this screen's own Ark addresses and invoices.
    val records = rememberSaveable(stateSaver = recordsSaver) { mutableStateOf<ReceiveRecords?>(null) }
    var ownArk by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var ownHashes by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val currency = app.preferredCurrency.takeIf { it in FiatCurrency.supported } ?: "USD"
    val scope = rememberCoroutineScope()
    val reduceMotion = com.noahwallet.ui.onboarding.rememberReduceMotion()
    // Keep the displayed request across activity recreation; history catches any payment during the gap.
    LaunchedEffect(state.retry.intValue, request == null) { state.loadRequest(app) }
    LaunchedEffect(request?.arkAddress, request?.paymentHash) {
        request?.let { current ->
            if (current.arkAddress !in ownArk) ownArk = ownArk + current.arkAddress
            current.paymentHash?.let { if (it !in ownHashes) ownHashes = ownHashes + it }
        }
    }
    val price = rememberPrice(app, currency)
    val sessionRevision by app.session.walletChanges.collectAsState()
    LaunchedEffect(request, sessionRevision) {
        val current = request?.takeIf { app.session.isOpen } ?: return@LaunchedEffect
        state.listen(app, current)
    }
    // Once the card has flipped back the arrival is done. Ark and Lightning get a fresh request (the invoice is paid
    // and addresses aren't reused); an on-chain arrival keeps it, and its block row carries on through the chain state.
    LaunchedEffect(arrival) { state.finishArrival(reduceMotion) }
    // On-chain arrivals and payments to your Lightning address, from the wallet's records, one at a time.
    ReceiveRecordsWatcher(app.history.transactions, app.history.isLoading, request, ownArk.toSet(), ownHashes.toSet(), records, state.arrival,
        chainState) { scope.launch { app.refresh() } }
    val ui = receiveUi(app, state, chainState.value, rememberChainTip(app), price, currency)
    com.noahwallet.ui.onboarding.OnboardingFrame(null, splitAtWindowCentre = true) {
        ReceiveContent(ui, state.actions(app, scope, onProfile))
    }
}

/** What the screen shows: the request, its format and arrival, the chain's block row, and whether it's listening. */
@Composable
private fun receiveUi(app: AppModel, state: ReceiveScreenState, chain: ReceiveArrival?, tip: Long?, price: Double?, currency: String): ReceiveUi {
    val rows = app.history.transactions
    val request = state.request.value
    val format = state.format.value
    val error = state.error.value
    val confirmedAt = chain?.txid?.let { txid -> rows.firstOrNull { it.txid == txid && it.source == "onchain-wallet" } }
        ?.takeIf { it.hasConfirmation == true }?.confirmationHeight?.toLong()
    val address = app.server.state.lightningAddress?.takeIf { it.isNotEmpty() }
    return ReceiveUi(request, address, format, app.amountUnit, java.util.Locale.forLanguageTag(app.localeIdentifier), price, currency, error = error,
        errorTitle = state.errorTitle.value, saving = state.saving.value, invoiceError = state.invoiceError.value,
        arrival = state.arrival.value, chain = chain, tip = tip?.let { current -> confirmedAt?.let { maxOf(current, it) } ?: current },
        confirmedAt = confirmedAt,
        listening = app.session.isOpen && !app.isWalletSuspended && error == null &&
            ((format == ReceiveFormat.lightningAddress && address != null) || request != null))
}

/**
 * Receive's request, its format and arrival (saved with the screen), the request's errors and the invoice save in
 * progress (not saved), as the screen's effects and actions change them.
 */
private class ReceiveScreenState(
    val request: MutableState<ReceiveRequest?>, val format: MutableState<ReceiveFormat>, val arrival: MutableState<ReceiveArrival?>,
) {
    val error = mutableStateOf<String?>(null)
    val errorTitle = mutableStateOf<String?>(null)
    val invoiceError = mutableStateOf<String?>(null)
    val saving = mutableStateOf(false)
    val retry = mutableIntStateOf(0)

    fun actions(app: AppModel, scope: CoroutineScope, onProfile: () -> Unit) = ReceiveActions(
        select = { format.value = it },
        saveAmount = { sats, note ->
            val current = request.value
            if (!saving.value && current != null) {
                saving.value = true; invoiceError.value = null
                scope.launch {
                    try { invoiceError.value = saveInvoice(app, current, sats, note) } finally { saving.value = false }
                }
            }
        },
        // Clearing the amount returns to your Lightning address, whichever format was showing.
        removeAmount = {
            request.value?.let { request.value = ReceiveRequest(it.arkAddress, it.onchainAddress) }
            format.value = ReceiveFormat.lightningAddress
        },
        retry = { error.value = null; errorTitle.value = null; retry.intValue++ },
        profile = onProfile,
    )

    /** Today's request, unless one is showing or the last attempt failed (Retry clears that). */
    suspend fun loadRequest(app: AppModel) {
        if (request.value != null || error.value != null) return
        try { request.value = app.session.receiveRequest() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error.value = "Could not generate a receive request. Please try again." }
    }

    /** Waits for [current] to be paid, or for its Lightning receive to fail, checking each second. */
    suspend fun listen(app: AppModel, current: ReceiveRequest) {
        while (currentCoroutineContext().isActive) {
            try {
                when (val event = app.session.receiveCompletion(current)) {
                    is ReceiveCompletion.Received -> { received(event); app.refresh(); return }
                    is ReceiveCompletion.LightningFailed -> { lightningFailed(event); return }
                    null -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: WalletSession.SessionError) { error.value = "Your wallet isn't ready. Please try again."; request.value = null; return }
            catch (_: Exception) { /* Reconcile history when the notification stream reconnects. */ }
            delay(1000)
        }
    }

    private fun received(event: ReceiveCompletion.Received) {
        if (!ReceiveArrivals.takesCard(arrival.value)) return
        val rail = when (event.rail) {
            ReceiveCompletion.Rail.ark -> ReceiveArrival.Rail.ark
            ReceiveCompletion.Rail.lightning -> ReceiveArrival.Rail.lightning
        }
        arrival.value = ReceiveArrival(rail, event.sats, SystemClock.elapsedRealtime())
    }

    private fun lightningFailed(event: ReceiveCompletion.LightningFailed) {
        request.value = null; format.value = ReceiveFormats.afterAmount(format.value, false)
        errorTitle.value = if (event.canceled) "Lightning Receive Canceled" else "Lightning Receive Failed"
        error.value = "The Lightning payment could not be received. Generate a new payment request and try again."
    }

    /** Clears the arrival once its presentation ends; Ark and Lightning then need a fresh request. */
    suspend fun finishArrival(reduceMotion: Boolean) {
        val current = arrival.value ?: return
        delay((ReceiveTimeline.end(current.rail, reduceMotion) - (SystemClock.elapsedRealtime() - current.startedAt)).coerceAtLeast(0))
        arrival.value = null
        if (current.rail != ReceiveArrival.Rail.onchain) { format.value = ReceiveFormats.afterAmount(format.value, false); request.value = null }
    }

    /** Today's invoice creation for [sats] and [note] on [current]; the error to show, or null once it's created. */
    private suspend fun saveInvoice(app: AppModel, current: ReceiveRequest, sats: Long, note: String): String? {
        try {
            val next = app.session.receiveInvoice(current, sats, note)
            // A payment may complete while the invoice is being generated.
            if (request.value == current && ReceiveArrivals.takesCard(arrival.value)) {
                request.value = next; format.value = ReceiveFormats.afterAmount(format.value, true)
            }
            return null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val message = "Could not generate a Lightning invoice. Please try again."
            app.error = message
            return message
        }
    }
}

/** Today's price in [currency], when there is one. */
@Composable
private fun rememberPrice(app: AppModel, currency: String): Double? {
    var price by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(currency) {
        runCatching { V0PricesClient(app.services.client).fiatPrices(JsonObject(emptyMap())).value().rates[currency] }
            .getOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { price = it }
    }
    return price
}

/** While visible: re-check the wallet and the chain tip every 30 s (no faster), for on-chain arrivals and the block row. */
@Composable
private fun rememberChainTip(app: AppModel): Long? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tip by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(lifecycle, app.isWalletSuspended) {
        if (app.isWalletSuspended) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                runCatching { app.session.chainTip() }.getOrNull()?.let { tip = it }
                delay(30_000)
                runCatching { app.refresh(sync = true); app.history.refresh(app.preferredCurrency) }
            }
        }
    }
    return tip
}

@Composable
internal fun ReceiveQR(uri: String, actionLabel: String = "Show payment details", maximumSide: androidx.compose.ui.unit.Dp = 302.dp, onClick: () -> Unit) {
    val localizedActionLabel = tr(actionLabel)
    var qrPixels by remember { mutableIntStateOf(840) }
    val bitmap = remember(uri, qrPixels) {
        runCatching {
            val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, qrPixels, qrPixels,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 4))
            createBitmap(matrix.width, matrix.height).apply {
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            }.asImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.testTag("receive-qr-button").sizeIn(maxWidth = maximumSide).aspectRatio(1f).onSizeChanged { qrPixels = it.width.coerceAtLeast(1) }
        .clip(RoundedCornerShape(28.dp)).background(Color.White)
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = localizedActionLabel, onClick = onClick).clearAndSetSemantics {
            contentDescription = localizedActionLabel
            role = Role.Button
            this.onClick(label = localizedActionLabel) { onClick(); true }
        }, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
            Image(painterResource(R.drawable.receive_logo), null, Modifier.size(44.dp).background(Color.White).padding(4.dp))
        } else Text(tr("QR unavailable. Tap to copy payment details."), color = Color.Black)
    }
}
