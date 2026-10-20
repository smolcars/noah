package com.noahwallet.services.wallet

import java.util.concurrent.atomic.AtomicInteger

object WalletBackgroundActivity {
    private val count = AtomicInteger()
    val isRunning get() = count.get() > 0
    suspend fun <T> during(block: suspend () -> T): T {
        count.incrementAndGet()
        try { return block() } finally { count.decrementAndGet() }
    }
}
