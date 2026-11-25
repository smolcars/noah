package com.noahwallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.noahwallet.localization.tr
import com.noahwallet.ui.settings.*
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
    val tab = rememberSaveable { mutableStateOf(Destination.Home) }
    val nav = remember(backStack, settingsStack, tab) { AppNavigator(model, backStack, settingsStack, tab) }
    val reduceMotion = com.noahwallet.ui.onboarding.rememberReduceMotion()
    // The launch preview shows Home alone: a tab or page restored with the activity reads the wallet once it's open.
    val preview = model.showsLaunchPreview
    val (selectedTab, homeStack) = if (preview) Destination.Home to listOf<NavKey>(Destination.Home) else tab.value to backStack
    val tabState = rememberSaveableStateHolder()
    BackHandler(enabled = selectedTab != Destination.Home) { nav.goBack() }
    // Leaving Activity forgets a notification's payment it hasn't opened yet; a rotation keeps the tab, and the payment.
    // The live tab: a tap delivered in this same frame has already moved it to Activity.
    LaunchedEffect(selectedTab) { if (nav.selectedTab != Destination.History) model.paymentTap = null }
    if (links != null) LinkDelivery(model, links, nav)
    LaunchedEffect(model.recurringNotification) {
        if (model.recurringNotification) { nav.openSettings(SettingsDestination.Recurring); model.recurringNotification = false }
    }
    WalletForeground(model)
    RecentShortcuts(model)
    if (!preview && selectedTab == Destination.Home && homeStack.last() == Destination.Home) {
        com.noahwallet.onchain.AutoBoardPrompt(model) { model.autoBoardSuccessBanner = it to System.currentTimeMillis() }
    }
    // Home, Settings, Send, Receive and Activity split the whole window at a fold themselves; every other screen (the
    // map among them) keeps one pane.
    var sendBarHidden by remember { mutableStateOf(false) }
    var activityBarHidden by remember { mutableStateOf(false) }
    val settingsVisible = homeStack.last() == SettingsDestination.Settings && selectedTab == Destination.Home
    val homeOwnsWindow = ownsWindow(model, selectedTab, homeStack, settingsVisible)
    WindowBackground(homeOwnsWindow)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().avoidHinge(if (homeOwnsWindow) emptyList() else LocalFoldingFeatures.current)) {
        // Settings isn't a tab: phones hide the bar under it; the rail stays.
        val barHidden = (selectedTab == Destination.Send && sendBarHidden) || (selectedTab == Destination.History && activityBarHidden)
        AdaptiveNavigation(selectedTab, { tab.value = it }, fullWindow = homeOwnsWindow, showBar = !barHidden && !settingsVisible) { padding ->
            tabState.SaveableStateProvider(selectedTab) {
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalAppShared provides this) {
                        NavDisplay(
                            backStack = mainTabEntries(selectedTab, homeStack),
                            modifier = Modifier.padding(padding),
                            onBack = { nav.goBack() },
                            sizeTransform = null,
                            transitionSpec = { stackTransition(predictive = false, reduceMotion = reduceMotion) },
                            popTransitionSpec = { stackTransition(predictive = false, reduceMotion = reduceMotion) },
                            predictivePopTransitionSpec = { stackTransition(predictive = true, reduceMotion = reduceMotion) },
                            entryProvider = entryProvider {
                                entry<QRDestination> { destination -> QRHub(model, nav, destination.scan) }
                                entry<MapDestination> { com.noahwallet.btcmap.BtcMapScreen { nav.goBack() } }
                                entry<SettingsDestination> {
                                    CompositionLocalProvider(LocalEntryScope provides LocalNavAnimatedContentScope.current) {
                                        SettingsHost(model, settingsStack, close = { nav.goBack() }, myCode = { backStack.add(QRDestination(false)) },
                                            map = { backStack.add(MapDestination) })
                                    }
                                }
                                entry<Destination> { destination ->
                                    TabScreen(model, nav, destination, sendBar = { sendBarHidden = it }) { activityBarHidden = it }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Where the app's tabs and stacks go: Home's stack, Settings beneath a page, Send with a scanned code. */
@Stable
private class AppNavigator(private val model: AppModel, val backStack: NavBackStack<NavKey>, val settingsStack: NavBackStack<NavKey>,
    private val tab: MutableState<Destination>) {
    val selectedTab get() = tab.value
    fun select(destination: Destination) { tab.value = destination }
    fun showHome() { backStack.clear(); backStack.add(Destination.Home); tab.value = Destination.Home }
    fun showSend() { backStack.clear(); backStack.add(Destination.Home); tab.value = Destination.Send }
    /** A scanned code, a QR hub paste or an OS link: Send applies it on To once no payment holds it. */
    fun scanned(value: String) { model.scannedDestination = value; showSend() }
    fun goBack() {
        if (model.busy) return
        if (tab.value != Destination.Home) tab.value = Destination.Home
        else if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    /** Opens [page] with Settings beneath it (settings.md, Deep links). */
    fun openSettings(page: NavKey) {
        tab.value = Destination.Home
        SettingsRoutes.open(backStack, settingsStack, page)
    }
    /** Opens Profile straight over Home, skipping Settings in the stack (matching iOS). */
    fun openProfile() {
        tab.value = Destination.Home
        SettingsRoutes.openDirect(backStack, settingsStack, SettingsDestination.Profile)
    }
    fun repeat(payment: com.noahwallet.history.RepeatPayment) { model.repeatPayment = payment; showSend() }
    /** A recent recipient's shortcut or an assistant's Pay: Send fills it in once no payment holds it. */
    fun pay(route: PayRoute) { model.sendPrefill = route; showSend() }
}

/** OS payment links go to Send once no payment holds it; a held one says so. */
@Composable
private fun LinkDelivery(model: AppModel, links: PaymentLinks, nav: AppNavigator) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val send = remember(context) { com.noahwallet.send.SendRuntime.shared(context).controller }
    LaunchedEffect(links.pending, send.loading) { links.deliver(send, model.openReviews, nav::scanned) }
    LaunchedEffect(links.pay, send.loading) { links.deliverPay(send, model.openReviews, nav::pay) }
    LaunchedEffect(links.route, send.loading) {
        links.deliverRoute(send, model.sendBusy, model.recurringApprovals) { route ->
            when (route) {
                WidgetRoute.home -> nav.showHome()
                WidgetRoute.receive -> nav.select(Destination.Receive)
                // The Send tab as it is: its draft, review or payment stays.
                WidgetRoute.send -> nav.select(Destination.Send)
                // Home's scan button: the QR hub, scanning.
                WidgetRoute.scan -> { nav.showHome(); nav.backStack.add(QRDestination(true)) }
            }
        }
    }
    LaunchedEffect(links.payment, send.loading) {
        links.deliverPayment(send, model.sendBusy, model.recurringApprovals) { tap -> model.paymentTap = tap; nav.select(Destination.History) }
    }
    if (links.blocked) WalletAlertDialog(onDismissRequest = { links.blocked = false },
        title = { Text(tr("Payment in progress")) }, text = { Text(tr(PaymentLinks.blockedMessage)) },
        confirmButton = { androidx.compose.material3.TextButton({ links.blocked = false }) { Text(tr("OK")) } })
}

/** While the wallet is open and the app started: refresh it, keep backups due, and follow its history. */
@Composable
private fun WalletForeground(model: AppModel) {
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
}

/** Send's recent recipients on the launcher, refreshed as history changes. */
@Composable
private fun RecentShortcuts(model: AppModel) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(model.history.revision) {
        if (model.history.revision == 0L) return@LaunchedEffect
        val history = model.history.transactions
        val ownDomain = model.server.state.lightningAddress?.substringAfter('@', "")?.takeIf { it.isNotEmpty() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.noahwallet.assistant.LauncherShortcuts.update(context, history, ownDomain) }
    }
}

/** Home, Settings and the Send, Receive and Activity tabs draw the whole window; others keep one pane. */
private fun ownsWindow(model: AppModel, tab: Destination, homeStack: List<NavKey>, settingsVisible: Boolean): Boolean {
    val homeVisible = homeStack.last() == Destination.Home && (tab == Destination.Home || model.isWalletSuspended)
    return homeVisible || settingsVisible ||
        (!model.isWalletSuspended && tab in listOf(Destination.Send, Destination.Receive, Destination.History))
}

/** The window's background follows Home's palette while Home owns the window. */
@Composable
private fun WindowBackground(homeOwnsWindow: Boolean) {
    val chrome = LocalWindowChrome.current
    val palette = com.noahwallet.ui.onboarding.noahPalette()
    DisposableEffect(homeOwnsWindow, palette) {
        chrome.background = if (homeOwnsWindow) palette.background else null
        onDispose { chrome.background = null }
    }
}

/** The QR hub: your code (with your name) and the scanner. */
@Composable
private fun QRHub(model: AppModel, nav: AppNavigator, scan: Boolean) {
    val profile = com.noahwallet.services.storage.SettingsPreferences.state(model.settings.string("profile-storage"))
    val name = profile["displayName"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: ""
    QRHubScreen(model.server.state.lightningAddress, name,
        onBack = { nav.backStack.removeAt(nav.backStack.lastIndex) },
        onProfile = { nav.openSettings(SettingsDestination.Profile) },
        onScan = nav::scanned, startScanning = scan)
}

/** A tab's screen; a suspended wallet shows Home in every tab. */
@Composable
private fun TabScreen(model: AppModel, nav: AppNavigator, destination: Destination, sendBar: (Boolean) -> Unit, activityBar: (Boolean) -> Unit) {
    // A payment's VTXO, exit and recurring links, from Activity and Home alike.
    val openPage: (NavKey) -> Unit = { nav.openSettings(it) }
    when {
        destination == Destination.Home || model.isWalletSuspended -> CompositionLocalProvider(LocalEntryScope provides LocalNavAnimatedContentScope.current) {
            HomeScreen(model, onViewAll = { nav.select(Destination.History) }, onRepeatPayment = nav::repeat, onSettings = openPage,
                onScan = { nav.backStack.add(QRDestination(true)) },
                onBackup = { nav.openSettings(SettingsDestination.Seed) },
                onProfile = { nav.openProfile() },
                onRenew = { ids -> model.renewVtxos = ids; nav.openSettings(SettingsDestination.Vtxos) }) {
                nav.openSettings(SettingsDestination.Settings)
            }
        }
        destination == Destination.History -> ActivityScreen(model, nav::repeat, onReceive = { nav.select(Destination.Receive) },
            onSettings = openPage, barHidden = activityBar)
        destination == Destination.Receive -> ReceiveScreen(model, onProfile = { nav.openSettings(SettingsDestination.Profile) })
        else -> SendScreen(model, tabBar = sendBar) { nav.showHome() }
    }
}

/**
 * Home stack pushes/pops (Home ↔ Settings, QR hub, BTC Map): edge-to-edge opaque horizontal slide on the snappy spring
 * so screens never overlap or ghost over Home's QR card; predictive back tracks the swipe 1:1. Direction comes from the
 * transition's own scene depths rather than NavDisplay's `isPop` flag, because NavDisplay flips to `popTransitionSpec`
 * while rewinding an interrupted open transition (whose segment is still `initialState = Home -> targetState = Settings`).
 * Tab switches (both scenes at depth 0) cross-fade instead of sliding.
 */
private fun AnimatedContentTransitionScope<Scene<NavKey>>.stackTransition(predictive: Boolean, reduceMotion: Boolean): ContentTransform {
    val tabSwitch = initialState.previousEntries.isEmpty() && targetState.previousEntries.isEmpty()
    if (reduceMotion || tabSwitch) {
        return fadeIn(tween(com.noahwallet.ui.design.NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) togetherWith
            fadeOut(tween(com.noahwallet.ui.design.NoahTokens.Motion.REDUCE_MOTION_FADE_MS))
    }
    val forward = targetState.previousEntries.size >= initialState.previousEntries.size
    if (predictive) {
        return if (forward) slideInHorizontally(tween(250, easing = LinearEasing)) { it } togetherWith
            slideOutHorizontally(tween(250, easing = LinearEasing)) { -it }
        else slideInHorizontally(tween(250, easing = LinearEasing)) { -it } togetherWith
            slideOutHorizontally(tween(250, easing = LinearEasing)) { it }
    }
    val spring = com.noahwallet.ui.design.NoahTokens.Motion.snappy<androidx.compose.ui.unit.IntOffset>()
    return if (forward) slideInHorizontally(spring) { it } togetherWith slideOutHorizontally(spring) { -it }
    else slideInHorizontally(spring) { -it } togetherWith slideOutHorizontally(spring) { it }
}
