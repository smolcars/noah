package com.noahwallet.ui

import com.noahwallet.localization.tr
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
import com.noahwallet.services.server.value
import com.noahwallet.services.wallet.*
import com.noahwallet.ui.receive.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

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
fun ReceiveScreen(app: AppModel, onProfile: () -> Unit = {}, onDone: () -> Unit) {
    val locale = java.util.Locale.forLanguageTag(app.localeIdentifier)
    var request by rememberSaveable(stateSaver = requestSaver) { mutableStateOf<ReceiveRequest?>(null) }
    var format by rememberSaveable { mutableStateOf(ReceiveFormat.lightningAddress) }
    val arrivalState = rememberSaveable(stateSaver = arrivalSaver) { mutableStateOf<ReceiveArrival?>(null) }
    var arrival by arrivalState
    // The on-chain arrival whose confirmation the block row follows, after its presentation has ended.
    val chainState = rememberSaveable(stateSaver = arrivalSaver) { mutableStateOf<ReceiveArrival?>(null) }
    val chain by chainState
    // What Receive has taken from the wallet's records, and this screen's own Ark addresses and invoices.
    val records = rememberSaveable(stateSaver = recordsSaver) { mutableStateOf<ReceiveRecords?>(null) }
    var ownArk by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var ownHashes by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTitle by remember { mutableStateOf<String?>(null) }
    var invoiceError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var price by remember { mutableStateOf<Double?>(null) }
    var tip by remember { mutableStateOf<Long?>(null) }
    val currency = app.preferredCurrency.takeIf { it in FiatCurrency.supported } ?: "USD"
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduceMotion = com.noahwallet.ui.onboarding.rememberReduceMotion()
    LaunchedEffect(retry, request == null) {
        // Keep the displayed request across activity recreation; history catches any payment during the gap.
        if (request != null || error != null) return@LaunchedEffect
        try { request = app.session.receiveRequest() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not generate a receive request. Please try again." }
    }
    LaunchedEffect(request?.arkAddress, request?.paymentHash) {
        request?.let { current ->
            if (current.arkAddress !in ownArk) ownArk = ownArk + current.arkAddress
            current.paymentHash?.let { if (it !in ownHashes) ownHashes = ownHashes + it }
        }
    }
    LaunchedEffect(currency) {
        runCatching { V0PricesClient(app.services.client).fiatPrices(JsonObject(emptyMap())).value().rates[currency] }
            .getOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { price = it }
    }
    val sessionRevision by app.session.walletChanges.collectAsState()
    LaunchedEffect(request, sessionRevision) {
        val current = request?.takeIf { app.session.isOpen } ?: return@LaunchedEffect
        while (isActive) {
            try {
                when (val event = app.session.receiveCompletion(current)) {
                    is ReceiveCompletion.Received -> {
                        if (ReceiveArrivals.takesCard(arrival)) arrival = ReceiveArrival(when (event.rail) {
                            ReceiveCompletion.Rail.ark -> ReceiveArrival.Rail.ark; ReceiveCompletion.Rail.lightning -> ReceiveArrival.Rail.lightning
                        }, event.sats, SystemClock.elapsedRealtime())
                        app.refresh(); return@LaunchedEffect
                    }
                    is ReceiveCompletion.LightningFailed -> {
                        request = null; format = ReceiveFormats.afterAmount(format, false)
                        errorTitle = if (event.canceled) "Lightning Receive Canceled" else "Lightning Receive Failed"
                        error = "The Lightning payment could not be received. Generate a new payment request and try again."
                        return@LaunchedEffect
                    }
                    null -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: WalletSession.SessionError) { error = "Your wallet isn't ready. Please try again."; request = null; return@LaunchedEffect }
            catch (_: Exception) { /* Reconcile history when the notification stream reconnects. */ }
            delay(1000)
        }
    }
    // Once the card has flipped back the arrival is done. Ark and Lightning get a fresh request (the invoice is paid
    // and addresses aren't reused); an on-chain arrival keeps it, and its block row carries on through [chain].
    LaunchedEffect(arrival) {
        val current = arrival ?: return@LaunchedEffect
        delay((ReceiveTimeline.end(current.rail, reduceMotion) - (SystemClock.elapsedRealtime() - current.startedAt)).coerceAtLeast(0))
        arrival = null
        if (current.rail != ReceiveArrival.Rail.onchain) { format = ReceiveFormats.afterAmount(format, false); request = null }
    }
    // On-chain arrivals and payments to your Lightning address, from the wallet's records, one at a time.
    val rows = app.history.transactions
    ReceiveRecordsWatcher(rows, app.history.isLoading, request, ownArk.toSet(), ownHashes.toSet(), records, arrivalState, chainState) { scope.launch { app.refresh() } }
    // While visible: re-check the wallet and the chain tip every 30 s (no faster), for on-chain arrivals and the block row.
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
    val confirmedAt = chain?.txid?.let { txid -> rows.firstOrNull { it.txid == txid && it.source == "onchain-wallet" } }
        ?.takeIf { it.hasConfirmation == true }?.confirmationHeight?.toLong()
    val address = app.server.state.lightningAddress?.takeIf { it.isNotEmpty() }
    val ui = ReceiveUi(request, address, format, app.amountUnit, locale, price, currency, error = error, errorTitle = errorTitle, saving = saving, invoiceError = invoiceError,
        arrival = arrival, chain = chain, tip = tip?.let { current -> confirmedAt?.let { maxOf(current, it) } ?: current }, confirmedAt = confirmedAt,
        listening = app.session.isOpen && !app.isWalletSuspended && error == null && (format == ReceiveFormat.lightningAddress && address != null || request != null))
    val actions = ReceiveActions(
        select = { format = it },
        saveAmount = { sats, note ->
            val current = request
            if (!saving && current != null) {
                saving = true; invoiceError = null
                scope.launch {
                    try {
                        val next = app.session.receiveInvoice(current, sats, note)
                        // A payment may complete while the invoice is being generated.
                        if (request == current && ReceiveArrivals.takesCard(arrival)) { request = next; format = ReceiveFormats.afterAmount(format, true) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { invoiceError = "Could not generate a Lightning invoice. Please try again."; app.error = invoiceError }
                    finally { saving = false }
                }
            }
        },
        // Clearing the amount returns to your Lightning address, whichever format was showing.
        removeAmount = { request?.let { request = ReceiveRequest(it.arkAddress, it.onchainAddress) }; format = ReceiveFormat.lightningAddress },
        retry = { error = null; errorTitle = null; retry++ },
        profile = onProfile,
    )
    com.noahwallet.ui.onboarding.OnboardingFrame(null, splitAtWindowCentre = true) {
        ReceiveContent(ui, actions)
    }
}

@Composable
internal fun ReceiveQR(uri: String, actionLabel: String = "Show payment details", maximumSide: androidx.compose.ui.unit.Dp = 302.dp, onClick: () -> Unit) {
    val localizedActionLabel = tr(actionLabel)
    var qrPixels by remember { mutableIntStateOf(840) }
    val bitmap = remember(uri, qrPixels) {
        runCatching {
            val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, qrPixels, qrPixels,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 4))
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            }.asImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.testTag("receive-qr-button").sizeIn(maxWidth = maximumSide).aspectRatio(1f).onSizeChanged { qrPixels = it.width.coerceAtLeast(1) }.clip(RoundedCornerShape(28.dp)).background(Color.White).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = localizedActionLabel, onClick = onClick).clearAndSetSemantics {
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

