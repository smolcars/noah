package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The fake wallet and journal every OnchainOperations test runs against. */
internal abstract class OnchainOperationsFixture {
    protected class Wallet : OnchainWalletAccess {
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
            val spendable = balance - fundingUtxos.filterIsInstance<OnchainUtxo.Local>().filter {
                "${it.outpoint.txid}:${it.outpoint.vout}" in excluded
            }.sumOf { it.amountSats.toLong() }
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
        override suspend fun drain(ids: List<String>, destination: String): ExitClaimTransaction {
            drainDestinations.add(destination)
            calls.add("drain")
            return ExitClaimTransaction("psbt", claimFee.toULong())
        }
        override suspend fun broadcastClaim(psbt: String): String { calls.add("broadcast"); broadcastError?.let { throw it }; return "claim-tx" }
        /** A signed one-output transaction (txid 75ebf755…), standing in for the drain's claim. */
        override suspend fun claimTransaction(psbt: String) =
            "020000000001013e16e76ee2b6a9dc798b81be3a4903286e4e454dc019c48062d5396b4e21fdca0000000000fdffffff01d09b0000000000002251208751b9234a38be714a80" +
                "11a02bad9f346f7b80e4a76b13fcacda7b0e2890c5e601408982addc732de240dd1dfb0bd4a66e0ab807073c73d92b26256827a2643735827b1eb0cb92812c4969544c107489" +
                "479dc54ec9bc27409a15bef8486972bf03297f030000"
        companion object {
            fun vtxo(id: String, amount: ULong = 30_000u) =
                Vtxo("server", 144u, OutPoint("anchor", 0u), OutPoint("point", 0u), id, amount, 500u, "pubkey", VtxoState.Spendable, 1u, 500u, true)
        }
    }

    protected suspend fun fixture(body: suspend (Wallet, OnchainOperations, OnchainOperationJournal, File) -> Unit) {
        val root = Files.createTempDirectory("m6-ops").toFile()
        try {
            val journal = OnchainOperationJournal(OnchainFile(File(root, "attempt")) {})
            val history = File(root, "noah-exit-history.json")
            val wallet = Wallet().apply { attemptFile = File(root, "attempt") }
            withTimeout(10_000) { body(wallet, OnchainOperations(wallet, journal), journal, history) }
        } finally { root.deleteRecursively() }
    }
    // exit.md, Decision 8: coins claimed to this phone stay on-chain; auto-board weighs only the rest.
    protected val claimed = OnchainUtxo.Local(OutPoint("claim", 0u), 28_375u, 100u)
    protected val deposit = OnchainUtxo.Local(OutPoint("deposit", 0u), 60_000u, 100u)
    protected val claimTx = WalletTransaction("claim", "", null, 28_375, null, false)
    protected val depositTx = WalletTransaction("deposit", "", null, 60_000, null, false)
    protected fun ledger(): AutoBoardFunding {
        var disk: String? = null
        return AutoBoardFunding({ disk }, { disk = it }).apply { keep("wallet", setOf("claim:0")) }
    }
}
