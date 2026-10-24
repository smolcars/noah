package com.noahwallet.ui

import android.content.Intent
import android.os.Bundle
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
    /** Send was holding a payment when the last link came. */
    var blocked by mutableStateOf(false)

    /** [savedState]: an activity restored from saved state took its intent when it was first created. */
    fun receive(intent: Intent, savedState: Bundle? = null) {
        // Recents relaunches a finished task with the intent that first opened it.
        if (savedState != null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action == Intent.ACTION_VIEW && intent.scheme?.lowercase() in schemes) pending = intent.dataString
    }

    fun drop() { pending = null }

    /** Gives the link to [scan] once Send has read its saved payment; a payment in flight keeps Send and blocks it instead. */
    fun deliver(send: SendController, scan: (String) -> Unit, showSend: () -> Unit) {
        val link = pending ?: return
        if (send.loading) return
        pending = null
        if (send.locked) { blocked = true; showSend() } else scan(link)
    }

    companion object {
        val schemes = setOf("bitcoin", "lightning", "lnurlp")
        const val blockedMessage = "Finish the current payment before opening another payment request."
    }
}

/** What a held link does at the launch gate. */
enum class LinkStep { wait, open, ignore }
