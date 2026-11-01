package com.noahwallet.services.wallet

import java.util.concurrent.atomic.AtomicBoolean

/**
 * What bark's daemon did while Noah is in front, done by Noah itself so no round is ever joined interactively: each
 * foreground pass syncs and moves any live exit forward, and the first pass after the app comes to the front also runs
 * delegated maintenance, so VTXOs due for refresh go into a delegated round. Background jobs cover the time in between.
 */
internal class ForegroundWalletWork(
    private val sync: suspend () -> Unit,
    private val maintenance: suspend () -> Unit,
    /** Never throws but for cancellation; calls bark only while an exit is live. */
    private val progressExits: suspend () -> Unit,
) {
    private val maintenanceDue = AtomicBoolean(false)

    /** The app came to the front: the next pass that runs also runs delegated maintenance, once. */
    fun entered() = maintenanceDue.set(true)

    /** Every stage runs, admission checked before each; the first failure is rethrown once all have tried. */
    suspend fun run() {
        if (maintenanceDue.getAndSet(false)) WalletSync.attemptAll(sync, maintenance, progressExits)
        else WalletSync.attemptAll(sync, progressExits)
    }
}
