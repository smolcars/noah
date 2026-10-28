package com.noahwallet.services.wallet

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Drop overlapping ticks; attempt independent RN stages until foreground admission is revoked. */
internal class WalletSync {
    private val flight = Mutex()
    suspend fun run(vararg stages: suspend () -> Unit) {
        if (!flight.tryLock()) return
        try { attemptAll(*stages) } finally { flight.unlock() }
    }

    suspend fun serialized(stage: suspend () -> Unit) = flight.withLock {
        currentCoroutineContext().checkWalletAdmission()
        stage()
    }

    companion object {
        suspend fun attemptAll(vararg stages: suspend () -> Unit) {
            val caller = currentCoroutineContext()
            var failure: Exception? = null
            for (stage in stages) {
                caller.checkWalletAdmission()
                // Every stage runs; the first failure is rethrown once all have tried.
                try { stage() } catch (@Suppress("TooGenericExceptionCaught") error: Exception) { if (failure == null) failure = error }
            }
            failure?.let { throw it }
        }
    }
}
