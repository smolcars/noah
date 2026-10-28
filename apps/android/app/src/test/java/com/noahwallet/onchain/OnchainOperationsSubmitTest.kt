package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Submission: starts and cancels, claims and their records, and uncertain boards. */
internal class OnchainOperationsSubmitTest : OnchainOperationsFixture() {
    @Test fun aStartOrCancelForgetsItsExitsEvidenceEvenIfItsCallerIsCanceled() = runBlocking {
        for ((action, accepted) in listOf(OnchainAction.startExit to true, OnchainAction.cancel to true, OnchainAction.cancel to false))
            fixture { wallet, ops, journal, _ ->
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
            assertEquals(if (mine) null else "This address isn't in your Noah wallet. The claimed funds will be sent there.",
                reviewed.externalDestinationWarning)
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
                val unknown = (runCatching { ops.quote(OnchainAction.claim, listOf("a"),
                    destination = destination) }.exceptionOrNull() as ExitEstimateUnavailable).review
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
        val unknown = (runCatching { ops.quote(OnchainAction.claim, listOf("a"),
            destination = "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn") }.exceptionOrNull() as ExitEstimateUnavailable).review
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
        ops.execute(ops.quote(OnchainAction.claim, listOf("a"), destination = "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202"),
            recordClaim = { error("disk full") })
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
        assertEquals(OnchainFailure.busy,
            (runCatching { ops.execute(ops.quote(OnchainAction.board, amount = 30_000)) }.exceptionOrNull() as OnchainException).reason)
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
        assertEquals(OnchainFailure.busy,
            (runCatching { again.execute(again.quote(OnchainAction.board, amount = 30_000)) }.exceptionOrNull() as OnchainException).reason)
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
                assertEquals("$case crash=$crash", OnchainFailure.busy,
                    (runCatching { journal.overrideUncertain(id) }.exceptionOrNull() as OnchainException).reason)
                assertEquals(OnchainFailure.busy,
                    (runCatching { ops.execute(ops.quote(OnchainAction.board, amount = 30_000)) }.exceptionOrNull() as OnchainException).reason)
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
        for ((code, reason) in listOf(BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE to OnchainFailure.reserved,
            BarkErrorCode.OTHER to OnchainFailure.boardNotAttempted)) fixture { wallet, ops, journal, _ ->
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
        val quote = OnchainQuote(OnchainAction.board, "wallet", reviewedAt = 1000,
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
        assertEquals(all, OnchainOperations(wallet,
            OnchainOperationJournal(OnchainFile(File(wallet.attemptFile.parentFile, "reopened")) {})).overview(24u).exits)
    } }
}
