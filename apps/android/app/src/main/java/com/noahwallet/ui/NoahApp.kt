package com.noahwallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
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
import com.noahwallet.BuildConfig
import com.noahwallet.R
import kotlinx.serialization.Serializable

@Serializable
enum class Destination(val title: Int, val icon: Int) : NavKey {
    Home(R.string.home, R.drawable.ic_home),
    Receive(R.string.receive, R.drawable.ic_receive),
    Send(R.string.send, R.drawable.ic_send),
    History(R.string.history, R.drawable.ic_history),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoahApp(model: AppModel) {
    val backStack = rememberNavBackStack(Destination.Home)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val walletAvailable = model.open && !model.isWalletSuspended
    LaunchedEffect(model, model.preferredCurrency, lifecycle, walletAvailable) {
        if (walletAvailable) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.history.observe(model.preferredCurrency) }
    }
    val repeat: (com.noahwallet.history.RepeatPayment) -> Unit = {
        model.repeatPayment = it
        backStack.clear(); backStack.add(Destination.Home); backStack.add(Destination.Send)
    }
    Scaffold(
        topBar = {
            val destination = backStack.last() as? SettingsDestination
            if (destination != null) TopAppBar(title = { Text(destination.title) }, navigationIcon = {
                TextButton({ if (!model.busy && backStack.size > 1) backStack.removeAt(backStack.lastIndex) }) { Text("Back") }
            })
        },
        bottomBar = {
            if (backStack.last() !is SettingsDestination) NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = backStack.last() == destination,
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
                        label = { Text(stringResource(destination.title)) },
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
                entry<SettingsDestination> { destination ->
                    SettingsPage(model, destination) { next ->
                        if (next == SettingsDestination.Profile && backStack.last() in listOf(SettingsDestination.Email, SettingsDestination.Lightning)) backStack.removeAt(backStack.lastIndex)
                        else backStack.add(next)
                    }
                }
                entry<Destination> { destination ->
                    if (destination == Destination.Home || model.isWalletSuspended) HomeScreen(model, onViewAll = {
                        backStack.add(Destination.History)
                    }, onRepeatPayment = repeat) { backStack.add(SettingsDestination.Settings) }
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
