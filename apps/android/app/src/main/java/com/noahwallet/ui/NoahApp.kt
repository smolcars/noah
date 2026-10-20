package com.noahwallet.ui

import com.noahwallet.localization.tr
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoahApp(model: AppModel) {
    val backStack = rememberNavBackStack(Destination.Home)
    LaunchedEffect(model.recurringNotification) {
        if (model.recurringNotification) { backStack.add(SettingsDestination.Recurring); model.recurringNotification = false }
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
    if (backStack.last() == Destination.Home) com.noahwallet.onchain.AutoBoardPrompt(model) { model.autoBoardSuccessBanner = it to System.currentTimeMillis() }
    val repeat: (com.noahwallet.history.RepeatPayment) -> Unit = {
        model.repeatPayment = it
        backStack.clear(); backStack.add(Destination.Home); backStack.add(Destination.Send)
    }
    Scaffold(
        topBar = {
            val destination = backStack.last() as? SettingsDestination
            if (destination != null) TopAppBar(title = { Text(tr(destination.title)) }, navigationIcon = {
                TextButton({ if (!model.busy && backStack.size > 1) backStack.removeAt(backStack.lastIndex) }) { Text(tr("Back")) }
            })
        },
        bottomBar = {
            if (backStack.last() !is SettingsDestination) NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = backStack.last() == destination || (destination == Destination.Home && backStack.last() == QRDestination),
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSurface,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        onClick = {
                            backStack.clear()
                            backStack.add(Destination.Home)
                            if (destination != Destination.Home) backStack.add(destination)
                        },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(tr(stringResource(destination.title))) },
                    )
                }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(padding),
            onBack = { if (!model.busy && backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryProvider = entryProvider {
                entry<QRDestination> {
                    QRHubScreen(model.server.state.lightningAddress,
                        com.noahwallet.services.storage.SettingsPreferences.state(model.settings.string("profile-storage"))["displayName"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: "",
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                        onProfile = { backStack.add(SettingsDestination.Profile) },
                        onScan = { value ->
                            model.scannedDestination = value
                            backStack.clear(); backStack.add(Destination.Home); backStack.add(Destination.Send)
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
                        backStack.add(Destination.History)
                    }, onRepeatPayment = repeat, onQR = { backStack.add(QRDestination) }) { backStack.add(SettingsDestination.Settings) }
                    else if (destination == Destination.History) TransactionsScreen(model, repeat)
                    else if (destination == Destination.Receive) ReceiveScreen(model) {
                        backStack.clear(); backStack.add(Destination.Home)
                    } else SendScreen(model) {
                        backStack.clear(); backStack.add(Destination.Home)
                    }
                }
            },
        )
    }
}
