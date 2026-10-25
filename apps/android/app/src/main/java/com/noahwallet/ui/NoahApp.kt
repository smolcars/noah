package com.noahwallet.ui

import com.noahwallet.localization.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.noahwallet.ui.settings.*
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull

/** The four tabs; History's tab reads "Activity". */
@Serializable
enum class Destination(val title: String) : NavKey {
    Home("Home"),
    Receive("Receive"),
    Send("Send"),
    History("Activity"),
}

/** Today's QR hub, on My code or (from Home's scan button) straight on Scan. */
@Serializable
data class QRDestination(val scan: Boolean = false) : NavKey

/** Today's BTC Map, opened from Settings. */
@Serializable
data object MapDestination : NavKey

internal fun mainTabEntries(tab: Destination, homeStack: List<NavKey>): List<NavKey> =
    if (tab == Destination.Home) homeStack else listOf(tab)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NoahApp(model: AppModel, links: PaymentLinks? = null) {
    val backStack = rememberNavBackStack(Destination.Home)
    // Settings' own pages: on Home's stack, Settings is one entry over Home.
    val settingsStack = rememberNavBackStack(SettingsDestination.Settings)
    val settingsEntry = remember { SettingsEntry() }
    var selectedTab by rememberSaveable { mutableStateOf(Destination.Home) }
    val tabState = rememberSaveableStateHolder()
    fun showHome() { backStack.clear(); backStack.add(Destination.Home); selectedTab = Destination.Home }
    fun showSend() { backStack.clear(); backStack.add(Destination.Home); selectedTab = Destination.Send }
    /** A scanned code, a QR hub paste or an OS link: Send applies it on To once no payment holds it. */
    fun scanned(value: String) { model.scannedDestination = value; showSend() }
    fun goBack() {
        if (!model.busy) {
            if (selectedTab != Destination.Home) selectedTab = Destination.Home
            else if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        }
    }
    /** Opens [page] with Settings beneath it (settings.md, Deep links); [fromGear] zooms out of Home's gear. */
    fun openSettings(page: NavKey, fromGear: Boolean = false) {
        settingsEntry.fromGear = fromGear
        selectedTab = Destination.Home
        SettingsRoutes.open(backStack, settingsStack, page)
    }
    BackHandler(enabled = selectedTab != Destination.Home) { goBack() }
    if (links != null) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val send = remember(context) { com.noahwallet.send.SendRuntime.shared(context).controller }
        LaunchedEffect(links.pending, send.loading) { links.deliver(send, model.openReviews, ::scanned) }
        if (links.blocked) WalletAlertDialog(onDismissRequest = { links.blocked = false },
            title = { Text(tr("Payment in progress")) }, text = { Text(tr(PaymentLinks.blockedMessage)) },
            confirmButton = { androidx.compose.material3.TextButton({ links.blocked = false }) { Text(tr("OK")) } })
    }
    LaunchedEffect(model.recurringNotification) {
        if (model.recurringNotification) { openSettings(SettingsDestination.Recurring); model.recurringNotification = false }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sessionRevision by model.session.walletChanges.collectAsState()
    val walletAvailable = model.open && !model.isWalletSuspended && model.session.isOpen
    LaunchedEffect(model, model.preferredCurrency, lifecycle, walletAvailable, sessionRevision) {
        if (walletAvailable) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.refresh()
            model.services.reconcileBackupTriggers()
            model.history.observe(model.preferredCurrency)
        }
    }
    if (selectedTab == Destination.Home && backStack.last() == Destination.Home) com.noahwallet.onchain.AutoBoardPrompt(model) { model.autoBoardSuccessBanner = it to System.currentTimeMillis() }
    val repeat: (com.noahwallet.history.RepeatPayment) -> Unit = {
        model.repeatPayment = it
        showSend()
    }
    // A payment's VTXO, exit and recurring links, from Activity and Home alike.
    val openPage: (NavKey) -> Unit = { openSettings(it) }
    // Home, Settings, Send, Receive and Activity split the whole window at a fold themselves; every other screen (the
    // map among them), and Home's email flow, keep one pane.
    var homeOverlay by rememberSaveable { mutableStateOf<String?>(null) }
    var sendBarHidden by remember { mutableStateOf(false) }
    var activityBarHidden by remember { mutableStateOf(false) }
    val homeVisible = backStack.last() == Destination.Home && (selectedTab == Destination.Home || model.isWalletSuspended)
    val settingsVisible = backStack.last() == SettingsDestination.Settings && selectedTab == Destination.Home
    val homeOwnsWindow = homeVisible && homeOverlay == null || settingsVisible ||
        !model.isWalletSuspended && selectedTab in listOf(Destination.Send, Destination.Receive, Destination.History)
    val chrome = LocalWindowChrome.current
    val palette = com.noahwallet.ui.onboarding.noahPalette()
    DisposableEffect(homeOwnsWindow, palette) {
        chrome.background = if (homeOwnsWindow) palette.background else null
        onDispose { chrome.background = null }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().avoidHinge(if (homeOwnsWindow) emptyList() else LocalFoldingFeatures.current)) {
    // Settings isn't a tab: phones hide the bar under it; the rail stays.
    AdaptiveNavigation(selectedTab, { selectedTab = it }, fullWindow = homeOwnsWindow, showBar = !(selectedTab == Destination.Send && sendBarHidden) &&
        !(selectedTab == Destination.History && activityBarHidden) && !settingsVisible,
    ) { padding ->
        tabState.SaveableStateProvider(selectedTab) {
            SharedTransitionLayout {
            CompositionLocalProvider(LocalAppShared provides this, LocalSettingsEntry provides settingsEntry) {
            NavDisplay(
                backStack = mainTabEntries(selectedTab, backStack),
                modifier = Modifier.padding(padding),
                onBack = { goBack() },
                entryProvider = entryProvider {
                    entry<QRDestination> { destination ->
                        QRHubScreen(model.server.state.lightningAddress,
                            com.noahwallet.services.storage.SettingsPreferences.state(model.settings.string("profile-storage"))["displayName"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: "",
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onProfile = { openSettings(SettingsDestination.Profile) },
                            onScan = ::scanned, startScanning = destination.scan)
                    }
                    entry<MapDestination> { com.noahwallet.btcmap.BtcMapScreen { goBack() } }
                    entry<SettingsDestination>(metadata = settingsZoom()) {
                        CompositionLocalProvider(LocalEntryScope provides LocalNavAnimatedContentScope.current) {
                            SettingsHost(model, settingsStack, close = { goBack() }, myCode = { backStack.add(QRDestination(false)) },
                                map = { backStack.add(MapDestination) })
                        }
                    }
                    entry<Destination> { destination ->
                        if (destination == Destination.Home || model.isWalletSuspended) CompositionLocalProvider(LocalEntryScope provides LocalNavAnimatedContentScope.current) {
                            HomeScreen(model, homeOverlay, { homeOverlay = it }, onViewAll = {
                                selectedTab = Destination.History
                            }, onRepeatPayment = repeat, onSettings = openPage, onScan = { backStack.add(QRDestination(true)) },
                                onBackup = { openSettings(SettingsDestination.Seed) },
                                onProfile = { openSettings(SettingsDestination.Profile) },
                                onRenew = { ids -> model.renewVtxos = ids; openSettings(SettingsDestination.Vtxos) }) {
                                openSettings(SettingsDestination.Settings, fromGear = true)
                            }
                        }
                        else if (destination == Destination.History) ActivityScreen(model, repeat, onReceive = { selectedTab = Destination.Receive },
                            onSettings = openPage, barHidden = { activityBarHidden = it })
                        else if (destination == Destination.Receive) ReceiveScreen(model, onProfile = {
                            openSettings(SettingsDestination.Profile)
                        }) {
                            showHome()
                        } else SendScreen(model, tabBar = { sendBarHidden = it }) {
                            showHome()
                        }
                    }
                },
            )
            }
            }
        }
    }
    }
}

/**
 * Home → Settings (settings.md, Root): Home stays while Settings opens out of its gear, and Settings closes back
 * into it, drawn above Home; [SettingsHost] reads the entry's transition for the zoom itself.
 */
private fun settingsZoom(): Map<String, Any> {
    // Home holds still (fully drawn) underneath until the zoom ends, then goes.
    val hold = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(520, easing = { if (it >= 1f) 1f else 0f }))
    val stay = { EnterTransition.None togetherWith hold }
    return NavDisplay.transitionSpec { stay() } + NavDisplay.popTransitionSpec { stay().apply { targetContentZIndex = -1f } } +
        NavDisplay.predictivePopTransitionSpec { stay().apply { targetContentZIndex = -1f } }
}
