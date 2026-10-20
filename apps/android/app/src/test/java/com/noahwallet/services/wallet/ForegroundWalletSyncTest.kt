package com.noahwallet.services.wallet

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class ForegroundWalletSyncTest {
    @Test fun foregroundReentryDropsBeforeWaitingOnEitherOuterGate() = runBlocking {
        for (backgroundGate in listOf(false, true)) {
            val mutation = Mutex()
            val foreground = ForegroundWalletSync { mutation.withLock { it() } }
            val flight = WalletSync()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val gateEntered = CompletableDeferred<Unit>(); val gateRelease = CompletableDeferred<Unit>()
            var passes = 0
            val holder = launch {
                val hold: suspend () -> Unit = { gateEntered.complete(Unit); gateRelease.await() }
                if (backgroundGate) WalletBackgroundActivity.withExclusiveAccess(hold) else mutation.withLock { hold() }
            }
            gateEntered.await()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                foreground.run { flight.run({ passes++; entered.complete(Unit); release.await() }) }
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) { foreground.run { flight.run({ passes++ }) } }
            gateRelease.complete(Unit); holder.join(); entered.await()
            release.complete(Unit); first.await(); second.await()
            assertEquals("Overlapping request queued behind outer gate (background=$backgroundGate)", 1, passes)
            foreground.run { flight.run({ passes++ }) }
            assertEquals(2, passes)
        }
    }
}
