package com.noahwallet.assistant

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.noahwallet.BuildConfig
import com.noahwallet.R
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.send.SendDestination
import com.noahwallet.services.logging.AppLog
import com.noahwallet.ui.PayRoute
import com.noahwallet.ui.routeIntent
import com.noahwallet.ui.send.SendWhoRules
import java.util.Locale

/**
 * Launcher shortcuts (voice-assistants.md). Receive, Scan and Send are static (each flavor's res/xml/shortcuts.xml);
 * these are the dynamic ones: Send's recent recipients, by the rule its To screen uses, each opening Send filled in.
 */
internal object LauncherShortcuts {
    const val RECENTS = 4
    private const val PREFIX = "recent:"
    /** Receive, Scan and Send (res/xml/shortcuts.xml) count against the launcher's per-activity limit too. */
    private const val STATIC = 3

    /** Shows [history]'s recent recipients, newest first; nothing changes while they're the ones already shown. */
    fun update(context: Context, history: List<HistoryTransaction>, ownDomain: String?, network: String = BuildConfig.APP_VARIANT) {
        guarded(context, "update") {
            val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - STATIC).coerceIn(0, RECENTS)
            val recents = SendWhoRules.recents(history, network, room).associateBy { PREFIX + SendDestination.cleaned(it).lowercase(Locale.ROOT) }
            val shown = ShortcutManagerCompat.getDynamicShortcuts(context).filter { it.id.startsWith(PREFIX) }.sortedBy { it.rank }.map { it.id }
            if (shown == recents.keys.toList()) return@guarded
            (shown - recents.keys).takeIf { it.isNotEmpty() }?.let { ShortcutManagerCompat.removeDynamicShortcuts(context, it) }
            recents.entries.forEachIndexed { rank, (id, destination) ->
                val who = SendWhoRules.of(destination, SendDestination.parse(destination, network).destinationType, ownDomain)
                ShortcutManagerCompat.pushDynamicShortcut(context, ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(who.name.substringBefore('@')).setLongLabel(who.name)
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_person))
                    .setIntent(routeIntent(context, PayRoute(destination).uri(network))).setRank(rank).build())
            }
        }
    }

    /** A deleted wallet's recipients leave the launcher with it. */
    fun clear(context: Context) = guarded(context, "clear") { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }

    // Shortcuts are a convenience: a launcher that refuses them (rate limit, locked user) never stops the app.
    @Suppress("TooGenericExceptionCaught")
    private inline fun guarded(context: Context, action: String, block: () -> Unit) = try { block() } catch (failure: RuntimeException) {
        AppLog(context, "shortcuts").w("Launcher shortcuts $action failed (${failure::class.simpleName})")
    }
}
