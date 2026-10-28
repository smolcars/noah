package com.noahwallet.send

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SendDiscardTest {
    @Test fun cancelledCallersCoalesceAndCannotClaimWhileCleanupRetries() = runBlocking {
        for (cancellation in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            var calls = 0; var reserved = true; var sawCancellation = false
            val held = SendPreparations({
                calls++; entered.complete(Unit); gate.await()
                sawCancellation = sawCancellation || !currentCoroutineContext().isActive
                if (calls == 1) { if (cancellation) throw CancellationException("ffi") else error("discard") }
                reserved = false
            }, {})
            held.retain("process-local"); held.bind("process-local", chainPayment, chainQuote)
            val caller = launch { held.discard("process-local") }
            entered.await(); caller.cancel()
            val other = launch { held.discard("process-local") }; yield()
            assertTrue(reserved)
            assertTrue(runCatching { held.claim(chainPayment, chainQuote) }.isFailure)
            gate.complete(Unit); caller.join(); other.join()
            assertEquals(2, calls); assertFalse(reserved); assertFalse(sawCancellation)
            held.discardAll(); assertEquals(2, calls)
        }
    }
    @Test fun exhaustedCleanupRetainsUnboundAndClaimedPreparationsUntilAnotherPass() = runBlocking {
        for (claimed in listOf(false, true)) {
            var calls = 0; var reserved = true
            val held = SendPreparations({
                calls++
                if (calls <= 3) error("discard")
                reserved = false
            }, {})
            held.retain("process-local")
            if (claimed) { held.bind("process-local", chainPayment, chainQuote); held.claim(chainPayment, chainQuote) }
            held.discard("process-local", true)
            assertEquals(3, calls); assertTrue(reserved)
            assertTrue(runCatching { held.claim(chainPayment, chainQuote) }.isFailure)
            held.retryDiscards()
            assertEquals(4, calls); assertFalse(reserved); held.discardAll()
        }
    }
    @Test fun closeRefusesOutstandingCleanupAndNextCloseCanFinish() = runBlocking {
        var calls = 0
        val held = SendPreparations({ calls++; if (calls <= 3) error("discard") }, {})
        held.retain("process-local"); held.bind("process-local", chainPayment, chainQuote)
        assertTrue(runCatching { held.discardAll() }.exceptionOrNull() is SendFailure)
        assertEquals(3, calls)
        held.discardAll(); assertEquals(4, calls)
    }
    @Test fun claimedReviewOnlyDisposesAfterSubmissionAndCannotBeConsumedTwice() = runBlocking {
        var calls = 0
        val held = SendPreparations({ calls++ }, {})
        held.retain("process-local"); held.bind("process-local", chainPayment, chainQuote); held.claim(chainPayment, chainQuote)
        held.discard("process-local"); assertEquals(0, calls)
        assertTrue(held.consume(chainPayment, chainQuote)); assertFalse(held.consume(chainPayment, chainQuote))
        held.discard("process-local", true); assertEquals(1, calls)
    }
}
