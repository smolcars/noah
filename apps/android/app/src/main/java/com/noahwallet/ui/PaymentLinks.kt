package com.noahwallet.ui

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.noahwallet.send.SendController

/**
 * OS deep links (docs/native-rewrite/deep-links.md): a `bitcoin:`, `lightning:` or `lnurlp:` link takes a scanned code's
 * path into Send. It's held here, in memory only: a fold or rotation keeps it, process death drops it. Never log it.
 */
class PaymentLinks : ViewModel() {
    var pending by mutableStateOf<String?>(null); private set
    /** A widget's Home, Receive, Scan or Send (widgets.md, Opening the app from a widget), held and gated like a link. */
    var route by mutableStateOf<WidgetRoute?>(null); private set
    /** A payment, review or approval was in progress when the last link came. */
    var blocked by mutableStateOf(false)

    /** [savedState]: an activity restored from saved state took its intent when it was first created. */
    fun receive(intent: Intent, savedState: Bundle? = null) {
        // Recents relaunches a finished task with the intent that first opened it.
        if (savedState != null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action == Intent.ACTION_VIEW && intent.scheme?.lowercase() in schemes) pending = intent.dataString
        else WidgetRoute.of(intent)?.let { route = it }
    }

    fun drop() { pending = null; route = null }

    /**
     * Opens the widget's [route] once Send has read its saved payment, then forgets it. Nothing is discarded or
     * replaced: a review or approval on screen stays as it is, and Scan opens the Send tab as it is while a payment
     * holds it. Nothing pays from a widget.
     */
    fun deliverRoute(send: SendController, reviews: IntState, open: (WidgetRoute) -> Unit) {
        val next = route ?: return
        if (send.loading) return
        route = null
        WidgetRoute.target(next, paying = send.locked, reviewing = reviews.intValue > 0)?.let(open)
    }

    /**
     * Gives the link to [scan] once Send has read its saved payment. A payment in flight, or any Send review or recurring
     * approval open in [reviews] ([ReviewOpen]), rejects it before anything moves: the screen and payment stay as they are.
     */
    fun deliver(send: SendController, reviews: IntState, scan: (String) -> Unit) {
        val link = pending ?: return
        if (send.loading) return
        pending = null
        if (send.locked || reviews.intValue > 0) blocked = true else scan(link)
    }

    companion object {
        val schemes = setOf("bitcoin", "lightning", "lnurlp")
        const val blockedMessage = "Finish the current payment before opening another payment request."
    }
}

/** Holds [count] up while [open]: a Send review or recurring approval that an OS link mustn't replace. */
@Composable
internal fun ReviewOpen(count: MutableIntState, open: Boolean) {
    if (open) DisposableEffect(count) { count.intValue++; onDispose { count.intValue-- } }
}

/** What a held link does at the launch gate. */
enum class LinkStep { wait, open, ignore }

/** The widget's routes: `com.noahwallet.<variant>://widget/<route>`, sent as an explicit intent's data. */
enum class WidgetRoute {
    home, receive, scan, send;

    companion object {
        fun of(intent: Intent, variant: String = com.noahwallet.BuildConfig.APP_VARIANT): WidgetRoute? {
            val data = intent.data ?: return null
            if (intent.action != Intent.ACTION_VIEW || data.scheme != "com.noahwallet.$variant" || data.host != "widget") return null
            return entries.firstOrNull { "/${it.name}" == data.path }
        }

        /** Where [route] may go: nowhere over an open review or approval; Scan, while a payment holds Send, to Send as it is. */
        fun target(route: WidgetRoute, paying: Boolean, reviewing: Boolean): WidgetRoute? = when {
            reviewing -> null
            route == scan && paying -> send
            else -> route
        }
    }
}
