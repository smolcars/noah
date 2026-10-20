package com.noahwallet.ui.home

import com.noahwallet.services.wallet.WalletBackgroundActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class HomeRoundObserverTest {
    private suspend fun settle() { repeat(20) { yield() } }

    @Test fun busyAtMountPausesAndCompletionRetriesAnEmptyOrFailedQuery() = runBlocking {
        val activity = MutableStateFlow(WalletBackgroundActivity.Status(count = 1))
        var queries = 0
        var shown = emptyList<Boolean>()
        val job = launch { observeHomeRounds(activity, MutableSharedFlow<Unit>(), {
            queries++; if (queries == 1) error("exclusive operation") else listOf(true)
        }, { it }, { shown = it }, { awaitCancellation() }) }
        settle(); assertEquals(0, queries)
        activity.value = WalletBackgroundActivity.Status(completions = 1)
        settle(); assertEquals(1, queries); assertTrue(shown.isEmpty())
        activity.value = WalletBackgroundActivity.Status(count = 1, completions = 1)
        settle(); assertEquals(1, queries)
        activity.value = WalletBackgroundActivity.Status(completions = 2)
        settle(); assertEquals(2, queries); assertEquals(listOf(true), shown)
        job.cancelAndJoin()
    }

    @Test fun jobEntirelyBetweenPollsRequeriesImmediatelyAndPausesTheTimer() = runBlocking {
        val activity = MutableStateFlow(WalletBackgroundActivity.Status())
        val ticks = Channel<Unit>()
        var queries = 0
        val job = launch { observeHomeRounds(activity, MutableSharedFlow<Unit>(), { queries++; listOf(true) }, { it }, {}, {
            assertEquals(30_000L, it); ticks.receive()
        }) }
        settle(); assertEquals(1, queries)
        // Conflated busy/idle still changes the completion revision.
        activity.value = WalletBackgroundActivity.Status(count = 1)
        activity.value = WalletBackgroundActivity.Status(completions = 1)
        settle(); assertEquals(2, queries)
        activity.value = WalletBackgroundActivity.Status(count = 1, completions = 1)
        settle(); assertFalse(ticks.trySend(Unit).isSuccess); assertEquals(2, queries)
        activity.value = WalletBackgroundActivity.Status(completions = 2)
        settle(); assertEquals(3, queries)
        job.cancelAndJoin()
    }

    @Test fun inactiveRoundsDoNotPollButWalletChangesAndJobCompletionStillRequery() = runBlocking {
        val activity = MutableStateFlow(WalletBackgroundActivity.Status())
        val changes = MutableSharedFlow<Unit>()
        var queries = 0
        val job = launch { observeHomeRounds(activity, changes, { queries++; emptyList<Boolean>() }, { it }, {}, { error("idle timer") }) }
        settle(); assertEquals(1, queries)
        changes.emit(Unit); settle(); assertEquals(2, queries)
        activity.value = WalletBackgroundActivity.Status(completions = 1)
        settle(); assertEquals(3, queries)
        job.cancelAndJoin()
    }

    @Test fun transientQueryFailureRetainsActivePollingUntilAFinalResult() = runBlocking {
        val ticks = Channel<Unit>()
        var queries = 0
        var shown = emptyList<Boolean>()
        val job = launch { observeHomeRounds(MutableStateFlow(WalletBackgroundActivity.Status()), MutableSharedFlow<Unit>(), {
            queries++
            if (queries == 2) error("temporary query failure")
            listOf(queries < 3)
        }, { it }, { shown = it }, { ticks.receive() }) }
        settle(); assertEquals(listOf(true), shown)
        ticks.send(Unit); settle(); assertEquals(2, queries); assertEquals(listOf(true), shown)
        ticks.send(Unit); settle(); assertEquals(3, queries); assertEquals(listOf(false), shown)
        assertFalse(ticks.trySend(Unit).isSuccess)
        job.cancelAndJoin()
    }

    @Test fun failedQueryOnRefreshOrForegroundKeepsKnownActiveRoundsPolling() = runBlocking {
        val ticks = Channel<Unit>()
        var queries = 0
        val job = launch { observeHomeRounds(MutableStateFlow(WalletBackgroundActivity.Status()), MutableSharedFlow<Unit>(), {
            if (++queries == 1) error("foreground query failed")
            emptyList<Boolean>()
        }, { it }, {}, { ticks.receive() }, initiallyActive = true) }
        settle(); assertEquals(1, queries)
        ticks.send(Unit); settle(); assertEquals(2, queries)
        assertFalse(ticks.trySend(Unit).isSuccess)
        job.cancelAndJoin()
    }

    @Test fun overlappingJobsPublishCompletionEvenWhenOneFails() = runBlocking {
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        val before = WalletBackgroundActivity.changes.value.completions
        val a = launch { WalletBackgroundActivity.during { first.await() } }
        val b = launch { runCatching { WalletBackgroundActivity.during { second.await(); error("job") } } }
        settle(); assertEquals(2, WalletBackgroundActivity.changes.value.count)
        first.complete(Unit); a.join(); assertTrue(WalletBackgroundActivity.isRunning)
        second.complete(Unit); b.join(); assertFalse(WalletBackgroundActivity.isRunning)
        assertEquals(before + 2, WalletBackgroundActivity.changes.value.completions)
    }
}
