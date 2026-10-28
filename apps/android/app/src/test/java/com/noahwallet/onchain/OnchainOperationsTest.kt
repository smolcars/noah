package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Quotes, estimates, starts and progress of exits and boards. */
internal class OnchainOperationsTest : OnchainOperationsFixture() {
    @Test fun orphanBoardWithoutCheckpointLocalTransactionOrNetworkEvidenceAllowsExplicitOverride() = runBlocking { fixture { wallet, ops, journal, _ ->
        val reviewed = ops.quote(OnchainAction.board, amount = 30_000)
        runCatching { journal.perform(reviewed) { record -> record("board-tx"); error("process died before checkpoint") } }
        wallet.fundingTransactions = emptyList()
        wallet.chainStatus = TransactionStatus.NotFound
        // No pending board, BOARD movement or transaction names it, and the process died before the SDK answered (no
        // Uncertain outcome), so the explicit override stays available.
        assertTrue(wallet.boardTransactionIDs().isEmpty())
        assertFalse(ops.check(reviewed.id))
        assertNull(ops.autoBoardFunding())
        val uncertain = journal.loadAll().single()
        assertEquals("board-tx", uncertain.txid)
        assertNull(uncertain.receipt)
        assertTrue(uncertain.blocks(OnchainAction.board, emptyList()))
        journal.overrideUncertain(reviewed.id)
        val overridden = journal.loadAll().single()
        assertNotNull(overridden.overrideAudit)
        assertFalse(overridden.blocks(OnchainAction.board, emptyList()))
        ops.execute(ops.quote(OnchainAction.board, amount = 30_000))
        assertEquals(1, wallet.calls.count { it.startsWith("submit:") })
    } }
    @Test fun renewalChecksItsVtxosAreStillDueUnderTheLockJustBeforeSubmitting() = runBlocking { fixture { wallet, ops, journal, _ ->
        val expiry = Wallet.vtxo("a").expiryHeight.toLong()
        wallet.expiringIDs = setOf("a"); wallet.tipHeight = expiry - 10
        val reviewed = ops.quote(OnchainAction.refresh, listOf("a"))
        // A sync holds the wallet's mutation lock while Renew's confirmation waits for it, and moves the tip past
        // the VTXO's expiry. Bark still reports the VTXO spendable, so the refresh quote alone would pass.
        val lock = kotlinx.coroutines.sync.Mutex(locked = true)
        val confirming = async { lock.withLock { runCatching { ops.execute(reviewed, renewalThreshold = 144u) } } }
        repeat(5) { yield() }
        wallet.tipHeight = expiry
        lock.unlock()
        val failure = confirming.await().exceptionOrNull()
        assertEquals(OnchainFailure.changed, (failure as OnchainException).reason)
        assertTrue("nothing was submitted", wallet.calls.none { it.startsWith("refresh") })
        assertTrue(journal.loadAll().isEmpty())
        // Out of the expiring set (a sync's new threshold view) is the same: nothing is submitted.
        wallet.tipHeight = expiry - 10; wallet.expiringIDs = emptySet()
        assertEquals(OnchainFailure.changed, (runCatching { ops.execute(ops.quote(OnchainAction.refresh, listOf("a")), renewalThreshold = 144u) }
            .exceptionOrNull() as OnchainException).reason)
        assertTrue(wallet.calls.none { it.startsWith("refresh") })
        // Still due when the lock is free: it's submitted.
        wallet.expiringIDs = setOf("a")
        ops.execute(ops.quote(OnchainAction.refresh, listOf("a")), renewalThreshold = 144u)
        assertEquals(listOf("refresh:a"), wallet.calls.filter { it.startsWith("refresh") })
        // A manual refresh isn't Renew: it isn't held to the expiring set, so an expired VTXO still refreshes as before.
        wallet.items = listOf(Wallet.vtxo("a"), Wallet.vtxo("b")); wallet.tipHeight = expiry; wallet.expiringIDs = emptySet()
        ops.execute(ops.quote(OnchainAction.refresh, listOf("b")))
        assertEquals(listOf("refresh:a", "refresh:b"), wallet.calls.filter { it.startsWith("refresh") })
    } }
    @Test fun incompleteProbeCleanupStopsBeforePreparingAgain() = runBlocking { fixture { wallet, ops, journal, _ ->
        wallet.cleanupComplete = false
        val failure = runCatching { ops.autoBoardQuote() }.exceptionOrNull()
        assertEquals(OnchainFailure.uncertain, (failure as OnchainException).reason)
        assertEquals(1, wallet.preparations.size)
        assertEquals(listOf("prepared-0"), wallet.discarded)
        assertTrue(journal.loadAll().isEmpty())
    } }
    @Test fun estimatorCallClassifiesTypedErrorsAfterPrechecks() = runBlocking {
        for (action in listOf(OnchainAction.startExit, OnchainAction.claim)) {
            val errors = BarkErrorCode.entries.map { BarkError("untrusted diagnostic", it) } +
                listOf(java.net.SocketTimeoutException(), java.net.UnknownHostException(), IllegalArgumentException(), CancellationException())
            for (error in errors) fixture { wallet, ops, journal, _ ->
                wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "block"), null), true))
                wallet.estimateError = error
                val failure = runCatching { ops.quote(action, listOf("a"), destination = if (action == OnchainAction.claim)
                    "address" else null) }.exceptionOrNull()
                // Untyped failures, an unreachable chain and a fee-rate overflow keep today's fallback; refusals offer none.
                val allowed = (error is BarkError && (error.code == BarkErrorCode.OTHER || error.code == BarkErrorCode.INVALID_FEE_RATE ||
                    error.code in ExitErrors.chain)) || error is java.net.SocketTimeoutException || error is java.net.UnknownHostException
                assertEquals("$action / $error", allowed, failure is ExitEstimateUnavailable)
                if (error is BarkError && error.code in ExitErrors.never) assertTrue("$action / ${error.code}", failure is ExitNotPossible)
                else if (!allowed) assertSame(error, failure)
                assertEquals(1, wallet.estimates); assertTrue(wallet.calls.isEmpty()); assertTrue(journal.loadAll().isEmpty())
            }
        }
    }
    @Test fun aNeverExitableEstimateShowsWhyOffersNoFallbackAndLeavesTheSelection() = runBlocking {
        val cases = mapOf(BarkErrorCode.EXIT_VTXO_ALREADY_SPENT to "Already spent", BarkErrorCode.EXIT_VTXO_SWEPT to "Swept") +
            (ExitErrors.never - BarkErrorCode.EXIT_VTXO_ALREADY_SPENT - BarkErrorCode.EXIT_VTXO_SWEPT).associateWith { "This VTXO can't be exited." }
        for ((code, reason) in cases) fixture { wallet, _, journal, _ ->
            val refused = mutableMapOf<String, String>()
            val ops = OnchainOperations(wallet, journal, refused = refused)
            wallet.items = listOf(Wallet.vtxo("a"), Wallet.vtxo("b"))
            wallet.estimateError = BarkError("untrusted diagnostic", code, vtxoId = "b")
            val failure = runCatching { ops.quote(OnchainAction.startExit, listOf("a", "b")) }.exceptionOrNull() as ExitNotPossible
            assertEquals(code.name, reason, failure.message); assertEquals("b", failure.vtxoId)
            assertEquals("Choose VTXOs leaves it out", mapOf("b" to reason), refused)
            // Without a VTXO named, a standalone estimate still knows which one it asked about.
            wallet.estimateError = BarkError("untrusted diagnostic", code)
            assertEquals(mapOf("a" to null), ops.exitCosts(listOf("a")))
            assertEquals(mapOf("b" to reason, "a" to reason), refused)
            assertTrue(wallet.calls.isEmpty()); assertTrue(journal.loadAll().isEmpty())
        }
    }
    @Test fun theAllEstimateNamesTheVtxosItLeavesOut() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.excludedVtxos = listOf(ExcludedExitVtxo("dusty", 200u, BarkErrorCode.EXIT_NON_STANDARD_VTXO, "untrusted diagnostic"),
            ExcludedExitVtxo("unloadable", 9_000u, BarkErrorCode.OTHER, "untrusted diagnostic"))
        assertEquals(setOf("dusty", "unloadable"), ops.exitExcluded())
        assertEquals("one whole-wallet estimate, no ids", 1 to emptyList<String>(), wallet.estimates to wallet.estimatedIds)
        assertTrue(wallet.calls.isEmpty())
    } }
    @Test fun anUnavailableChainKeepsTodaysEstimateFallbackAndNothingIsRefused() = runBlocking {
        for (code in ExitErrors.chain + BarkErrorCode.INVALID_FEE_RATE) fixture { wallet, _, journal, _ ->
            val refused = mutableMapOf<String, String>()
            val ops = OnchainOperations(wallet, journal, refused = refused)
            wallet.estimateError = BarkError("untrusted diagnostic", code, vtxoId = "a")
            assertEquals(mapOf("a" to null), ops.exitCosts(listOf("a")))
            val unknown = (runCatching { ops.quote(OnchainAction.startExit, listOf("a")) }.exceptionOrNull() as ExitEstimateUnavailable).review
            ops.execute(unknown.copy(unknownFeeConsent = unknown.id))
            assertEquals(code.name, listOf("start"), wallet.calls)
            assertTrue(refused.isEmpty())
        }
    }
    @Test fun aRefusedStartShowsWhyKeepsNoAttemptAndForgetsTheRun() = runBlocking { fixture { wallet, _, journal, _ ->
        val refused = mutableMapOf<String, String>()
        val ops = OnchainOperations(wallet, journal, refused = refused)
        wallet.estimateError = java.net.SocketTimeoutException()
        val unknown = (runCatching { ops.quote(OnchainAction.startExit, listOf("a")) }.exceptionOrNull() as ExitEstimateUnavailable).review
        wallet.startError = BarkError("untrusted diagnostic", BarkErrorCode.EXIT_NON_STANDARD_VTXO, vtxoId = "a")
        val forgotten = mutableListOf<List<String>>()
        val failure = runCatching { ops.execute(unknown.copy(unknownFeeConsent = unknown.id), newRun = { forgotten += it }) }.exceptionOrNull()
        assertEquals("This VTXO can't be exited.", (failure as ExitNotPossible).message)
        assertEquals(mapOf("a" to "This VTXO can't be exited."), refused)
        assertTrue("not uncertain: nothing blocks another review", journal.loadAll().isEmpty())
        assertEquals("bark may have registered others first", listOf(listOf("a")), forgotten)
        // Any other start failure stays uncertain, as today.
        wallet.startError = BarkError("untrusted diagnostic", BarkErrorCode.OTHER)
        assertEquals(OnchainFailure.uncertain,
            (runCatching { ops.execute(unknown.copy(id = "second", unknownFeeConsent = "second")) }.exceptionOrNull() as OnchainException).reason)
    } }
    @Test fun aStartBarkRefusedPartWayIsRetriedAfterAReloadWithoutADuplicateExit() = runBlocking { fixture { wallet, _, journal, _ ->
        wallet.items = listOf(Wallet.vtxo("a"), Wallet.vtxo("b")); wallet.unexitable = setOf("b")
        val ops = OnchainOperations(wallet, journal, refused = mutableMapOf())
        // Reviewed without an estimate (it was unavailable): bark registers A, then refuses B.
        wallet.estimateError = java.net.SocketTimeoutException()
        val unknown = (runCatching { ops.quote(OnchainAction.startExit, listOf("a", "b")) }.exceptionOrNull() as ExitEstimateUnavailable).review
        assertTrue(runCatching { ops.execute(unknown.copy(unknownFeeConsent = unknown.id)) }.exceptionOrNull() is ExitNotPossible)
        assertEquals(listOf("a"), wallet.registered)
        // Relaunched: a new journal over the same file, the app's refusals forgotten.
        wallet.estimateError = null
        val reloaded = OnchainOperationJournal(OnchainFile(wallet.attemptFile) {})
        val refused = mutableMapOf<String, String>()
        val relaunched = OnchainOperations(wallet, reloaded, refused = refused)
        assertTrue(reloaded.loadAll().isEmpty())
        // Choose VTXOs classifies again; bark refuses B by code once more, so All holds only A.
        val tip = wallet.tip()
        val vtxos = wallet.vtxos().map { vtxoModel(it, tip, emptySet()) }
        val all = ExitCosts.all(vtxos, relaunched.exitCosts(vtxos.map { it.id }), refused)
        assertEquals(mapOf("b" to "This VTXO can't be exited."), refused)
        assertEquals(listOf("a"), all.map { it.id })
        relaunched.execute(relaunched.quote(OnchainAction.startExit, all.map { it.id }))
        // Asking for B anyway is refused before anything starts.
        assertTrue(runCatching { relaunched.quote(OnchainAction.startExit, listOf("a", "b")) }.exceptionOrNull() is ExitNotPossible)
        assertEquals("bark skipped A, already under way, and never started B", listOf("a"), wallet.registered)
        assertEquals(setOf("a"), wallet.states.keys)
        assertEquals(2, wallet.calls.count { it == "start" })
    } }
    @Test fun progressReadsEachStatusErrorByItsCode() = runBlocking { fixture { wallet, ops, journal, _ ->
        wallet.active = listOf("a", "b", "c").map { ExitVtxo(it, 30_000u, ExitState.Start(100u), false) }
        val short = BarkError("untrusted diagnostic", BarkErrorCode.EXIT_INSUFFICIENT_CONFIRMED_FUNDS, neededSats = 5_000u, availableSats = 1_200u)
        wallet.progressStatuses = listOf(ExitProgressStatus("a", ExitState.Start(100u), short), ExitProgressStatus("b", ExitState.Start(100u), null),
            ExitProgressStatus("c", ExitState.Start(100u), null))
        var reported: Map<String, Long>? = null
        assertEquals("short of funds is evidence, not a failure", OnchainReceipt.progressedMessage,
            ops.execute(ops.quote(OnchainAction.progress, listOf("a", "b", "c")), shortOfFunds = { reported = it }))
        assertEquals(mapOf("a" to 5_000L), reported)
        journal.acknowledge(journal.loadAll().single().id)
        wallet.progressStatuses = listOf(ExitProgressStatus("a", ExitState.Start(100u), short),
            ExitProgressStatus("b", ExitState.Start(100u), BarkError("untrusted diagnostic", BarkErrorCode.EXIT_TIP_RETRIEVAL_FAILURE)),
            ExitProgressStatus("c", ExitState.Start(100u), BarkError("untrusted diagnostic", BarkErrorCode.OTHER)))
        val message = ops.execute(ops.quote(OnchainAction.progress, listOf("a", "b", "c")), shortOfFunds = { reported = it })
        assertTrue(message, message.startsWith("Exit progression returned 2 errors."))
        assertEquals(mapOf("a" to 5_000L), reported)
    } }
    @Test fun backgroundProgressCallsBarkOnlyForPendingExitsAndRecordsLikeACheck() = runBlocking { fixture { wallet, ops, journal, _ ->
        val funding = ExitFunding(0) { 0 }
        // None pending: finished exits only, so no progress call and nothing recorded.
        wallet.active = listOf(ExitVtxo("done", 30_000u, ExitState.Canceled(100u), false),
            ExitVtxo("gone", 30_000u, ExitState.VtxoAlreadySpent(100u), false))
        assertFalse(ops.progressPending(funding) {})
        assertTrue(wallet.calls.none { it == "progress" })
        // A pending one: one call at bark's own rate, short of funds recorded, no journal attempt (nothing to acknowledge).
        wallet.active += ExitVtxo("a", 30_000u, ExitState.Start(100u), false)
        val short = BarkError("untrusted diagnostic", BarkErrorCode.EXIT_INSUFFICIENT_CONFIRMED_FUNDS, neededSats = 5_000u, availableSats = 1_200u)
        wallet.progressStatuses = listOf(ExitProgressStatus("a", ExitState.Start(100u), short))
        assertTrue(ops.progressPending(funding) {})
        assertEquals(listOf<Long?>(null), wallet.progressRates)
        assertEquals(5_000L, funding.needed("a"))
        assertFalse(funding.checkFailed)
        assertTrue(journal.loadAll().isEmpty())
        // Another error marks the check failed, as Move exit forward's would.
        wallet.progressStatuses = listOf(ExitProgressStatus("a", ExitState.Start(100u), BarkError("untrusted diagnostic", BarkErrorCode.OTHER)))
        ops.progressPending(funding) {}
        assertTrue(funding.checkFailed)
        // A call that throws is recorded failed and rethrown for the caller to log.
        funding.checked(emptyList(), completed = true); wallet.progressError = IllegalStateException("offline")
        assertTrue(runCatching { ops.progressPending(funding) {} }.exceptionOrNull() is IllegalStateException)
        assertTrue(funding.checkFailed)
        // The job's budget gone after the read: bark is never called.
        wallet.progressError = null; val calls = wallet.progressRates.size
        assertTrue(runCatching { ops.progressPending(funding) { throw CancellationException() } }.exceptionOrNull() is CancellationException)
        assertEquals(calls, wallet.progressRates.size)
    } }
    @Test fun confirmationEstimatorFailureKeepsReviewIdentityScopeAndDeadline() = runBlocking {
        for (action in listOf(OnchainAction.startExit, OnchainAction.claim)) fixture { wallet, _, journal, _ ->
            var now = 100_000L
            val ops = OnchainOperations(wallet, journal, now = { now }, elapsed = { now })
            wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "block"), null), true))
            val original = ops.quote(action, listOf("a"), destination = if (action == OnchainAction.claim) "address" else null)
            now += 29_000
            wallet.estimateError = java.net.SocketTimeoutException()
            val failure = runCatching { ops.execute(original) }.exceptionOrNull() as ExitEstimateUnavailable
            assertEquals(original.id, failure.review.id); assertEquals(original.createdAt, failure.review.createdAt)
            assertFalse(failure.review.current(130_000)); assertFalse(failure.review.hasUnknownFeeConsent)
            assertTrue(runCatching { failure.preserving(original, 130_000) }.isFailure)
            assertTrue(runCatching { failure.preserving(original.copy(amount = 1), now) }.isFailure)
            assertTrue(runCatching { failure.preserving(original.copy(destination = "other"), now) }.isFailure)
            assertTrue(runCatching { failure.preserving(original.copy(walletID = "other"), now) }.isFailure)
            assertTrue(runCatching { failure.preserving(original.copy(ids = listOf("other")), now) }.isFailure)
            now = 130_000
            val estimates = wallet.estimates
            assertTrue(runCatching { ops.execute(original) }.exceptionOrNull() is OnchainException)
            assertEquals(estimates, wallet.estimates)
            assertTrue(wallet.calls.isEmpty()); assertTrue(journal.loadAll().isEmpty())
        }
    }
    @Test fun reviewDeadlinesRunOnTheReviewClockWhereverTheWallClockMoves() = runBlocking {
        for (move in listOf(-86_400_000L, 86_400_000L)) fixture { wallet, _, journal, _ ->
            var wall = 1_760_000_000_000L; var elapsed = 5_000L
            val ops = OnchainOperations(wallet, journal, now = { wall }, elapsed = { elapsed })
            val exit = ops.quote(OnchainAction.startExit, listOf("a"))
            val board = ops.quote(OnchainAction.board, amount = 30_000)
            assertEquals(5_000L, exit.reviewedAt); assertEquals(1_760_000_000_000L, exit.createdAt); assertEquals(5_000L, board.reviewedAt)
            wall += move; elapsed += 29_999
            assertTrue(exit.current(elapsed, wall))
            wall += 1; elapsed += 1
            assertTrue(runCatching { ops.execute(exit) }.exceptionOrNull() is OnchainException)
            elapsed += 89_999
            assertTrue(board.current(elapsed, wall))
            wall += 1; elapsed += 1
            assertTrue(runCatching { ops.execute(board) }.exceptionOrNull() is OnchainException)
            assertTrue(wallet.calls.isEmpty()); assertTrue(journal.loadAll().isEmpty())
        }
    }
    @Test fun exitEstimatorFailureRequiresScopedConsentWithoutRetryOrFabricatedFees() = runBlocking {
        for (action in listOf(OnchainAction.startExit, OnchainAction.claim)) fixture { wallet, ops, journal, _ ->
            wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "block"), null), true))
            wallet.estimateError = java.net.SocketTimeoutException("estimator offline")
            val failure = runCatching { ops.quote(action, listOf("a"), destination = if (action == OnchainAction.claim)
                "address" else null) }.exceptionOrNull() as ExitEstimateUnavailable
            val unknown = failure.review
            assertNull(unknown.fee); assertNull(unknown.chainFee); assertNull(unknown.feeRate)
            assertTrue(runCatching { ops.execute(unknown) }.isFailure)
            assertTrue(wallet.calls.isEmpty()); assertEquals(1, wallet.estimates)
            val reviewed = unknown.copy(unknownFeeConsent = unknown.id)
            for (invalid in listOf(reviewed.copy(reviewedAt = checkNotNull(reviewed.reviewedAt) - 30_000), reviewed.copy(walletID = "other"),
                reviewed.copy(ids = listOf("missing"))))
                assertTrue(runCatching { ops.execute(invalid) }.isFailure)
            if (action == OnchainAction.claim) {
                wallet.ownershipError = true
                assertTrue(runCatching { ops.execute(reviewed) }.isFailure)
                wallet.ownershipError = false
            }
            assertTrue(journal.loadAll().isEmpty())
            ops.execute(reviewed)
            assertEquals(1, wallet.estimates)
            val saved = journal.loadAll().single().quote
            assertEquals(listOf("a"), saved.ids); assertTrue(saved.hasUnknownFeeConsent)
            assertNull(saved.fee); assertNull(saved.chainFee)
            assertEquals(1, wallet.calls.count { it == if (action == OnchainAction.claim) "broadcast" else "start" })
        }
    }
    @Test fun inputOwnershipAndProgressFailuresCannotOfferExitFallback() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.estimateError = java.net.UnknownHostException("offline")
        for (ids in listOf(emptyList(), listOf("missing"), listOf("a", "a")))
            assertFalse(runCatching { ops.quote(OnchainAction.startExit, ids) }.exceptionOrNull() is ExitEstimateUnavailable)
        wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Start(100u), false))
        assertFalse(runCatching { ops.quote(OnchainAction.progress, listOf("a")) }.exceptionOrNull() is ExitEstimateUnavailable)
        wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "block"), null), true))
        wallet.ownershipError = true
        assertFalse(runCatching { ops.quote(OnchainAction.claim, listOf("a"), destination = "address") }.exceptionOrNull() is ExitEstimateUnavailable)
    } }
    @Test fun knownEstimateAndInsufficientFundingUseNormalStartReview() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.balance = 0
        val quote = ops.quote(OnchainAction.startExit, listOf("a"))
        assertFalse(quote.feesUnknown); assertFalse(quote.fundable)
        assertEquals(1295L, quote.fee); assertEquals(7630L, quote.chainFee)
        ops.execute(quote)
        assertEquals(2, wallet.estimates); assertTrue(wallet.calls.contains("start"))
    } }
    @Test fun changedFundingRejectsBeforeClaimAndCanReleaseForFreshReview() = runBlocking { fixture { wallet, ops, journal, _ ->
        val quote = checkNotNull(ops.autoBoardQuote())
        val reviewedFunding = AutoBoardFunding.Snapshot("wallet", setOf("deposit:0"))
        assertTrue(runCatching { ops.execute(quote, reviewedFunding) }.isFailure)
        assertTrue(journal.loadAll().isEmpty())
        assertFalse(wallet.calls.any { it.startsWith("submit:") })
        assertTrue(ops.abandonUnsubmittedAutoBoard(quote))
    } }
    @Test fun autoBoardReleaseRequiresNoJournalAttemptAndMatchingWallet() = runBlocking { fixture { wallet, ops, journal, _ ->
        val quote = checkNotNull(ops.autoBoardQuote())
        assertFalse(ops.abandonUnsubmittedAutoBoard(quote.copy(walletID = "other")))
        assertTrue(ops.abandonUnsubmittedAutoBoard(quote))
        val next = checkNotNull(ops.autoBoardQuote())
        runCatching { journal.perform(next) { error("uncertain") } }
        assertFalse(ops.abandonUnsubmittedAutoBoard(next))
    } }
    @Test fun aClaimToThisPhoneKeepsItsOutputsOnchainBeforeItsBroadcast() = runBlocking {
        for (mine in listOf(true, false)) fixture { wallet, ops, journal, _ ->
            wallet.mine = mine
            wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "hash"), null), true))
            val kept = mutableListOf<Set<String>>()
            ops.execute(ops.quote(OnchainAction.claim, listOf("a"), destination = "address"), keepOnchain = { kept += it; wallet.calls.add("keep") })
            if (mine) {
                assertEquals(listOf(setOf("75ebf7553b394d32e07c7e4297c86f2e861101cf5cae510a1c4f28f490cf20c6:0")), kept)
                assertEquals(listOf("drain", "keep", "broadcast"), wallet.calls)
            } else { assertTrue("another wallet's coins aren't ours to keep", kept.isEmpty()); assertEquals(listOf("drain", "broadcast"), wallet.calls) }
            assertEquals("claim-tx", journal.loadAll().single().receipt!!.txid)
        }
    }
    @Test fun aClaimWhoseOutputsCantBeKeptIsNeverSent() = runBlocking { fixture { wallet, ops, journal, _ ->
        wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "hash"), null), true))
        val failure = runCatching { ops.execute(ops.quote(OnchainAction.claim, listOf("a"), destination = "address"),
            keepOnchain = { error("disk full") }) }.exceptionOrNull()
        assertEquals(OnchainFailure.keepFailed, (failure as OnchainException).reason)
        assertTrue(wallet.calls.none { it == "broadcast" })
        assertTrue("nothing was sent, so nothing blocks trying again", journal.loadAll().isEmpty())
    } }
    @Test fun autoBoardNeverOffersCoinsClaimedToThisPhone() = runBlocking {
        var disk: String? = null
        val store = AutoBoardFunding({ disk }, { disk = it })
        store.keep("wallet", setOf("claim:0"))
        assertFalse(store.hasUnhandled(AutoBoardFunding.Snapshot("wallet", setOf("claim:0"))))
        var quoted = 0
        assertNull(AutoBoardReview(store).prepare({ true }, { AutoBoardFunding.Snapshot("wallet", setOf("claim:0")) }, { quoted++; null }, {}))
        assertEquals(0, quoted)
        // A real deposit later is still offered, as today; the kept coin stays handled.
        assertTrue(store.hasUnhandled(AutoBoardFunding.Snapshot("wallet", setOf("claim:0", "deposit:0"))))
        assertTrue(store.hasUnhandled(AutoBoardFunding.Snapshot("other", setOf("claim:0"))))
    }
    @Test fun exitsCarryTheirFeesAndTheWaitingPackagesFee() = runBlocking { fixture { wallet, _, journal, _ ->
        val node = ExitTransactions.parse(wallet.claimTransaction(""))!!
        // As BDK reports a fee child live: no fee (its anchor input isn't a wallet coin), only what left the wallet.
        wallet.fundingTransactions = listOf(WalletTransaction("child-1", "", null, -1_215, null, false),
            WalletTransaction("unrelated", "", 99u, -99, null, false))
        val waiting = ExitState.Processing(101u,
            listOf(ExitTx("root", ExitTxStatus.Confirmed("child-1", BlockRef(101u, "h"), ExitTxOrigin.Wallet(BlockRef(101u, "h")))),
                ExitTx("leaf", ExitTxStatus.AwaitingCpfpBroadcast)))
        wallet.states["a"] = waiting
        val hex = wallet.claimTransaction("")
        val packages = listOf(ExitTransactionPackage("root", hex, "child-1", hex, ExitTxOrigin.Wallet(BlockRef(101u, "h")), waiting.transactions[0].status),
            ExitTransactionPackage("leaf", hex, null, null, null, ExitTxStatus.AwaitingCpfpBroadcast))
        val status = ExitTransactionStatus("a", 30_000u, waiting, listOf(ExitState.Start(100u)), 2u, packages)
        val exit = OnchainOperations(object : OnchainWalletAccess by wallet { override suspend fun status(id: String) = status }, journal).detail("a")!!
        assertEquals(mapOf("child-1" to 1_215L), exit.childFees)
        // At the rate bark's exits pay, from its estimate (10 sat/vB here), not the on-chain wallet's own estimate.
        assertEquals(ExitTransactions.packageFee(node.weight, 2_500), exit.nextPackageFee)
        assertEquals(listOf("a"), wallet.estimatedIds)
        assertEquals(listOf("root", "leaf"), exit.packages.map { it.txid })
        assertEquals("wallet", exit.packages[0].childOrigin); assertEquals(node.weight, exit.packages[1].weight); assertEquals(1, exit.packages[1].outputs)
        assertEquals(1_215L, ExitStages.of(exit, 101).feesPaid)
    } }
    @Test fun aSpentCoinLoadsByIdThoughTheOverviewListsOnlyCurrentOnes() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.records["spent"] = Vtxo("server", 144u, OutPoint("anchor", 0u), OutPoint("point", 0u), "spent", 2_500u, 90u, "pubkey",
            VtxoState.Spent, 1u, 500u, true)
        assertTrue(ops.overview(24u).vtxos.none { it.id == "spent" })
        val coin = ops.vtxo("spent", 24u)
        assertEquals("Spent", coin.state); assertEquals(2_500L, coin.amount); assertEquals(90L, coin.expiry)
        // A spent coin is never offered a refresh.
        assertFalse(coin.refreshable)
    } }

    @Test fun everyFailedCoinLookupIsAFailedReadWhateverBarkSays() = runBlocking { fixture { wallet, ops, _, _ ->
        // bark-bolt-ffi has no not-found code: neither its "cannot be found" wording nor a database error proves the coin is gone.
        for (message in listOf("The VTXO with id ${"ab".repeat(32)}:0 cannot be found", "Error when querying vtxo in database")) {
            wallet.lookupErrors["coin"] = BarkError(message)
            assertTrue(message, runCatching { ops.vtxo("coin", 24u) }.exceptionOrNull() is BarkError)
        }
    } }
}
