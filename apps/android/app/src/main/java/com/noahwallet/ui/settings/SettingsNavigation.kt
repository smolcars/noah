package com.noahwallet.ui.settings

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Settings' pages (docs/native-rewrite/design/settings.md). [Settings] is the list; on Home's stack it stands for the
 * whole of Settings, whose own stack holds the pages.
 */
@Serializable
enum class SettingsDestination : NavKey {
    Settings, Profile, Lightning, Email, Seed, Backup, Vtxos, Board, Recurring, Exits,
    Notifications, UnifiedPush, Advanced, Ark, Esplora, Logs, Debug, Feedback, About,
    /** Today's battery screen, until Notifications takes it in. */
    Battery,
}

internal object SettingsRoutes {
    private val parents = listOf(SettingsDestination.Lightning, SettingsDestination.Email).associateWith { SettingsDestination.Profile } +
        listOf(SettingsDestination.UnifiedPush, SettingsDestination.Battery).associateWith { SettingsDestination.Notifications } +
        listOf(SettingsDestination.Ark, SettingsDestination.Esplora, SettingsDestination.Logs, SettingsDestination.Debug).associateWith { SettingsDestination.Advanced }

    /** The page a page's Back returns to; null for the list. */
    fun parent(page: NavKey): NavKey? = when (page) {
        SettingsDestination.Settings -> null
        is SettingsDestination -> parents[page] ?: SettingsDestination.Settings
        else -> null
    }

    /** What a deep link puts on Settings' stack: the list, every page above it, then [page]. */
    fun chain(page: NavKey): List<NavKey> = generateSequence(page, ::parent).toList().reversed()

    /** A deep link: Settings sits right above Home (anything over it closes), holding [page] with its parents beneath. */
    fun open(home: MutableList<NavKey>, settings: MutableList<NavKey>, page: NavKey) {
        settings.clear(); settings.addAll(chain(page))
        while (home.size > 1) home.removeAt(home.lastIndex)
        home.add(SettingsDestination.Settings)
    }
}
