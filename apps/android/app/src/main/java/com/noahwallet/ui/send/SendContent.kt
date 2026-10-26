package com.noahwallet.ui.send

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.formatting.FiatCurrency
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.localization.tr
import com.noahwallet.send.*
import com.noahwallet.ui.WalletAlertDialog
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.CircleButton
import com.noahwallet.ui.home.HomeIcons
import com.noahwallet.ui.home.HomeRow
import com.noahwallet.ui.home.tonal
import com.noahwallet.ui.keypadInput
import com.noahwallet.ui.SendPresentation
import com.noahwallet.ui.onboarding.*
import kotlinx.coroutines.isActive
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.util.Locale

/** What Send shows beyond SendModel and SendController: formatting, history and the chain, from the app or a fixture. */
@Immutable
class SendEnv(
    val unit: BitcoinAmountUnit,
    val locale: Locale,
    val network: String,
    /** Your Lightning address's domain: addresses on it are Noah users. */
    val ownDomain: String?,
    /** Recent outgoing history (recents) and successful rows (Lightning proof). */
    val history: List<HistoryTransaction> = emptyList(),
    /** Today's pending-round line under "available", already in words. */
    val pendingRound: String? = null,
    /** The chain source's tip, and how many confirmations an on-chain send via Ark waits for. */
    val tip: Long? = null,
    val chainNeeded: Long = 1,
    /** What the wallet recorded coming back from a failed Lightning send; null until read. */
    val refund: SendRefund? = null,
    /** Today's per-rail estimates for the method cards, display only. */
    val estimates: Map<SendRail, Long> = emptyMap(),
    val variant: String = "regtest",
)

class SendActions(
    val scan: () -> Unit = {},
    /** Done on an ended payment: today's acknowledge, then back to Amount. */
    val done: () -> Unit = {},
    /** Leave while Lightning is still paying or a broadcast is still retrying; the payment carries on. */
    val leave: () -> Unit = {},
    val checkStatus: () -> Unit = {},
    val startOver: () -> Unit = {},
    /** The host has no tab bar to leave by (the recurring approval sheet): unresolved results offer Close ([leave]) too. */
    val leaveUnresolved: Boolean = false,
)

/** One screen of the flow. Sending covers the motion and every ending. */
enum class SendStep { amount, to, method, source, review, sending }

internal fun SendModel.step(): SendStep = when {
    controller.locked -> SendStep.sending
    else -> when (stage) {
        SendStage.amount -> SendStep.amount
        SendStage.recipient -> SendStep.to
        SendStage.method -> SendStep.method
        SendStage.source -> SendStep.source
        SendStage.review -> SendStep.review
    }
}

/** What the result area says for the controller's state. Lines and titles keep today's wording for edge states. */
enum class SendEnding { sending, arkSent, lightningSent, chainSent, pending, refunded, failed, notSent, alreadyPaid, unknown, unavailable;
    companion object {
        /** [failed]: Lightning failed, but a retried invoice's refund can't be tied to this attempt. */
        fun of(state: SendController.State, attempt: SendAttempt?, refund: SendRefund?, stillPaying: Boolean): SendEnding? {
            val route = attempt?.payment?.let(SendRoute::of)
            return when (state) {
                SendController.State.ready -> null
                SendController.State.submitting -> if (stillPaying) pending else sending
                SendController.State.succeeded -> when (route) { SendRoute.ark -> arkSent; SendRoute.lightning -> lightningSent; null -> unknown; else -> chainSent }
                SendController.State.failed -> when {
                    route != SendRoute.lightning -> notSent
                    refund == SendRefund.recorded -> refunded
                    refund == SendRefund.untied -> failed
                    else -> notSent
                }
                SendController.State.alreadyPaid -> alreadyPaid
                SendController.State.uncertain -> if (route == SendRoute.lightning && attempt?.existingLightning != null) pending else unknown
                SendController.State.storageBlocked -> unavailable
            }
        }
    }
}

/** How a payment reads on Send: the name and the stage's avatar, from the typed destination or the attempt. */
internal object SendWhoRules {
    fun of(destination: String, method: String?, ownDomain: String?): SendWho {
        val value = SendDestination.cleaned(destination)
        return when (method) {
            "lnurl" -> {
                val address = value.lowercase(Locale.ROOT)
                val local = address.substringBefore('@')
                val noahUser = ownDomain != null && address.substringAfter('@', "") == ownDomain.lowercase(Locale.ROOT)
                SendWho(if (noahUser) local else address, local.take(1).ifEmpty { null })
            }
            "lightning" -> SendWho("Lightning invoice", glyph = SendWho.Glyph.lightning)
            "offer" -> SendWho("Lightning offer", glyph = SendWho.Glyph.lightning)
            "onchain" -> SendWho(short(value), glyph = SendWho.Glyph.chain)
            "ark" -> SendWho(short(value), glyph = SendWho.Glyph.ark)
            "bip321" -> SendWho("Payment request", glyph = SendWho.Glyph.request)
            else -> SendWho(short(value), glyph = SendWho.Glyph.request)
        }
    }
    /** The type line under a result: Lightning address, Noah user, Bitcoin address and so on. */
    fun kind(type: String?, destination: String, ownDomain: String?): String? = when (type) {
        "lnurl" -> if (ownDomain != null && SendDestination.cleaned(destination).lowercase(Locale.ROOT).substringAfter('@', "") == ownDomain.lowercase(Locale.ROOT)) "Noah user" else "Lightning address"
        "onchain" -> "Bitcoin address"
        "lightning" -> "Lightning invoice"
        "bip321" -> "Payment request"
        "ark" -> "Ark address"
        "offer" -> "Lightning offer"
        else -> null
    }
    fun short(value: String) = if (value.length <= 14) value else value.take(4) + "…" + value.takeLast(4)

    /** Recent recipients from outgoing history (no new storage): payable again, newest first, each once. */
    fun recents(history: List<HistoryTransaction>, network: String, limit: Int = 8): List<String> = history
        .filter { it.direction == "outgoing" && it.movementStatus == "successful" }
        .mapNotNull { row -> (row.repeatPayment?.destination ?: row.destination)?.takeIf { it.isNotEmpty() } }
        .filter { SendDestination.parse(it, network).let { parsed -> parsed.error == null && parsed.destinationType in setOf("lnurl", "ark", "onchain") } }
        .distinctBy { SendDestination.cleaned(it).lowercase(Locale.ROOT) }
        .take(limit)
}

/**
 * Send (docs/native-rewrite/design/send-receive.md): today's SendModel steps restyled. Must sit inside
 * [OnboardingFrame], which resolves the pose: one column, two panes split at the fold, or tabletop.
 */
@Composable
internal fun SendContent(presentation: SendPresentation, env: SendEnv, actions: SendActions) {
    val model = presentation.model
    val step = model.step()
    val reduceMotion = LocalReduceMotion.current
    val forward = remember { mutableStateOf(true) }
    var last by remember { mutableStateOf(step) }
    if (last != step) { forward.value = step.ordinal >= last.ordinal; last = step }
    val shift = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.roundToPx() }
    AnimatedContent(step, Modifier.fillMaxSize(), label = "send-step", transitionSpec = {
        if (reduceMotion) fadeIn(tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) togetherWith fadeOut(tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS))
        else (fadeIn(NoahTokens.Motion.snappy()) + slideInHorizontally(NoahTokens.Motion.snappy()) { if (forward.value) shift else -shift }) togetherWith
            fadeOut(tween(120))
    }) { shown ->
        when (shown) {
            SendStep.amount -> AmountStep(presentation, env, actions)
            SendStep.to -> ToStep(presentation, env, actions)
            SendStep.method -> MethodStep(presentation, env)
            SendStep.source -> SourceStep(presentation, env)
            SendStep.review -> ReviewStep(presentation, env)
            SendStep.sending -> SendingStep(presentation, env, actions)
        }
    }
}

/**
 * A recurring payment's manual approval (iOS `RecurringPaymentsView.approval`): the executor's reviewed [payment] and
 * [quote] on Send's review, then Sending and the result once the controller holds the payment. [confirm] hands the
 * hold to the recurring executor, which submits through the same controller; [paying] is true until that run returns.
 * Back is [cancel]. Must sit inside [OnboardingFrame].
 */
@Composable
internal fun SendApproval(presentation: SendPresentation, payment: SendPayment, quote: SendQuote, env: SendEnv, actions: SendActions,
                          paying: Boolean, cancel: () -> Unit, confirm: () -> Unit) {
    val controller = presentation.model.controller
    val reduceMotion = LocalReduceMotion.current
    val shift = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.roundToPx() }
    AnimatedContent(controller.locked, Modifier.fillMaxSize(), label = "send-approval", transitionSpec = {
        if (reduceMotion) fadeIn(tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) togetherWith fadeOut(tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS))
        else (fadeIn(NoahTokens.Motion.snappy()) + slideInHorizontally(NoahTokens.Motion.snappy()) { shift }) togetherWith fadeOut(tween(120))
    }) { sending ->
        if (sending) SendingStep(presentation, env, actions, reviewed = payment)
        else ReviewPanes(quote.amount, SendWhoRules.of(payment.destination, payment.method, env.ownDomain), payment.rail(), payment.source, quote, payment, env,
            backEnabled = !paying, back = cancel, notes = {
                reviewNote(null, quote, env, null)?.let { Text(it, Modifier.padding(top = 12.dp), style = SendType.small, color = LocalPalette.current.muted) }
                controller.message?.let { ErrorLine(it) }
            }) {
            HoldToSend(formatted(quote.amount, env), enabled = !paying, sending = paying, modifier = Modifier.padding(bottom = 6.dp), onConfirm = confirm)
        }
    }
}

private fun SendPayment.rail() = when (SendRoute.of(this)) { SendRoute.ark -> SendRail.ark; SendRoute.lightning -> SendRail.lightning; else -> SendRail.onchain }

private fun formatted(sats: Long, env: SendEnv) = BitcoinAmount.format(sats, env.unit, env.locale)

/**
 * The step's two parts: [leading] (the amount, title, recipient or stage) and [trailing] (keypad, results,
 * cards, rows and actions). One column on phones; split at the fold (or the centre) when wide; above and
 * below the fold in tabletop.
 */
@Composable
private fun StepPanes(nav: @Composable RowScope.() -> Unit, leadingCentred: Boolean, trailingAt: Alignment.Vertical,
                      leading: @Composable ColumnScope.() -> Unit, trailing: @Composable ColumnScope.() -> Unit,
                      bottom: (@Composable ColumnScope.() -> Unit)? = null) {
    val pose = LocalPose.current
    val navRow = @Composable { Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, content = nav) }
    // One column (or tabletop's lower half): [trailing] from the top and [bottom] pinned to the foot, scrolling only if they don't fit.
    val stacked = @Composable { modifier: Modifier ->
        BoxWithConstraints(modifier) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = if (bottom != null) Arrangement.SpaceBetween else arrangement(trailingAt)) {
                Column(Modifier.fillMaxWidth(), content = trailing)
                bottom?.let { Column(Modifier.fillMaxWidth(), content = it) }
            }
        }
    }
    when (pose) {
        OnboardingPose.Compact -> Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 16.dp)) {
            navRow()
            val column = Modifier.fillMaxWidth().widthIn(max = 520.dp).align(Alignment.CenterHorizontally)
            // A step with an action at the foot (Hold to send, Done, Close) scrolls as one page when it doesn't fit, so a
            // short window or large text can never squeeze the action out under [leading]; when it fits, nothing moves.
            if (bottom != null) BoxWithConstraints(column.weight(1f)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
                    Column(Modifier.fillMaxWidth(), content = leading)
                    Column(Modifier.fillMaxWidth(), content = trailing)
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth(), content = bottom)
                }
            } else {
                Column(column, content = leading)
                stacked(column.weight(1f))
            }
        }
        is OnboardingPose.Wide -> Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(pose.start).fillMaxHeight().padding(start = 34.dp, end = 34.dp, top = 14.dp, bottom = 24.dp), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxSize()) {
                    navRow()
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = if (leadingCentred) Arrangement.Center else Arrangement.Top, content = leading)
                }
            }
            Spacer(Modifier.width(pose.end - pose.start))
            Box(Modifier.weight(1f).fillMaxHeight().imePadding().padding(start = 40.dp, end = 34.dp, top = 22.dp, bottom = 24.dp), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = arrangement(trailingAt)) { trailing(); bottom?.invoke(this) }
            }
        }
        is OnboardingPose.Tabletop -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.height(pose.top).fillMaxWidth().clipToBounds().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxSize()) {
                    navRow()
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, content = leading)
                }
            }
            Spacer(Modifier.height(pose.bottom - pose.top))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().imePadding().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
                stacked(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxSize())
            }
        }
    }
}

private fun arrangement(at: Alignment.Vertical) = when (at) {
    Alignment.Bottom -> Arrangement.Bottom
    Alignment.CenterVertically -> Arrangement.Center
    else -> Arrangement.Top
}

@Composable
private fun RowScope.Back(enabled: Boolean = true, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    CircleButton(SendIcons.back, tr("Back"), Modifier.testTag("send-back").alpha(if (enabled) 1f else .4f)) { if (enabled) { haptics.light(); onClick() } }
}

@Composable
private fun RowScope.Title() {
    Text(tr("Send"), Modifier.weight(1f).semantics { heading() }, style = SendType.title, color = LocalPalette.current.foreground, textAlign = TextAlign.Center)
}

@Composable
private fun RowScope.Scan(enabled: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    CircleButton(HomeIcons.scan, tr("Scan"), Modifier.testTag("send-scan")) { if (enabled) { haptics.light(); onClick() } }
}

/** The clipboard's first item as text, as Paste reads it. */
internal fun Context.clipboardText(): String? =
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()

/**
 * Back one step, as today's flow allows; the review's back discards its quote first (today's leaveReview).
 * Returning to To clears the rail and balance choices, so continuing asks for them again.
 */
internal fun SendModel.back() {
    if (controller.locked || busy) return
    fun toRecipient() {
        progress = progress.copy(railConfirmed = false, sourceConfirmed = isMax && progress.sourceConfirmed)
        stage = SendStage.recipient
    }
    when (stage) {
        SendStage.amount -> if (progress.recipientConfirmed) stage = SendStage.recipient
        SendStage.recipient -> when {
            isMax -> stage = SendStage.source
            progress.entry == "amount-first" -> stage = SendStage.amount
            else -> reset()
        }
        SendStage.method -> toRecipient()
        SendStage.source -> if (isMax) reset() else if (progress.rails.size > 1) stage = SendStage.method else toRecipient()
        SendStage.review -> {
            discardReview()
            when {
                isMax -> stage = SendStage.source
                progress.selectedRail == SendRail.onchain && progress.sourceOptions.size > 1 -> stage = SendStage.source
                progress.rails.size > 1 || progress.selectedRailAvailable == false -> stage = SendStage.method
                else -> toRecipient()
            }
        }
    }
}

// Amount

@Composable
private fun AmountStep(presentation: SendPresentation, env: SendEnv, actions: SendActions) {
    val model = presentation.model
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val scope = presentation.viewModelScope
    val context = LocalContext.current
    val shake = rememberAmountShake()
    val available = maxOf(model.arkBalance, model.onchainBalance)
    val sats = model.sats
    val over = !model.isMax && model.parsed.isAmountEditable && sats > available
    val editable = model.parsed.isAmountEditable && !model.busy
    val text = when {
        model.amount.isEmpty() -> formatted(0, env)
        model.useFiat -> fiatTyping(model.amount, model.fiatCurrency, env.locale)
        else -> formatted(model.amount.toLongOrNull() ?: 0, env)
    }
    LaunchedEffect(model.amount) { if (over) { haptics.error(); shake.shakeAmount() } }
    val canMax = model.parsed.isAmountEditable && (model.arkBalance > 0 || model.onchainBalance > 0) &&
        (model.destination.isEmpty() || SendFlow.canMax(model.parsed.destinationType, model.parsed.bip321))
    // Today's import, as Scan does: an amount in the request skips the keypad; one it can't accept stays here with its error.
    fun paste() {
        haptics.light()
        val value = context.clipboardText()?.takeIf { it.isNotBlank() } ?: return
        scope.launch { model.importRecipient(value, stay = true) }
    }
    StepPanes(nav = {
        Spacer(Modifier.weight(1f))
        SendChip(tr("Paste"), Modifier.padding(end = 8.dp).testTag("send-paste-from-amount"), enabled = !model.busy, onClick = ::paste)
        Scan(!model.busy, actions.scan)
    }, leadingCentred = true, trailingAt = Alignment.Bottom, leading = {
        Text(if (over) tr("Only {amount} available", formatted(available, env)) else tr("{amount} available", formatted(available, env)),
            Modifier.fillMaxWidth().padding(top = 28.dp), style = SendType.line, color = if (over) palette.failure else palette.muted, textAlign = TextAlign.Center)
        Box(Modifier.fillMaxWidth().padding(top = 30.dp).height(72.dp), contentAlignment = Alignment.BottomCenter) {
            AutoFit { AmountFigure(text, model.amount.isEmpty(), shake.value, modifier = Modifier.testTag("send-amount")) }
        }
        val price = model.btcPrice
        Row(Modifier.fillMaxWidth().padding(top = 14.dp).height(24.dp).then(
            if (price != null && editable) Modifier.clickableNoRipple { haptics.selection(); model.toggleCurrency() }.testTag("send-currency-toggle") else Modifier),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (price != null) {
                Text(if (model.useFiat) formatted(sats, env) else FiatCurrency.display(sats, price, model.fiatCurrency, env.locale),
                    style = SendType.body, color = palette.muted)
                Icon(painterResource(SendIcons.swap), null, Modifier.padding(start = 8.dp).size(16.dp), tint = palette.muted)
            }
        }
        // Recipient-first (a scan or paste without an amount): who it's for while you type the amount.
        if (model.progress.recipientConfirmed && !model.isMax) {
            val who = SendWhoRules.of(model.destination, model.parsed.destinationType, env.ownDomain)
            Text(tr("to {recipient}", tr(who.name)), Modifier.fillMaxWidth().padding(top = 10.dp).testTag("send-amount-recipient"), style = SendType.body,
                color = palette.muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp).height(32.dp), horizontalArrangement = Arrangement.Center) {
            if (canMax) SendChip(tr("Max"), Modifier.testTag("send-max"), enabled = !model.busy) {
                haptics.light()
                val keep = model.destination.takeIf { it.isNotEmpty() }
                model.maxSend(); keep?.let(model::setRecipient)
            }
        }
        listOfNotNull(env.pendingRound, if (!model.parsed.isAmountEditable) tr("Amount set by payment request") else null).forEach {
            Text(it, Modifier.fillMaxWidth().padding(top = 10.dp), style = SendType.small, color = palette.muted, textAlign = TextAlign.Center)
        }
        model.error?.let { ErrorLine(it, model) }
    }, trailing = {
        SendKeypad(model.useFiat, editable, Modifier.padding(top = 12.dp), keyHeight = if (LocalPose.current is OnboardingPose.Tabletop) 48.dp else 60.dp) { key -> model.amount = keypadInput(model.amount, key, model.useFiat) }
        // An empty amount continues recipient-first, as today's "Choose recipient first" did.
        SendPill(if (model.busy) tr("Preparing…") else tr("Next"), Modifier.fillMaxWidth().padding(top = 12.dp).testTag("send-continue"), enabled = !model.busy && !over) {
            haptics.light()
            scope.launch { if (model.amount.isEmpty() && !model.progress.recipientConfirmed) model.recipientFirst() else model.continueStage() }
        }
    })
}

private fun fiatTyping(amount: String, currency: String, locale: Locale): String {
    val symbol = runCatching { java.util.Currency.getInstance(currency).getSymbol(locale) }.getOrDefault(currency)
    val separator = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return symbol + amount.replace('.', separator)
}

/** Shrinks its content to fit the width, so a long amount never clips. */
@Composable
private fun AutoFit(content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
        val scale = if (placeable.width > constraints.maxWidth) constraints.maxWidth / placeable.width.toFloat() else 1f
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeWithLayer((constraints.maxWidth - placeable.width) / 2, 0) { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1f) }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit) = clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)

@Composable
private fun ErrorLine(text: String, model: SendModel? = null) {
    val context = LocalContext.current
    Text(model?.destinationError?.localized(context) ?: tr(text), Modifier.fillMaxWidth().padding(top = 12.dp), style = SendType.small,
        color = LocalPalette.current.failure, textAlign = TextAlign.Center)
}

// To

@Composable
private fun ToStep(presentation: SendPresentation, env: SendEnv, actions: SendActions) {
    val model = presentation.model
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val scope = presentation.viewModelScope
    val context = LocalContext.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun next() { focus.clearFocus(); scope.launch { model.continueStage() } }
    val typed = model.destination.isNotEmpty()
    val parsed = remember(model.destination) { SendDestination.parse(model.destination, env.network) }
    StepPanes(nav = { Back { model.back() }; Title(); Scan(!model.busy, actions.scan) }, leadingCentred = false, trailingAt = Alignment.Top, leading = {
        if (model.sats > 0 && !model.isMax) Text(formatted(model.sats, env), Modifier.padding(top = 6.dp), style = SendType.summary, color = palette.foreground)
        ToField(model.destination, model::setRecipient, !model.busy, onPaste = {
            context.clipboardText()?.let { value ->
                haptics.light(); focus.clearFocus(); scope.launch { model.importRecipient(value) }
            }
        }, onDone = ::next, modifier = Modifier.padding(top = 18.dp))
    }, trailing = {
        if (!typed) {
            val recents = remember(env.history) { SendWhoRules.recents(env.history, env.network) }
            if (recents.isNotEmpty()) {
                Section(tr("Recent"))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    recents.forEach { destination ->
                        val type = SendDestination.parse(destination, env.network).destinationType
                        val who = SendWhoRules.of(destination, type, env.ownDomain)
                        Column(Modifier.width(60.dp).clickableNoRipple { haptics.light(); scope.launch { model.importRecipient(destination) } }
                            .semantics(mergeDescendants = true) { }, horizontalAlignment = Alignment.CenterHorizontally) {
                            WhoAvatar(who, 56.dp, SendType.initial.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)))
                            Text(who.name.substringBefore('@'), Modifier.padding(top = 6.dp), style = SendType.small.copy(fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)),
                                color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else {
            Section(tr("Results"))
            val kind = SendWhoRules.kind(parsed.destinationType, model.destination, env.ownDomain)
            val suggestions = model.suggestions.filter { it.lowercase(Locale.ROOT) != SendDestination.cleaned(model.destination).lowercase(Locale.ROOT) }
            if (kind != null) {
                val who = SendWhoRules.of(model.destination, parsed.destinationType, env.ownDomain)
                val subtitle = listOfNotNull(tr(kind), if (model.matchingRoute != null) tr("Recipient verified") else null).joinToString(" · ")
                WhoRow(who.copy(name = tr(who.name)), subtitle, divider = suggestions.isNotEmpty(), enabled = !model.busy, busy = model.resolvingRecipient || model.busy,
                    modifier = Modifier.testTag("send-result")) { haptics.light(); next() }
            }
            suggestions.forEachIndexed { index, suggestion ->
                WhoRow(SendWhoRules.of(suggestion, "lnurl", env.ownDomain).copy(name = suggestion), tr("Noah user"), divider = index < suggestions.lastIndex, enabled = !model.busy) {
                    haptics.light(); model.setRecipient(suggestion); next()
                }
            }
            if (kind == null && suggestions.isEmpty() && !model.resolvingRecipient)
                Text(tr("No matches"), Modifier.padding(top = 12.dp), style = SendType.small, color = palette.muted)
        }
        model.matchingRoute?.takeIf { it.commentAllowed > 0 }?.let { route ->
            NoteField(model.comment, { model.comment = it }, route.commentAllowed, !model.busy)
            if (model.comment.isNotEmpty() && route.arkAddress != null)
                Text(tr("Adding a note will send this payment over Lightning."), Modifier.padding(top = 8.dp), style = SendType.small, color = palette.muted)
        }
        model.error?.let { ErrorLine(it, model) }
        model.controller.message?.let { ErrorLine(it) }
        if (typed) SendPill(if (model.busy || model.resolvingRecipient) tr("Preparing…") else tr("Next"),
            Modifier.fillMaxWidth().padding(top = 20.dp).testTag("send-continue"), enabled = !model.busy && !model.resolvingRecipient) { haptics.light(); next() }
    })
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(top = 22.dp, bottom = 8.dp).semantics { heading() }, style = SendType.section, color = LocalPalette.current.muted)
}

@Composable
private fun NoteField(value: String, onValue: (String) -> Unit, limit: Int, enabled: Boolean) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 52.dp).tonal(RoundedCornerShape(26.dp), palette.soft).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(value, onValue, Modifier.weight(1f).padding(vertical = 14.dp).testTag("send-note"), enabled = enabled,
            textStyle = SendType.body.copy(color = palette.foreground), cursorBrush = SolidColor(palette.orange), maxLines = 3,
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(tr("Add a note"), style = SendType.body, color = palette.muted); inner() } })
        Text("${value.length}/$limit", Modifier.padding(start = 8.dp), style = SendType.small, color = if (value.length > limit) palette.failure else palette.muted)
    }
}

// Method and balance

private fun railIcon(rail: SendRail) = when (rail) { SendRail.ark -> SendIcons.ark; SendRail.lightning -> HomeIcons.lightning; SendRail.onchain -> HomeIcons.chain }

/** A card's line: the SDK's fee estimate, labelled as one; without an estimate, just the timing (never a zero fee). */
@Composable
private fun railLine(rail: SendRail, fee: Long?, env: SendEnv): String = when (rail) {
    SendRail.ark -> when (fee) { null -> tr("Instantly"); 0L -> tr("Instant · free"); else -> tr("Instant · {fee} fee", formatted(fee, env)) }
    SendRail.lightning -> fee?.let { tr("Seconds · {fee} fee", formatted(it, env)) } ?: tr("In seconds")
    SendRail.onchain -> fee?.let { tr("About 20 min · {fee} fee", formatted(it, env)) } ?: tr("In about 20 minutes")
}

@Composable
private fun MethodStep(presentation: SendPresentation, env: SendEnv) {
    val model = presentation.model
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val scope = presentation.viewModelScope
    val rails = model.rails
    val availability = model.availability
    val best = SendFlow.recommend(rails, availability)
    StepPanes(nav = { Back { model.back() }; Title(); Spacer(Modifier.width(40.dp)) }, leadingCentred = false, trailingAt = Alignment.Top, leading = {
        Text(tr("How to send {amount}", formatted(model.sats, env)), Modifier.padding(top = 18.dp).semantics { heading() }, style = SendType.big, color = palette.foreground)
        Text(tr("Choose payment method"), Modifier.padding(top = 12.dp), style = SendType.small, color = palette.muted)
    }, trailing = {
        Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rails.forEach { rail ->
                val available = availability[rail.name] == true
                RailCard(railIcon(rail), tr(rail.label), if (available) railLine(rail, env.estimates[rail], env) else tr("Insufficient funds for this payment method."),
                    best = available && rail == best && rails.size > 1, enabled = available && !model.busy, selected = model.progress.railConfirmed && model.progress.selectedRail == rail,
                    modifier = Modifier.testTag("send-rail-${rail.name}")) {
                    haptics.selection()
                    model.progress = model.progress.copy(selectedRail = rail, railConfirmed = true)
                    scope.launch { model.continueStage() }
                }
            }
        }
        model.error?.let { ErrorLine(it, model) }
    })
}

@Composable
private fun SourceStep(presentation: SendPresentation, env: SendEnv) {
    val model = presentation.model
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val scope = presentation.viewModelScope
    StepPanes(nav = { Back { model.back() }; Title(); Spacer(Modifier.width(40.dp)) }, leadingCentred = false, trailingAt = Alignment.Top, leading = {
        Text(tr("Choose balance"), Modifier.padding(top = 18.dp).semantics { heading() }, style = SendType.big, color = palette.foreground)
        if (!model.isMax) Text(tr("Send {amount}", formatted(model.sats, env)), Modifier.padding(top = 12.dp), style = SendType.small, color = palette.muted)
    }, trailing = {
        Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SendSource.entries.forEach { source ->
                val balance = if (source == SendSource.offchain) model.arkBalance else model.onchainBalance
                RailCard(if (source == SendSource.offchain) SendIcons.ark else HomeIcons.chain, tr(source.label), tr("{amount} available", formatted(balance, env)),
                    best = false, enabled = source in model.sources && !model.busy, selected = model.source == source,
                    modifier = Modifier.testTag("send-source-${source.name}")) {
                    haptics.selection(); model.source = source
                    scope.launch { model.continueStage() }
                }
            }
        }
        model.error?.let { ErrorLine(it, model) }
    })
}

// Review

@Composable
private fun ReviewStep(presentation: SendPresentation, env: SendEnv) {
    val model = presentation.model
    val controller = model.controller
    val haptics = rememberHaptics()
    val scope = presentation.viewModelScope
    val quote = model.quote
    val payment = model.payment
    val ready = (model.showReview || controller.locked) && quote != null && payment != null
    val amount = quote?.amount ?: model.sats
    ReviewPanes(amount, SendWhoRules.of(model.destination, model.parsed.destinationType, env.ownDomain), model.progress.selectedRail, model.source,
        quote, payment, env, backEnabled = !model.busy, back = model::back, notes = {
            reviewNote(model, quote, env, presentation.typedBefore)?.let { Text(it, Modifier.padding(top = 12.dp), style = SendType.small, color = LocalPalette.current.muted) }
            model.error?.let { ErrorLine(it, model) }
            controller.message?.let { ErrorLine(it) }
        }) {
        if (ready) HoldToSend(formatted(amount, env), enabled = !controller.locked, sending = controller.locked, modifier = Modifier.padding(bottom = 6.dp)) {
            presentation.confirm(payment!!, quote!!)
        } else SendPill(if (model.busy) tr("Preparing…") else tr("Next"), Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("send-continue"), enabled = !model.busy) {
            haptics.light(); scope.launch { model.continueStage() }
        }
    }
}

/** The review's layout: "Send ₿X", who it goes to, the rows, then [notes] and [action] (Hold to send) at the foot. */
@Composable
private fun ReviewPanes(amount: Long, who: SendWho, rail: SendRail?, source: SendSource?, quote: SendQuote?, payment: SendPayment?, env: SendEnv,
                        backEnabled: Boolean, back: () -> Unit, notes: @Composable ColumnScope.() -> Unit, action: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    val tabletop = LocalPose.current is OnboardingPose.Tabletop
    val rows = @Composable { ReviewRows(source, quote, payment, rail, env, if (tabletop) 16.dp else 24.dp) }
    StepPanes(nav = { Back(backEnabled, back); Spacer(Modifier.weight(1f)) }, leadingCentred = false, trailingAt = Alignment.Bottom, leading = {
        Text(tr("Send {amount}", formatted(amount, env)), Modifier.padding(top = 18.dp).semantics { heading() }.testTag("send-review-title"), style = SendType.big, color = palette.foreground)
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WhoAvatar(who, 36.dp, SendType.initial.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)))
            Text(tr(who.name), style = SendType.body, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (tabletop) rows()
    }, trailing = {
        if (!tabletop) rows()
        notes()
    }, bottom = {
        Spacer(Modifier.height(24.dp))
        action()
    })
}

@Composable
private fun ReviewRows(source: SendSource?, quote: SendQuote?, payment: SendPayment?, rail: SendRail?, env: SendEnv, top: Dp) {
        // Every row puts its label above its value at large text sizes rather than cutting either off.
        Column(Modifier.padding(top = top)) {
            if (rail != null) ValueRow(tr("Via"), tr(rail.label), icon = railIcon(rail), wraps = true)
            if (rail != null) ValueRow(tr("Arrives"), arrives(rail, source, env.chainNeeded).let { (words, count) -> if (count == null) tr(words) else tr(words, count) }, wraps = true)
            if (quote != null) {
                ValueRow(tr(if (quote.approximate) "Estimated fee" else "Fee"), if (quote.fee == 0L && rail == SendRail.ark) tr("Free") else formatted(quote.fee, env), number = quote.fee != 0L || rail != SendRail.ark, wraps = true)
                payment?.source?.let { ValueRow(tr("From"), tr(it.label), wraps = true) }
                if (!payment?.comment.isNullOrEmpty()) ValueRow(tr("Note"), payment!!.comment, wraps = true)
                ValueRow(tr(if (!quote.vtxoIDs.isNullOrEmpty()) "Total (reviewed coins)" else if (quote.approximate) "Estimated total" else "Total"), formatted(quote.total, env), number = true, divider = false, wraps = true)
            }
        }
}

/** The Arrives row's words and count. An Ark-funded on-chain send waits for the round's confirmations (today's setting). */
internal fun arrives(rail: SendRail, source: SendSource?, needed: Long): Pair<String, Long?> = when (rail) {
    SendRail.ark -> "Instantly" to null
    SendRail.lightning -> "In seconds" to null
    SendRail.onchain -> when {
        source == SendSource.onchain -> "In about 20 minutes" to null
        needed <= 1 -> "After one confirmation" to null
        else -> "After {count} confirmations" to needed
    }
}

/** At most one line of fine print, today's notes first: checkpoint, invoice amount, sweep, estimate. [model] is null for a recurring approval (no typed amount). */
@Composable
private fun reviewNote(model: SendModel?, quote: SendQuote?, env: SendEnv, typedBefore: Long?): String? = when {
    quote?.checkpoint != null -> tr("An existing Lightning payment will be checked. No new payment will be created.")
    model?.parsed?.amount != null && typedBefore != null && typedBefore > 0 && typedBefore != quote?.amount ->
        tr("The invoice sets the amount to {amount}", formatted(model.parsed.amount!!.toLong(), env))
    !quote?.vtxoIDs.isNullOrEmpty() -> tr("The exact amount depends on the network fee when sent. The total is exactly the reviewed coins.")
    quote?.approximate == true -> tr("The fee and total are estimates based on two inputs and two outputs. The final fee and total may be higher if more coins are needed.")
    else -> null
}

// Sending and results

/** [reviewed]: the payment just confirmed, until the controller has saved its attempt. */
@Composable
private fun SendingStep(presentation: SendPresentation, env: SendEnv, actions: SendActions, reviewed: SendPayment? = presentation.model.payment) {
    val model = presentation.model
    val controller = model.controller
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val pose = LocalPose.current
    val attempt = controller.attempt
    val receipt = attempt?.alreadyPaid ?: attempt?.receipt
    val payment = attempt?.payment ?: reviewed
    val route = payment?.let(SendRoute::of) ?: presentation.motion?.route ?: SendRoute.ark
    // A live motion runs on the wall clock (so pose changes resume it); otherwise show the ending's last frame.
    val live = presentation.motion?.takeIf { it.route == route }
    val waitingForBlock = receipt?.chainStatus?.kind != com.noahwallet.services.wallet.ChainStatus.Kind.confirmed
    // Ticks each frame until the picture is still; a new milestone (a new motion) starts it again.
    val elapsed by produceState(live?.let { SystemClock.elapsedRealtime() - it.startedAt } ?: Long.MAX_VALUE / 4, live, waitingForBlock, reduceMotion) {
        if (live == null) return@produceState
        val quiet = SendTimeline.quietAt(live, reduceMotion, waitingForBlock)
        while (isActive) {
            withFrameMillis { }
            value = SystemClock.elapsedRealtime() - live.startedAt
            if (quiet != null && value > quiet) break
        }
    }
    val ending = SendEnding.of(controller.state, attempt, env.refund, live?.let { SendTimeline.stillPaying(it, elapsed, reduceMotion) } ?: false)
    val motion = live ?: SendMotion(route, 0, staticMilestones(ending, receipt?.broadcastRetryPending == true))
    val started = SendTimeline.started(motion, elapsed, reduceMotion)
    // Haptics fire once per moment, only while the motion is live.
    // Checked every 100 ms, so moments inside a phase (the signatures, the key) land on time.
    LaunchedEffect(elapsed / 100, live) {
        if (live == null) return@LaunchedEffect
        for (event in SendHaptics.between(started, presentation.hapticsAt, elapsed)) when (event) {
            SendHaptics.Kind.light -> haptics.light(); SendHaptics.Kind.success -> haptics.success(); SendHaptics.Kind.error -> haptics.error()
        }
        presentation.hapticsAt = elapsed
    }
    val amount = receipt?.takeIf { it.amount > 0 && !it.amountIsPending }?.amount ?: attempt?.existingLightning?.amount ?: attempt?.quote?.amount ?: payment?.amount ?: 0
    val who = payment?.let { SendWhoRules.of(it.destination, it.method, env.ownDomain) } ?: SendWho("")
    val confirmedAt = receipt?.chainStatus?.takeIf { it.kind == com.noahwallet.services.wallet.ChainStatus.Kind.confirmed }?.height
    val tip = env.tip?.let { tip -> confirmedAt?.let { maxOf(tip, it) } ?: tip } ?: confirmedAt
    val needed = if (route == SendRoute.chainArk) env.chainNeeded else 1
    val format = remember(env.locale) { java.text.NumberFormat.getIntegerInstance(env.locale) }
    val line = SendPhaseLines.of(motion, elapsed, reduceMotion, formatted(amount, env), tip, confirmedAt, needed) { format.format(it) }
    // A block confirming while you watch: light, and success once it has all its confirmations.
    var seenConfirmations by remember(attempt?.startedAt) { mutableStateOf<Long?>(null) }
    val confirmations = if (tip != null) BlockRow.confirmations(tip, confirmedAt) else 0
    LaunchedEffect(confirmations) {
        val before = seenConfirmations
        seenConfirmations = confirmations
        if (before != null && confirmations > before && live != null) { if (confirmations >= needed) haptics.success() else haptics.light() }
    }
    val resultShown = live == null || SendTimeline.resultShown(motion, elapsed, reduceMotion)
    val title = if (!resultShown || ending == null || ending == SendEnding.sending) tr("Sending {amount}", formatted(amount, env)) else resultTitle(ending, amount, receipt, env)
    val stageHeight: Dp = if (pose is OnboardingPose.Wide) 330.dp else if (pose is OnboardingPose.Tabletop) 200.dp else 270.dp
    val show = remember { Animatable(if (resultShown) 1f else 0f) }
    LaunchedEffect(resultShown) { if (resultShown) show.animateTo(1f, if (reduceMotion) tween(180) else NoahTokens.Motion.snappy()) }
    StepPanes(nav = { }, leadingCentred = false, trailingAt = Alignment.CenterVertically, leading = {
        val sent = resultShown && ending in setOf(SendEnding.arkSent, SendEnding.lightningSent, SendEnding.chainSent)
        SwapTitle(title, SendType.big, Modifier.padding(top = 6.dp).testTag(if (sent) "send-success" else "send-title"))
        if (who.name.isNotEmpty()) Text(tr("to {recipient}", tr(who.name)), Modifier.padding(top = 6.dp), style = SendType.body, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SendStage(motion, elapsed, who.copy(name = tr(who.name)), tip, confirmedAt, Modifier.fillMaxWidth().padding(top = 8.dp).height(stageHeight).testTag("send-stage"))
        PhaseText(line?.let { phase ->
            val text = if (phase.argument != null) tr(phase.source, phase.argument) else tr(phase.source)
            phase.suffix?.let { (n, total) -> text + " · " + tr("{n} of {total}", n.toString(), total.toString()) } ?: text
        }, line?.done == true, Modifier.padding(top = 4.dp))
    }, trailing = {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp).graphicsLayer { alpha = show.value.coerceIn(0f, 1f); if (!reduceMotion) translationY = 16 * (1 - show.value) * density }) {
            if (resultShown && ending != null && ending != SendEnding.sending) ResultArea(ending, controller, attempt, receipt, amount, who, confirmedAt, env, actions)
        }
    }, bottom = {
        Spacer(Modifier.height(16.dp))
        val doneShown = resultShown && ending in setOf(SendEnding.arkSent, SendEnding.lightningSent, SendEnding.chainSent, SendEnding.refunded,
            SendEnding.failed, SendEnding.notSent, SendEnding.alreadyPaid, SendEnding.pending)
        // Today's broadcast retry can't be acknowledged, and the phone hides the tab bar: Leave goes back with the
        // attempt and the send lock exactly as they are. A host without a tab bar offers the same on every unresolved result.
        val unresolved = resultShown && actions.leaveUnresolved && ending in setOf(SendEnding.unknown, SendEnding.unavailable)
        if (doneShown && receipt?.broadcastRetryPending == true || unresolved) SendPill(tr("Close"),
            Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("send-leave").graphicsLayer { alpha = show.value.coerceIn(0f, 1f) }) {
            haptics.light(); actions.leave()
        } else if (doneShown) SendPill(tr("Done"), Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("send-success-done").graphicsLayer { alpha = show.value.coerceIn(0f, 1f) },
            enabled = !controller.checking) {
            haptics.light(); if (ending == SendEnding.pending) actions.leave() else actions.done()
        }
    })
}

/**
 * The milestones that give an ending's final frame when no motion is running (opened later, or a fixture).
 * [broadcastPending]: today's broadcast retry, which shows no network progress until the broadcast succeeds.
 */
internal fun staticMilestones(ending: SendEnding?, broadcastPending: Boolean = false): Map<SendMilestone, Long> = buildMap {
    put(SendMilestone.submitted, 0L)
    when (ending) {
        SendEnding.arkSent, SendEnding.lightningSent, SendEnding.chainSent -> put(if (broadcastPending) SendMilestone.stopped else SendMilestone.sent, 0L)
        SendEnding.refunded -> put(SendMilestone.refunded, 0L)
        SendEnding.pending -> { put(SendMilestone.paying, 0L); put(SendMilestone.pending, 0L) }
        SendEnding.sending -> put(SendMilestone.paying, 0L)
        else -> put(SendMilestone.stopped, 0L)
    }
}

@Composable
private fun resultTitle(ending: SendEnding, amount: Long, receipt: SendReceipt?, env: SendEnv): String = when (ending) {
    SendEnding.sending -> tr("Sending {amount}", formatted(amount, env))
    SendEnding.arkSent, SendEnding.lightningSent, SendEnding.chainSent ->
        if (receipt?.amountIsPending == true || receipt?.broadcastRetryPending == true) tr("Payment submitted") else tr("Sent {amount}", formatted(amount, env))
    SendEnding.pending -> tr("Still sending {amount}", formatted(amount, env))
    SendEnding.refunded, SendEnding.failed -> tr("Payment failed")
    SendEnding.notSent -> tr("Payment not sent")
    SendEnding.alreadyPaid -> tr("Invoice already paid")
    SendEnding.unknown -> tr("Payment may have been sent")
    SendEnding.unavailable -> tr("Payment status unavailable")
}

/**
 * The result line and rows. Success, pending and refund use the design's words; every other state keeps
 * today's meaning and wording (status, messages, retries and the override audit), restyled.
 */
@Composable
internal fun ResultArea(ending: SendEnding, controller: SendController, attempt: SendAttempt?, receipt: SendReceipt?, amount: Long, who: SendWho,
                       confirmedAt: Long?, env: SendEnv, actions: SendActions) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var overrideStep by remember { mutableIntStateOf(0) }
    val payment = attempt?.payment
    val rail = payment?.rail()
    fun copy(label: String, value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value)); haptics.success()
    }
    val line: String? = when (ending) {
        SendEnding.arkSent -> if ((attempt?.quote?.fee ?: 0L) == 0L) tr("It arrived instantly. Ark sends are free.") else null
        SendEnding.lightningSent -> tr("Paid over Lightning. Their secret is your proof of payment.")
        SendEnding.chainSent -> when {
            receipt?.broadcastRetryPending == true -> tr(SendReceipt.broadcastPendingMessage)
            confirmedAt != null -> null // The block row's phase line names the block.
            else -> tr("It confirms in about 20 minutes. Activity shows when.")
        }
        SendEnding.pending -> tr("Lightning is still finding a route. You can leave this screen; Noah finishes it in the background.")
        // Only a person or address can be "reached"; an invoice just gets the refund line.
        SendEnding.refunded -> if (who.initial != null) tr("{amount} is back in your wallet. Lightning couldn’t reach {recipient}.", formatted(amount, env), who.name)
            else tr("{amount} is back in your wallet.", formatted(amount, env))
        // A retried invoice: bark's records can't say this attempt's coins came back, so no "back in your wallet".
        SendEnding.failed -> tr("That payment didn’t go through.")
        SendEnding.unknown -> tr(SendReceipt.uncertainMessage)
        else -> null
    }
    line?.let { Text(it, style = SendType.line, color = palette.muted, modifier = Modifier.testTag("send-result-line")) }
    Column(Modifier.padding(top = 12.dp)) {
        val rows = mutableListOf<@Composable (Boolean) -> Unit>()
        if (rail != null) rows += { divider -> ValueRow(tr("Via"), tr(rail.label), icon = railIcon(rail), divider = divider) }
        when (ending) {
            SendEnding.pending -> attempt?.existingLightning?.fee?.let { fee -> rows += { d -> ValueRow(tr("Fee"), tr("up to {amount}", formatted(fee, env)), number = true, divider = d) } }
                ?: attempt?.quote?.fee?.let { fee -> rows += { d -> ValueRow(tr("Fee"), tr("up to {amount}", formatted(fee, env)), number = true, divider = d) } }
            SendEnding.refunded -> rows += { d -> ValueRow(tr("Fee"), tr("None"), divider = d) }
            SendEnding.arkSent -> {
                val fee = receipt?.fee ?: attempt?.quote?.fee ?: 0
                rows += { d -> ValueRow(tr("Fee"), if (fee == 0L) tr("Free") else formatted(fee, env), number = fee != 0L, divider = d) }
            }
            SendEnding.failed, SendEnding.notSent, SendEnding.unavailable -> Unit
            else -> receipt?.let { r ->
                when {
                    r.fee != null -> rows += { d -> ValueRow(tr("Fee"), formatted(r.fee, env), number = true, divider = d) }
                    r.amountIsPending -> rows += { d -> ValueRow(tr("Fee"), tr("Actual fee pending"), divider = d) }
                    else -> attempt?.quote?.takeIf { it.approximate }?.let { quote ->
                        rows += { d -> ValueRow(tr("Estimated fee"), formatted(quote.fee, env), number = true, divider = d) }
                        rows += { d -> ValueRow(tr("Estimated total"), formatted(quote.total, env), number = true, divider = d) }
                    }
                }
                if (r.txid != null && ending != SendEnding.chainSent) rows += { d -> ValueRow(tr("Status"), tr(when (r.status) {
                    SendReceipt.Status.confirmed -> "Confirmed"; SendReceipt.Status.unlocated -> "Unable to locate"
                    SendReceipt.Status.conflicted -> "Conflicted"; SendReceipt.Status.submitted -> "Submitted" }), divider = d) }
                val proof = attempt?.let { SendSuccessDetails(r, it.payment, env.history, java.util.Date(it.startedAt)).proof }
                proof?.let { p -> rows += { d -> ValueRow(tr(if (p.label == "Payment preimage") "Proof of payment" else p.label),
                    SendSuccessDetails.truncate(p.value).let { if (it.length > 18) it.take(8) + "…" + it.takeLast(6) else it }, divider = d,
                    copy = { copy(p.label, p.value) }, modifier = Modifier.testTag("send-success-copy-proof")) } }
            }
        }
        attempt?.existingLightning?.takeIf { ending == SendEnding.unknown }?.let { existing -> rows += { d -> ValueRow(tr("Existing fee"), formatted(existing.fee, env), number = true, divider = d) } }
        rows.forEachIndexed { index, row -> row(index < rows.lastIndex) }
    }
    // Today's notes, in the order today's receipt showed them.
    val notes = buildList {
        receipt?.conflictDescription?.let { add(tr(it) to false) }
        if (ending == SendEnding.alreadyPaid || ending == SendEnding.unknown) attempt?.existingLightning?.let { add(tr("A payment of {amount} sats for this invoice is already in progress. Do not pay again.", java.text.NumberFormat.getIntegerInstance(env.locale).format(it.amount)) to false) }
        receipt?.chainStatus?.takeIf { it.kind == com.noahwallet.services.wallet.ChainStatus.Kind.notFound }?.let { add(tr(it.text) to false) }
        if (ending != SendEnding.chainSent && receipt?.broadcastRetryPending == true) add(tr(SendReceipt.broadcastPendingMessage) to false)
        controller.message?.let { add(tr(it) to true) }
    }
    notes.forEach { (text, failure) -> Text(text, Modifier.padding(top = 10.dp), style = SendType.small, color = if (failure) palette.failure else palette.muted) }
    // Today's actions: check status (never submits), the explorer link and the audited override.
    if (ending in setOf(SendEnding.unknown, SendEnding.unavailable, SendEnding.pending) || receipt?.txid != null) {
        val label = if (controller.checking) "Checking…" else if (controller.state == SendController.State.storageBlocked) "Retry"
            else if (controller.state == SendController.State.succeeded) "Refresh payment status" else "Check status"
        if (ending == SendEnding.chainSent) TextButton(actions.checkStatus, enabled = !controller.checking, modifier = Modifier.padding(top = 8.dp)) {
            Text(tr(label), style = SendType.line, color = palette.foreground)
        } else SendPill(tr(label), Modifier.fillMaxWidth().padding(top = 16.dp).testTag("send-check-status"), enabled = !controller.checking, outlined = true, onClick = actions.checkStatus)
    }
    receipt?.txid?.takeIf { it.isNotEmpty() && env.variant != "regtest" && ending == SendEnding.chainSent }?.let { txid ->
        TextButton({ context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(
            "https://mempool.space/${if (env.variant == "mainnet") "" else "signet/"}tx/$txid"))) }) { Text(tr("View in explorer"), style = SendType.line, color = palette.foreground) }
    }
    if (controller.canOverride) TextButton({ overrideStep = 1 }, Modifier.padding(top = 4.dp)) {
        Text(tr("Start a new payment after checking history"), style = SendType.line, color = palette.failure)
    }
    if (overrideStep > 0) WalletAlertDialog(onDismissRequest = { overrideStep = 0 }, title = { Text(tr(if (overrideStep == 1) "Have you checked?" else SendOverrideAudit.titleText)) },
        text = { Text(tr(if (overrideStep == 1) "Check your history and confirm with the recipient. This payment may already have been sent." else controller.overrideWarning)) },
        confirmButton = { TextButton(onClick = { if (overrideStep == 1) overrideStep = 2 else { scope.launch { controller.startNewPaymentAfterHistoryCheck(true); overrideStep = 0; actions.startOver() } } }) {
            Text(tr(if (overrideStep == 1) "I checked history" else SendOverrideAudit.confirmationText)) } },
        dismissButton = { TextButton(onClick = { overrideStep = 0 }) { Text(tr("Keep checking")) } })
}

/** The prototype's haptics during the motion, as moments after each phase's start. */
internal object SendHaptics {
    enum class Kind { light, success, error }
    private val moments = mapOf(
        SendPhase.split to listOf(0L to Kind.light),
        SendPhase.sign to listOf(420L to Kind.light, 900L to Kind.light),
        SendPhase.finale to listOf(0L to Kind.success),
        SendPhase.lock to listOf(0L to Kind.light),
        SendPhase.key to listOf(900L to Kind.light),
        SendPhase.fizzle to listOf(0L to Kind.error),
        SendPhase.back to listOf(0L to Kind.success),
        SendPhase.drop to listOf(0L to Kind.light),
    )
    /** Haptics whose moment falls after [since] and by [now] (both ms into the motion). */
    fun between(started: List<SendTimeline.Started>, since: Long, now: Long): List<Kind> = started.flatMap { phase ->
        moments[phase.phase].orEmpty().mapNotNull { (offset, kind) -> (phase.start + offset).takeIf { it > since && it <= now }?.let { it to kind } }
    }.sortedBy { it.first }.map { it.second }
}
