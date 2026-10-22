package com.noahwallet.ui

import com.noahwallet.localization.tr
import androidx.activity.compose.BackHandler
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
import com.noahwallet.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull

@Serializable
enum class Destination(val title: Int, val icon: Int) : NavKey {
    Home(R.string.home, R.drawable.ic_home),
    Receive(R.string.receive, R.drawable.ic_receive),
    Send(R.string.send, R.drawable.ic_send),
    History(R.string.history, R.drawable.ic_history),
}

@Serializable
data object QRDestination : NavKey

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
    AdaptiveNavigation(selectedTab, { selectedTab = it },
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
                    entry<QRDestination> {
                        QRHubScreen(model.server.state.lightningAddress,
                            com.noahwallet.services.storage.SettingsPreferences.state(model.settings.string("profile-storage"))["displayName"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: "",
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onProfile = { backStack.add(SettingsDestination.Profile) },
                            onScan = { value ->
                                model.scannedDestination = value
                                showSend()
                            })
                    }
                    entry<SettingsDestination> { destination ->
                        SettingsPage(model, destination, onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }) { next ->
                            if (next == SettingsDestination.Settings && backStack.last() == SettingsDestination.UnifiedPush) backStack.removeAt(backStack.lastIndex)
                            else if (next == SettingsDestination.Profile && backStack.last() in listOf(SettingsDestination.Email, SettingsDestination.Lightning)) backStack.removeAt(backStack.lastIndex)
                            else backStack.add(next)
                        }
                    }
                    entry<Destination> { destination ->
                        if (destination == Destination.Home || model.isWalletSuspended) HomeScreen(model, onViewAll = {
                            selectedTab = Destination.History
                        }, onRepeatPayment = repeat, onQR = { backStack.add(QRDestination) }, onBackup = { backStack.add(SettingsDestination.Seed) }) {
                            backStack.add(SettingsDestination.Settings)
                        }
                        else if (destination == Destination.History) TransactionsScreen(model, repeat)
                        else if (destination == Destination.Receive) ReceiveScreen(model) {
                            showHome()
                        } else SendScreen(model) {
                            showHome()
                        }
                    }
                },
            )
        }
    }
}
