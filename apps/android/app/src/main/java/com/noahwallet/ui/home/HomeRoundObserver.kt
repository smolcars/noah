package com.noahwallet.ui.home

import com.noahwallet.services.wallet.WalletBackgroundActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive

/** Completion revisions also catch jobs which begin and end between round polls. */
@Suppress("LongParameterList") // its flows, query, clock and sink are injected so HomeRoundObserverTest can drive each
internal suspend fun <T> observeHomeRounds(
    activity: StateFlow<WalletBackgroundActivity.Status>,
    walletChanges: Flow<*>,
    query: suspend () -> List<T>,
    active: (T) -> Boolean,
    publish: (List<T>) -> Unit,
    sleep: suspend (Long) -> Unit = { delay(it) },
    initiallyActive: Boolean = false,
) {
    var hasActiveRounds = initiallyActive
    activity.combine(walletChanges.map { Unit }.onStart { emit(Unit) }) { status, _ -> status }.collectLatest { status ->
        if (status.isRunning) return@collectLatest
        do {
            try {
                val rounds = query()
                publish(rounds)
                hasActiveRounds = rounds.any(active)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retain known active rounds and their polling after transient errors. */ }
            if (!hasActiveRounds) return@collectLatest
            sleep(30_000)
        } while (currentCoroutineContext().isActive)
    }
}
