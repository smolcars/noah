package com.noahwallet.services.wallet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object WalletBackgroundActivity {
    /** [foreground]: how many of [count] are the app's own foreground pass. */
    data class Status(val count: Int = 0, val completions: Long = 0, val foreground: Int = 0) {
        val isRunning get() = count > 0
        /** A push, periodic or assistant job: what on-chain reads still wait out. */
        val isJobRunning get() = count > foreground
        val message get() = if (isRunning) "Background task in progress..." else null
    }
    private val lock = Any()
    private val jobs = Mutex()
    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = jobs.withLock { block() }
    suspend fun <T> exclusive(block: suspend () -> T): T = jobs.withLock { during(block = block) }
    private val status = MutableStateFlow(Status())
    val changes = status.asStateFlow()
    val isRunning get() = status.value.isRunning
    val isJobRunning get() = status.value.isJobRunning
    suspend fun <T> during(foreground: Boolean = false, block: suspend () -> T): T {
        val pass = if (foreground) 1 else 0
        synchronized(lock) { status.value = status.value.let { it.copy(count = it.count + 1, foreground = it.foreground + pass) } }
        try { return block() } finally {
            synchronized(lock) { status.value = status.value.let { Status(it.count - 1, it.completions + 1, it.foreground - pass) } }
        }
    }
}
