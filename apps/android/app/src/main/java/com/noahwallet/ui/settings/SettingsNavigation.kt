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
}

/** One VTXO (Activity's VTXO links open it). */
@Serializable data class VtxoPage(val id: String) : NavKey

/** One recurring payment: what Activity's "Repeats ›" opens. */
@Serializable data class SchedulePage(val id: String) : NavKey

/** The recurring payment editor; [id] null is a new payment. */
@Serializable data class EditorPage(val id: String? = null) : NavKey

internal object SettingsRoutes {
    private val parents = listOf(SettingsDestination.Lightning, SettingsDestination.Email).associateWith { SettingsDestination.Profile } +
        (SettingsDestination.UnifiedPush to SettingsDestination.Notifications) +
        listOf(SettingsDestination.Ark, SettingsDestination.Esplora, SettingsDestination.Logs, SettingsDestination.Debug).associateWith { SettingsDestination.Advanced }

    /** The page a page's Back returns to; null for the list. */
    fun parent(page: NavKey): NavKey? = when (page) {
        SettingsDestination.Settings -> null
        is SettingsDestination -> parents[page] ?: SettingsDestination.Settings
        is VtxoPage -> SettingsDestination.Vtxos
        is SchedulePage, is EditorPage -> SettingsDestination.Recurring
        else -> null
    }

    /** Whether [page] is the one on top, so a finishing save leaves only its own page. */
    fun top(stack: List<NavKey>, page: NavKey) = stack.lastOrNull() == page

    /** What a deep link puts on Settings' stack: the list, every page above it, then [page]. */
    fun chain(page: NavKey): List<NavKey> = generateSequence(page, ::parent).toList().reversed()

    /** A deep link: Settings sits right above Home (anything over it closes), holding [page] with its parents beneath. */
    fun open(home: MutableList<NavKey>, settings: MutableList<NavKey>, page: NavKey) {
        settings.clear(); settings.addAll(chain(page))
        while (home.size > 1) home.removeAt(home.lastIndex)
        home.add(SettingsDestination.Settings)
    }
}
