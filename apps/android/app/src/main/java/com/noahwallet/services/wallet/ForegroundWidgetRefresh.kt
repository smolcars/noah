package com.noahwallet.services.wallet

import kotlinx.coroutines.CancellationException

/** Summary and expiry share the caller's foreground admission, never payment settlement. */
internal object ForegroundWidgetRefresh {
    suspend fun read(summary: suspend () -> WalletSession.Summary?, expiry: suspend () -> Long?,
                     checkStage: () -> Unit, publish: suspend (WalletBalance, Long?) -> Unit): WalletSession.Summary? {
        checkStage()
        val latest = summary()
        checkStage()
        if (latest == null) return null
        try {
            val blocks = expiry()
            checkStage()
            publish(latest.balance, blocks)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { checkStage() }
        return latest
    }
}
