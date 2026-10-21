package com.noahwallet.ui

import com.noahwallet.ui.WalletModalBottomSheet

import com.noahwallet.localization.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.noahwallet.R
import com.noahwallet.api.generated.client.V0PricesClient
import com.noahwallet.formatting.*
import com.noahwallet.services.server.value
import com.noahwallet.services.wallet.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import java.text.DecimalFormatSymbols

private val requestSaver = Saver<ReceiveRequest?, String>(
    save = { it?.let { request -> Json.encodeToString(request) } ?: "" },
    restore = { it.takeIf(String::isNotEmpty)?.let { value -> Json.decodeFromString<ReceiveRequest>(value) } },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(app: AppModel, onDone: () -> Unit) {
    val locale = java.util.Locale.forLanguageTag(app.localeIdentifier)
    var request by rememberSaveable(stateSaver = requestSaver) { mutableStateOf<ReceiveRequest?>(null) }
    var received by rememberSaveable { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var price by remember { mutableStateOf<Double?>(null) }
    val currency = app.preferredCurrency.takeIf { it in FiatCurrency.supported } ?: "USD"
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(retry) {
        // Keep the displayed address/invoice across activity recreation; history catches any payment during the gap.
        if (request != null || received != null) return@LaunchedEffect
        error = null
        try { request = app.session.receiveRequest() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not generate a receive request. Please try again." }
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
                    is ReceiveCompletion.Received -> { sheet = null; received = event.sats; app.refresh(); return@LaunchedEffect }
                    is ReceiveCompletion.LightningFailed -> {
                        sheet = null; request = null
                        error = "Lightning receive ${if (event.canceled) "canceled" else "failed"}. Generate a new payment request and try again."
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
    if (received != null) {
        var appeared by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(if (appeared) 1f else 0.85f)
        val view = LocalView.current
        LaunchedEffect(Unit) {
            appeared = true
            view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30)
                android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
        NoahForm("Funds received") {
            Image(painterResource(R.drawable.ic_received), null,
                Modifier.size(100.dp).graphicsLayer { scaleX = scale; scaleY = scale })
            Text(tr(BitcoinAmount.format(received!!, app.amountUnit, locale)), style = MaterialTheme.typography.displaySmall)
            price?.let { Text(tr("≈ " + FiatCurrency.display(received!!, it, currency, locale))) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("Status")); Text(tr("Settled"), color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("Wallet balance"))
                Text(tr(app.summary?.let { BitcoinAmount.format(it.balance.totalSats.toLong(), app.amountUnit, locale) } ?: "…"))
            }
            NoahButton("Done", onClick = onDone)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val qrSide = AdaptiveLayout.qrSide(maxHeight.value).dp
        Column(Modifier.fillMaxSize().readableWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Receive"), style = MaterialTheme.typography.headlineLarge)
            val current = request
            if (current != null) {
                val uri = current.uri()
                ReceiveQR(uri, maximumSide = qrSide) { sheet = "copy" }
                current.amountSat?.let {
                    Text(tr(BitcoinAmount.format(it, app.amountUnit, locale)), style = MaterialTheme.typography.headlineLarge)
                    price?.let { rate -> Text(tr(FiatCurrency.display(it, rate, currency, locale))) }
                }
                Text(tr("Scan to receive bitcoin with Noah"))
                if (current.amountSat == null) Text(tr("Add an amount to receive over Lightning"), style = MaterialTheme.typography.bodySmall)
                if (current.description.isNotEmpty()) Text(tr(current.description))
                NoahButton(if (current.amountSat == null) "Add amount" else "Edit amount", !saving, modifier = Modifier.testTag("receive-amount-button")) { sheet = "amount" }
                NoahButton("Payment details", secondary = true) { sheet = "copy" }
                NoahButton("Share", secondary = true) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, uri)
                    }, "Share payment request"))
                }
            } else if (error != null) {
                Text(tr(error!!), color = MaterialTheme.colorScheme.error)
                NoahButton("Retry") { retry++ }
            } else CircularProgressIndicator()
        }
    }
    val current = request
    if (sheet != null && current != null) WalletModalBottomSheet(
        onDismissRequest = { if (!saving) sheet = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        if (sheet == "copy") ReceiveCopySheet(current)
        else ReceiveAmountSheet(current, app.amountUnit, price, currency, locale, saving,
            onRemove = { request = ReceiveRequest(current.arkAddress, current.onchainAddress); sheet = null },
            onSave = { sats, note ->
                if (!saving) {
                    saving = true
                    scope.launch {
                        try {
                            val next = app.session.receiveInvoice(current, sats, note)
                            // A payment may complete while the invoice is being generated.
                            if (received == null && request == current) { request = next; sheet = null }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { app.error = "Could not generate a Lightning invoice. Please try again." }
                        finally { saving = false }
                    }
                }
            })
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

@Composable
private fun ReceiveCopySheet(request: ReceiveRequest) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf<String?>(null) }
    val items = listOf("Unified request" to request.uri(), "Ark address" to request.arkAddress,
        "On-chain address" to request.onchainAddress) + listOfNotNull(request.lightningInvoice?.let { "Lightning invoice" to it })
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tr("Payment details"), style = MaterialTheme.typography.headlineMedium)
        items.forEach { (label, value) ->
            Text(tr(label), style = MaterialTheme.typography.titleLarge)
            Text(tr(value), style = MaterialTheme.typography.bodySmall)
            NoahButton(if (copied == label) "Copied $label" else "Copy $label", secondary = true, modifier = Modifier.testTag("receive-copy-${when (label) { "Ark address" -> "ark"; "On-chain address" -> "onchain"; "Lightning invoice" -> "lightning"; else -> "request" }}")) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
                copied = label
            }
        }
    }
}

@Composable
internal fun ReceiveAmountSheet(request: ReceiveRequest, unit: BitcoinAmountUnit, price: Double?, currency: String, locale: java.util.Locale,
    saving: Boolean, onRemove: () -> Unit, onSave: (Long, String) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(request.amountSat?.toString() ?: "") }
    var note by rememberSaveable { mutableStateOf(request.description) }
    var fiat by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val normalized = amount.replace(DecimalFormatSymbols.getInstance(locale).decimalSeparator, '.')
    val sats = if (fiat && price != null) FiatCurrency.fiatToSats(normalized.toDoubleOrNull() ?: Double.NaN, price)
        else normalized.toLongOrNull() ?: 0
    val valid = runCatching { ReceiveRequest.validate(sats, note) }.isSuccess
    Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(tr(if (request.amountSat == null) "Add amount" else "Edit amount"), style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(amount, { amount = it }, label = { Text(tr(if (fiat) "Amount ($currency)" else "Amount (sats)")) },
            keyboardOptions = KeyboardOptions(keyboardType = if (fiat) KeyboardType.Decimal else KeyboardType.Number),
            singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("receive-amount-input"))
        TextButton(onClick = {
            amount = if (fiat) sats.toString() else FiatCurrency.satsToFiat(sats, price!!, currency)
            fiat = !fiat
        }, enabled = price != null && !saving) {
            Text(tr(if (price == null) tr("{currency} rate unavailable", currency) else if (fiat) "Switch to sats · ${BitcoinAmount.format(sats, unit, locale)}"
                else "Switch to $currency · ${FiatCurrency.display(sats, price, currency, locale)}"))
        }
        OutlinedTextField(note, { note = it }, label = { Text(tr("Description")) }, minLines = 2,
            enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("receive-description-input"))
        if (note.trim().length > 639) Text(tr("Note must be 639 characters or fewer."), color = MaterialTheme.colorScheme.error)
        NoahButton(if (saving) "Creating invoice…" else "Save request", valid && !saving, modifier = Modifier.testTag("receive-amount-submit")) { focus.clearFocus(); onSave(sats, note) }
        if (request.amountSat != null) NoahButton("Remove amount", !saving, secondary = true, modifier = Modifier.testTag("receive-amount-remove")) { focus.clearFocus(); onRemove() }
    }
}
