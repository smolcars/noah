package com.noahwallet.services.wallet

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object WalletBackgroundActivity {
    data class Status(val count: Int = 0, val completions: Long = 0) {
        val isRunning get() = count > 0
        val message get() = if (isRunning) "Background task in progress..." else null
    }
    private val lock = Any()
    private val jobs = Mutex()
    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = jobs.withLock { block() }
    suspend fun <T> exclusive(block: suspend () -> T): T = jobs.withLock { during(block) }
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
