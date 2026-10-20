package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OnchainOperationsTest {
    private class Wallet : OnchainWalletAccess {
        override val walletID = "wallet"
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
        var balance = 100_000L
        var fee = 330L
        var chainRate = 250L
        var items = listOf(Companion.vtxo("a"))
        var active = listOf<ExitVtxo>()
        var states = mutableMapOf<String, ExitState>()
        var calls = mutableListOf<String>()
        var feesUnavailable = false
        var claimFee = 1295L
        var broadcastError: Exception? = null
        override suspend fun confirmed() = balance
        override suspend fun minimum() = 10_000L
        override suspend fun regularSatPerKwu() = chainRate
        override suspend fun boardFee(amount: Long) = FeeEstimate(amount.toULong(), fee.toULong(), (amount - fee).toULong(), emptyList())
        override suspend fun refreshFee(ids: List<String>) = FeeEstimate(30_000u, 269u, 29_731u, ids)
        override suspend fun exitFee(ids: List<String>, destination: String?) = EmergencyExitFeeEstimate(7630u, 1295u, 8925u, 10u, 2u)
        override suspend fun vtxos() = items
        override suspend fun vtxo(id: String) = Companion.vtxo(id)
        override suspend fun exits() = active
        override suspend fun status(id: String) = states[id]?.let { ExitTransactionStatus(id, it, listOf(ExitState.Start(100u), it), 0u, emptyList()) }
        override suspend fun tip() = 100L
        override suspend fun expiring(threshold: UInt) = emptySet<String>()
        override suspend fun legacyExitIDs() = listOf("legacy")
        override suspend fun pendingTotal() = 0L
        override suspend fun claimableHeight(): Long? = null
        override suspend fun board(amount: Long, all: Boolean): PendingBoard {
            calls.add(if (all) "boardAll" else "board:$amount")
            return PendingBoard(listOf("new"), amount.toULong(), "board-tx")
        }
        override suspend fun transactions(): List<WalletTransaction> {
            calls.add("transactions")
            if (feesUnavailable) error("lookup unavailable")
            return listOf(WalletTransaction("board-tx", "", 1543u, -30_000, null, false))
        }
        override suspend fun start(ids: List<String>) { calls.add("start"); ids.forEach { states[it] = ExitState.Start(100u) } }
        override suspend fun cancel(id: String): Boolean { calls.add("cancel"); return false }
        override suspend fun drain(ids: List<String>, destination: String): ExitClaimTransaction { drainDestinations.add(destination); calls.add("drain"); return ExitClaimTransaction("psbt", claimFee.toULong()) }
        override suspend fun broadcastClaim(psbt: String): String { calls.add("broadcast"); broadcastError?.let { throw it }; return "claim-tx" }
        companion object {
            fun vtxo(id: String, amount: ULong = 30_000u) = Vtxo("server", 144u, OutPoint("anchor", 0u), OutPoint("point", 0u), id, amount, 500u, "pubkey", VtxoState.Spendable, 1u, 500u, true)
        }
    }
    private suspend fun fixture(body: suspend (Wallet, OnchainOperations, OnchainOperationJournal, ExitHistoryStore) -> Unit) {
        val root = Files.createTempDirectory("m6-ops").toFile()
        try {
            val journal = OnchainOperationJournal(OnchainFile(File(root, "attempt")) {})
            val history = ExitHistoryStore(OnchainFile(File(root, "history")) {})
            val wallet = Wallet()
            withTimeout(10_000) { body(wallet, OnchainOperations(wallet, journal, history), journal, history) }
        } finally { root.deleteRecursively() }
    }
    @Test fun maxUsesBoardAllAndMissingActualFeeDoesNotLoseReceipt() = runBlocking { fixture { wallet, ops, journal, _ ->
        val quote = ops.quote(OnchainAction.board)
        assertTrue(quote.boardAll)
        assertEquals(99_792L, quote.amount)
        wallet.feesUnavailable = true
        ops.execute(quote)
        assertEquals(listOf("boardAll", "transactions"), wallet.calls)
        assertEquals("board-tx", journal.loadAll().single().txid)
        assertNull(journal.loadAll().single().receipt!!.onchainFee)
        wallet.feesUnavailable = false
        ops.refreshReceiptFees()
        assertEquals(1543L, journal.loadAll().single().receipt!!.onchainFee)
        assertEquals(1, wallet.calls.count { it == "boardAll" })
    } }
    @Test fun automaticQuoteOnlyPlansWithEstimatedReserveAndThreshold() = runBlocking { fixture { wallet, ops, _, _ ->
        val quote = checkNotNull(ops.autoBoardQuote())
        assertEquals(94_792L, quote.amount)
        assertTrue(quote.automatic)
        assertFalse(quote.boardAll)
        assertTrue(wallet.calls.isEmpty())
        wallet.balance = 25_537
        assertNull(ops.autoBoardQuote())
        wallet.balance = 25_538
        assertNotNull(ops.autoBoardQuote())
        assertTrue(wallet.calls.isEmpty())
    } }
    @Test fun staleChangedFeesBalanceIdentityAndInputsPreventAllMutations() = runBlocking { fixture { wallet, ops, journal, _ ->
        val quote = ops.quote(OnchainAction.board, amount = 30_000)
        val invalid = listOf(quote.copy(createdAt = System.currentTimeMillis() - 30_000), quote.copy(walletID = "other"),
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
        assertTrue("a" in history.ids("wallet")); assertNotNull(journal.loadAll().single().receipt)
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
    @Test fun terminalDetailsAndLegacyDiscoveryWorkWithEmptyActiveList() = runBlocking { fixture { wallet, ops, _, history ->
        wallet.states["legacy"] = ExitState.Canceled(100u)
        val overview = ops.overview(24u)
        assertEquals("Canceled", overview.exits.single().state)
        assertEquals(listOf("Start", "Canceled"), ops.detail("legacy")!!.timeline.map { it.state })
        assertEquals(listOf("legacy"), history.ids("wallet"))
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

}
