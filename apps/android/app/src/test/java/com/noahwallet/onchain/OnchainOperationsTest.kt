package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class OnchainOperationsTest {
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
                val failure = runCatching { ops.quote(action, listOf("a"), destination = if (action == OnchainAction.claim) "address" else null) }.exceptionOrNull()
                // Untyped failures, an unreachable chain and a fee-rate overflow keep today's fallback; refusals offer none.
                val allowed = error is BarkError && (error.code == BarkErrorCode.OTHER || error.code == BarkErrorCode.INVALID_FEE_RATE ||
                    error.code in ExitErrors.chain) || error is java.net.SocketTimeoutException || error is java.net.UnknownHostException
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
        assertEquals(OnchainFailure.uncertain, (runCatching { ops.execute(unknown.copy(id = "second", unknownFeeConsent = "second")) }.exceptionOrNull() as OnchainException).reason)
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
            val ops = OnchainOperations(wallet, journal, now = { now })
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
    @Test fun exitEstimatorFailureRequiresScopedConsentWithoutRetryOrFabricatedFees() = runBlocking {
        for (action in listOf(OnchainAction.startExit, OnchainAction.claim)) fixture { wallet, ops, journal, _ ->
            wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "block"), null), true))
            wallet.estimateError = java.net.SocketTimeoutException("estimator offline")
            val failure = runCatching { ops.quote(action, listOf("a"), destination = if (action == OnchainAction.claim) "address" else null) }.exceptionOrNull() as ExitEstimateUnavailable
            val unknown = failure.review
            assertNull(unknown.fee); assertNull(unknown.chainFee); assertNull(unknown.feeRate)
            assertTrue(runCatching { ops.execute(unknown) }.isFailure)
            assertTrue(wallet.calls.isEmpty()); assertEquals(1, wallet.estimates)
            val reviewed = unknown.copy(unknownFeeConsent = unknown.id)
            for (invalid in listOf(reviewed.copy(createdAt = 0), reviewed.copy(walletID = "other"), reviewed.copy(ids = listOf("missing"))))
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
    private class Wallet : OnchainWalletAccess {
        override val walletID = "wallet"
        var cleanupComplete = true
        override fun boardCleanupComplete(id: String) = cleanupComplete
        var mine = true
        var ownershipError = false
        var network: String? = null
        val ownershipChecks = mutableListOf<String>()
        val drainDestinations = mutableListOf<String>()
        override suspend fun destinationIsMine(address: String): Boolean {
            ownershipChecks.add(address)
            network?.let { validateClaimDestination(address, it) }
            if (ownershipError) error("ownership unavailable")
            return mine
        }
        var pendingBoard = false
        /** Funding txids bark's pending boards and board movements name. */
        var boardTxids = emptySet<String>()
        override suspend fun boardTransactionIDs() = boardTxids
        /** Funding txids BOARD movements name in their chain_anchor. */
        var movementTxids = emptySet<String>()
        override suspend fun boardMovementTransactionIDs() = movementTxids
        var chainStatus: TransactionStatus = TransactionStatus.InMempool
        var fundingTransactions: List<WalletTransaction>? = null
        var fundingUtxos = emptyList<OnchainUtxo>()
        override suspend fun utxos() = fundingUtxos
        override suspend fun hasPendingBoards() = pendingBoard
        var balance = 100_000L
        var fee = 330L
        var chainRate = 250L
        var exactFee = 1543L
        var extraFixedFee = 0L
        var inputCount = 1
        /** The coins bark's selection spends for a prepared board, when a test picks them. */
        var boardInputs: List<OutPoint>? = null
        /** Each preparation's excluded outpoints, in order. */
        val excludedBy = mutableListOf<Set<String>>()
        var afterPrepare: suspend (PreparedBoard) -> Unit = {}
        var afterBind: () -> Unit = {}
        var submitOutcome: BroadcastOutcome = BroadcastOutcome.Accepted
        var submitError: Exception? = null
        var prepareError: BarkError? = null
        var beforeSubmit: suspend () -> Unit = {}
        lateinit var attemptFile: File
        val preparations = mutableListOf<PreparedBoard>()
        val discarded = mutableListOf<String>()
        val bound = mutableMapOf<String, OnchainQuote>()
        override suspend fun prepareBoard(amount: Long, all: Boolean, rate: ULong, excluded: Set<String>): PreparedBoard {
            excludedBy += excluded
            prepareError?.let { throw it }
            // As bark's selection: excluded coins are never spent, so their value isn't there to board.
            val spendable = balance - fundingUtxos.filterIsInstance<OnchainUtxo.Local>().filter { "${it.outpoint.txid}:${it.outpoint.vout}" in excluded }.sumOf { it.amountSats.toLong() }
            val chainFee = exactFee + if (all) 0 else 43 * rate.toLong() + extraFixedFee
            val gross = if (all) spendable - chainFee else amount
            if (gross < fee || gross + chainFee > spendable) throw BarkError("insufficient balance", BarkErrorCode.INSUFFICIENT_FUNDS)
            return PreparedBoard("prepared-${preparations.size}", "board-tx", gross.toULong(), (gross - fee).toULong(), fee.toULong(), chainFee.toULong(), 199u,
                boardInputs ?: List(inputCount) { OutPoint("input-$it", 0u) }).also { preparations.add(it); afterPrepare(it) }
        }
        override fun bindBoard(quote: OnchainQuote) { bound[quote.preparedBoard!!.id] = quote; afterBind() }
        override fun claimBoard(quote: OnchainQuote) { if (bound[quote.preparedBoard!!.id] != quote) fail(OnchainFailure.changed) }
        override suspend fun discardBoard(id: String, includingClaimed: Boolean) { discarded.add(id); bound.remove(id) }
        override suspend fun allExits() = states.keys.mapNotNull { status(it) }
        var chainError: Exception? = null
        override suspend fun transactionStatus(txid: String): TransactionStatus { chainError?.let { throw it }; return chainStatus }
        var items = listOf(Companion.vtxo("a"))
        var active = listOf<ExitVtxo>()
        var states = mutableMapOf<String, ExitState>()
        var calls = mutableListOf<String>()
        var feesUnavailable = false
        var forbidVtxoLookup = false
        var claimFee = 1295L
        var broadcastError: Exception? = null
        var cancelResult = false
        override suspend fun confirmed() = balance
        override suspend fun minimum() = 10_000L
        override suspend fun regularSatPerKwu() = chainRate
        override suspend fun refreshFee(ids: List<String>) = FeeEstimate(30_000u, 269u, 29_731u, ids)
        var estimateError: Exception? = null
        var estimates = 0
        val estimatedIds = mutableListOf<String>()
        /** What bark's "All" estimate (no ids) leaves out. */
        var excludedVtxos = emptyList<ExcludedExitVtxo>()
        override suspend fun exitFee(ids: List<String>, destination: String?): EmergencyExitFeeEstimate {
            estimates++; estimatedIds += ids; estimateError?.let { throw it }
            ids.firstOrNull { it in unexitable }?.let { throw BarkError("untrusted diagnostic", BarkErrorCode.EXIT_NON_STANDARD_VTXO, vtxoId = it) }
            return EmergencyExitFeeEstimate(7630u, 1295u, 8925u, 10u, 2u, if (ids.isEmpty()) excludedVtxos else emptyList())
        }
        override suspend fun abandonUnsubmittedBoard(quote: OnchainQuote): Boolean = bound.remove(quote.preparedBoard?.id) == quote
        override suspend fun vtxos() = items
        /** Coins the wallet keeps a record of by ID, spent ones included. */
        val records = mutableMapOf<String, Vtxo>()
        val lookupErrors = mutableMapOf<String, Exception>()
        override suspend fun vtxo(id: String): Vtxo { check(!forbidVtxoLookup); lookupErrors[id]?.let { throw it }; return records[id] ?: Companion.vtxo(id) }
        override suspend fun exits() = active
        override suspend fun status(id: String) = states[id]?.let { ExitTransactionStatus(id, 30_000u, it, listOf(ExitState.Start(100u), it), 0u, emptyList()) }
        var tipHeight = 100L
        var expiringIDs = emptySet<String>()
        override suspend fun tip() = tipHeight
        override suspend fun expiring(threshold: UInt) = expiringIDs
        override suspend fun refresh(ids: List<String>): RoundState? { calls.add("refresh:${ids.joinToString()}"); return null }
        override suspend fun pendingTotal() = 0L
        override suspend fun claimableHeight(): Long? = null
        override suspend fun submitBoard(quote: OnchainQuote): BoardResult {
            beforeSubmit()
            calls.add("submit:${quote.preparedBoard!!.id}")
            submitError?.let { throw it }
            return BoardResult(quote.preparedBoard.txid, submitOutcome, listOf("new"))
        }
        override suspend fun transactions(): List<WalletTransaction> {
            calls.add("transactions")
            if (feesUnavailable) error("lookup unavailable")
            fundingTransactions?.let { return it }
            return listOf(WalletTransaction("board-tx", "", 1543u, -30_000, null, false))
        }
        /** Runs inside start and cancel, so a test can hold them while their caller is canceled. */
        var duringMutation: suspend () -> Unit = {}
        var startError: Exception? = null
        /** VTXOs bark refuses to exit, by code, in estimates and starts. */
        var unexitable = emptySet<String>()
        /** Exits bark registered, in order. */
        val registered = mutableListOf<String>()
        override suspend fun start(ids: List<String>) {
            duringMutation(); calls.add("start"); startError?.let { throw it }
            // As bark: in order, skipping exits already under way; a refusal comes after registering the ones before it.
            for (id in ids) {
                if (id in states) continue
                if (id in unexitable) throw BarkError("untrusted diagnostic", BarkErrorCode.EXIT_NON_STANDARD_VTXO, vtxoId = id)
                states[id] = ExitState.Start(100u); registered += id
            }
        }
        var progressStatuses = emptyList<ExitProgressStatus>()
        var progressError: Exception? = null
        val progressRates = mutableListOf<Long?>()
        override suspend fun progress(feeRate: Long?): List<ExitProgressStatus> {
            calls.add("progress"); progressRates += feeRate; progressError?.let { throw it }; return progressStatuses
        }
        override suspend fun cancel(id: String): Boolean { duringMutation(); calls.add("cancel"); return cancelResult }
        override suspend fun drain(ids: List<String>, destination: String): ExitClaimTransaction { drainDestinations.add(destination); calls.add("drain"); return ExitClaimTransaction("psbt", claimFee.toULong()) }
        override suspend fun broadcastClaim(psbt: String): String { calls.add("broadcast"); broadcastError?.let { throw it }; return "claim-tx" }
        /** A signed one-output transaction (txid 75ebf755…), standing in for the drain's claim. */
        override suspend fun claimTransaction(psbt: String) = "020000000001013e16e76ee2b6a9dc798b81be3a4903286e4e454dc019c48062d5396b4e21fdca0000000000fdffffff01d09b0000000000002251208751b9234a38be714a8011a02bad9f346f7b80e4a76b13fcacda7b0e2890c5e601408982addc732de240dd1dfb0bd4a66e0ab807073c73d92b26256827a2643735827b1eb0cb92812c4969544c107489479dc54ec9bc27409a15bef8486972bf03297f030000"
        companion object {
            fun vtxo(id: String, amount: ULong = 30_000u) = Vtxo("server", 144u, OutPoint("anchor", 0u), OutPoint("point", 0u), id, amount, 500u, "pubkey", VtxoState.Spendable, 1u, 500u, true)
        }
    }
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
        val failure = runCatching { ops.execute(ops.quote(OnchainAction.claim, listOf("a"), destination = "address"), keepOnchain = { error("disk full") }) }.exceptionOrNull()
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
        wallet.fundingTransactions = listOf(WalletTransaction("child-1", "", null, -1_215, null, false), WalletTransaction("unrelated", "", 99u, -99, null, false))
        val waiting = ExitState.Processing(101u, listOf(ExitTx("root", ExitTxStatus.Confirmed("child-1", BlockRef(101u, "h"), ExitTxOrigin.Wallet(BlockRef(101u, "h")))),
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

    private suspend fun fixture(body: suspend (Wallet, OnchainOperations, OnchainOperationJournal, File) -> Unit) {
        val root = Files.createTempDirectory("m6-ops").toFile()
        try {
            val journal = OnchainOperationJournal(OnchainFile(File(root, "attempt")) {})
            val history = File(root, "noah-exit-history.json")
            val wallet = Wallet().apply { attemptFile = File(root, "attempt") }
            withTimeout(10_000) { body(wallet, OnchainOperations(wallet, journal), journal, history) }
        } finally { root.deleteRecursively() }
    }
    // exit.md, Decision 8: coins claimed to this phone stay on-chain; auto-board weighs only the rest.
    private val claimed = OnchainUtxo.Local(OutPoint("claim", 0u), 28_375u, 100u)
    private val deposit = OnchainUtxo.Local(OutPoint("deposit", 0u), 60_000u, 100u)
    private val claimTx = WalletTransaction("claim", "", null, 28_375, null, false)
    private val depositTx = WalletTransaction("deposit", "", null, 60_000, null, false)
    private fun ledger(): AutoBoardFunding { var disk: String? = null; return AutoBoardFunding({ disk }, { disk = it }).apply { keep("wallet", setOf("claim:0")) } }

    @Test fun aDepositAfterAClaimToThisPhoneIsOfferedWithoutTheClaim() = runBlocking { fixture { wallet, ops, _, _ ->
        val store = ledger()
        wallet.fundingTransactions = listOf(claimTx, depositTx); wallet.fundingUtxos = listOf(claimed, deposit); wallet.balance = 88_375
        val funding = checkNotNull(ops.autoBoardFunding(store::kept))
        assertTrue("the deposit is new", store.hasUnhandled(funding))
        val quote = checkNotNull(ops.autoBoardQuote(store::kept))
        // The deposit less the on-chain fee, the change output and today's 5,000 reserve: never the claim's 28,375.
        assertEquals(60_000L - 1_543 - 43 - 5_000, quote.amount)
        assertEquals("the claim's value stays on-chain beside the reserve", 28_375L + 5_000, quote.reserveLeft)
        assertFalse("prepared by amount, so the rest is change", quote.boardAll)
        // Submitting rechecks the same weighing under the wallet's locks.
        ops.execute(quote, funding, kept = store::kept)
        assertEquals(listOf("submit:${quote.preparedBoard!!.id}"), wallet.calls.filter { it.startsWith("submit:") })
    } }
    @Test fun withOnlyTheClaimOnChainAutoBoardNeverAsks() = runBlocking { fixture { wallet, ops, _, _ ->
        val store = ledger()
        wallet.fundingTransactions = listOf(claimTx); wallet.fundingUtxos = listOf(claimed); wallet.balance = 28_375
        assertNull(ops.autoBoardFunding(store::kept))
        var quoted = 0
        assertNull(AutoBoardReview(store).prepare({ true }, { ops.autoBoardFunding(store::kept) }, { quoted++; ops.autoBoardQuote(store::kept) }, {}))
        assertEquals(0, quoted)
        assertNull(ops.autoBoardQuote(store::kept)); assertTrue("nothing was even prepared", wallet.preparations.isEmpty())
        // A small deposit beside it: the balance passes 20,000, but what auto-board may move doesn't.
        val small = OnchainUtxo.Local(OutPoint("deposit", 0u), 10_000u, 100u)
        wallet.fundingTransactions = listOf(claimTx, depositTx.copy(balanceChangeSats = 10_000)); wallet.fundingUtxos = listOf(claimed, small); wallet.balance = 38_375
        assertNull(ops.autoBoardFunding(store::kept))
        assertNotNull("weighing the claim too would have asked", ops.autoBoardFunding())
    } }
    @Test fun onceAClaimedCoinIsSpentItNoLongerHoldsValueBack() = runBlocking { fixture { wallet, ops, _, _ ->
        val store = ledger()
        // The claimed coin was spent (moved with Max, say): only the deposit is left, and all of it may move.
        wallet.fundingTransactions = listOf(claimTx, depositTx); wallet.fundingUtxos = listOf(deposit); wallet.balance = 60_000
        assertEquals(60_000L, AutoBoardFunding.boardable(60_000, wallet.fundingUtxos, store.kept("wallet")))
        assertEquals(60_000L - 1_543 - 43 - 5_000, checkNotNull(ops.autoBoardQuote(store::kept)).amount)
        // Unconfirmed, it isn't in the confirmed balance yet, so there's nothing to subtract for it either.
        assertEquals(60_000L, AutoBoardFunding.boardable(60_000, listOf(deposit, OnchainUtxo.Local(OutPoint("claim", 0u), 28_375u, null)), setOf("claim:0")))
        assertEquals(31_625L, AutoBoardFunding.boardable(60_000, listOf(deposit, claimed), setOf("claim:0")))
    } }
    @Test fun theClaimedCoinsArePassedAsExclusionsAndAPreparationIsMadeOnce() = runBlocking { fixture { wallet, ops, _, _ ->
        val store = ledger()
        wallet.fundingTransactions = listOf(claimTx, depositTx); wallet.fundingUtxos = listOf(claimed, deposit); wallet.balance = 88_375
        val quote = checkNotNull(ops.autoBoardQuote(store::kept))
        val full = 60_000L - 1_543 - 43 - 5_000
        assertEquals("the drain probe, then one preparation", listOf(60_000L - 1_543, full), wallet.preparations.map { it.grossSats.toLong() })
        assertEquals("bark never spends the claimed coin in either", listOf(setOf("claim:0"), setOf("claim:0")), wallet.excludedBy)
        assertEquals(full, quote.amount)
        assertEquals("only the offer stays prepared", setOf(quote.preparedBoard!!.id), wallet.bound.keys)
        // Manual Move to Ark passes nothing, so you can still move everything.
        wallet.discardBoard(quote.preparedBoard.id)
        assertEquals(88_375L - 1_543, ops.quote(OnchainAction.board).amount)
        assertEquals(emptySet<String>(), wallet.excludedBy.last())
    } }
    @Test fun aPreparationThatStillSpendsAClaimedCoinIsNeverOffered() = runBlocking { fixture { wallet, ops, _, _ ->
        val store = ledger()
        wallet.fundingTransactions = listOf(claimTx, depositTx); wallet.fundingUtxos = listOf(claimed, deposit); wallet.balance = 88_375
        // The input check stays as cheap defence: were bark to ignore the exclusion, nothing is offered or asked again.
        wallet.boardInputs = listOf(OutPoint("claim", 0u))
        assertNull(ops.autoBoardQuote(store::kept))
        assertEquals(2, wallet.preparations.size)
        assertTrue("both preparations are discarded", wallet.bound.isEmpty())
    } }
    @Test fun claimedCoinsAreKeptBesideTheHandledLedgerInOneWrite() {
        var disk: String? = null; var writes = 0
        val store = AutoBoardFunding({ disk }, { disk = it; writes++ })
        store.keep("wallet", setOf("claim:0"))
        assertEquals(1, writes)
        assertEquals(setOf("claim:0"), store.kept("wallet")); assertTrue(store.kept("other").isEmpty())
        assertFalse(store.hasUnhandled(AutoBoardFunding.Snapshot("wallet", setOf("claim:0"))))
        // Releasing a declined review keeps them.
        store.release(AutoBoardFunding.Snapshot("wallet", setOf("deposit:0")), 1)
        assertEquals(setOf("claim:0"), store.kept("wallet"))
        // A ledger saved before kept coins existed still reads.
        disk = """{"handled":{"wallet":["deposit:0"]},"retryAfter":{}}"""
        assertTrue(store.kept("wallet").isEmpty()); assertFalse(store.hasUnhandled(AutoBoardFunding.Snapshot("wallet", setOf("deposit:0"))))
    }
    @Test fun confirmedFundingExcludesPendingExitUnknownAndBoardChange() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.fundingTransactions = listOf(
            WalletTransaction("deposit", "", null, 150_529, null, false),
            WalletTransaction("board-change", "", 155u, -140_000, null, false))
        wallet.fundingUtxos = listOf(OnchainUtxo.Local(OutPoint("deposit", 1u), 150_529u, 100u),
            OnchainUtxo.Local(OutPoint("pending", 0u), 150_529u, null),
            OnchainUtxo.Local(OutPoint("board-change", 1u), 5_000u, 100u),
            OnchainUtxo.Local(OutPoint("unknown", 0u), 100_000u, 100u), OnchainUtxo.Exit("exit", 100_000u, 100u))
        assertEquals(setOf("deposit:1"), ops.autoBoardFunding()!!.outpoints)
        wallet.pendingBoard = true
        assertNull(ops.autoBoardFunding())
    } }
    @Test fun autoAdmissionReconcilesConfirmedReceiptWithoutDoneButKeepsUncertainty() = runBlocking { fixture { wallet, ops, journal, _ ->
        val quote = ops.quote(OnchainAction.board, amount = 30_000)
        ops.execute(quote)
        assertNull(ops.autoBoardFunding())
        wallet.chainStatus = TransactionStatus.Confirmed(100u)
        assertNotNull(ops.autoBoardFunding())
        assertNotNull(journal.loadAll().single().receipt)
        val next = ops.quote(OnchainAction.board, amount = 30_000)
        wallet.submitOutcome = BroadcastOutcome.Uncertain("checkpoint write failed")
        assertTrue(runCatching { ops.execute(next) }.isFailure)
        wallet.chainStatus = TransactionStatus.NotFound
        wallet.fundingTransactions = emptyList()
        assertNull(ops.autoBoardFunding())
    } }
    @Test fun overlappingManualAndAutomaticPreparationsOnlySubmitOnce() = runBlocking { fixture { wallet, ops, journal, _ ->
        val automatic = checkNotNull(ops.autoBoardQuote())
        val manual = ops.quote(OnchainAction.board, amount = 30_000)
        ops.execute(manual)
        assertTrue(runCatching { ops.execute(automatic) }.isFailure)
        assertEquals(1, wallet.calls.count { it.startsWith("submit:") })
        assertEquals(1, journal.loadAll().size)
    } }
    @Test fun maxUsesExactConfirmedDrainAndJournalsTxidBeforeSubmit() = runBlocking { fixture { wallet, ops, journal, _ ->
        val quote = ops.quote(OnchainAction.board)
        assertTrue(quote.boardAll)
        assertEquals(98_457L, quote.amount)
        assertEquals(98_127L, quote.net)
        assertEquals(1543L, quote.chainFee)
        assertEquals(100_000L, quote.totalLeaving)
        assertEquals(199L, quote.preparedBoard!!.vsize)
        assertEquals(1, quote.preparedBoard.inputs.size)
        wallet.beforeSubmit = {
            val durable = kotlinx.serialization.json.Json.decodeFromString<List<OnchainOperationJournal.Attempt>>(wallet.attemptFile.readText()).single()
            assertEquals("board-tx", durable.txid)
            assertNull(durable.receipt)
        }
        wallet.feesUnavailable = true
        ops.execute(quote)
        assertEquals(listOf("submit:prepared-0"), wallet.calls)
        assertEquals("board-tx", journal.loadAll().single().txid)
        assertEquals(1543L, journal.loadAll().single().receipt!!.onchainFee)
        assertEquals(listOf("prepared-0"), wallet.discarded)
        assertEquals(1, wallet.preparations.size)
    } }
    @Test fun automaticQuoteRepreparesOnceAndShowsExactReserve() = runBlocking { fixture { wallet, ops, _, _ ->
        val quote = checkNotNull(ops.autoBoardQuote())
        assertEquals(93_414L, quote.amount)
        assertEquals(5000L, quote.reserveLeft)
        assertTrue(quote.automatic)
        assertFalse(quote.boardAll)
        assertEquals(2, wallet.preparations.size)
        assertEquals(listOf("prepared-0"), wallet.discarded)
        assertTrue(wallet.calls.isEmpty())
        wallet.discardBoard(quote.preparedBoard!!.id)
        wallet.balance = 25_537
        assertNull(ops.autoBoardQuote())
        assertTrue(wallet.bound.isEmpty())
        assertTrue(wallet.calls.isEmpty())
    } }
    @Test fun automaticBoardAcceptsAddedFundsButRejectsAReducedBalance() = runBlocking {
        fixture { wallet, ops, _, _ ->
            val quote = checkNotNull(ops.autoBoardQuote())
            wallet.balance -= 1
            assertTrue(runCatching { ops.execute(quote) }.isFailure)
            assertTrue(wallet.calls.isEmpty())
        }
        fixture { wallet, ops, _, _ ->
            val quote = checkNotNull(ops.autoBoardQuote())
            wallet.balance += 10_000
            ops.execute(quote)
            assertEquals(listOf("submit:${quote.preparedBoard!!.id}"), wallet.calls)
        }
    }
    @Test fun automaticSizingStartsFundableWithManyInputsAndHighFees() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.chainRate = 2500; wallet.exactFee = 8570; wallet.inputCount = 15
        assertTrue(runCatching { ops.quote(OnchainAction.board, amount = 92_920) }.isFailure)
        assertTrue(wallet.preparations.isEmpty())
        val quote = checkNotNull(ops.autoBoardQuote())
        assertEquals(86_000L, quote.amount); assertEquals(9000L, quote.chainFee)
        assertEquals(5000L, quote.reserveLeft); assertEquals(15, quote.preparedBoard!!.inputs.size)
        assertEquals(2, wallet.preparations.size); assertEquals(listOf("prepared-0"), wallet.discarded)
        assertEquals(setOf(quote.preparedBoard.id), wallet.bound.keys)
        wallet.discardBoard(quote.preparedBoard.id)
        assertTrue(wallet.bound.isEmpty()); assertTrue(wallet.calls.isEmpty())
    } }
    @Test fun automaticSizingInsufficientBalanceNeverLeavesAPreparation() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.balance = 25_000
        assertNull(ops.autoBoardQuote()); assertTrue(wallet.preparations.isEmpty())
        wallet.balance = 30_000; wallet.exactFee = 30_000
        assertTrue(runCatching { ops.autoBoardQuote() }.isFailure)
        assertTrue(wallet.preparations.isEmpty()); assertTrue(wallet.bound.isEmpty())
        wallet.balance = 26_000; wallet.exactFee = 1543
        assertNull(ops.autoBoardQuote())
        assertEquals(listOf("prepared-0"), wallet.discarded); assertTrue(wallet.bound.isEmpty())
    } }
    @Test fun automaticSizingChecksExactNetAndReserveEdges() = runBlocking {
        for (excess in listOf(0L, 1L)) fixture { wallet, ops, _, _ ->
            wallet.extraFixedFee = excess
            val quote = ops.autoBoardQuote()
            if (excess == 0L) {
                assertEquals(5000L, quote!!.reserveLeft)
                wallet.discardBoard(quote.preparedBoard!!.id)
            } else assertNull(quote)
            assertEquals(listOf("prepared-0", "prepared-1"), wallet.discarded)
            assertTrue(wallet.bound.isEmpty()); assertTrue(wallet.calls.isEmpty())
        }
        for (balance in listOf(26_915L, 26_916L)) fixture { wallet, ops, _, _ ->
            wallet.balance = balance
            val quote = ops.autoBoardQuote()
            if (balance == 26_916L) {
                assertEquals(20_000L, quote!!.net); assertEquals(5000L, quote.reserveLeft)
                wallet.discardBoard(quote.preparedBoard!!.id)
            } else assertNull(quote)
            assertTrue(wallet.bound.isEmpty())
        }
    }
    @Test fun automaticSizingDiscardsProbeBeforeReducedPreparationFailure() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.extraFixedFee = 6000
        assertTrue(runCatching { ops.autoBoardQuote() }.isFailure)
        assertEquals(1, wallet.preparations.size)
        assertEquals(listOf("prepared-0"), wallet.discarded)
        assertTrue(wallet.bound.isEmpty()); assertTrue(wallet.calls.isEmpty())
    } }
    @Test fun automaticSizingDiscardsOnErrorCancellationAndLostReadiness() = runBlocking {
        for (stage in listOf(1, 2)) for (outcome in listOf("error", "cancel", "not-ready")) fixture { wallet, _, journal, _ ->
            var ready = true
            val ops = OnchainOperations(wallet, journal, ready = { ready })
            wallet.afterBind = {
                if (wallet.preparations.size == stage) when (outcome) {
                    "error" -> error("bind failed")
                    "cancel" -> throw CancellationException("interrupted")
                    else -> ready = false
                }
            }
            val result = runCatching { ops.autoBoardQuote() }
            if (outcome == "not-ready") assertNull(result.getOrThrow()) else assertTrue(result.isFailure)
            assertEquals(wallet.preparations.map { it.id }, wallet.discarded)
            assertTrue(wallet.bound.isEmpty()); assertTrue(wallet.calls.isEmpty()); assertTrue(journal.loadAll().isEmpty())
        }
        for (stage in listOf(1, 2)) fixture { wallet, ops, _, _ ->
            val owner = Job()
            wallet.afterPrepare = { if (wallet.preparations.size == stage) owner.cancel() }
            assertTrue(runCatching { withContext(owner) { ops.autoBoardQuote() } }.exceptionOrNull() is CancellationException)
            assertEquals(wallet.preparations.map { it.id }, wallet.discarded)
            assertTrue(wallet.bound.isEmpty())
        }
    }
    @Test fun staleChangedFeesBalanceIdentityAndInputsPreventAllMutations() = runBlocking { fixture { wallet, ops, journal, _ ->
        val quote = ops.quote(OnchainAction.board, amount = 30_000)
        val invalid = listOf(quote.copy(createdAt = System.currentTimeMillis() - 120_000), quote.copy(walletID = "other"),
            quote.copy(fee = checkNotNull(quote.fee) + 1), quote.copy(chainFee = checkNotNull(quote.chainFee) + 1), quote.copy(confirmed = quote.confirmed + 1))
        for (q in invalid) assertTrue(runCatching { ops.execute(q) }.isFailure)
        val exit = ops.quote(OnchainAction.startExit, listOf("a"))
        wallet.items = emptyList()
        assertTrue(runCatching { ops.execute(exit) }.isFailure)
        assertTrue(wallet.calls.isEmpty()); assertTrue(journal.loadAll().isEmpty())
    } }
    @Test fun estimateNeverStartsOrProgressesAndStartReceiptExplainsDaemon() = runBlocking { fixture { wallet, ops, journal, history ->
        val quote = ops.quote(OnchainAction.startExit, listOf("a"))
        assertEquals(7630L, quote.chainFee); assertEquals(1295L, quote.fee)
        assertTrue(wallet.calls.isEmpty()); assertTrue(wallet.states.isEmpty())
        val message = ops.execute(quote)
        assertTrue(message.contains("in the background when the system lets it")); assertEquals(listOf("start"), wallet.calls)
        assertFalse(history.exists()); assertNotNull(journal.loadAll().single().receipt)
    } }
    @Test fun duplicateIDsDustAndPartialProgressAreRefused() = runBlocking { fixture { wallet, ops, _, _ ->
        assertTrue(runCatching { ops.quote(OnchainAction.refresh, listOf("a", "a")) }.isFailure)
        wallet.items = listOf(Wallet.vtxo("a", 329u))
        assertTrue(runCatching { ops.quote(OnchainAction.startExit, listOf("a")) }.isFailure)
        wallet.active = listOf("a", "b").map { ExitVtxo(it, 30_000u, ExitState.Start(100u), false) }
        assertTrue(runCatching { ops.quote(OnchainAction.progress, listOf("a")) }.isFailure)
    } }
    @Test fun aStartOrCancelForgetsItsExitsEvidenceEvenIfItsCallerIsCanceled() = runBlocking {
        for ((action, accepted) in listOf(OnchainAction.startExit to true, OnchainAction.cancel to true, OnchainAction.cancel to false)) fixture { wallet, ops, journal, _ ->
            wallet.cancelResult = accepted
            if (action == OnchainAction.cancel) {
                wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Start(100u), false)); wallet.states["a"] = ExitState.Start(100u)
            }
            val quote = ops.quote(action, listOf("a"))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            wallet.duringMutation = { entered.complete(Unit); release.await() }
            val forgotten = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
            var after = false
            // As WalletSession.withOpen runs it: the native call on IO under NonCancellable, its caller on its own thread (the
            // page's) and free to go. Coming back to a canceled caller throws, so nothing after the call runs.
            val page = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val caller = launch(page) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { ops.execute(quote, newRun = { forgotten += it }) } }
                after = true
            }
            entered.await(); caller.cancel(); release.complete(Unit); caller.join(); page.close()
            assertFalse("the caller is gone before anything after the call runs", after)
            assertEquals("$action accepted=$accepted", if (accepted) listOf(listOf("a")) else emptyList(), forgotten.toList())
            assertEquals(accepted, journal.loadAll().any { it.receipt != null })
        }
    }

    @Test fun claimFeeIsCheckedBeforeBroadcastAndBroadcastErrorsStayUncertain() = runBlocking { fixture { wallet, ops, journal, _ ->
        wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "hash"), null), true))
        val quote = ops.quote(OnchainAction.claim, listOf("a"), destination = "address")
        wallet.claimFee = 1296
        assertEquals(OnchainFailure.claimFeeChanged, (runCatching { ops.execute(quote) }.exceptionOrNull() as OnchainException).reason)
        assertEquals(listOf("drain"), wallet.calls); assertTrue(journal.loadAll().isEmpty())
        wallet.claimFee = 1295; wallet.broadcastError = BarkError("pre-broadcast looking", BarkErrorCode.DUST)
        assertEquals(OnchainFailure.uncertain, (runCatching { ops.execute(quote) }.exceptionOrNull() as OnchainException).reason)
        assertNotNull(journal.loadAll().single()); assertEquals(listOf("drain", "drain", "broadcast"), wallet.calls)
    } }
    @Test fun refusedCancelIsNotReportedAsSuccess() = runBlocking { fixture { wallet, ops, journal, _ ->
        wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Start(100u), false)); wallet.states["a"] = ExitState.Start(100u)
        val quote = ops.quote(OnchainAction.cancel, listOf("a"))
        assertEquals(OnchainFailure.cancellationRefused, (runCatching { ops.execute(quote) }.exceptionOrNull() as OnchainException).reason)
        assertTrue(journal.loadAll().isEmpty()); assertEquals(listOf("cancel"), wallet.calls)
    } }
    @Test fun corruptLegacyIndexCannotBlockStartClaimOrCancel() = runBlocking {
        for (action in listOf(OnchainAction.startExit, OnchainAction.claim, OnchainAction.cancel)) fixture { wallet, ops, journal, legacy ->
            legacy.writeText("{corrupt legacy index")
            val state = if (action == OnchainAction.claim) ExitState.Claimable(100u, BlockRef(100u, "hash"), null) else ExitState.Start(100u)
            wallet.active = listOf(ExitVtxo("a", 30_000u, state, action == OnchainAction.claim))
            wallet.states["a"] = state; wallet.cancelResult = true
            val quote = ops.quote(action, listOf("a"), destination = if (action == OnchainAction.claim) "address" else null)
            ops.execute(quote)
            assertEquals(when (action) {
                OnchainAction.startExit -> listOf("start")
                OnchainAction.claim -> listOf("drain", "broadcast")
                else -> listOf("cancel")
            }, wallet.calls)
            assertNotNull(journal.loadAll().single().receipt)
            assertEquals("{corrupt legacy index", legacy.readText())
            assertEquals(30_000L, ops.overview(24u).exits.single().amount)
        }
    }
    @Test fun terminalDetailsAndLegacyDiscoveryWorkWithEmptyActiveList() = runBlocking { fixture { wallet, ops, _, history ->
        wallet.states["legacy"] = ExitState.Canceled(100u)
        val overview = ops.overview(24u)
        assertEquals("Canceled", overview.exits.single().state)
        assertEquals(listOf("Start", "Canceled"), ops.detail("legacy")!!.timeline.map { it.state })
        assertTrue(!history.exists())
        assertTrue(wallet.calls.isEmpty())
    } }
    @Test fun reviewedDestinationIsDrainedAndJournaledForOwnAndExternalClaims() = runBlocking {
        for (mine in listOf(true, false)) fixture { wallet, ops, journal, _ ->
            wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "hash"), null), true))
            wallet.mine = mine
            wallet.network = "regtest"
            val destination = if (mine) "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202" else "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"
            val reviewed = ops.quote(OnchainAction.claim, listOf("a"), destination = destination)
            assertEquals(mine, reviewed.destinationIsMine)
            assertEquals(if (mine) null else "This address isn't in your Noah wallet. The claimed funds will be sent there.", reviewed.externalDestinationWarning)
            ops.execute(reviewed)
            assertEquals(listOf(destination, destination), wallet.ownershipChecks)
            assertEquals(listOf(reviewed.destination), wallet.drainDestinations)
            assertEquals(listOf("drain", "broadcast"), wallet.calls)
            val saved = journal.loadAll().single()
            assertEquals(wallet.drainDestinations.single(), saved.quote.destination)
            assertEquals("claim-tx", saved.receipt!!.txid)
        }
    }

    @Test fun aClaimsRecordKeepsWhatItActuallyPaidPastAcknowledgement() = runBlocking {
        for (estimated in listOf(true, false)) fixture { wallet, ops, journal, _ ->
            var disk: String? = null
            val records = ExitClaimRecords({ disk }, { disk = it })
            wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "hash"), null), true))
            wallet.mine = false
            val destination = "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"
            val reviewed = if (estimated) ops.quote(OnchainAction.claim, listOf("a"), destination = destination) else {
                wallet.estimateError = java.net.SocketTimeoutException("estimator offline")
                val unknown = (runCatching { ops.quote(OnchainAction.claim, listOf("a"), destination = destination) }.exceptionOrNull() as ExitEstimateUnavailable).review
                // Continue without estimate: the review has no fee; the drain's own is the one paid.
                wallet.claimFee = 1_310
                unknown.copy(unknownFeeConsent = unknown.id)
            }
            ops.execute(reviewed, recordClaim = records::add)
            val fee = if (estimated) 1_295L else 1_310L
            journal.acknowledge(journal.loadAll().single().id)
            assertTrue("acknowledged: the journal no longer has it", journal.loadAll().isEmpty())
            assertEquals(ExitClaimRecords.Record("claim-tx", fee, listOf("a"), false, destination), records.of("a", "claim-tx"))
            // Reopening the finished exit: its receipt comes from the record, with or without the journal.
            val context = ExitContext(0, null, null).claimed(records.of("a", "claim-tx"), null)
            assertEquals(30_000L - fee, ExitRules.received(30_000, checkNotNull(context.claimShare("a"))))
            assertEquals(destination, context.claimedTo)
            assertNull("another exit isn't in it", records.of("b", null))
        }
    }
    @Test fun anUncertainBroadcastStillKeepsWhatTheClaimPays() = runBlocking { fixture { wallet, ops, journal, _ ->
        var disk: String? = null
        val records = ExitClaimRecords({ disk }, { disk = it })
        wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "hash"), null), true))
        wallet.mine = false
        wallet.estimateError = java.net.SocketTimeoutException("estimator offline")
        val unknown = (runCatching { ops.quote(OnchainAction.claim, listOf("a"), destination = "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn") }.exceptionOrNull() as ExitEstimateUnavailable).review
        wallet.claimFee = 1_310
        // The node takes the claim, but its answer times out.
        wallet.broadcastError = java.net.SocketTimeoutException("no answer")
        val failure = runCatching { ops.execute(unknown.copy(unknownFeeConsent = unknown.id), recordClaim = records::add) }.exceptionOrNull()
        assertEquals(OnchainFailure.uncertain, (failure as OnchainException).reason)
        val txid = checkNotNull(ExitTransactions.parse(wallet.claimTransaction("psbt"))).txid
        // Written before broadcast, from the signed claim: once bark reports it claimed by this txid, Done has its real fee.
        assertEquals(ExitClaimRecords.Record(txid, 1_310, listOf("a"), false, "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"), records.of("a", txid))
        assertEquals(30_000L - 1_310, ExitRules.received(30_000, checkNotNull(ExitContext(0, null, null).claimed(records.of("a", txid), null).claimShare("a"))))
        assertNull("bark names another claim: this record isn't it", records.of("a", "other-claim"))
        assertNull("still uncertain in the journal", journal.loadAll().single().receipt)
    } }
    @Test fun aClaimToThisPhoneIsRecordedWithoutItsAddressAndARecordFailureChangesNothing() = runBlocking { fixture { wallet, ops, journal, _ ->
        wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "hash"), null), true))
        var record: ExitClaimRecords.Record? = null
        ops.execute(ops.quote(OnchainAction.claim, listOf("a"), destination = "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202"), recordClaim = { record = it })
        assertEquals(ExitClaimRecords.Record("claim-tx", 1_295, listOf("a"), true, null), record)
        journal.acknowledge(journal.loadAll().single().id)
        ops.execute(ops.quote(OnchainAction.claim, listOf("a"), destination = "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202"), recordClaim = { error("disk full") })
        assertEquals("the second claim still went out and was journaled", 2, wallet.calls.count { it == "broadcast" })
        assertEquals("claim-tx", journal.loadAll().single().receipt!!.txid)
    } }

    @Test fun executionOwnershipChangesAndFailuresPreventClaimConstructionAndSubmission() = runBlocking {
        for (change in listOf("own-external", "external-own", "throws", "invalid", "wrong-network")) fixture { wallet, ops, journal, _ ->
            wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "hash"), null), true))
            wallet.mine = change != "external-own"
            wallet.network = "regtest"
            var reviewed = ops.quote(OnchainAction.claim, listOf("a"), destination = "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202")
            when (change) {
                "own-external", "external-own" -> wallet.mine = !wallet.mine
                "throws" -> wallet.ownershipError = true
                "invalid" -> reviewed = reviewed.copy(destination = "invalid")
                "wrong-network" -> reviewed = reviewed.copy(destination = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT")
            }
            val failure = runCatching { ops.execute(reviewed) }.exceptionOrNull()
            assertNotNull(change, failure)
            if (change != "throws") assertEquals(if (change == "own-external" || change == "external-own") OnchainFailure.changed else OnchainFailure.invalid,
                (failure as OnchainException).reason)
            assertEquals(listOf("bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202", reviewed.destination), wallet.ownershipChecks)
            assertTrue(change, wallet.drainDestinations.isEmpty())
            assertTrue(change, wallet.calls.isEmpty())
            assertTrue(change, journal.loadAll().isEmpty())
        }
    }

    @Test fun emptyDestinationCannotBeReviewedOrExecuted() = runBlocking { fixture { wallet, ops, journal, _ ->
        wallet.active = listOf(ExitVtxo("a", 30_000u, ExitState.Claimable(100u, BlockRef(100u, "hash"), null), true))
        val reviewed = ops.quote(OnchainAction.claim, listOf("a"), destination = "address")
        for (destination in listOf("", "  ")) {
            assertTrue(runCatching { ops.quote(OnchainAction.claim, listOf("a"), destination = destination) }.isFailure)
            assertTrue(runCatching { ops.execute(reviewed.copy(destination = destination)) }.isFailure)
        }
        assertTrue(wallet.drainDestinations.isEmpty()); assertTrue(wallet.calls.isEmpty()); assertTrue(journal.loadAll().isEmpty())
    } }
    @Test fun destinationValidationRejectsMalformedWrongNetworkAndPaymentURIs() {
        val mainnet = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
        validateClaimDestination(mainnet, "mainnet")
        for (address in listOf(mainnet, "invalid", "bitcoin:$mainnet", "tb1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0l98cr"))
            assertTrue(runCatching { validateClaimDestination(address, "regtest") }.isFailure)
        validateClaimDestination("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn", "regtest")
        validateClaimDestination("bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202", "regtest")
        validateClaimDestination("tb1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0l98cr", "signet")
    }

    @Test fun explicitGrossIsNeverRebuiltAndReturnedFeesAreExact() = runBlocking { fixture { wallet, ops, journal, _ ->
        val reviewed = ops.quote(OnchainAction.board, amount = 30_000)
        assertEquals(30_000L, reviewed.amount); assertEquals(29_670L, reviewed.net)
        assertEquals(31_586L, reviewed.totalLeaving)
        assertFalse(reviewExplanation(reviewed).contains("estimate"))
        wallet.chainRate = 2500 // The already signed draft remains the approved transaction.
        ops.execute(reviewed)
        assertEquals(1, wallet.preparations.size)
        assertEquals(reviewed.chainFee, journal.loadAll().single().receipt!!.onchainFee)
    } }
    @Test fun outcomesAndTypedSubmissionErrorsKeepOnlyPotentialDeliveriesLocked() = runBlocking {
        val cases = listOf<Any>(BroadcastOutcome.Accepted, BroadcastOutcome.Failed("transport"), BroadcastOutcome.NotAttempted("setup"),
            BroadcastOutcome.Uncertain("checkpoint write failed"),
            BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND, BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE, BarkErrorCode.PREPARED_BOARD_CHANGED,
            BarkErrorCode.PERSISTENCE, BarkErrorCode.OTHER, BarkErrorCode.INSUFFICIENT_FUNDS)
        for (case in cases) fixture { wallet, ops, journal, _ ->
            val reviewed = ops.quote(OnchainAction.board, amount = 30_000)
            if (case is BroadcastOutcome) wallet.submitOutcome = case
            else wallet.submitError = BarkError("never classify diagnostics", case as BarkErrorCode)
            runCatching { ops.execute(reviewed) }
            val noSubmission = case is BroadcastOutcome.NotAttempted || case in setOf(BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND,
                BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE, BarkErrorCode.PREPARED_BOARD_CHANGED, BarkErrorCode.OTHER)
            assertEquals(case.toString(), noSubmission, journal.loadAll().isEmpty())
            if (case is BroadcastOutcome.Failed) {
                val attempt = journal.loadAll().single()
                assertTrue(attempt.receipt!!.broadcastRetryPending)
                assertEquals(OnchainReceipt.broadcastFailedMessage, attempt.receipt.message)
                assertTrue(runCatching { journal.acknowledge(attempt.id) }.isFailure)
            }
            assertEquals(listOf(reviewed.preparedBoard!!.id), wallet.discarded)
        }
    }
    @Test fun typedPreparationFailuresCreateNoAttemptAndNotAttemptedAllowsFreshReview() = runBlocking { fixture { wallet, ops, journal, _ ->
        for (code in listOf(BarkErrorCode.BELOW_MINIMUM_BOARD_AMOUNT, BarkErrorCode.TOO_MANY_INPUTS,
                BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE, BarkErrorCode.UNSUPPORTED_OPERATION)) {
            wallet.prepareError = BarkError("never display native diagnostics", code)
            val failure = runCatching { ops.quote(OnchainAction.board, amount = 30_000) }.exceptionOrNull() as OnchainException
            assertEquals(boardFailure(wallet.prepareError!!), failure.reason)
            assertTrue(journal.loadAll().isEmpty()); assertTrue(wallet.calls.isEmpty())
        }
        wallet.prepareError = null
        val first = ops.quote(OnchainAction.board, amount = 30_000)
        wallet.submitOutcome = BroadcastOutcome.NotAttempted("before initial broadcast")
        assertTrue(runCatching { ops.execute(first) }.isFailure)
        assertTrue(journal.loadAll().isEmpty())
        wallet.submitOutcome = BroadcastOutcome.Accepted
        val second = ops.quote(OnchainAction.board, amount = 30_000)
        assertNotEquals(first.preparedBoard!!.id, second.preparedBoard!!.id)
        ops.execute(second)
        assertNotNull(journal.loadAll().single().receipt)
    } }
    @Test fun anUncertainBoardStaysLockedAndUncertainAndIsNeverSubmittedAgain() = runBlocking { fixture { wallet, ops, journal, _ ->
        val reviewed = ops.quote(OnchainAction.board, amount = 30_000)
        wallet.submitOutcome = BroadcastOutcome.Uncertain("checkpoint write failed")
        assertEquals(OnchainFailure.uncertain, (runCatching { ops.execute(reviewed) }.exceptionOrNull() as OnchainException).reason)
        val attempt = journal.loadAll().single()
        assertEquals("board-tx", attempt.txid); assertNull(attempt.receipt); assertTrue(attempt.uncertainBoard)
        assertTrue(attempt.blocks(OnchainAction.board, emptyList()))
        assertEquals("released, not resubmitted", listOf(reviewed.preparedBoard!!.id), wallet.discarded)
        // Nothing found yet, but the coins aren't proven unspent: it stays uncertain, and neither a review nor auto-board can board.
        wallet.fundingTransactions = emptyList(); wallet.chainStatus = TransactionStatus.NotFound
        assertFalse(ops.check(attempt.id))
        assertNull(ops.autoBoardFunding())
        wallet.submitOutcome = BroadcastOutcome.Accepted
        assertEquals(OnchainFailure.busy, (runCatching { ops.execute(ops.quote(OnchainAction.board, amount = 30_000)) }.exceptionOrNull() as OnchainException).reason)
        // bark had recorded it: its pending board names the funding txid, and Check status finds it with delivery protection.
        wallet.boardTxids = setOf("board-tx")
        assertTrue(ops.check(attempt.id))
        val found = journal.loadAll().single()
        assertTrue(found.receipt!!.broadcastRetryPending); assertTrue(found.receipt.message.contains("Do not board again"))
        assertTrue(found.blocks(OnchainAction.board, emptyList()))
        assertEquals(OnchainFailure.busy, (runCatching { journal.acknowledge(found.id) }.exceptionOrNull() as OnchainException).reason)
        assertEquals(1, wallet.calls.count { it.startsWith("submit:") })
    } }
    @Test fun noOverrideUnlocksAnUncertainBoardEvenAfterAReload() = runBlocking { fixture { wallet, ops, journal, _ ->
        wallet.submitOutcome = BroadcastOutcome.Uncertain("checkpoint write failed")
        val first = ops.quote(OnchainAction.board, amount = 30_000)
        runCatching { ops.execute(first) }
        wallet.submitOutcome = BroadcastOutcome.Accepted
        assertEquals(OnchainFailure.busy, (runCatching { journal.overrideUncertain(first.id) }.exceptionOrNull() as OnchainException).reason)
        // Relaunched: the flag is on disk, so the reloaded journal refuses the override and the second board too.
        val reloaded = OnchainOperationJournal(OnchainFile(wallet.attemptFile) {})
        val again = OnchainOperations(wallet, reloaded)
        assertTrue(reloaded.loadAll().single().uncertainBoard)
        assertEquals(OnchainFailure.busy, (runCatching { reloaded.overrideUncertain(first.id) }.exceptionOrNull() as OnchainException).reason)
        assertEquals(OnchainFailure.busy, (runCatching { again.execute(again.quote(OnchainAction.board, amount = 30_000)) }.exceptionOrNull() as OnchainException).reason)
        // Even a journal written with an override on it still blocks.
        assertTrue(reloaded.loadAll().single().copy(overrideAudit = OnchainOperationJournal.OverrideAudit(1)).blocks(OnchainAction.board, emptyList()))
        assertEquals(1, wallet.calls.count { it.startsWith("submit:") })
    } }
    @Test fun anUncertainBoardProvenNeverRecordedIsReleased() = runBlocking { fixture { wallet, ops, journal, _ ->
        wallet.submitOutcome = BroadcastOutcome.Uncertain("lookup failed")
        val first = ops.quote(OnchainAction.board, amount = 30_000)
        runCatching { ops.execute(first) }
        wallet.submitOutcome = BroadcastOutcome.Accepted
        val id = journal.loadAll().single().id
        wallet.fundingTransactions = emptyList(); wallet.chainStatus = TransactionStatus.NotFound
        val input = OnchainUtxo.Local(OutPoint("input-0", 0u), 100_000u, 100u)
        // Any doubt keeps it: the chain source unreachable, a coin no longer the wallet's, or bark naming the txid.
        wallet.fundingUtxos = listOf(input); wallet.chainError = java.net.SocketTimeoutException()
        assertTrue(runCatching { ops.check(id) }.isFailure); assertEquals(1, journal.loadAll().size)
        wallet.chainError = null; wallet.fundingUtxos = emptyList()
        assertFalse(ops.check(id)); assertEquals(1, journal.loadAll().size)
        // Nothing names it, the chain source doesn't know it, and its input is still the wallet's: nothing was boarded.
        wallet.fundingUtxos = listOf(input)
        assertTrue(ops.check(id))
        assertTrue(journal.loadAll().isEmpty())
        ops.execute(ops.quote(OnchainAction.board, amount = 30_000))
        assertEquals("a fresh review boards", 2, wallet.calls.count { it.startsWith("submit:") })
        assertNotNull(journal.loadAll().single().receipt)
    } }
    @Test fun aBoardMovementIsFoundByItsChainAnchorWithoutAPendingBoardOrStoredVtxo() {
        val txid = "8052815cfc4cfce003689ae6bdc6d6feea531577cb6732e98e335d959a4b5fc9"
        fun movement(subsystem: String, metadata: String) = Movement(4u, "pending", subsystem, "board", metadata, 30_000, 29_670, 330u,
            emptyList(), emptyList(), emptyList(), listOf("unstored:0"), emptyList(), "now", "now", null, null, null, null)
        // Written before bark's checkpoint: no pending board, no stored VTXO, no chain transaction.
        assertEquals(setOf(txid), boardMovementTxids(listOf(movement("bark.board", """{"chain_anchor":"$txid:0","onchain_fee_sat":155}"""))))
        // Only BOARD movements, and only a well-formed anchor.
        assertTrue(boardMovementTxids(listOf(movement("bark.arkoor", """{"chain_anchor":"$txid:0"}"""),
            movement("bark.board", """{"chain_anchor":"not-an-outpoint"}"""), movement("bark.board", "{"), movement("bark.board", "{}"))).isEmpty())
    }
    @Test fun anOrphanBoardMovementReleasesOnlyWhenNothingCanBroadcastTheBoard() = runBlocking {
        val cases = listOf("released", "pending board", "found txid", "wallet transaction", "spent input", "unreachable source")
        for (crash in listOf(false, true)) for (case in cases) fixture { wallet, ops, journal, _ ->
            val first = ops.quote(OnchainAction.board, amount = 30_000)
            if (crash) runCatching { journal.perform(first) { record -> record("board-tx"); error("process died before checkpoint") } }
            else { wallet.submitOutcome = BroadcastOutcome.Uncertain("checkpoint write failed"); runCatching { ops.execute(first) } }
            wallet.submitOutcome = BroadcastOutcome.Accepted
            val id = journal.loadAll().single().id
            // bark wrote the BOARD movement and nothing else.
            wallet.movementTxids = setOf("board-tx"); wallet.fundingTransactions = emptyList(); wallet.chainStatus = TransactionStatus.NotFound
            wallet.fundingUtxos = listOf(OnchainUtxo.Local(OutPoint("input-0", 0u), 100_000u, 100u))
            when (case) {
                "pending board" -> wallet.boardTxids = setOf("board-tx")
                "found txid" -> wallet.chainStatus = TransactionStatus.InMempool
                "wallet transaction" -> wallet.fundingTransactions = listOf(WalletTransaction("board-tx", "", 155u, -30_155, null, false))
                "spent input" -> wallet.fundingUtxos = emptyList()
                "unreachable source" -> wallet.chainError = java.net.SocketTimeoutException()
            }
            runCatching { ops.check(id) }
            val submits = if (crash) 0 else 1
            if (case == "released") {
                assertTrue("crash=$crash", journal.loadAll().isEmpty())
                ops.execute(ops.quote(OnchainAction.board, amount = 30_000))
                assertEquals(submits + 1, wallet.calls.count { it.startsWith("submit:") })
            } else {
                val kept = journal.loadAll().single()
                assertTrue("$case crash=$crash", kept.blocks(OnchainAction.board, emptyList()))
                assertEquals("$case crash=$crash", OnchainFailure.busy, (runCatching { journal.overrideUncertain(id) }.exceptionOrNull() as OnchainException).reason)
                assertEquals(OnchainFailure.busy, (runCatching { ops.execute(ops.quote(OnchainAction.board, amount = 30_000)) }.exceptionOrNull() as OnchainException).reason)
                assertEquals(submits, wallet.calls.count { it.startsWith("submit:") })
            }
        }
    }
    @Test fun checkStatusAndAutoBoardWaitForABoardStillBeingSubmitted() = runBlocking { fixture { wallet, ops, journal, _ ->
        // Everything a check would read as "nothing boarded", even an orphan movement, while bark is still submitting.
        wallet.movementTxids = setOf("board-tx"); wallet.chainStatus = TransactionStatus.NotFound
        wallet.fundingUtxos = listOf(OnchainUtxo.Local(OutPoint("input-0", 0u), 100_000u, 100u))
        wallet.fundingTransactions = listOf(WalletTransaction("input-0", "", null, 100_000, null, false))
        val reviewed = ops.quote(OnchainAction.board, amount = 30_000)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        wallet.beforeSubmit = { entered.complete(Unit); gate.await() }
        val submission = async { ops.execute(reviewed) }
        entered.await()
        var disk: String? = null
        val check = async { ops.check(reviewed.id) }
        val admission = async { AutoBoardReview(AutoBoardFunding({ disk }, { disk = it })).prepare({ true }, { ops.autoBoardFunding() },
            { ops.autoBoardQuote() }, { wallet.discardBoard(it.preparedBoard!!.id) }) }
        repeat(20) { yield() }
        assertFalse("both wait for the submission's journal lock", check.isCompleted || admission.isCompleted)
        val held = kotlinx.serialization.json.Json.decodeFromString<List<OnchainOperationJournal.Attempt>>(wallet.attemptFile.readText()).single()
        assertEquals("board-tx", held.txid); assertNull(held.receipt)
        // The only submission is the one still inside the SDK call; nothing prepared another.
        assertEquals(1, wallet.preparations.size); assertTrue(wallet.calls.none { it.startsWith("submit:") })
        // The board settles (bark accepted it): the check finds its receipt and auto-board offers nothing.
        gate.complete(Unit)
        submission.await()
        assertTrue(check.await()); assertNull(admission.await())
        assertNotNull(journal.loadAll().single().receipt)
        assertEquals("no second preparation or submission", 1 to 1, wallet.preparations.size to wallet.calls.count { it.startsWith("submit:") })
    } }
    @Test fun aReorgedOrUnverifiableParentAtSubmitReadsAsBeforeSubmission() = runBlocking {
        // SDK 0.7.0's confirmed-only re-check: a parent back in the mempool, or a chain source it can't reach.
        for ((code, reason) in listOf(BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE to OnchainFailure.reserved, BarkErrorCode.OTHER to OnchainFailure.boardNotAttempted)) fixture { wallet, ops, journal, _ ->
            wallet.submitError = BarkError("prepared board parent is no longer confirmed", code)
            val failure = runCatching { ops.execute(ops.quote(OnchainAction.board, amount = 30_000)) }.exceptionOrNull() as OnchainException
            assertEquals(code.name, reason, failure.reason)
            assertTrue(failure.reason.text.contains("not submitted") || failure.reason.text.contains("unavailable"))
            assertTrue("nothing recorded, nothing locked", journal.loadAll().isEmpty())
            wallet.submitError = null
            ops.execute(ops.quote(OnchainAction.board, amount = 30_000))
            assertNotNull("a new review boards", journal.loadAll().single().receipt)
        }
    }
    @Test fun rejectedBoardReleasesJournalForLaterManualAndAutomaticBoards() = runBlocking {
        for (automatic in listOf(false, true)) fixture { wallet, ops, journal, _ ->
            val first = ops.quote(OnchainAction.board, amount = 30_000)
            wallet.submitOutcome = BroadcastOutcome.NotAttempted("server rejected cosign")
            assertTrue(runCatching { ops.execute(first) }.isFailure)
            assertTrue(journal.loadAll().isEmpty())
            assertTrue(wallet.bound.isEmpty())
            wallet.submitOutcome = BroadcastOutcome.Accepted
            val next = if (automatic) checkNotNull(ops.autoBoardQuote()) else ops.quote(OnchainAction.board, amount = 30_000)
            ops.execute(next)
            assertEquals(1, journal.loadAll().size)
            assertNotNull(journal.loadAll().single().receipt)
        }
    }
    @Test fun typedPreparationCopyAndTwoMinuteExpiry() {
        val expected = mapOf(BarkErrorCode.BELOW_MINIMUM_BOARD_AMOUNT to OnchainFailure.belowMinimum,
            BarkErrorCode.TOO_MANY_INPUTS to OnchainFailure.tooManyInputs,
            BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE to OnchainFailure.reserved,
            BarkErrorCode.PREPARED_BOARD_CHANGED to OnchainFailure.changed,
            BarkErrorCode.UNSUPPORTED_OPERATION to OnchainFailure.notSynced)
        expected.forEach { (code, reason) -> assertEquals(reason, boardFailure(BarkError("untrusted", code))) }
        val quote = OnchainQuote(OnchainAction.board, "wallet", createdAt = 1000,
            preparedBoard = BoardPrepared("id", "tx", 199, listOf(BoardInput("in", 0u))))
        assertTrue(quote.current(120_999)); assertFalse(quote.current(121_000)); assertFalse(quote.current(999))
    }
    @Test fun persistedListingOwnsTerminalDiscoveryAndExactAmountsWithoutVtxoLookupOrAppIndex() = runBlocking { fixture { wallet, ops, _, history ->
        wallet.forbidVtxoLookup = true
        wallet.states["imported-canceled"] = ExitState.Canceled(100u)
        wallet.states["imported-claimed"] = ExitState.Claimed(101u, "claim-tx", BlockRef(101u, "block"))
        assertTrue(wallet.active.isEmpty()); assertTrue(!history.exists())
        val all = ops.overview(24u).exits
        assertEquals(setOf("Canceled", "Claimed"), all.map { it.state }.toSet())
        assertTrue(all.all { it.amount == 30_000L && it.timeline.first().state == "Start" })
        assertEquals(all, OnchainOperations(wallet, OnchainOperationJournal(OnchainFile(File(wallet.attemptFile.parentFile, "reopened")) {})).overview(24u).exits)
    } }

}
