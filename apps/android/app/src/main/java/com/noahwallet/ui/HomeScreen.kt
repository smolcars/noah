package com.noahwallet.ui

import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.BuildConfig
import com.noahwallet.history.RepeatPayment
import com.noahwallet.localization.tr
import com.noahwallet.services.server.value
import com.noahwallet.ui.activity.*
import com.noahwallet.ui.home.*
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalPose
import com.noahwallet.ui.onboarding.OnboardingPose
import com.noahwallet.ui.settings.SettingsDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

/**
 * Home (docs/native-rewrite/design/home.md): live wallet state into [HomeContent]. Every action links to an
 * existing flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppModel, onViewAll: () -> Unit,
    onRepeatPayment: (RepeatPayment) -> Unit, onSettings: (androidx.navigation3.runtime.NavKey) -> Unit, onScan: () -> Unit, onBackup: () -> Unit = {},
    onProfile: () -> Unit = {}, onRenew: (List<String>) -> Unit = {}, settings: () -> Unit) {
    val appColors = MaterialTheme.colorScheme
    val appTypography = MaterialTheme.typography
    val appShapes = MaterialTheme.shapes
    val detail = rememberActivityState()
    var showRounds by rememberSaveable { mutableStateOf(false) }
    var refreshRevision by remember { mutableIntStateOf(0) }
    var dismissed by remember { mutableStateOf(model.server.state.isEmailPromptDismissed == true) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currency = model.preferredCurrency.takeIf { it in com.noahwallet.formatting.FiatCurrency.supported } ?: "USD"
    val price = rememberHomePrice(model, currency)
    val memory = model.homeMemory
    FirstPaymentBaseline(model)
    // Each visit refreshes the balance.
    LaunchedEffect(Unit) { model.refresh() }
    val toasts = remember { HomeToasts() }
    val uri = LocalUriHandler.current
    val signals = rememberHomeSignals(model, refreshRevision, memory)
    val (detailUi, detailActions) = paymentDetail(model, detail, price, onRepeatPayment, onSettings)
    RefreshOnAccounting(model)
    val newRows = rememberNewRows(model)
    val starting = rememberFirstPaymentStart(model)
    HomeToastEffects(model, toasts, signals)
    val ui = homeUi(model, signals, price, currency, newRows, emailPrompt = !dismissed, fresh = starting)
    val actions = HomeActions(
        scan = onScan, settings = settings, suspended = { onSettings(SettingsDestination.Advanced) },
        toggleHidden = model::toggleBalance, seeAll = onViewAll, openRow = { detail.open(it) }, profile = onProfile,
        nudge = { nudge ->
            when (nudge) {
                is HomeRules.Nudge.Notice -> Unit
                is HomeRules.Nudge.Renewal -> onRenew(nudge.renew.ids)
                is HomeRules.Nudge.BackupFailed -> signals.retry()
                HomeRules.Nudge.BackUp -> onBackup()
                HomeRules.Nudge.Email -> onSettings(SettingsDestination.Email)
                HomeRules.Nudge.Update -> uri.openUri(HomeRules.updateURL(BuildConfig.APP_VARIANT))
            }
        },
        dismiss = { nudge ->
            when (nudge) {
                is HomeRules.Nudge.Notice -> memory.notices = memory.notices - nudge.message
                HomeRules.Nudge.Email -> { model.server.dismissEmailPrompt(); dismissed = true }
                else -> Unit
            }
        },
        rounds = { showRounds = true },
        refresh = {
            scope.launch { refreshing = true
                try { model.refresh(true); model.history.refresh(model.preferredCurrency); refreshRevision++ } finally { refreshing = false } }
        },
    )
    com.noahwallet.ui.onboarding.OnboardingFrame(null, splitAtWindowCentre = true) {
        HomeContent(ui, actions, memory, toasts, refreshing)
        HomePaymentSheet(detailUi, detail, detailActions)
        // Today's Pending Rounds sheet, in today's look, kept inside the trailing pane.
        if (showRounds && signals.active.isNotEmpty()) MaterialTheme(appColors, appShapes, appTypography) {
            PendingRoundsSheet(signals.active) { showRounds = false }
        }
    }
}

/**
 * The first payment's baseline, once per foreground visit: after a refresh that landed since the visit began
 * (retried if it fails), read the wallet as it is. Before Home's own refresh, so it sees where it started.
 */
@Composable
private fun FirstPaymentBaseline(model: AppModel) {
    com.noahwallet.ui.home.FirstPaymentVisits(model.homeMemory.firstPaymentWatch, active = !model.isWalletSuspended, revision = { model.summaryRevision },
        refresh = { model.refresh() }, settled = {
            snapshotFlow { model.history.isLoading }.first { !it }
            val history = model.history.transactions
            HomeRules.state(model.isWalletSuspended, model.summary?.balance?.totalSats, false, model.history.error, history.isNotEmpty()) to history
        })
}

/** Today's price in [currency] while Home is started, every five minutes; none while suspended. */
@Composable
private fun rememberHomePrice(model: AppModel, currency: String): Double? {
    var price by remember { mutableStateOf<Double?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(currency, model.isWalletSuspended, lifecycle) {
        price = null
        if (model.isWalletSuspended) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                try { price = com.noahwallet.api.generated.client.V0PricesClient(model.services.client)
                    .fiatPrices(kotlinx.serialization.json.JsonObject(emptyMap())).value().rates[currency]?.takeIf { it.isFinite() && it > 0 } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { }
                kotlinx.coroutines.delay(300_000)
            }
        }
    }
    return price
}

/**
 * A history change that moves money (a new row, a confirmation, a status or delta change, from bark's state
 * stream) means the balance moved too; re-read it so both agree. Enrichment doesn't count. Home appearing
 * already refreshes, so only a change while it's shown does this.
 */
@Composable
private fun RefreshOnAccounting(model: AppModel) {
    val rows = model.history.transactions
    val historyLoaded = !model.history.isLoading
    val accounting = remember(rows) { HomeRules.accounting(rows) }
    var refreshedAccounting by remember { mutableStateOf(accounting) }
    LaunchedEffect(accounting) { if (accounting != refreshedAccounting) { refreshedAccounting = accounting; if (historyLoaded) model.refresh() } }
}

/** "New" marks incoming rows that arrived since Home was last shown; in memory only. */
@Composable
private fun rememberNewRows(model: AppModel): Set<String> {
    val memory = model.homeMemory
    val rows = model.history.transactions
    val historyLoaded = !model.history.isLoading
    val ids = rows.map { it.id }
    val currentIds by rememberUpdatedState(ids)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    SideEffect { if (memory.seenRows == null && historyLoaded) memory.seenRows = ids.toSet() }
    val seen = memory.seenRows
    val newRows = if (seen == null) emptySet() else rows.filter { HomeRow.received(it) && it.id !in seen }.map { it.id }.toSet()
    val activity = LocalActivity.current
    DisposableEffect(lifecycle) {
        fun hidden() { memory.seenRows = currentIds.toSet() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) hidden() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); if (activity?.isChangingConfigurations != true) hidden() }
    }
    return newRows
}

/**
 * The first payment: incoming funds that appear after this visit's baseline on a Fresh wallet, with Home on screen.
 * True until the effect starts the sequence, so Home keeps showing Fresh rather than flashing Everyday for a frame.
 */
@Composable
private fun rememberFirstPaymentStart(model: AppModel): Boolean {
    val memory = model.homeMemory
    val haptics = com.noahwallet.ui.onboarding.rememberHaptics()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val arrival = memory.firstPaymentWatch.arrival(model.history.transactions, model.summary?.balance?.totalSats)
    val address = model.server.state.lightningAddress?.takeIf { it.isNotEmpty() }
    LaunchedEffect(arrival, resumed) {
        if (arrival == null || !resumed || memory.firstPayment != null) return@LaunchedEffect
        memory.firstPaymentWatch.played()
        memory.firstPayment = HomeMemory.FirstPayment(SystemClock.elapsedRealtime(), arrival.sats,
            arrival.row?.let { HomeRow.of(it, address?.substringAfter('@')).counterparty })
        haptics.success()
    }
    return arrival != null && resumed && memory.firstPayment == null
}

/** Toasts appear only after real success; "Backup completed" and boarding keep today's 5-second expiry. */
@Composable
private fun HomeToastEffects(model: AppModel, toasts: HomeToasts, signals: HomeSignals) {
    val memory = model.homeMemory
    val haptics = com.noahwallet.ui.onboarding.rememberHaptics()
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    val emailVerified = model.server.state.isEmailVerified == true
    val backedUp = tr("Backed up")
    val emailAdded = tr("Email added")
    LaunchedEffect(model.recoveryPhraseBackedUp) {
        if (memory.backedUpSeen == false && model.recoveryPhraseBackedUp) { toasts.show(backedUp); haptics.success() }
        memory.backedUpSeen = model.recoveryPhraseBackedUp
    }
    LaunchedEffect(emailVerified) {
        if (memory.emailSeen == false && emailVerified) { toasts.show(emailAdded); haptics.success() }
        memory.emailSeen = emailVerified
    }
    // A completed backup is the top bar's check, not a toast.
    val boarding = model.autoBoardSuccessBanner
    val completion = boarding?.let { (amount, created) -> HomeRules.boarding(amount, created, signals.now, locale) }?.title
    val completionAt = boarding?.second
    // Boarding names its amount in Home's format, so a hidden balance stays hidden.
    val completionText = boarding?.takeIf { completion != null }?.let { (amount, _) ->
        tr("{amount} available in Ark", if (model.hidden) "••••" else com.noahwallet.formatting.BitcoinAmount.format(amount, model.amountUnit, locale))
    }
    LaunchedEffect(completionText, completionAt) {
        val key = "$completion:$completionAt"
        if (completionText == null || completionAt == null) return@LaunchedEffect
        // Hiding amounts (or changing language) while it shows swaps the text in place, for the rest of its time.
        if (memory.toastSeen == key) toasts.retext(key, completionText)
        else {
            memory.toastSeen = key
            toasts.show(completionText, (HomeRules.successMillis - (System.currentTimeMillis() - completionAt)).coerceIn(1_000, HomeRules.successMillis),
                tag = key)
        }
    }
}

/** Home's state for [HomeContent]; [fresh] holds the Fresh state while the first payment starts. */
@Composable
private fun homeUi(model: AppModel, signals: HomeSignals, price: Double?, currency: String, newRows: Set<String>, emailPrompt: Boolean,
    fresh: Boolean): HomeUi {
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    val balance = model.summary?.balance
    val rows = model.history.transactions
    val memory = model.homeMemory
    val state = HomeRules.state(model.isWalletSuspended, balance?.totalSats, model.history.isLoading, model.history.error, rows.isNotEmpty())
    val backupBanner = HomeRules.backup(signals.backup, signals.now, locale)
    val suspended = model.isWalletSuspended
    return HomeUi(
        state = if (fresh) HomeRules.State.fresh else state, address = model.server.state.lightningAddress?.takeIf { it.isNotEmpty() },
        variant = BuildConfig.APP_VARIANT, balance = balance,
        unit = model.amountUnit,
        locale = locale, hidden = model.hidden,
        fiat = HomeRules.fiat(balance?.totalSats?.toLong(), price, currency, locale, model.hidden),
        status = HomeRules.status(model.error, balance, rows, signals.active.size),
        nudge = HomeRules.nudge(memory.notices, memory.renew, backupBanner, model.recoveryPhraseBackedUp,
            emailPrompt = emailPrompt && model.server.state.isEmailVerified != true, updateRequired = signals.version?.updateRequired == true),
        rows = rows, historyError = model.history.error, newRows = newRows, retrying = signals.retrying, now = Date(),
        roundsActive = signals.active.isNotEmpty(),
        activity = HomeRules.activity(backupBanner, signals.background).takeIf { !suspended },
        backedUpAt = HomeRules.backedUpAt(backupBanner, signals.backup).takeIf { !suspended },
    )
}

/** A row's payment: Activity's detail in a sheet, kept inside the trailing pane on wide windows and the fold. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomePaymentSheet(ui: ActivityUi, state: ActivityState, actions: ActivityActions) {
    val record = ui.records.firstOrNull { it.id == state.selected } ?: return
    val row = remember(record, ui.ownDomain) { ActivityRules.Row(record, HomeRow.of(record, ui.ownDomain)) }
    val toasts = remember { HomeToasts() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // As in Activity: the block row reads the chain as the payment opens and on each history read.
    LaunchedEffect(record, ui.revision) { actions.shown(record) }
    // The detail's own colour edge to edge, as on iOS: dark mode's sheet grey would hide its soft circles.
    MaterialTheme(MaterialTheme.colorScheme.copy(surfaceContainerLow = LocalPalette.current.background)) {
        com.noahwallet.ui.onboarding.OnboardingSheet({ state.selected = null }, sheet, padded = false) {
            ActivityDetail(ui, row, actions, state, toasts, wide = LocalPose.current != OnboardingPose.Compact, shared = null, closable = true) {
                scope.launch { sheet.hide() }.invokeOnCompletion { state.selected = null }
            }
        }
    }
}
