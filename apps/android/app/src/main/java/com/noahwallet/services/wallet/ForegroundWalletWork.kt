package com.noahwallet.services.wallet

import java.util.concurrent.atomic.AtomicBoolean

/**
 * What bark's daemon did while Noah is in front, done by Noah itself so no round is ever joined interactively: each
 * foreground pass syncs and moves any live exit forward, and the first pass after the app comes to the front, or on a
 * wallet that has just opened (created, restored or reopened), also runs delegated maintenance, so VTXOs due for refresh
 * go into a delegated round. Background jobs cover the time in between.
 */
internal class ForegroundWalletWork(
    private val sync: suspend () -> Unit,
    private val maintenance: suspend () -> Unit,
    /** How many refreshes bark has recorded, failed ones included (its `bark.round` refresh movements). */
    private val refreshes: suspend () -> Int,
    /** Never throws but for cancellation; calls bark only while an exit is live. */
    private val progressExits: suspend () -> Unit,
) {
    private val maintenanceDue = AtomicBoolean(false)
    /** The open ([WalletSession.walletChanges]) the last pass ran on; passes never overlap ([ForegroundWalletSync]). */
    @Volatile private var lastOpen: Long? = null

    /** The app came to the front: the next pass that runs also runs delegated maintenance, once. */
    fun entered() = maintenanceDue.set(true)

    /**
     * [open] is the wallet's current open ([WalletSession.walletChanges]). Every stage runs, admission checked before
     * each; the first failure is rethrown once all have tried. A maintenance that doesn't complete (failed offline, or
     * the pass ended first) stays due for the next pass: bark's sync never moves an interactive attempt an older version
     * left, so only maintenance frees its VTXOs. One that failed after creating its refresh (the server down or refusing
     * it) doesn't: each try would leave another failed renewal in history, so the next entry tries again.
     */
    suspend fun run(open: Long) {
        val maintain = maintenanceDue.getAndSet(false) or (open != lastOpen)
        lastOpen = open
        var settled = !maintain
        try {
            if (maintain) WalletSync.attemptAll(sync, {
                val created = refreshes()
                var completed = false
                try { maintenance(); completed = true }
                finally { settled = completed || refreshes() > created }
            }, progressExits)
            else WalletSync.attemptAll(sync, progressExits)
        } finally {
            if (!settled) maintenanceDue.set(true)
        }
    }
}
