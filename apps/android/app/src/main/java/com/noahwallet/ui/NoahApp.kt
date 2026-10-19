package com.noahwallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
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

@Composable
fun NoahApp(model: AppModel) {
    val backStack = rememberNavBackStack(Destination.Home)
    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = backStack.last() == destination,
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
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryProvider = entryProvider {
                entry<Destination> { destination ->
                    if (destination == Destination.Home) HomeScreen(model) else Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(destination.title), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.placeholder))
                        Text(
                            stringResource(R.string.build_variant, BuildConfig.APP_VARIANT),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            },
        )
    }
}
