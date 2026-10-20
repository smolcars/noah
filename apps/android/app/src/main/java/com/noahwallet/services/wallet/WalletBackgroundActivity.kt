package com.noahwallet.services.wallet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object WalletBackgroundActivity {
    data class Status(val count: Int = 0, val completions: Long = 0) {
        val isRunning get() = count > 0
    }
    private val lock = Any()
    private val status = MutableStateFlow(Status())
    val changes = status.asStateFlow()
    val isRunning get() = status.value.isRunning
    suspend fun <T> during(block: suspend () -> T): T {
        synchronized(lock) { status.value = status.value.copy(count = status.value.count + 1) }
        try { return block() } finally {
            synchronized(lock) { status.value = Status(status.value.count - 1, status.value.completions + 1) }
        }
    }
}
