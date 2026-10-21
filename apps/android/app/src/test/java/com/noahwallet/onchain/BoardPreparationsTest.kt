package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BoardPreparationsTest {
    private fun quote() = OnchainQuote(OnchainAction.board, "wallet", amount = 30_000, fee = 330, chainFee = 199,
        preparedBoard = BoardPrepared("id", "tx", 199, listOf(BoardInput("input", 0u))))
    @Test fun unsubmittedProofRetiresReservationButClaimedMissingAndSubmittedCannotRelease() = runBlocking {
        for (state in listOf("unclaimed", "discarded", "claimed", "submitted", "missing")) {
            val held = BoardPreparations({}, { error("must not retry") }, {})
            val quote = quote().copy(automatic = true)
            if (state != "missing") { held.retain("id"); held.bind(quote) }
            if (state == "discarded") held.discard("id")
            if (state in listOf("claimed", "submitted")) held.claim(quote)
            if (state == "submitted") held.consume(quote)
            assertEquals(state, state in listOf("unclaimed", "discarded"), held.abandonUnsubmitted(quote))
            assertFalse(held.abandonUnsubmitted(quote))
            held.discardAll()
        }
    }
    @Test fun exactQuoteCannotBeChangedReusedOrSubmittedAfterDiscard() = runBlocking {
        var discards = 0
        val held = BoardPreparations({ discards++ }, { error("must not submit") }, {})
        val quote = quote(); held.retain("id"); held.bind(quote)
        assertTrue(runCatching { held.claim(quote.copy(chainFee = 200)) }.isFailure)
        held.claim(quote)
        held.discard("id"); assertEquals(0, discards)
        held.consume(quote)
        assertTrue(runCatching { held.consume(quote) }.isFailure)
        held.discard("id", true)
        assertTrue(runCatching { held.claim(quote) }.isFailure)
        assertEquals(1, discards)
    }
    @Test fun expiryReleasesUnreviewedInputsAndCloseJoinsDisposal() = runBlocking {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            val held = BoardPreparations({ entered.complete(Unit); gate.await() }, { error("must not submit") }, {}, lifetimeMillis = 10)
            held.retain("id"); held.bind(quote())
            entered.await()
            assertTrue(runCatching { held.claim(quote()) }.isFailure)
            val closing = async { held.discardAll() }
            yield(); assertFalse(closing.isCompleted)
            gate.complete(Unit); closing.await()
        }
    }
    @Test fun acceptedBoardPersistenceIsRepairedWithSameIDBeforeDiscard() = runBlocking {
        val trace = mutableListOf<String>(); var repaired = false
        val held = BoardPreparations({ id -> trace.add("discard:$id"); if (!repaired) throw BarkError("cache", BarkErrorCode.PERSISTENCE) },
            { id -> trace.add("retry:$id"); repaired = true }, {})
        val quote = quote(); held.retain("id"); held.bind(quote); held.claim(quote); held.consume(quote)
        held.discard("id", true)
        assertEquals(listOf("discard:id", "retry:id", "discard:id"), trace)
        held.discardAll()
    }
    @Test fun failedCacheRepairRetainsHandleAndPreventsCloseUntilRetrySucceeds() = runBlocking {
        var repair = false; var retries = 0
        val held = BoardPreparations({ if (!repair) throw BarkError("cache", BarkErrorCode.PERSISTENCE) },
            { retries++; if (!repair) error("storage offline") }, {})
        val quote = quote(); held.retain("id"); held.bind(quote); held.claim(quote); held.consume(quote)
        assertTrue(runCatching { held.discardAll() }.isFailure)
        assertEquals(3, retries)
        repair = true; held.retryDiscards(); held.discardAll()
    }
    @Test fun cancelledDiscardCoalescesAndBlocksClaimUntilNativeSuccess() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); var calls = 0
        val held = BoardPreparations({ calls++; entered.complete(Unit); gate.await() }, {}, {})
        held.retain("id"); held.bind(quote())
        val caller = launch { held.discard("id") }
        entered.await(); caller.cancel()
        val other = async { held.discard("id") }; yield()
        assertTrue(runCatching { held.claim(quote()) }.isFailure)
        gate.complete(Unit); caller.join(); other.await()
        assertEquals(1, calls); held.discardAll()
    }
}
