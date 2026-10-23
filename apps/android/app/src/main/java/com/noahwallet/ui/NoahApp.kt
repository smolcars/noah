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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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

internal fun mainTabEntries(tab: Destination, homeStack: List<NavKey>): List<NavKey> =
    if (tab == Destination.Home) homeStack else listOf(tab)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoahApp(model: AppModel) {
    val backStack = rememberNavBackStack(Destination.Home)
    var selectedTab by rememberSaveable { mutableStateOf(Destination.Home) }
    val tabState = rememberSaveableStateHolder()
    fun showHome() { backStack.clear(); backStack.add(Destination.Home); selectedTab = Destination.Home }
    fun showSend() { backStack.clear(); backStack.add(Destination.Home); selectedTab = Destination.Send }
    fun goBack() {
        if (!model.busy) {
            if (selectedTab != Destination.Home) selectedTab = Destination.Home
            else if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        }
    }
    BackHandler(enabled = selectedTab != Destination.Home) { goBack() }
    LaunchedEffect(model.recurringNotification) {
        if (model.recurringNotification) { selectedTab = Destination.Home; backStack.add(SettingsDestination.Recurring); model.recurringNotification = false }
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
    // Home, Send, Receive and Activity split the whole window at a fold themselves; every other screen, and Home's
    // map and email flows, keep one pane.
    var homeOverlay by rememberSaveable { mutableStateOf<String?>(null) }
    var sendBarHidden by remember { mutableStateOf(false) }
    var activityBarHidden by remember { mutableStateOf(false) }
    val homeVisible = backStack.last() == Destination.Home && (selectedTab == Destination.Home || model.isWalletSuspended)
    val homeOwnsWindow = homeVisible && homeOverlay == null ||
        !model.isWalletSuspended && selectedTab in listOf(Destination.Send, Destination.Receive, Destination.History)
    val chrome = LocalWindowChrome.current
    val palette = com.noahwallet.ui.onboarding.noahPalette()
    DisposableEffect(homeOwnsWindow, palette) {
        chrome.background = if (homeOwnsWindow) palette.background else null
        onDispose { chrome.background = null }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().avoidHinge(if (homeOwnsWindow) emptyList() else LocalFoldingFeatures.current)) {
    AdaptiveNavigation(selectedTab, { selectedTab = it }, fullWindow = homeOwnsWindow, showBar = !(selectedTab == Destination.Send && sendBarHidden) && !(selectedTab == Destination.History && activityBarHidden),
        topBar = {
            val destination = if (selectedTab == Destination.Home) backStack.last() as? SettingsDestination else null
            if (destination != null) TopAppBar(title = { Text(tr(destination.title)) }, navigationIcon = {
                TextButton({ goBack() }) { Text(tr("Back")) }
            })
        },
    ) { padding ->
        tabState.SaveableStateProvider(selectedTab) {
            NavDisplay(
                backStack = mainTabEntries(selectedTab, backStack),
                modifier = Modifier.padding(padding),
                onBack = { goBack() },
                entryProvider = entryProvider {
                    entry<QRDestination> { destination ->
                        QRHubScreen(model.server.state.lightningAddress,
                            com.noahwallet.services.storage.SettingsPreferences.state(model.settings.string("profile-storage"))["displayName"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: "",
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onProfile = { backStack.add(SettingsDestination.Profile) },
                            onScan = { value ->
                                model.scannedDestination = value
                                showSend()
                            }, startScanning = destination.scan)
                    }
                    entry<SettingsDestination> { destination ->
                        SettingsPage(model, destination, onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }) { next ->
                            if (next == SettingsDestination.Settings && backStack.last() == SettingsDestination.UnifiedPush) backStack.removeAt(backStack.lastIndex)
                            else if (next == SettingsDestination.Profile && backStack.last() in listOf(SettingsDestination.Email, SettingsDestination.Lightning)) backStack.removeAt(backStack.lastIndex)
                            else backStack.add(next)
                        }
                    }
                    entry<Destination> { destination ->
                        if (destination == Destination.Home || model.isWalletSuspended) HomeScreen(model, homeOverlay, { homeOverlay = it }, onViewAll = {
                            selectedTab = Destination.History
                        }, onRepeatPayment = repeat, onQR = { scan -> backStack.add(QRDestination(scan)) }, onBackup = { backStack.add(SettingsDestination.Seed) },
                            onProfile = { backStack.add(SettingsDestination.Profile) },
                            onRenew = { ids -> model.renewVtxos = ids; backStack.add(SettingsDestination.Vtxos) }) {
                            backStack.add(SettingsDestination.Settings)
                        }
                        else if (destination == Destination.History) ActivityScreen(model, repeat, onReceive = { selectedTab = Destination.Receive },
                            onSettings = { selectedTab = Destination.Home; backStack.add(it) }, barHidden = { activityBarHidden = it })
                        else if (destination == Destination.Receive) ReceiveScreen(model, onProfile = {
                            selectedTab = Destination.Home; backStack.add(SettingsDestination.Profile)
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
