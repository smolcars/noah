package com.noahwallet.ui

import androidx.navigation3.runtime.NavKey
import com.noahwallet.ui.settings.SettingsDestination
import org.junit.Assert.*
import org.junit.Test

class MainTabsTest {
    @Test fun switchingTabsRetainsTheHomeSettingsStack() {
        val home = mutableListOf<NavKey>(Destination.Home, SettingsDestination.Settings, SettingsDestination.Profile, SettingsDestination.Email)
        val expected = home.toList()
        for (tab in Destination.entries) {
            assertEquals(if (tab == Destination.Home) expected else listOf(tab), mainTabEntries(tab, home))
            assertEquals(expected, home)
        }
        assertSame(home, mainTabEntries(Destination.Home, home))
        home.removeAt(home.lastIndex)
        assertEquals(SettingsDestination.Profile, mainTabEntries(Destination.Home, home).last())
    }
}
