package com.noahwallet.services.wallet

import kotlinx.coroutines.CancellationException

/** Summary and the widget's extras share the caller's foreground admission, never payment settlement. */
internal object ForegroundWidgetRefresh {
    suspend fun <T> read(summary: suspend () -> WalletSession.Summary?, extras: suspend (WalletBalance) -> T,
        checkStage: () -> Unit, publish: suspend (WalletBalance, T) -> Unit): WalletSession.Summary? {
        checkStage()
        val latest = summary()
        checkStage()
        if (latest == null) return null
        try {
            val read = extras(latest.balance)
            checkStage()
            publish(latest.balance, read)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { checkStage() }
        return latest
    }
}
