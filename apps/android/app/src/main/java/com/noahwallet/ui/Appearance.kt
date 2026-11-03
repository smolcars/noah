package com.noahwallet.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build

/**
 * Settings → Display → Appearance (design/appearance.md): follow the phone, or keep Noah light or dark. Stored by name
 * under [key], the same key and strings as iOS; missing or unknown is Automatic, so upgrades open as they did before.
 */
enum class Appearance {
    automatic, light, dark;

    /** Whether Noah is dark while the phone is [phoneDark]. */
    fun dark(phoneDark: Boolean) = when (this) { automatic -> phoneDark; light -> false; dark -> true }

    /** [UiModeManager.setApplicationNightMode]'s mode: Automatic clears the app's own mode, so the phone's applies. */
    val nightMode get() = when (this) {
        automatic -> UiModeManager.MODE_NIGHT_AUTO; light -> UiModeManager.MODE_NIGHT_NO; dark -> UiModeManager.MODE_NIGHT_YES
    }

    /**
     * Gives the platform the same choice (API 31+), so system dialogs, the system bars and the splash match. Android keeps
     * it for the next launch too, so a relaunch in Dark never starts light. MainActivity handles the uiMode change in place.
     */
    fun applyToPlatform(context: Context) {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(UiModeManager::class.java).setApplicationNightMode(nightMode)
    }

    companion object {
        const val key = "appearance"
        fun parse(stored: String?) = entries.firstOrNull { it.name == stored } ?: automatic

        /** Whether picking [to] over [from] changes Noah's colours; null when that rests on a [phoneDark] Android can't tell yet. */
        fun turns(from: Appearance, to: Appearance, phoneDark: Boolean?): Boolean? = when {
            from == to -> false
            phoneDark != null -> from.dark(phoneDark) != to.dark(phoneDark)
            automatic == from || automatic == to -> null
            else -> true
        }
    }
}
