package com.noahwallet.recurring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecurringPushDeadlineTest {
    @Test fun callbackReturnsWhileCancellationShieldedSettlementIsStillRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val settled = CompletableDeferred<Unit>()
        val budget = RecurringBudget()
        try {
            withTimeout(2000) { RecurringPushDeadline(scope, 20).run(budget) { withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                settled.complete(Unit)
            } } }
            assertTrue(entered.isCompleted); assertFalse(settled.isCompleted); assertFalse(budget.available)
            release.complete(Unit); withTimeout(2000) { settled.await() }
        } finally { release.complete(Unit); scope.cancel() }
    }
    @Test fun revokedBackgroundBudgetCannotStartWalletOpen() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val budget = RecurringBudget().apply { revoke() }
        var opened = false
        try {
            runCatching { RecurringPushDeadline(scope).run(budget) { opened = true } }
            assertFalse(opened)
        } finally { scope.cancel() }
    }

    @Test fun callerCancellationRevokesSubmissionBudgetWithoutCancellingSettlement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val settled = CompletableDeferred<Unit>()
        val budget = RecurringBudget()
        try {
            val callback = launch { RecurringPushDeadline(scope).run(budget) { entered.complete(Unit); release.await(); settled.complete(Unit) } }
            entered.await(); callback.cancelAndJoin(); assertFalse(budget.available); assertFalse(settled.isCompleted)
            release.complete(Unit); withTimeout(2000) { settled.await() }
        } finally { release.complete(Unit); scope.cancel() }
    }
}
