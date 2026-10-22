package com.noahwallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahwallet.localization.tr
import com.noahwallet.ui.home.HomeIcons

private val Destination.icon: Int get() = when (this) {
    Destination.Home -> HomeIcons.home
    Destination.Receive -> HomeIcons.receive
    Destination.Send -> HomeIcons.send
    Destination.History -> HomeIcons.activity
}

/**
 * Material 3 navigation bar or (from medium width) rail, restyled monochrome: the tonal soft surface,
 * foreground icons and labels, and a hairline-tint indicator pill. [fullWindow] is a tab splitting the whole
 * window itself: under a horizontal fold the bar stays in the bottom half instead of a rail crossing the fold.
 * [showBar] false hides the bar (Send's steps after Amount); the rail always stays.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdaptiveNavigation(selectedTab: Destination, select: (Destination) -> Unit, fullWindow: Boolean = false, showBar: Boolean = true,
    topBar: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val palette = com.noahwallet.ui.onboarding.noahPalette()
    val haptics = com.noahwallet.ui.onboarding.rememberHaptics()
    val hinges = LocalFoldingFeatures.current
    val chrome = LocalWindowChrome.current
    fun label(destination: Destination) = @Composable {
        Text(tr(destination.title), style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = if (selectedTab == destination) FontWeight.SemiBold else FontWeight.Medium))
    }
    fun choose(destination: Destination) { if (destination != selectedTab) haptics.selection(); select(destination) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tabletop = fullWindow && hinges.any { it.width > it.height }
        val rail = AdaptiveLayout.useRail(maxWidth.value, maxHeight.value) && !tabletop
        val keyboard = WindowInsets.isImeVisible
        val bar = !rail && !keyboard && showBar
        // The bar's surface continues under the gesture area when it reaches the window's bottom edge.
        val reachesBottom = fullWindow || hinges.isEmpty()
        DisposableEffect(bar, reachesBottom, palette) {
            chrome.bottom = if (bar && reachesBottom) palette.soft else null
            onDispose { chrome.bottom = null }
        }
        Row(Modifier.fillMaxSize()) {
            if (rail) NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                containerColor = palette.soft, contentColor = palette.foreground, windowInsets = WindowInsets(0, 0, 0, 0)) {
                Destination.entries.forEach { destination ->
                    NavigationRailItem(selected = selectedTab == destination, onClick = { choose(destination) },
                        icon = { Icon(painterResource(destination.icon), null) }, label = label(destination),
                        colors = NavigationRailItemDefaults.colors(selectedIconColor = palette.foreground,
                            selectedTextColor = palette.foreground, unselectedIconColor = palette.foreground,
                            unselectedTextColor = palette.foreground, indicatorColor = palette.hairline))
                }
            }
            Scaffold(Modifier.weight(1f), topBar = topBar,
                contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
                    if (bar) NavigationBar(containerColor = palette.soft, contentColor = palette.foreground, tonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0)) {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(selected = selectedTab == destination, onClick = { choose(destination) },
                                icon = { Icon(painterResource(destination.icon), null) }, label = label(destination),
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = palette.foreground,
                                    selectedTextColor = palette.foreground,
                                    unselectedIconColor = palette.foreground,
                                    unselectedTextColor = palette.foreground,
                                    indicatorColor = palette.hairline))
                        }
                    }
                }, content = content)
        }
    }
}
