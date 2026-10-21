package com.noahwallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.noahwallet.localization.tr

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdaptiveNavigation(selectedTab: Destination, select: (Destination) -> Unit,
    topBar: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = AdaptiveLayout.useRail(maxWidth.value, maxHeight.value)
        val keyboard = WindowInsets.isImeVisible
        Row(Modifier.fillMaxSize()) {
            if (rail) NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                windowInsets = WindowInsets(0, 0, 0, 0)) {
                Destination.entries.forEach { destination ->
                    NavigationRailItem(selected = selectedTab == destination, onClick = { select(destination) },
                        icon = { Icon(painterResource(destination.icon), null) },
                        label = { Text(tr(stringResource(destination.title))) },
                        colors = NavigationRailItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.onSurface,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface, indicatorColor = MaterialTheme.colorScheme.surfaceVariant))
                }
            }
            Scaffold(Modifier.weight(1f), topBar = topBar,
                contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
                    if (!rail && !keyboard) NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(selected = selectedTab == destination, onClick = { select(destination) },
                                icon = { Icon(painterResource(destination.icon), null) },
                                label = { Text(tr(stringResource(destination.title))) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant))
                        }
                    }
                }, content = content)
        }
    }
}
