package com.noahwallet.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.localization.tr
import com.noahwallet.services.wallet.WalletBalance
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.onboarding.*
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawBehind

/** Everything Home shows, from live wallet state or a fixture. */
@Immutable
data class HomeUi(
    val state: HomeRules.State,
    val address: String?,
    val variant: String,
    val balance: WalletBalance?,
    val unit: BitcoinAmountUnit,
    val locale: Locale,
    val hidden: Boolean,
    val fiat: String?,
    val status: HomeRules.Status?,
    val nudge: HomeRules.Nudge?,
    val rows: List<HistoryTransaction>,
    val historyError: String?,
    val newRows: Set<String> = emptySet(),
    val retrying: Boolean = false,
    val now: Date = Date(),
    /** A round is active: funds in a round open it. */
    val roundsActive: Boolean = false,
    /** The top bar's [ActivityGlyph]. */
    val activity: String? = null,
    val backedUpAt: Long? = null,
) {
    val domain get() = address?.substringAfter('@', "")?.takeIf { it.isNotEmpty() }
    fun amount(sats: ULong) = if (hidden) "••••" else BitcoinAmount.format(sats.toLong(), unit, locale)
}

/** Home's in-memory presentation state for this process: never persisted, kept across pose changes by AppModel. */
class HomeMemory {
    /** Elapsed realtime of Home's first appearance; drives the one entrance. */
    var enteredAt: Long? = null
    /** Row IDs when Home was last hidden; incoming rows outside it are marked new. */
    var seenRows: Set<String>? = null
    /** Decides when the first payment plays; see [FirstPaymentWatch]. */
    val firstPaymentWatch = FirstPaymentWatch()
    var notices by mutableStateOf<List<String>>(emptyList())
    /** The last renew check, so returning to Home shows it at once and can tell when it resolved. */
    var renew by mutableStateOf<HomeRules.Renew?>(null)
    var noticesTaken = false
    var firstPayment by mutableStateOf<FirstPayment?>(null)
    var recentRevealAt by mutableStateOf<Long?>(null)
    var backedUpSeen: Boolean? = null
    var emailSeen: Boolean? = null
    var toastSeen: String? = null

    /** A running first-payment sequence; timed from [startedAt] so a pose change resumes it in place. */
    data class FirstPayment(val startedAt: Long, val sats: Long, val from: String?)
}

class HomeToasts {
    var current by mutableStateOf<HomeToastMessage?>(null)
    fun show(text: String, millis: Long = 1_700, tag: String? = null) { current = HomeToastMessage(text, millis, tag = tag) }
    /** New text for the toast tagged [tag] while it is the latest; it stays for the rest of its time. */
    fun retext(tag: String, text: String) { current?.takeIf { it.tag == tag && it.text != text }?.let { current = it.copy(text = text) } }
}

class HomeActions(
    val scan: () -> Unit = {}, val settings: () -> Unit = {},
    /** The suspended state's "Open Settings": Advanced, where Suspend wallet is. */
    val suspended: () -> Unit = settings,
    val toggleHidden: () -> Unit = {}, val seeAll: () -> Unit = {}, val openRow: (String) -> Unit = {}, val profile: () -> Unit = {},
    val nudge: (HomeRules.Nudge) -> Unit = {}, val dismiss: (HomeRules.Nudge) -> Unit = {}, val rounds: () -> Unit = {},
    val refresh: () -> Unit = {},
)

private val compactTop = 60.dp

/**
 * Home's one layout: compact (one column), wide (leading: you, balance, status and nudge; trailing: the
 * card or the activity list) and tabletop (top half: the same leading content; bottom half: card or list).
 * Must sit inside [OnboardingFrame], which resolves the pose from the full window and the fold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(ui: HomeUi, actions: HomeActions, memory: HomeMemory, toasts: HomeToasts, refreshing: Boolean) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val pose = LocalPose.current
    var sheet by rememberSaveable { mutableStateOf(false) }
    val homeScroll = rememberHomeScroll()
    if (memory.enteredAt == null) memory.enteredAt = SystemClock.elapsedRealtime()
    val entered = rememberElapsed(memory.enteredAt, 1_600)
    val payment = memory.firstPayment
    val paymentEnd = Receipt.end(reduceMotion)
    val paymentElapsed = rememberElapsed(payment?.startedAt, paymentEnd)
    LaunchedEffect(payment, paymentElapsed >= paymentEnd) {
        if (payment != null && paymentElapsed >= paymentEnd) {
            memory.firstPayment = null
            memory.recentRevealAt = SystemClock.elapsedRealtime()
            haptics.light()
        }
    }
    val revealed = rememberElapsed(memory.recentRevealAt, 1_000)
    // While the first payment plays, Home is still the Fresh card turning into the receipt.
    val state = if (payment != null) HomeRules.State.fresh else ui.state
    val total = ui.balance?.totalSats
    val shownTotal = if (payment != null && paymentElapsed < 300) 0uL else total
    val copied = tr("Copied")
    val copy = { address: String ->
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Lightning address", address))
        haptics.success()
        toasts.show(copied)
    }
    val share = { address: String ->
        haptics.light()
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address), null))
    }
    val openSheet = { haptics.light(); sheet = true }
    val toggle = { haptics.medium(); actions.toggleHidden() }

    // The prototype's entrance: top bar, balance, fiat, status (when shown), nudge, card or Recent; 40 ms apart.
    val after = if (ui.status != null) 40L else 0L
    @Composable fun Leading(tuck: Float) {
        if (state == HomeRules.State.suspended) { Box(Modifier.rise(entered, 100, 14f, reduceMotion)) { Suspended(actions.suspended) }; return }
        BalanceBlock(ui, shownTotal, tuck, entered, onTap = openSheet, onToggle = toggle)
        Box(Modifier.padding(top = 10.dp).height(20.dp).rise(entered, 140, 14f, reduceMotion)) {
            ui.fiat?.let { Text(it, style = HomeType.line, color = palette.muted, maxLines = 1) }
        }
        Box(Modifier.rise(entered, 180, 14f, reduceMotion)) {
            StatusBlock(ui, onBalance = openSheet, onRounds = actions.rounds)
        }
        Box(Modifier.rise(entered, 180 + after, 14f, reduceMotion)) {
            NudgeHost(ui, onAction = { nudge ->
                if (nudge is HomeRules.Nudge.Renewal) haptics.medium() else haptics.light()
                actions.nudge(nudge)
            }, onDismiss = { haptics.light(); actions.dismiss(it) })
        }
    }
    @Composable fun Primary(wide: Boolean) {
        when (state) {
            HomeRules.State.fresh -> GetPaidCard(ui, payment, paymentElapsed, wide, onCopy = copy, onShare = share, onProfile = actions.profile,
                modifier = Modifier.onGloballyPositioned { homeScroll.measureCard(it, wide) }.rise(entered, 220 + after, 14f, reduceMotion))
            HomeRules.State.everyday -> if (!wide) Box(Modifier.rise(entered, 220 + after, 14f, reduceMotion).rise(revealed, 0, 12f, reduceMotion)) {
                Recent(ui, entered, memory, actions, onFirstRow = homeScroll::measureRecent)
            }
            else -> {}
        }
    }

    val pullState = rememberPullToRefreshState()
    // Scroll positions live above the pose switch, so folding, unfolding and bending keep them; the saved anchor
    // carries the place between one column and two panes and across recreation. Whenever Home is laid out (and after
    // a switch) it puts the anchor in place, but only once history is known, so a slow or failed load can't drop it
    // to the top or have it overwritten; only then does the anchor follow the reader's scrolling.
    val paned = pose !is OnboardingPose.Compact
    val items = remember(ui.rows, ui.domain, ui.now) {
        ActivityItems(HomeDays.grouped(ui.rows.map { HomeRow.of(it, ui.domain) }, { it.date }, ui.now).map { (_, rows) -> rows.map { it.id } }, ui.rows.isEmpty())
    }
    val recent = remember(ui.rows) { ui.rows.take(Recent.rows).map { it.id } }
    val currentItems by rememberUpdatedState(items)
    val currentRecent by rememberUpdatedState(recent)
    val currentState by rememberUpdatedState(state)
    val historyKnown = ui.rows.isNotEmpty() || (ui.state != HomeRules.State.loading && ui.historyError == null)
    LaunchedEffect(paned, historyKnown) {
        if (!historyKnown) return@LaunchedEffect
        homeScroll.place(paned, currentItems, currentRecent)
        snapshotFlow { if (paned) homeScroll.panesAnchor(currentState, currentItems) else homeScroll.compactAnchor(currentState, currentItems, currentRecent) }
            .collect { homeScroll.anchor = it }
    }
    when (pose) {
        OnboardingPose.Compact -> {
            val scroll = homeScroll.compact
            val tuck = ((with(androidx.compose.ui.platform.LocalDensity.current) { scroll.value.toDp().value } - 4f) / 52f).coerceIn(0f, 1f)
            PullToRefreshBox(refreshing, actions.refresh, state = pullState, indicator = {
                PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = compactTop),
                    containerColor = palette.sheet, color = palette.foreground)
            }) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).onGloballyPositioned { homeScroll.content = it }.testTag("home-scroll"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = compactTop, bottom = 32.dp)) {
                        Leading(tuck)
                        if (state != HomeRules.State.suspended) Primary(false)
                    }
                }
                ScrollEdge(scroll.value > 2)
                TopBar(ui, actions, state, tuck, Modifier.padding(horizontal = 16.dp), entered)
                HomeToast(toasts.current, Modifier.align(Alignment.TopCenter).padding(top = 76.dp))
            }
        }
        is OnboardingPose.Wide -> Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(pose.start).fillMaxHeight()) {
                PullToRefreshBox(refreshing, actions.refresh, state = pullState, indicator = {
                    PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = compactTop),
                        containerColor = palette.sheet, color = palette.foreground)
                }) {
                    Pane(homeScroll.leading, top = 70.dp, horizontal = 24.dp) {
                        Leading(0f)
                    }
                    TopBar(ui, actions, state, 0f, Modifier.padding(top = 10.dp).padding(horizontal = 20.dp), entered)
                    HomeToast(toasts.current, Modifier.align(Alignment.TopCenter).padding(top = 22.dp))
                }
            }
            Spacer(Modifier.width(pose.end - pose.start))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                TrailingPane(ui, state, entered, revealed, memory, actions, homeScroll) { Primary(true) }
            }
        }
        is OnboardingPose.Tabletop -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.height(pose.top).fillMaxWidth().clipToBounds()) {
                PullToRefreshBox(refreshing, actions.refresh, state = pullState, indicator = {
                    PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = compactTop),
                        containerColor = palette.sheet, color = palette.foreground)
                }) {
                    Pane(homeScroll.leading, top = compactTop, horizontal = 24.dp) {
                        Leading(0f)
                    }
                    // Lined up with the content column below it (the bar sits 8 dp outside the content's edges).
                    TopBar(ui, actions, state, 0f, Modifier.widthIn(max = OnboardingPose.paneContent.dp).padding(horizontal = 16.dp), entered)
                    HomeToast(toasts.current, Modifier.align(Alignment.TopCenter).padding(top = 76.dp))
                }
            }
            Spacer(Modifier.height(pose.bottom - pose.top))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.TopCenter) {
                TrailingPane(ui, state, entered, revealed, memory, actions, homeScroll) { Primary(true) }
            }
        }
    }
    if (sheet) BalanceSheet(ui) { sheet = false }
}

@Composable
private fun Pane(state: ScrollState, top: Dp, horizontal: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(state).testTag("home-leading"), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxWidth().padding(start = horizontal, end = horizontal, top = top, bottom = 32.dp), content = content)
    }
}

/** Content fades under the top bar once it scrolls (compact only). */
@Composable
private fun BoxScope.ScrollEdge(visible: Boolean) {
    val palette = LocalPalette.current
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f, androidx.compose.animation.core.tween(200), label = "edge")
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(compactTop).graphicsLayer { this.alpha = alpha }
        .background(Brush.verticalGradient(0f to palette.background, .62f to palette.background, 1f to palette.background.copy(alpha = 0f))))
}

@Composable
private fun BoxScope.TopBar(ui: HomeUi, actions: HomeActions, state: HomeRules.State, tuck: Float, modifier: Modifier, entered: Long) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    Box(modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 4.dp).height(44.dp).rise(entered, 60, 14f, reduceMotion)) {
        val suspended = state == HomeRules.State.suspended
        // The buttons keep their size; the chip gets the rest (its name ellipsizes) and the tucked balance is
        // centred in the gap between them, so a long name never pushes the buttons out of a narrow pane.
        androidx.compose.ui.layout.Layout(contents = listOf(
            { if (!suspended) NameChip(ui.address, ui.variant) { haptics.light(); actions.profile() } },
            {
                if (!suspended) {
                    val shown = tuck > .7f
                    val alpha by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(if (reduceMotion) 180 else 250), label = "mini")
                    val rise by androidx.compose.animation.core.animateFloatAsState(if (shown || reduceMotion) 0f else 6f, androidx.compose.animation.core.tween(300), label = "mini-rise")
                    Text(if (ui.hidden) BitcoinAmount.format(0, ui.unit, ui.locale).replace("0", "••••") else ui.balance?.let { BitcoinAmount.format(it.totalSats.toLong(), ui.unit, ui.locale) } ?: "",
                        Modifier.padding(horizontal = 8.dp).graphicsLayer { this.alpha = alpha; translationY = rise * density }.clearAndSetSemantics { },
                        style = HomeType.mini, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!suspended) ActivityGlyph(ui.activity, ui.backedUpAt)
                    if (!suspended) CircleButton(HomeIcons.scan, tr("Scan")) { haptics.light(); actions.scan() }
                    val entry = com.noahwallet.ui.settings.LocalSettingsEntry.current
                    CircleButton(HomeIcons.settings, tr("Settings"), Modifier.testTag("home-settings-button").then(if (entry == null) Modifier
                        else Modifier.onGloballyPositioned { entry.gear = it.boundsInWindow() })) { haptics.light(); actions.settings() }
                }
            },
        ), Modifier.fillMaxSize()) { (chip, mini, buttons), constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val end = buttons.first().measure(loose)
            val gapEnd = constraints.maxWidth - end.width
            val start = chip.firstOrNull()?.measure(loose.copy(maxWidth = (gapEnd - 8.dp.roundToPx()).coerceAtLeast(0)))
            val gapStart = start?.width ?: 0
            val middle = mini.firstOrNull()?.measure(loose.copy(maxWidth = (gapEnd - gapStart).coerceAtLeast(0)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                fun centreY(height: Int) = (constraints.maxHeight - height) / 2
                start?.place(0, centreY(start.height))
                end.place(gapEnd, centreY(end.height))
                middle?.place(gapStart + (gapEnd - gapStart - middle.width) / 2, centreY(middle.height))
            }
        }
    }
}

/** 50 sp Space Grotesk; tap opens the balance sheet, long-press (or the accessibility action) hides it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BalanceBlock(ui: HomeUi, total: ULong?, tuck: Float, entered: Long, onTap: () -> Unit, onToggle: () -> Unit) {
    if (ui.state == HomeRules.State.suspended) return
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    var hidden by remember { mutableStateOf(ui.hidden) }
    val morph = remember { androidx.compose.animation.core.Animatable(1f) }
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(ui.hidden) {
        if (hidden == ui.hidden) return@LaunchedEffect
        if (reduceMotion) { morph.animateTo(0f, androidx.compose.animation.core.tween(90)); hidden = ui.hidden; morph.animateTo(1f, androidx.compose.animation.core.tween(90)); return@LaunchedEffect }
        kotlinx.coroutines.coroutineScope {
            launch { morph.animateTo(0f, androidx.compose.animation.core.tween(140)) }
            launch { scale.animateTo(.96f, androidx.compose.animation.core.tween(140)) }
        }
        hidden = ui.hidden
        scale.snapTo(1.04f)
        kotlinx.coroutines.coroutineScope {
            launch { morph.animateTo(1f, NoahTokens.Motion.bouncy()) }
            launch { scale.animateTo(1f, NoahTokens.Motion.bouncy()) }
        }
    }
    val text = when {
        hidden -> BitcoinAmount.format(0, ui.unit, ui.locale).replace("0", "••••••")
        total != null -> BitcoinAmount.format(total.toLong(), ui.unit, ui.locale)
        else -> null
    }
    val label = tr("Balance")
    val toggleLabel = tr(if (ui.hidden) "Show wallet balance" else "Hide wallet balance")
    val description = text ?: tr("Loading…")
    Box(Modifier.padding(top = 8.dp).rise(entered, 100, 14f, reduceMotion)
        .graphicsLayer {
            alpha = (1 - tuck) * morph.value.coerceIn(0f, 1f)
            translationY = -6 * tuck * density
            val s = (1 - .06f * tuck) * scale.value
            scaleX = s; scaleY = s
            transformOrigin = TransformOrigin(0f, 1f)
        }
        .then(if (Build.VERSION.SDK_INT >= 31 && morph.value < .99f && !reduceMotion) Modifier.blur((4 * (1 - morph.value.coerceIn(0f, 1f))).dp, BlurredEdgeTreatment.Unbounded) else Modifier)
        .combinedClickable(interactionSource = null, indication = null, hapticFeedbackEnabled = false, onClick = onTap, onLongClick = onToggle)
        .clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick(label) { onTap(); true }
            onLongClick(toggleLabel) { onToggle(); true }
            customActions = listOf(CustomAccessibilityAction(toggleLabel) { onToggle(); true })
            testTag = "home-balance"
        }) {
        BalanceFigures(text, hidden, rollFromZero = entered < 1_600, startDelay = if (entered < 1_600) 160 else 0)
    }
}

/**
 * The balance's figures: Space Grotesk 50 sp, or smaller when the whole formatted amount (₿, its gap and every
 * character's box) wouldn't fit the width available, as in a narrow book pane, so no digit column is squeezed
 * out. Each candidate size is measured as laid out, box by box, because Android scales large text less than small
 * text at big font sizes, so a smaller sp doesn't shrink the figures in proportion. The block keeps the 50 sp line
 * box either way, so neither scaling nor hiding moves what follows.
 */
@Composable
internal fun BalanceFigures(text: String?, hidden: Boolean, rollFromZero: Boolean, startDelay: Long, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    // BIP-177's ₿ sits 3 dp from the digits at this size (prototype `.bal .b`), not a full no-break space.
    val symbol = text?.startsWith("₿\u00a0") == true
    val digits = if (symbol) text!!.removePrefix("₿\u00a0") else text
    val base = HomeType.balance
    val baseLine = with(density) { base.lineHeight.toDp() }
    BoxWithConstraints(modifier.heightIn(min = baseLine), contentAlignment = Alignment.BottomStart) {
        val available = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val scale = remember(digits, symbol, hidden, density, available, bounded) {
            // The row's width in px at a scale: ₿ and its gap, then each character's own box (a digit's column is
            // its measured width, rounded through dp as RollingText lays it out), or the hidden dots as one line.
            fun needed(scale: Float): Int = with(density) {
                val style = base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale)
                val head = if (symbol || text == null) measurer.measure("₿", style).size.width + (3.dp * scale).roundToPx() else 0
                head + when {
                    digits == null -> 0
                    hidden -> measurer.measure(digits, style.copy(letterSpacing = .06.em)).size.width
                    else -> digits.sumOf { char ->
                        val width = measurer.measure(char.toString(), style).size.width
                        if (char.isDigit()) width.toDp().roundToPx() else width
                    }
                }
            }
            var scale = 1f
            if (bounded) repeat(24) {
                val width = needed(scale)
                if (width <= available || scale <= .1f) return@remember scale
                // Shrinking the size in proportion undershoots when the font scales nonlinearly; measure again.
                scale = (scale * minOf(available.toFloat() / width, .98f)).coerceAtLeast(.1f)
            }
            scale
        }
        val style = base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale)
        val line = with(density) { style.lineHeight.toDp() }
        Row(verticalAlignment = Alignment.Bottom) {
            if (symbol || text == null) Text("₿", Modifier.padding(end = 3.dp * scale).height(line).testTag("home-balance-char"), style = style,
                color = if (text == null) palette.hairline else palette.foreground, overflow = TextOverflow.Visible)
            if (digits != null) {
                // The same line box as the digits, so hiding never moves what's below.
                if (hidden) Text(digits, Modifier.height(line).testTag("home-balance-char"), style = style.copy(letterSpacing = .06.em),
                    color = palette.foreground, maxLines = 1, overflow = TextOverflow.Visible)
                else RollingText(digits, style, palette.foreground, rollFromZero = rollFromZero, startDelay = startDelay)
            }
        }
    }
}

/** The status line under the fiat value. Coming or going it expands or shrinks (snappy), so what's below glides rather than
 *  jumps; under Reduce Motion it comes and goes at once. A new line in its place just replaces the text. */
@Composable
private fun StatusBlock(ui: HomeUi, onBalance: () -> Unit, onRounds: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    // The last line, so one shrinking away keeps its text.
    var last by remember { mutableStateOf(ui.status) }
    if (ui.status != null) last = ui.status
    androidx.compose.animation.AnimatedVisibility(ui.status != null,
        enter = if (reduceMotion) androidx.compose.animation.EnterTransition.None
            else androidx.compose.animation.expandVertically(NoahTokens.Motion.snappy(), Alignment.Top) +
                androidx.compose.animation.fadeIn(NoahTokens.Motion.snappy()),
        exit = if (reduceMotion) androidx.compose.animation.ExitTransition.None
            else androidx.compose.animation.shrinkVertically(NoahTokens.Motion.snappy(), Alignment.Top) +
                androidx.compose.animation.fadeOut(NoahTokens.Motion.snappy())) {
        val status = last ?: return@AnimatedVisibility
        val (text, failed, dot) = when (status) {
            is HomeRules.Status.Error -> Triple(tr(status.message), true, false)
            is HomeRules.Status.Arriving -> Triple(tr("{amount} arriving", if (ui.hidden) "••••" else "+" + ui.amount(status.sats)), false, true)
            is HomeRules.Status.InRound -> Triple(tr(ui.balance!!.pendingRoundMessage(ui.amount(status.sats))!!), false, true)
            is HomeRules.Status.Rounds -> Triple(tr(if (status.count == 1) "Round in progress" else "Rounds in progress"), false, true)
        }
        val onClick = when (status.tap(ui.roundsActive)) {
            HomeRules.StatusTap.Balance -> onBalance
            HomeRules.StatusTap.Rounds -> onRounds
            null -> null
        }
        StatusLine(text, failed = failed, dot = dot, onClick = onClick, modifier = Modifier.padding(top = 8.dp))
    }
}

/** One nudge at a time: resolving or dismissing folds it away (soft); the next slides in (snappy). */
@Composable
private fun NudgeHost(ui: HomeUi, onAction: (HomeRules.Nudge) -> Unit, onDismiss: (HomeRules.Nudge) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    var shown by remember { mutableStateOf(ui.nudge) }
    val fold = remember { androidx.compose.animation.core.Animatable(0f) }
    val slide = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(ui.nudge?.key) {
        // Back to the nudge still on screen (it changed and changed back mid-fold): unfold it in place.
        if (shown?.key == ui.nudge?.key) {
            kotlinx.coroutines.coroutineScope {
                launch { if (fold.value > 0f) fold.animateTo(0f, NoahTokens.Motion.snappy()) }
                launch { if (slide.value < 1f) slide.animateTo(1f, NoahTokens.Motion.snappy()) }
            }
            return@LaunchedEffect
        }
        if (shown != null) {
            if (reduceMotion) fold.animateTo(1f, androidx.compose.animation.core.tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS))
            else fold.animateTo(1f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.Easing { SpringCurve.soft.over(it) }))
        }
        shown = ui.nudge
        fold.snapTo(0f)
        if (shown != null) {
            slide.snapTo(0f)
            if (reduceMotion) slide.animateTo(1f, androidx.compose.animation.core.tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS))
            else slide.animateTo(1f, NoahTokens.Motion.snappy())
        }
    }
    // The same nudge keeps its place and updates in place (for example a new renew amount).
    val nudge = if (shown?.key == ui.nudge?.key) ui.nudge else shown
    if (nudge == null) return
    val f = fold.value
    // Clipped while it folds, like the prototype's overflow: hidden, so it never paints over what follows.
    Box(Modifier.clipToBounds().collapse(f).padding(top = (24 * (1 - f)).dp).graphicsLayer {
        alpha = (1 - f) * slide.value.coerceIn(0f, 1f)
        if (!reduceMotion) { translationX = 30 * f * density; translationY = 14 * (1 - slide.value) * density }
    }) {
        val (icon, title, subtitle) = when (nudge) {
            is HomeRules.Nudge.Notice -> Triple(HomeIcons.notice, tr(nudge.message), null)
            is HomeRules.Nudge.Renewal -> Triple(HomeIcons.renew, renewTitle(nudge.renew, ui.unit, ui.locale, ui.hidden), renewSubtitle(nudge.renew))
            is HomeRules.Nudge.BackupFailed -> Triple(HomeIcons.backupFailed, tr(nudge.banner.title), tr(nudge.banner.message))
            HomeRules.Nudge.BackUp -> Triple(HomeIcons.backup, tr("Back up your wallet"), tr("Save your recovery phrase"))
            HomeRules.Nudge.Email -> Triple(HomeIcons.email, tr("Add your email"), tr("For urgent wallet alerts"))
            HomeRules.Nudge.Update -> Triple(HomeIcons.update, tr("Update available"), tr("Get the latest fixes"))
        }
        val action = when (nudge) {
            is HomeRules.Nudge.Notice -> null
            is HomeRules.Nudge.Renewal -> tr("Renew")
            is HomeRules.Nudge.BackupFailed -> tr(if (ui.retrying) "Working" else "Retry")
            HomeRules.Nudge.BackUp -> tr("Back up")
            HomeRules.Nudge.Email -> tr("Add")
            HomeRules.Nudge.Update -> tr("Update")
        }
        val dismiss = when (nudge) {
            is HomeRules.Nudge.Notice -> tr("Close")
            HomeRules.Nudge.Email -> tr("Dismiss emergency email prompt")
            else -> null
        }
        NudgeCard(icon, title, subtitle, action, dismiss, enabled = !(nudge is HomeRules.Nudge.BackupFailed && ui.retrying),
            onAction = { onAction(nudge) }, onDismiss = { onDismiss(nudge) },
            modifier = Modifier.testTag("home-nudge-${nudge.key.substringBefore(':')}"))
    }
}

/** Today's suspended state, restyled: no balance, nudge or list. */
@Composable
private fun Suspended(settings: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().tonal(RoundedCornerShape(22.dp), palette.soft).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Wallet Suspended"), style = NoahTokens.Typography.sheetTitle, color = palette.foreground)
        Text(tr("All wallet operations are disabled. Resume your wallet in Settings."), style = HomeType.line, color = palette.muted)
        Spacer(Modifier.height(4.dp))
        Pill(tr("Open Settings"), height = 44.dp, onClick = settings)
    }
}

/** Wide and tabletop's second pane: the get-paid card centred, or the full activity list grouped by day. */
@Composable
private fun TrailingPane(ui: HomeUi, state: HomeRules.State, entered: Long, revealed: Long, memory: HomeMemory, actions: HomeActions,
                         scroll: HomeScroll, card: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    when (state) {
        HomeRules.State.fresh -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val height = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(scroll.card).onGloballyPositioned { scroll.paneContent = it }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min = height).widthIn(max = OnboardingPose.paneContent.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
                    contentAlignment = Alignment.Center) { card() }
            }
        }
        HomeRules.State.everyday -> Box(Modifier.fillMaxSize().rise(revealed, 0, 12f, reduceMotion), contentAlignment = Alignment.TopCenter) {
            ActivityList(ui, entered, memory, actions, scroll.list)
        }
        else -> {}
    }
}

@Composable
private fun ActivityList(ui: HomeUi, entered: Long, memory: HomeMemory, actions: HomeActions, state: androidx.compose.foundation.lazy.LazyListState) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val rows = remember(ui.rows, ui.domain) { ui.rows.map { HomeRow.of(it, ui.domain) } }
    val groups = remember(rows, ui.now) { HomeDays.grouped(rows, { it.date }, ui.now) }
    LazyColumn(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxSize().testTag("home-activity"), state,
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 28.dp)) {
        item { Text(tr("Activity"), Modifier.padding(bottom = 8.dp).semantics { heading() }, style = HomeType.listTitle, color = palette.foreground) }
        if (rows.isEmpty()) item { Text(tr(ui.historyError ?: "No activity yet"), style = HomeType.line, color = palette.muted) }
        var index = 0
        for ((day, items) in groups) {
            item(key = "day-$day") {
                Text(when (day) { HomeDays.Day.Today -> tr("Today"); HomeDays.Day.Yesterday -> tr("Yesterday"); is HomeDays.Day.On -> dayLabel(day.date, ui.locale, ui.now) },
                    Modifier.padding(top = 14.dp), style = HomeType.header, color = palette.muted)
            }
            itemsIndexed(items, key = { _, row -> row.id }) { i, row ->
                val order = index + i
                Box(Modifier.rise(entered, 300L + order * 40, 10f, reduceMotion)) {
                    ActivityRow(row, ui.hidden, ui.unit, ui.locale, ui.now, new = row.id in ui.newRows, pop = memory.recentRevealAt != null,
                        divider = i < items.lastIndex) { haptics.light(); actions.openRow(row.id) }
                }
            }
            index += items.size
        }
    }
}

/** Compact Everyday: the five latest rows; "See all" opens Activity. */
@Composable
private fun Recent(ui: HomeUi, entered: Long, memory: HomeMemory, actions: HomeActions, onFirstRow: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val rows = remember(ui.rows, ui.domain) { ui.rows.take(5).map { HomeRow.of(it, ui.domain) } }
    Column(Modifier.padding(top = 30.dp).fillMaxWidth().testTag("home-recent")) {
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Text(tr("Recent"), Modifier.weight(1f).alignByBaseline().semantics { heading() }, style = HomeType.header, color = palette.muted)
            Text(tr("See all"), Modifier.alignByBaseline().clickable(role = Role.Button) { haptics.selection(); actions.seeAll() },
                style = HomeType.strong, color = palette.foreground)
        }
        if (rows.isEmpty()) Text(tr(ui.historyError ?: "No activity yet"), Modifier.padding(top = 12.dp), style = HomeType.line, color = palette.muted)
        rows.forEachIndexed { i, row ->
            // Measured before the rise's layer, so the entrance offset doesn't count.
            Box(Modifier.then(if (i == 0) Modifier.onGloballyPositioned(onFirstRow) else Modifier).rise(entered, 300L + i * 40, 10f, reduceMotion)) {
                ActivityRow(row, ui.hidden, ui.unit, ui.locale, ui.now, new = row.id in ui.newRows, pop = memory.recentRevealAt != null,
                    divider = i < rows.lastIndex) { haptics.light(); actions.openRow(row.id) }
            }
        }
    }
}

/**
 * Fresh's get-paid card: always white with the styled QR of your Lightning address. Tap copies; Copy and
 * Share sit below. During the first payment it becomes the receipt, then folds away (prototype `firstPayment`).
 */
@Composable
private fun GetPaidCard(ui: HomeUi, payment: HomeMemory.FirstPayment?, elapsed: Long, wide: Boolean, onCopy: (String) -> Unit,
                        onShare: (String) -> Unit, onProfile: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val address = ui.address
    val t = if (payment == null) 0L else elapsed
    val playing = payment != null
    val frame = if (playing) Receipt.at(t, reduceMotion) else Receipt.idle
    val qrOut = frame.qrOut
    val gotIn = frame.gotIn
    val ring = frame.ring
    val check = frame.check
    val amountIn = frame.amountIn
    val fromIn = frame.fromIn
    val buttonsOut = frame.buttonsOut
    val folding = frame.folding
    // Folding collapses and shrinks the card; under Reduce Motion it only fades.
    val collapsing = if (frame.collapse) folding else 0f
    val cardHeight = remember { IntArray(1) }
    val qrSide = if (wide) 228.dp else 212.dp
    Column(modifier.fillMaxWidth().padding(top = if (wide) 0.dp else 24.dp)
        .then(if (collapsing > 0f) Modifier.clipToBounds() else Modifier)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            if (cardHeight[0] == 0 || !playing) cardHeight[0] = placeable.height
            // The prototype folds max-height from height + 80 px to 0, so the card shrinks late in the fold.
            val max = ((cardHeight[0] + 80 * density) * (1 - collapsing)).toInt()
            layout(placeable.width, minOf(placeable.height, max.coerceAtLeast(0))) { placeable.place(0, 0) }
        }
        .graphicsLayer { alpha = 1 - folding.coerceIn(0f, 1f); val sc = 1 - .04f * collapsing; scaleX = sc; scaleY = sc }
        .testTag("home-get-paid")) {
        val ink = NoahTokens.Light.qrInk
        val copyLabel = tr("Copy Lightning address")
        Box(Modifier.fillMaxWidth()
            .shadow(if (palette === NoahTokens.Dark) 0.dp else 14.dp, RoundedCornerShape(30.dp), ambientColor = Color.Black.copy(alpha = .08f), spotColor = Color.Black.copy(alpha = .08f))
            .background(NoahTokens.Light.qrCard, RoundedCornerShape(30.dp))
            .border(1.dp, Color.Black.copy(alpha = .07f), RoundedCornerShape(30.dp))
            .then(if (address != null && !playing) Modifier.clickable(onClickLabel = copyLabel, role = Role.Button) { onCopy(address) } else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp), contentAlignment = Alignment.TopCenter) {
            if (address == null) Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Lightning address unavailable"), style = NoahTokens.Typography.sheetTitle, color = ink)
                Text(tr("Finish setting up your Lightning address before sharing your QR."), style = HomeType.line, color = Color(0xFF6B6B6B))
                Pill(tr("Open Profile"), height = 44.dp, container = ink, content = Color.White, onClick = onProfile)
            } else Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = copyLabel }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(qrSide).graphicsLayer {
                    alpha = 1 - qrOut.coerceIn(0f, 1f)
                    if (!reduceMotion) { val sc = 1 - .14f * qrOut; scaleX = sc; scaleY = sc }
                }.then(if (Build.VERSION.SDK_INT >= 31 && playing && !reduceMotion && qrOut > .01f) Modifier.blur((6 * qrOut.coerceIn(0f, 1f)).dp, BlurredEdgeTreatment.Unbounded) else Modifier)
                    .testTag("home-qr")) {
                    StyledQR(address, Modifier.fillMaxSize(), reveal = true, reduceMotion = reduceMotion)
                }
                Text(address, Modifier.padding(top = 14.dp).graphicsLayer { alpha = 1 - qrOut.coerceIn(0f, 1f) }, style = HomeType.qrName, color = ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("Scan with any Lightning wallet"), Modifier.padding(top = 2.dp).graphicsLayer { alpha = 1 - qrOut.coerceIn(0f, 1f) },
                    style = HomeType.small, color = Color(0xFF6B6B6B), textAlign = TextAlign.Center)
            }
            if (playing) Column(Modifier.matchParentSize().graphicsLayer { alpha = gotIn.coerceIn(0f, 1f) }.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Canvas(Modifier.size(84.dp)) {
                    val unit = size.width / 84f
                    drawArc(NoahTokens.Light.orange, -90f, 360f * ring.coerceIn(0f, 1f), false, Offset(2 * unit, 2 * unit),
                        androidx.compose.ui.geometry.Size(80 * unit, 80 * unit), style = Stroke(2.5f * unit))
                    val path = Path().apply { moveTo(27 * unit, 43 * unit); relativeLineTo(10 * unit, 10 * unit); relativeLineTo(21 * unit, -22 * unit) }
                    val measure = PathMeasure().apply { setPath(path, false) }
                    val partial = Path()
                    measure.getSegment(0f, measure.length * check.coerceIn(0f, 1f), partial, true)
                    drawPath(partial, ink, style = Stroke(3.2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                Text("+" + if (ui.hidden) "••••" else BitcoinAmount.format(payment.sats, ui.unit, ui.locale),
                    Modifier.padding(top = 18.dp).graphicsLayer { alpha = amountIn.coerceIn(0f, 1f); if (!reduceMotion) translationY = 8 * (1 - amountIn) * density },
                    style = HomeType.got, color = ink)
                payment.from?.let { from ->
                    Text(tr("from {name}", from), Modifier.padding(top = 6.dp).graphicsLayer { alpha = fromIn.coerceIn(0f, 1f) }, style = HomeType.line, color = Color(0xFF6B6B6B))
                }
            }
        }
        if (address != null) Row(Modifier.fillMaxWidth().padding(top = 12.dp).graphicsLayer { alpha = 1 - buttonsOut.coerceIn(0f, 1f) },
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(tr("Copy"), Modifier.weight(1f), height = 48.dp, outlined = true, style = HomeType.button, enabled = !playing) { onCopy(address) }
            Pill(tr("Share"), Modifier.weight(1f), height = 48.dp, style = HomeType.button, enabled = !playing) { onShare(address) }
        }
    }
}

/** "Balance", the total and today's balance.details rows with their existing labels; a medium-detent sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalanceSheet(ui: HomeUi, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val balance = ui.balance
    androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.MaterialTheme.colorScheme.copy(
        surfaceContainerLow = palette.soft, surfaceContainer = palette.soft, surfaceContainerHigh = palette.soft)) {
        OnboardingSheet(onDismiss, rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
            Text(tr("Balance"), Modifier.semantics { heading() }, style = NoahTokens.Typography.sheetTitle, color = palette.foreground)
            Text(if (balance == null) "" else if (ui.hidden) BitcoinAmount.format(0, ui.unit, ui.locale).replace("0", "••••••") else BitcoinAmount.format(balance.totalSats.toLong(), ui.unit, ui.locale),
                Modifier.padding(top = 12.dp, bottom = 16.dp), style = HomeType.total, color = palette.foreground)
            balance?.details?.forEachIndexed { index, row ->
                Row(Modifier.fillMaxWidth().height(46.dp).then(if (index < balance.details.lastIndex) Modifier.drawBehindHairline(palette.hairline) else Modifier),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(row.label), Modifier.weight(1f), style = HomeType.rowTitle, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ui.amount(row.sats), style = HomeType.amount, color = palette.foreground)
                }
            }
        }
    }
}

private fun Modifier.drawBehindHairline(color: Color) = drawBehind {
    drawRect(color, Offset(0f, size.height - 1.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
}
