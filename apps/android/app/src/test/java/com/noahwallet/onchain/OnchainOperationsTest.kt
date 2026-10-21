package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OnchainOperationsTest {
    @Test fun orphanBoardWithoutCheckpointLocalTransactionOrNetworkEvidenceAllowsExplicitOverride() = runBlocking { fixture { wallet, ops, journal, _ ->
        val reviewed = ops.quote(OnchainAction.board, amount = 30_000)
        runCatching { journal.perform(reviewed) { record -> record("board-tx"); error("process died before checkpoint") } }
        wallet.fundingTransactions = emptyList()
        wallet.chainStatus = TransactionStatus.NotFound
        // An orphan movement has no persisted output VTXO to resolve; Android never reads chain_anchor.
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
    @Test fun incompleteProbeCleanupStopsBeforePreparingAgain() = runBlocking { fixture { wallet, ops, journal, _ ->
        wallet.cleanupComplete = false
        val failure = runCatching { ops.autoBoardQuote() }.exceptionOrNull()
        assertEquals(OnchainFailure.uncertain, (failure as OnchainException).reason)
        assertEquals(1, wallet.preparations.size)
        assertEquals(listOf("prepared-0"), wallet.discarded)
        assertTrue(journal.loadAll().isEmpty())
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
        override suspend fun prepareBoard(amount: Long, all: Boolean, rate: ULong): PreparedBoard {
            prepareError?.let { throw it }
            val chainFee = exactFee + if (all) 0 else 43 * rate.toLong() + extraFixedFee
            val gross = if (all) balance - chainFee else amount
            if (gross < fee || gross + chainFee > balance) throw BarkError("insufficient balance", BarkErrorCode.INSUFFICIENT_FUNDS)
            return PreparedBoard("prepared-${preparations.size}", "board-tx", gross.toULong(), (gross - fee).toULong(), fee.toULong(), chainFee.toULong(), 199u,
                List(inputCount) { OutPoint("input-$it", 0u) }).also { preparations.add(it); afterPrepare(it) }
        }
        override fun bindBoard(quote: OnchainQuote) { bound[quote.preparedBoard!!.id] = quote; afterBind() }
        override fun claimBoard(quote: OnchainQuote) { if (bound[quote.preparedBoard!!.id] != quote) fail(OnchainFailure.changed) }
        override suspend fun discardBoard(id: String, includingClaimed: Boolean) { discarded.add(id); bound.remove(id) }
        override suspend fun allExits() = states.keys.mapNotNull { status(it) }
        override suspend fun transactionStatus(txid: String): TransactionStatus = chainStatus
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
        override suspend fun exitFee(ids: List<String>, destination: String?) = EmergencyExitFeeEstimate(7630u, 1295u, 8925u, 10u, 2u)
        override suspend fun vtxos() = items
        override suspend fun vtxo(id: String): Vtxo { check(!forbidVtxoLookup); return Companion.vtxo(id) }
        override suspend fun exits() = active
        override suspend fun status(id: String) = states[id]?.let { ExitTransactionStatus(id, 30_000u, it, listOf(ExitState.Start(100u), it), 0u, emptyList()) }
        override suspend fun tip() = 100L
        override suspend fun expiring(threshold: UInt) = emptySet<String>()
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
        override suspend fun start(ids: List<String>) { calls.add("start"); ids.forEach { states[it] = ExitState.Start(100u) } }
        override suspend fun cancel(id: String): Boolean { calls.add("cancel"); return cancelResult }
        override suspend fun drain(ids: List<String>, destination: String): ExitClaimTransaction { drainDestinations.add(destination); calls.add("drain"); return ExitClaimTransaction("psbt", claimFee.toULong()) }
        override suspend fun broadcastClaim(psbt: String): String { calls.add("broadcast"); broadcastError?.let { throw it }; return "claim-tx" }
        companion object {
            fun vtxo(id: String, amount: ULong = 30_000u) = Vtxo("server", 144u, OutPoint("anchor", 0u), OutPoint("point", 0u), id, amount, 500u, "pubkey", VtxoState.Spendable, 1u, 500u, true)
        }
    }
    private suspend fun fixture(body: suspend (Wallet, OnchainOperations, OnchainOperationJournal, File) -> Unit) {
        val root = Files.createTempDirectory("m6-ops").toFile()
        try {
            val journal = OnchainOperationJournal(OnchainFile(File(root, "attempt")) {})
            val history = File(root, "noah-exit-history.json")
            val wallet = Wallet().apply { attemptFile = File(root, "attempt") }
            withTimeout(10_000) { body(wallet, OnchainOperations(wallet, journal), journal, history) }
        } finally { root.deleteRecursively() }
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
        wallet.submitError = BarkError("uncertain", BarkErrorCode.OTHER)
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
            quote.copy(fee = quote.fee + 1), quote.copy(chainFee = quote.chainFee + 1), quote.copy(confirmed = quote.confirmed + 1))
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
        assertTrue(message.contains("automatically")); assertEquals(listOf("start"), wallet.calls)
        assertFalse(history.exists()); assertNotNull(journal.loadAll().single().receipt)
    } }
    @Test fun duplicateIDsDustAndPartialProgressAreRefused() = runBlocking { fixture { wallet, ops, _, _ ->
        assertTrue(runCatching { ops.quote(OnchainAction.refresh, listOf("a", "a")) }.isFailure)
        wallet.items = listOf(Wallet.vtxo("a", 329u))
        assertTrue(runCatching { ops.quote(OnchainAction.startExit, listOf("a")) }.isFailure)
        wallet.active = listOf("a", "b").map { ExitVtxo(it, 30_000u, ExitState.Start(100u), false) }
        assertTrue(runCatching { ops.quote(OnchainAction.progress, listOf("a")) }.isFailure)
    } }
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
            BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND, BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE, BarkErrorCode.PREPARED_BOARD_CHANGED,
            BarkErrorCode.PERSISTENCE, BarkErrorCode.OTHER, BarkErrorCode.INSUFFICIENT_FUNDS)
        for (case in cases) fixture { wallet, ops, journal, _ ->
            val reviewed = ops.quote(OnchainAction.board, amount = 30_000)
            if (case is BroadcastOutcome) wallet.submitOutcome = case
            else wallet.submitError = BarkError("never classify diagnostics", case as BarkErrorCode)
            runCatching { ops.execute(reviewed) }
            val noSubmission = case is BroadcastOutcome.NotAttempted || case in setOf(BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND, BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE, BarkErrorCode.PREPARED_BOARD_CHANGED)
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
