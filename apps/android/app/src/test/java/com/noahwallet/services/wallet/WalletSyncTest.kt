package com.noahwallet.services.wallet

import com.noahwallet.recurring.RecurringBudget
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WalletSyncTest {
    @Test fun failedSyncStillAttemptsOtherWalletAndLightningClaims() = runBlocking {
        val stages = mutableListOf<Int>()
        val first = IllegalStateException("onchain")
        val result = runCatching { WalletSync().run(
            { stages += 1; throw first },
            { stages += 2; error("ark") },
            { stages += 3 },
        ) }
        assertSame(first, result.exceptionOrNull())
        assertEquals(listOf(1, 2, 3), stages)
    }

    @Test fun overlappingSyncSkipsUntilCountedPassSettles() = runBlocking {
        val flight = WalletSync()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { flight.run({ calls++; entered.complete(Unit); release.await() }) }
        entered.await()
        flight.run({ calls++ })
        assertEquals(1, calls)
        release.complete(Unit); first.await()
        flight.run({ calls++ })
        assertEquals(2, calls)
    }

    @Test fun reconciliationWaitsForForegroundSyncRatherThanUsingStaleState() = runBlocking {
        val flight = WalletSync()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var reconciled = false
        val first = async { flight.run({ entered.complete(Unit); release.await() }) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { flight.serialized { reconciled = true } }
        assertFalse(reconciled)
        release.complete(Unit); first.await(); second.await()
        assertTrue(reconciled)
    }

    @Test fun foregroundRevocationStopsNewStagesButDrainsAdmittedWork() = runBlocking {
        val flight = WalletSync(); val budget = RecurringBudget()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var claims = 0
        val work = async(WalletExecutionBudget(budget)) {
            runCatching { flight.run({ entered.complete(Unit); release.await() }, { claims++ }) }
        }
        entered.await(); budget.revoke()
        assertFalse(work.isCompleted)
        release.complete(Unit)
        assertTrue(work.await().exceptionOrNull() is CancellationException)
        assertEquals(0, claims)
        flight.run({ claims++ }); assertEquals(1, claims)
    }
}
