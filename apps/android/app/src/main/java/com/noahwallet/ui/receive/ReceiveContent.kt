package com.noahwallet.ui.receive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.formatting.FiatCurrency
import com.noahwallet.localization.tr
import com.noahwallet.services.wallet.ReceiveRequest
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.CircleButton
import com.noahwallet.ui.home.HomeToast
import com.noahwallet.ui.home.HomeToasts
import com.noahwallet.ui.home.tonal
import com.noahwallet.ui.keypadInput
import com.noahwallet.ui.onboarding.*
import com.noahwallet.ui.send.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Everything Receive shows, from the wallet or a fixture. */
@Immutable
data class ReceiveUi(
    val request: ReceiveRequest?,
    /** Your Lightning address, the default QR; null shows today's unavailable card. */
    val address: String?,
    val format: ReceiveFormat,
    val unit: BitcoinAmountUnit,
    val locale: Locale,
    val price: Double?,
    val currency: String,
    /** Today's request error, with Retry; null while it loads or once it's ready. */
    val error: String? = null,
    /** The title over [error] that says whether a Lightning receive was canceled or failed, as iOS does. */
    val errorTitle: String? = null,
    val saving: Boolean = false,
    val invoiceError: String? = null,
    val arrival: ReceiveArrival? = null,
    /** The on-chain arrival whose confirmation the block row follows; it outlives that arrival's presentation. */
    val chain: ReceiveArrival? = arrival?.takeIf { it.rail == ReceiveArrival.Rail.onchain },
    val tip: Long? = null,
    val confirmedAt: Long? = null,
    /** The wallet can receive here: the listening light circles the card. */
    val listening: Boolean = false,
) {
    val amount get() = request?.amountSat
    fun sats(value: Long) = BitcoinAmount.format(value, unit, locale)
    fun fiat(value: Long) = price?.let { FiatCurrency.display(value, it, currency, locale) }
}

class ReceiveActions(
    val select: (ReceiveFormat) -> Unit = {},
    val saveAmount: (Long, String) -> Unit = { _, _ -> },
    val removeAmount: () -> Unit = {},
    val retry: () -> Unit = {},
    val profile: () -> Unit = {},
)

/** What the card says for [format]. */
@Composable
internal fun cardFace(ui: ReceiveUi, format: ReceiveFormat): CardFace {
    val payload = ReceiveFormats.payload(format, ui.request, ui.address)
    val amount = ui.amount
    return when (format) {
        ReceiveFormat.lightningAddress -> CardFace(payload, ui.address ?: "", tr("Scan with any Lightning wallet"), null, ui.address.isNullOrEmpty())
        ReceiveFormat.request -> CardFace(payload, tr("Payment request"),
            if (amount != null) tr("Scan with any bitcoin or Lightning wallet") else tr("Scan with any bitcoin wallet"),
            amount?.let { ui.sats(it) to ui.fiat(it) }, false)
        ReceiveFormat.invoice -> CardFace(payload, tr("Lightning invoice"), tr("Scan with any Lightning wallet"), amount?.let { ui.sats(it) to ui.fiat(it) },
            false)
        ReceiveFormat.ark -> CardFace(payload, tr("Ark address"), tr("Scan with an Ark wallet"), null, false)
        ReceiveFormat.onchain -> CardFace(payload, tr("Bitcoin address"), tr("Scan with any bitcoin wallet"), null, false)
    }
}

private class FormatRow(val format: ReceiveFormat, val name: String, val value: String, val subtitle: String, val enabled: Boolean)

@Composable
private fun formatRows(ui: ReceiveUi): List<FormatRow> {
    val amount = ui.amount
    fun preview(format: ReceiveFormat) = ReceiveFormats.payload(format, ui.request, ui.address)?.let(ReceiveFormats::preview) ?: ""
    return listOf(
        FormatRow(ReceiveFormat.lightningAddress, tr("Lightning address"), ui.address ?: tr("Lightning address unavailable"), tr("From any Lightning wallet"),
            !ui.address.isNullOrEmpty()),
        FormatRow(ReceiveFormat.request, tr("Payment request"), preview(ReceiveFormat.request),
            if (amount != null) tr("Bitcoin, Ark and Lightning for {amount}",
                ui.sats(amount)) else tr("Bitcoin and Ark in one code. Add an amount for Lightning"), ui.request != null),
        FormatRow(ReceiveFormat.invoice, tr("Lightning invoice"), preview(ReceiveFormat.invoice).ifEmpty { tr("Add an amount first") },
            if (amount != null) tr("For {amount} only", ui.sats(amount)) else tr("Invoices need an amount"), amount != null),
        FormatRow(ReceiveFormat.ark, tr("Ark address"), preview(ReceiveFormat.ark), tr("Instant and free from Ark wallets"), ui.request != null),
        FormatRow(ReceiveFormat.onchain, tr("Bitcoin address"), preview(ReceiveFormat.onchain), tr("From any bitcoin wallet, about 20 min"),
            ui.request != null),
    )
}

/**
 * Receive (docs/native-rewrite/design/send-receive.md): the card leads, the formats trail. Compact keeps the
 * formats in the "Ways to pay you" sheet; wide and tabletop show them beside or below the card, with no sheet.
 * Must sit inside [OnboardingFrame], which resolves the pose.
 */
@Composable
fun ReceiveContent(ui: ReceiveUi, actions: ReceiveActions, toasts: HomeToasts = remember { HomeToasts() }) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val pose = LocalPose.current
    val sheet = rememberSaveable { mutableStateOf<String?>(null) }
    val tilt = remember { Animatable(0f) }
    val face = cardFace(ui, ui.format)
    val rows = formatRows(ui)
    val copy = rememberFormatCopy(ui, toasts)
    fun share() {
        val value = face.payload ?: return
        haptics.light()
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value), null))
    }
    fun amountSheet() { haptics.light(); sheet.value = "amount" }
    val select = rememberFormatSelect(ui, actions, sheet, tilt)
    val paned = pose !is OnboardingPose.Compact
    val cardWidth = if (pose is OnboardingPose.Wide) 310.dp else 300.dp
    val qrSide = if (pose is OnboardingPose.Wide) 228.dp else 226.dp
    val (line, done) = arrivalLine(ui)
    val openFormats = { haptics.light(); sheet.value = "formats" }
    val card = @Composable { ReceiveCardSlot(ui, face, tilt.value, cardWidth, qrSide, actions, openFormats.takeIf { !paned }) }
    val under = @Composable {
        PhaseText(line, done, Modifier.padding(top = 14.dp).width(cardWidth))
        ReceiveBlockRow(ui.chain, ui.tip, ui.confirmedAt, cardWidth, Modifier.padding(top = 8.dp))
    }
    val pills = @Composable { modifier: Modifier -> ReceivePills(ui, face.payload != null, modifier, ::amountSheet, ::share) }
    val list = @Composable { FormatList(ui, rows, onSelect = { select(it, false) }, onCopy = copy) }
    val problem = @Composable { modifier: Modifier -> RequestProblem(ui, actions.retry, modifier) }
    val toast = @Composable { modifier: Modifier -> HomeToast(toasts.current, modifier) }
    when (pose) {
        OnboardingPose.Compact -> CompactReceive(ui, cardWidth, card, under, pills, problem, toast, openFormats)
        is OnboardingPose.Wide -> WideReceive(ui, pose, card, under, pills, list, problem, toast)
        is OnboardingPose.Tabletop -> TabletopReceive(ui, pose, cardWidth, line, done, card, pills, list, problem, toast)
    }
    ReceiveSheets(ui, actions, sheet, paned) { FormatList(ui, rows, onSelect = { select(it, true) }, onCopy = copy) }
}

/** Copies a format's payload, with a toast. */
@Composable
private fun rememberFormatCopy(ui: ReceiveUi, toasts: HomeToasts): (ReceiveFormat) -> Unit {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val copied = tr("Copied")
    return { format ->
        ReceiveFormats.payload(format, ui.request, ui.address)?.let { value ->
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(format.name, value))
            haptics.success(); toasts.show(copied)
        }
    }
}

/** Picks a format (the card tilts); the invoice first asks for an amount. [fromSheet] closes the formats sheet after. */
@Composable
private fun rememberFormatSelect(ui: ReceiveUi, actions: ReceiveActions, sheet: MutableState<String?>,
    tilt: Animatable<Float, AnimationVector1D>): (ReceiveFormat, Boolean) -> Unit {
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    return select@{ format, fromSheet ->
        if (format == ReceiveFormat.invoice && ui.amount == null) {
            scope.launch {
                if (fromSheet) { sheet.value = null; delay(200) }
                haptics.light(); sheet.value = "amount"
            }
            return@select
        }
        if (format == ui.format) return@select
        haptics.selection(); actions.select(format)
        if (!reduceMotion) scope.launch { tilt.animateTo(-10f, tween(120)); tilt.animateTo(0f, NoahTokens.Motion.bouncy()) }
        if (fromSheet) scope.launch { delay(250); sheet.value = null }
    }
}

/** The card, or today's error card while there's no request to show. [openFormats] is the compact card's formats tap. */
@Composable
private fun ReceiveCardSlot(ui: ReceiveUi, face: CardFace, tilt: Float, cardWidth: Dp, qrSide: Dp, actions: ReceiveActions, openFormats: (() -> Unit)?) {
    if (ui.error != null && ui.request == null && ui.format != ReceiveFormat.lightningAddress) ErrorCard(ui.error, ui.errorTitle, cardWidth, actions.retry)
    else FitHeight {
        ReceiveCard(face, ui.arrival, ui.arrival?.let { ui.sats(it.sats) }, ui.listening && ui.error == null, tilt, cardWidth, qrSide,
            actions.profile, openFormats)
    }
}

/**
 * The formats sheet (compact only) and the amount sheet. An arrival plays on the card, so a new one closes any open
 * sheet in the frame it starts; one already playing when the screen is restored keeps its sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiveSheets(ui: ReceiveUi, actions: ReceiveActions, sheet: MutableState<String?>, paned: Boolean, content: @Composable () -> Unit) {
    val arriving = rememberArriving(ui, sheet)
    if (sheet.value == "formats" && !paned && !arriving) TonalSheet({ sheet.value = null }) {
        Text(tr("Ways to pay you"), Modifier.padding(bottom = 4.dp).semantics { heading() },
            style = SendType.strong.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = LocalPalette.current.foreground)
        content()
    }
    if (sheet.value == "amount" && ui.request != null && !arriving) TonalSheet({ if (!ui.saving) sheet.value = null }) {
        ReceiveAmountSheet(ui, onDone = { sats, note -> if (finishAmount(ui, actions, sats, note)) sheet.value = null })
    }
    CloseOnSaved(ui, sheet)
}

/** True from a new arrival's first frame until it's presented; it closes any open sheet as it starts. */
@Composable
private fun rememberArriving(ui: ReceiveUi, sheet: MutableState<String?>): Boolean {
    var presented by rememberSaveable { mutableStateOf(ui.arrival?.startedAt) }
    LaunchedEffect(ui.arrival?.startedAt) {
        if (ui.arrival != null && ui.arrival.startedAt != presented) sheet.value = null
        presented = ui.arrival?.startedAt
    }
    return ui.arrival != null && ui.arrival.startedAt != presented
}

@Composable
private fun CloseOnSaved(ui: ReceiveUi, sheet: MutableState<String?>) {
    // Saving closes the sheet once today's invoice creation has returned a new request: only after a save seen
    // starting here, so a sheet restored with the screen (rotation, a pose change) stays open.
    var savingFrom by remember { mutableStateOf<ReceiveRequest?>(null) }
    LaunchedEffect(ui.saving, ui.request) {
        if (ui.saving) {
            if (savingFrom == null) savingFrom = ui.request
            return@LaunchedEffect
        }
        val from = savingFrom ?: return@LaunchedEffect
        savingFrom = null
        if (sheet.value == "amount" && ui.request != from && ui.invoiceError == null) sheet.value = null
    }
}

/** The amount sheet's Done: clear, keep or save the amount; true when the sheet can close now (a save closes it later). */
private fun finishAmount(ui: ReceiveUi, actions: ReceiveActions, sats: Long, note: String): Boolean = when {
    sats == 0L && ui.amount == null -> true
    sats == 0L -> { actions.removeAmount(); true }
    sats == ui.amount && note.trim() == ui.request?.description -> true
    else -> { actions.saveAmount(sats, note); false }
}

/** The line under the card: the arrival's phase while it plays, then an on-chain payment's block; and whether it reads as done. */
@Composable
private fun arrivalLine(ui: ReceiveUi): Pair<String?, Boolean> {
    val reduceMotion = LocalReduceMotion.current
    val now by produceState(0L, ui.arrival?.startedAt) {
        val started = ui.arrival?.startedAt ?: return@produceState
        while (true) {
            value = android.os.SystemClock.elapsedRealtime() - started
            if (value > 6_000) break
            delay(100)
        }
    }
    val phase = ui.arrival?.let { ReceiveTimeline.phase(it.rail, now, reduceMotion) }
    val chainPhase = ui.chain?.takeIf { phase == null }?.let { _ -> blockLine(ui) }
    val line = phase?.let { tr(it) } ?: chainPhase
    return line to (phase == "Received" || (ui.confirmedAt != null && chainPhase != null))
}

@Composable
private fun blockLine(ui: ReceiveUi): String? {
    val tip = ui.tip
    val format = java.text.NumberFormat.getIntegerInstance(ui.locale)
    return when {
        tip == null -> null
        ui.confirmedAt != null -> tr("Confirmed in block {height}", format.format(ui.confirmedAt))
        else -> tr("Waiting for block {height}", format.format(tip + 1))
    }
}

/** Add or change the amount, and share what the card shows. */
@Composable
private fun ReceivePills(ui: ReceiveUi, canShare: Boolean, modifier: Modifier, amount: () -> Unit, share: () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SendPill(if (ui.amount == null) tr("Add amount") else tr("Change amount"), Modifier.weight(1f).testTag("receive-amount-button"),
            enabled = ui.request != null && !ui.saving,
            outlined = true, height = 48.dp, onClick = amount)
        SendPill(tr("Share"), Modifier.weight(1f).testTag("receive-share"), enabled = canShare, height = 48.dp, onClick = share)
    }
}

/** Today's request errors (and a failed Lightning receive) while the card still shows your address. */
@Composable
private fun RequestProblem(ui: ReceiveUi, retry: () -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    val error = ui.error ?: return
    if (ui.request == null && ui.format != ReceiveFormat.lightningAddress) return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ui.errorTitle?.let { Text(tr(it), style = SendType.strong, color = palette.foreground, textAlign = TextAlign.Center) }
        Text(tr(error), style = SendType.small, color = palette.failure, textAlign = TextAlign.Center)
        androidx.compose.material3.TextButton(retry) { Text(tr("Retry"), style = SendType.line, color = palette.foreground) }
    }
}

@Composable
private fun CompactReceive(ui: ReceiveUi, cardWidth: Dp, card: @Composable () -> Unit, under: @Composable () -> Unit,
    pills: @Composable (Modifier) -> Unit, problem: @Composable (Modifier) -> Unit, toast: @Composable (Modifier) -> Unit, formats: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                CircleButton(SendIcons.copy, tr("Ways to pay you"), Modifier.testTag("receive-formats"), onClick = formats)
            }
            Box(Modifier.padding(top = 12.dp)) { card() }
            under()
            pills(Modifier.width(cardWidth).padding(top = 18.dp))
            ui.invoiceError?.let { Text(tr(it), Modifier.padding(top = 12.dp).width(cardWidth), style = SendType.small, color = LocalPalette.current.failure,
                textAlign = TextAlign.Center) }
            problem(Modifier.padding(top = 12.dp).width(cardWidth))
        }
        toast(Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
    }
}

@Composable
private fun WideReceive(ui: ReceiveUi, pose: OnboardingPose.Wide, card: @Composable () -> Unit, under: @Composable () -> Unit,
    pills: @Composable (Modifier) -> Unit, list: @Composable () -> Unit, problem: @Composable (Modifier) -> Unit, toast: @Composable (Modifier) -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(pose.start).fillMaxHeight().padding(horizontal = 24.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { card(); under() }
            toast(Modifier.align(Alignment.TopCenter))
        }
        Spacer(Modifier.width(pose.end - pose.start))
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 40.dp, end = 34.dp, top = 40.dp, bottom = 24.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(tr("Show"), Modifier.padding(bottom = 8.dp).semantics { heading() }, style = SendType.section, color = palette.muted)
                    list()
                    ui.invoiceError?.let { Text(tr(it), Modifier.padding(top = 12.dp), style = SendType.small, color = palette.failure) }
                    problem(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                pills(Modifier.fillMaxWidth().padding(top = 16.dp))
            }
        }
    }
}

@Composable
private fun TabletopReceive(ui: ReceiveUi, pose: OnboardingPose.Tabletop, cardWidth: Dp, line: String?, done: Boolean, card: @Composable () -> Unit,
    pills: @Composable (Modifier) -> Unit, list: @Composable () -> Unit, problem: @Composable (Modifier) -> Unit, toast: @Composable (Modifier) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.height(pose.top).fillMaxWidth().clipToBounds().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f, fill = false)) { card() }
                PhaseText(line, done, Modifier.padding(top = 8.dp).width(cardWidth))
            }
            toast(Modifier.align(Alignment.TopCenter))
        }
        Spacer(Modifier.height(pose.bottom - pose.top))
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ReceiveBlockRow(ui.chain, ui.tip, ui.confirmedAt, cardWidth, Modifier.align(Alignment.CenterHorizontally))
                    list()
                    ui.invoiceError?.let { Text(tr(it), Modifier.padding(top = 12.dp), style = SendType.small, color = LocalPalette.current.failure) }
                    problem(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                pills(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}

/** The typed amount in sats: as typed, or converted at today's price while typing in fiat. */
private fun typedSats(amount: String, fiat: Boolean, ui: ReceiveUi): Long {
    val normalized = amount.replace(DecimalFormatSymbols.getInstance(ui.locale).decimalSeparator, '.')
    return if (fiat && ui.price != null) FiatCurrency.fiatToSats(normalized.toDoubleOrNull() ?: 0.0, ui.price) else normalized.toLongOrNull() ?: 0
}

/** The sheet's figure: the formatted sats, or the fiat symbol and the digits as typed. */
private fun typedText(amount: String, fiat: Boolean, sats: Long, ui: ReceiveUi): String = when {
    amount.isEmpty() -> ui.sats(0)
    fiat -> (runCatching { java.util.Currency.getInstance(ui.currency).getSymbol(ui.locale) }.getOrDefault(ui.currency)) + amount.replace('.',
        DecimalFormatSymbols.getInstance(ui.locale).decimalSeparator)
    else -> ui.sats(sats)
}

/** The amount in the other currency (tap to type in it, while there's a price and no save running). */
@Composable
private fun CurrencyLine(ui: ReceiveUi, fiat: Boolean, sats: Long, toggle: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.padding(top = 8.dp).height(24.dp).then(if (ui.price != null && !ui.saving) Modifier.clickable(role = Role.Button, onClick = toggle)
    else Modifier), verticalAlignment = Alignment.CenterVertically) {
        ui.price?.let { price ->
            Text(if (fiat) ui.sats(sats) else FiatCurrency.display(sats, price, ui.currency, ui.locale), style = SendType.body, color = palette.muted)
            Icon(painterResource(SendIcons.swap), null, Modifier.padding(start = 8.dp).size(16.dp), tint = palette.muted)
        }
    }
}

/** The typed amount for the other currency: sats when leaving fiat, today's price in fiat when entering it; empty for none. */
private fun switchedAmount(sats: Long, fiat: Boolean, ui: ReceiveUi): String {
    val positive = sats.takeIf { it > 0 } ?: return ""
    if (fiat) return positive.toString()
    return ui.price?.let { FiatCurrency.satsToFiat(positive, it, ui.currency) } ?: ""
}

@Composable
private fun NoteInput(note: String, onNote: (String) -> Unit, enabled: Boolean) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp).heightIn(min = 48.dp).tonal(RoundedCornerShape(24.dp),
        palette.soft).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(note, onNote, Modifier.weight(1f).padding(vertical = 12.dp).testTag("receive-description-input"), enabled = enabled,
            textStyle = SendType.body.copy(color = palette.foreground), cursorBrush = SolidColor(palette.orange), maxLines = 3,
            decorationBox = { inner ->
                Box {
                    if (note.isEmpty()) Text(tr("Add a note"), style = SendType.body, color = palette.muted)
                    inner()
                }
            })
    }
}

/** A Material 3 sheet on the tonal soft surface (prototype `.ph.android .sheet`), inside the trailing pane when split. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TonalSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.MaterialTheme.colorScheme.copy(surfaceContainerLow = palette.soft,
        surfaceContainer = palette.soft, surfaceContainerHigh = palette.soft)) {
        OnboardingSheet(onDismiss, rememberModalBottomSheetState(skipPartiallyExpanded = true), content = content)
    }
}

/** Shrinks the card to the height it's given (tabletop's top half), never past its natural size. */
@Composable
private fun FitHeight(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity, minWidth = 0))
        val limit = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
        val scale = if (placeable.height > limit && placeable.height > 0) limit / placeable.height.toFloat() else 1f
        val width = placeable.width
        val height = (placeable.height * scale).toInt()
        layout(width, height) { placeable.placeWithLayer(0, ((height - placeable.height) / 2f).toInt()) { scaleX = scale; scaleY = scale } }
    }
}

@Composable
private fun ErrorCard(error: String, title: String?, width: Dp, retry: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.width(width).padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        title?.let { Text(tr(it), style = SendType.strong, color = palette.foreground, textAlign = TextAlign.Center) }
        Text(tr(error), style = SendType.line, color = palette.failure, textAlign = TextAlign.Center)
        SendPill(tr("Retry"), Modifier.fillMaxWidth(), height = 48.dp, onClick = retry)
    }
}

/** The five formats: radio dot, name, value preview, one line and a copy button. The invoice waits for an amount. */
@Composable
private fun FormatList(ui: ReceiveUi, rows: List<FormatRow>, onSelect: (ReceiveFormat) -> Unit, onCopy: (ReceiveFormat) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().semantics { selectableGroup() }) {
        rows.forEachIndexed { index, row ->
            val selected = row.format == ui.format
            val dot by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "radio")
            val copyLabel = tr("Copy {label}", row.name)
            Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).graphicsLayer { alpha = if (row.enabled ||
                row.format == ReceiveFormat.invoice) (if (row.enabled) 1f else .35f) else .35f }
                .drawBehind { if (index < rows.lastIndex) drawRect(palette.hairline, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                .clickable(enabled = row.enabled || row.format == ReceiveFormat.invoice, role = Role.RadioButton) { onSelect(row.format) }
                .semantics { this.selected = selected }.testTag("receive-format-${row.format.name}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(10.dp).background(androidx.compose.ui.graphics.lerp(palette.background.copy(alpha = 0f), palette.foreground, dot),
                    CircleShape)
                    .then(if (dot < 1f) Modifier.drawBehind { drawCircle(palette.hairline, size.minDimension / 2 - .75.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())) } else Modifier))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(row.name, style = SendType.strong.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)),
                        color = palette.foreground)
                    if (row.value.isNotEmpty()) Text(row.value, Modifier.padding(top = 1.dp), style = SendType.mono, color = palette.foreground, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(row.subtitle, style = SendType.small, color = palette.muted)
                }
                Box(Modifier.size(36.dp).tonal(CircleShape, palette.soft).clickable(enabled = row.enabled, role = Role.Button,
                    onClickLabel = copyLabel) { onCopy(row.format) }
                    .semantics { contentDescription = copyLabel }.testTag("receive-copy-${row.format.name}"), contentAlignment = Alignment.Center) {
                    Icon(painterResource(SendIcons.copy), null, Modifier.size(18.dp), tint = palette.foreground)
                }
            }
        }
    }
}

/**
 * Add amount: the same keypad as Send, the fiat line (tap to type in fiat, as today), an "Add a note" chip for
 * today's invoice description, and Done, which runs today's invoice creation. An empty amount clears it.
 */
@Composable
internal fun ReceiveAmountSheet(ui: ReceiveUi, onDone: (Long, String) -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val request = ui.request ?: return
    var amount by rememberSaveable { mutableStateOf(request.amountSat?.toString() ?: "") }
    var note by rememberSaveable { mutableStateOf(request.description) }
    var noting by rememberSaveable { mutableStateOf(request.description.isNotEmpty()) }
    var fiat by rememberSaveable { mutableStateOf(false) }
    val shake = rememberAmountShake()
    val sats = typedSats(amount, fiat, ui)
    val valid = sats == 0L || runCatching { ReceiveRequest.validate(sats, note) }.isSuccess
    val text = typedText(amount, fiat, sats, ui)
    // Under a horizontal fold the sheet only has the lower half, so the amount sits beside the keypad there.
    val sideBySide = LocalPose.current is OnboardingPose.Tabletop
    val fields = @Composable {
        Text(if (request.amountSat == null) tr("Add amount") else tr("Change amount"), Modifier.fillMaxWidth().semantics { heading() },
            style = SendType.strong.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = palette.foreground)
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(58.dp), contentAlignment = Alignment.BottomCenter) {
            AmountFigure(text, amount.isEmpty(), shake.value, Modifier.testTag("receive-amount-input"), SendType.sheetAmount)
        }
        CurrencyLine(ui, fiat, sats) {
            haptics.selection()
            amount = switchedAmount(sats, fiat, ui)
            fiat = !fiat
        }
        if (!noting) Box(Modifier.padding(top = 12.dp, bottom = 6.dp)) { SendChip(tr("Add a note"), Modifier.testTag("receive-note-chip"),
            enabled = !ui.saving) { haptics.light(); noting = true } }
        else NoteInput(note, { note = it }, !ui.saving)
        if (note.trim().length > 639) Text(tr("Note must be 639 characters or fewer."), style = SendType.small, color = palette.failure)
        ui.invoiceError?.let { Text(tr(it), Modifier.padding(top = 6.dp), style = SendType.small, color = palette.failure, textAlign = TextAlign.Center) }
    }
    val keypad = @Composable { modifier: Modifier ->
        SendKeypad(fiat, !ui.saving, modifier, keyHeight = if (sideBySide) 52.dp else 56.dp) { key -> amount = keypadInput(amount, key, fiat) }
    }
    val done = @Composable {
        SendPill(if (ui.saving) tr("Creating invoice…") else tr("Done"), Modifier.fillMaxWidth().padding(top = 12.dp).testTag("receive-amount-submit"),
            enabled = valid && !ui.saving) { haptics.light(); onDone(sats, note) }
    }
    if (sideBySide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { fields(); done() }
        keypad(Modifier.weight(1f))
    } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        fields(); keypad(Modifier.padding(top = 6.dp)); done()
    }
}
