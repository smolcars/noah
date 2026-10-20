package com.noahwallet.services.wallet

import kotlinx.coroutines.sync.Mutex

/** Foreground reentry and minute ticks share admission before either outer gate can wait. */
internal class ForegroundWalletSync(private val withWalletMutation: suspend (suspend () -> Unit) -> Unit) {
    private val slot = Mutex()

    suspend fun run(operation: suspend () -> Unit) {
        if (!slot.tryLock()) return
        try {
            WalletBackgroundActivity.withExclusiveAccess {
                withWalletMutation(operation)
            }
        } finally { slot.unlock() }
    }
}
