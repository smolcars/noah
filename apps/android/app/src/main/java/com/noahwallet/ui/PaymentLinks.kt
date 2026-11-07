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
    /** A "Payment received" notification's payment, held and gated like a widget route. */
    var payment by mutableStateOf<com.noahwallet.history.PaymentTap?>(null); private set
    /** A recent recipient's launcher shortcut or an assistant's Pay ([PayRoute]), held and gated like a link. */
    var pay by mutableStateOf<PayRoute?>(null); private set
    /** A payment, review or approval was in progress when the last link came. */
    var blocked by mutableStateOf(false)

    /** [savedState]: an activity restored from saved state took its intent when it was first created. */
    fun receive(intent: Intent, savedState: Bundle? = null) {
        // Recents relaunches a finished task with the intent that first opened it.
        if (savedState != null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action == Intent.ACTION_VIEW && intent.scheme?.lowercase() in schemes) pending = intent.dataString
        else PayRoute.of(intent)?.let { pay = it } ?: WidgetRoute.of(intent)?.let { route = it }
        com.noahwallet.history.PaymentTap.of(intent)?.let { payment = it }
    }

    fun drop() { pending = null; route = null; payment = null; pay = null }

    /**
     * Opens Activity on the notification's [payment] once Send has read its saved payment, then forgets it. Like a
     * widget's Home, it leaves the app where it is while Send is preparing, reviewing or holding a payment, or a
     * recurring approval is on screen: leaving Send would discard its review.
     */
    fun deliverPayment(send: SendController, sendBusy: IntState, approvals: IntState, open: (com.noahwallet.history.PaymentTap) -> Unit) {
        val next = payment ?: return
        if (send.loading) return
        payment = null
        if (WidgetRoute.target(WidgetRoute.home, sendBusy = send.locked || sendBusy.intValue > 0, approvalOpen = approvals.intValue > 0) != null) open(next)
    }

    /**
     * Opens the widget's [route] once Send has read its saved payment, then forgets it. Nothing is left, discarded or
     * replaced ([WidgetRoute.target]): Send preparing or showing a review on screen ([sendBusy]) or holding a payment,
     * and a recurring approval on screen ([approvals]), keep their place. Nothing pays from a widget.
     */
    fun deliverRoute(send: SendController, sendBusy: IntState, approvals: IntState, open: (WidgetRoute) -> Unit) {
        val next = route ?: return
        if (send.loading) return
        route = null
        WidgetRoute.target(next, sendBusy = send.locked || sendBusy.intValue > 0, approvalOpen = approvals.intValue > 0)?.let(open)
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

    /** Gives [pay] to [open] once Send has read its saved payment, under [deliver]'s rule: nothing in flight or in review moves. */
    fun deliverPay(send: SendController, reviews: IntState, open: (PayRoute) -> Unit) {
        val next = pay ?: return
        if (send.loading) return
        pay = null
        if (send.locked || reviews.intValue > 0) blocked = true else open(next)
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

/** Drops a held link or widget route when the launch gate ignores it, including one that arrives while a gate is showing. */
@Composable
internal fun DropIgnoredLinks(links: PaymentLinks, step: LinkStep) {
    androidx.compose.runtime.LaunchedEffect(step, links.pending, links.route, links.payment, links.pay) { if (step == LinkStep.ignore) links.drop() }
}

/** The widget's routes: `com.noahwallet.<variant>://widget/<route>`, sent as an explicit intent's data. */
enum class WidgetRoute {
    home, receive, scan, send;

    companion object {
        fun of(intent: Intent, variant: String = com.noahwallet.BuildConfig.APP_VARIANT): WidgetRoute? {
            val data = intent.data ?: return null
            if (intent.action != Intent.ACTION_VIEW || data.scheme != "com.noahwallet.$variant" || data.host != "widget") return null
            return entries.firstOrNull { "/${it.name}" == data.path }
        }

        /**
         * Where [route] goes, or null to leave the app where it is (widgets.md, Opening the app from a widget). Nothing
         * moves under a recurring approval. While Send is preparing, reviewing or holding a payment, Send and Scan open
         * the Send tab as it is (no scanner, no new request) and Home and Receive stay put.
         */
        fun target(route: WidgetRoute, sendBusy: Boolean, approvalOpen: Boolean): WidgetRoute? = when {
            approvalOpen -> null
            !sendBusy -> route
            route == scan || route == send -> send
            else -> null
        }
    }
}

/**
 * Send filled in (voice-assistants.md): `com.noahwallet.<variant>://pay?to=<recipient>&amount=<sats>`, sent as an
 * explicit intent's data by a recent recipient's launcher shortcut or an assistant's Pay. Send stops at its review at
 * the latest; nothing pays until the user holds to send.
 */
data class PayRoute(val recipient: String, val amountSats: Long? = null) {
    fun uri(variant: String = com.noahwallet.BuildConfig.APP_VARIANT): android.net.Uri = android.net.Uri.Builder()
        .scheme("com.noahwallet.$variant").authority("pay").appendQueryParameter("to", recipient)
        .apply { amountSats?.let { appendQueryParameter("amount", it.toString()) } }.build()

    companion object {
        fun of(intent: Intent, variant: String = com.noahwallet.BuildConfig.APP_VARIANT): PayRoute? {
            val data = intent.data ?: return null
            if (intent.action != Intent.ACTION_VIEW || data.scheme != "com.noahwallet.$variant" || data.host != "pay") return null
            val recipient = data.getQueryParameter("to")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return PayRoute(recipient, data.getQueryParameter("amount")?.toLongOrNull()?.takeIf { it > 0 })
        }
    }
}

/** Opens [uri] in [com.noahwallet.MainActivity] the way a widget tap does: the app keeps its place (singleTask, no task reset). */
fun routeIntent(context: android.content.Context, uri: android.net.Uri): Intent =
    Intent(Intent.ACTION_VIEW, uri, context, com.noahwallet.MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
