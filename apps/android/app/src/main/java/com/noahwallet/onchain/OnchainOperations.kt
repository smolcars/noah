package com.noahwallet.onchain

import com.noahwallet.services.wallet.ChainStatus
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*

internal class OnchainOperations(
    private val wallet: OnchainWalletAccess, private val journal: OnchainOperationJournal,
    private val ready: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val warn: (String) -> Unit = {},
    /** Deposits auto-board couldn't prepare without a claimed coin, already logged (the session keeps it across calls). */
    private val stranded: MutableSet<String> = mutableSetOf(),
) {
    suspend fun overview(threshold: UInt): OnchainOverview {
        val tip = wallet.tip()
        val expiring = wallet.expiring(threshold)
        val vtxos = wallet.vtxos().map { vtxoModel(it, tip, expiring) }
        // SDK persisted rows cover live and terminal exits, including wallets imported without Noah's index.
        val exits = enrich(wallet.allExits().map(::exitModel))
        return OnchainOverview(wallet.walletID, vtxos, exits, wallet.confirmed(), tip, wallet.pendingTotal(), wallet.claimableHeight())
    }
    /**
     * What this wallet paid each exit's fee children (by txid, from its own transactions) and, for a transaction waiting
     * for its child, that package's fee at the rate bark's exits pay (its estimate's rate, read only while one waits).
     */
    private suspend fun enrich(exits: List<ExitModel>): List<ExitModel> {
        val children = exits.flatMap { exit -> exit.packages.filter { it.childOrigin == "wallet" }.mapNotNull { it.childTxid } }.toSet()
        // BDK can't price a fee child (its zero-value anchor input isn't a wallet coin), but what left the wallet is its fee.
        val fees = if (children.isEmpty()) emptyMap() else wallet.transactions().filter { it.txid in children }
            .mapNotNull { tx -> (tx.onchainFeeSats?.toLong() ?: (-tx.balanceChangeSats).takeIf { it > 0 })?.let { tx.txid to it } }.toMap()
        return exits.map { exit ->
            val waiting = exit.details.transactions?.firstOrNull { it.status.kind != "confirmed" }?.takeIf {
                exit.state == "Processing" && it.status.kind == "awaiting-cpfp-broadcast" }
            val weight = waiting?.let { tx -> exit.packages.find { it.txid == tx.txid }?.weight }
            // bark pays exit packages at its own exit rate, which its estimate reports; unknown leaves the inference off.
            val rate = weight?.let { try { wallet.exitFee(listOf(exit.id), null).feeRateSatPerVb.toLong() * 250 }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } }
            val next = weight?.let { w -> rate?.let { ExitTransactions.packageFee(w, it) } }
            exit.copy(childFees = exit.packages.mapNotNull { p -> p.childTxid?.let { id -> fees[id]?.let { id to it } } }.toMap(), nextPackageFee = next)
        }
    }
    /** Decision 4: each VTXO's own exit estimate (bark's, for it alone), or null where bark can't estimate it now. */
    // shortcut: the standalone estimate, so siblings carry their shared path each; split it once bark-bolt-ffi's estimate has per-transaction data.
    suspend fun exitCosts(ids: List<String>): Map<String, ExitCosts.Cost?> {
        if (!ready()) fail(OnchainFailure.unavailable)
        return ids.associateWith { id ->
            try { wallet.exitFee(listOf(id), null).let { ExitCosts.Cost(it.exitBroadcastFeeSats.toLong(), it.claimFeeSats.toLong(), it.txsToBroadcast.toLong()) } }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        }
    }
    private suspend fun renewable(ids: List<String>, threshold: UInt): Boolean {
        val tip = wallet.tip()
        val expiring = wallet.expiring(threshold)
        val due = wallet.vtxos().map { vtxoModel(it, tip, expiring) }.filter { it.renewable }.map { it.id }.toSet()
        return ids.isNotEmpty() && ids.all { it in due }
    }
    /** One coin by ID, spent ones included: the overview lists only the wallet's current coins. */
    suspend fun vtxo(id: String, threshold: UInt): VtxoModel = vtxoModel(wallet.vtxo(id), wallet.tip(), wallet.expiring(threshold))
    suspend fun detail(id: String): ExitModel? {
        val status = wallet.status(id) ?: return null
        return enrich(listOf(exitModel(status))).single()
    }
    private fun exitModel(status: ExitTransactionStatus): ExitModel {
        val current = exitState(status.state)
        val past = status.history.orEmpty().map(::exitState)
        val packages = status.transactions.map { tx ->
            val parent = ExitTransactions.parse(tx.parentTxHex)
            ExitPackage(tx.parentTxid, parent?.weight, parent?.outputs ?: 0, parent?.inputs?.firstOrNull()?.second, tx.childTxid,
                tx.childTxHex?.let { ExitTransactions.parse(it)?.weight }, tx.childOrigin?.let(::exitOrigin))
        }
        return ExitModel(status.vtxoId, status.amountSats.toLong(), current.first, current.second, past.map { it.first }, past.map { it.second }, packages)
    }
    /**
     * [kept]: each wallet's coins claimed to this phone (exit.md, Decision 8). Auto-board asks only when what it may move
     * without them reaches its 20,000-sat threshold.
     */
    suspend fun autoBoardFunding(kept: (String) -> Set<String> = { emptySet() }): AutoBoardFunding.Snapshot? {
        if (!ready()) fail(OnchainFailure.unavailable)
        for (attempt in journal.loadAll().filter { it.quote.walletID == wallet.walletID && it.blocks(OnchainAction.board, emptyList()) })
            check(attempt.id)
        if (journal.loadAll().any { it.blocks(OnchainAction.board, emptyList()) } || wallet.hasPendingBoards()) return null
        val utxos = wallet.utxos()
        if (AutoBoardFunding.boardable(wallet.confirmed(), utxos, kept(wallet.walletID)) < AutoBoardRules.minimumBalance) return null
        return AutoBoardFunding.confirmed(wallet.walletID, utxos, wallet.transactions())
    }
    /**
     * Sizes the review from what auto-board may move: the confirmed balance less coins claimed to this phone, which stay
     * on-chain as change. bark chooses the inputs, so a review that would spend a claimed coin is never offered; it asks
     * again for a little less, up to three tries in all.
     */
    suspend fun autoBoardQuote(kept: (String) -> Set<String> = { emptySet() }): OnchainQuote? {
        if (!ready()) fail(OnchainFailure.unavailable)
        val minimum = wallet.minimum() ?: fail(OnchainFailure.unavailable)
        val claimed = kept(wallet.walletID)
        val keptValue = if (claimed.isEmpty()) 0 else AutoBoardFunding.keptValue(wallet.utxos(), claimed)
        val balance = wallet.confirmed() - keptValue
        if (balance <= BoardingRules.reserve + BoardingRules.threshold(minimum)) return null
        val probe = quote(OnchainAction.board, automatic = true)
        val candidate = try {
            // Fixed-amount boarding adds a 43-vB Taproot change output to the confirmed-input drain.
            probe.amount - keptValue - BoardingRules.reserve - 43 * checkNotNull(probe.feeRate)
        } finally { withContext(NonCancellable) { wallet.discardBoard(checkNotNull(probe.preparedBoard).id) } }
        if (!wallet.boardCleanupComplete(checkNotNull(probe.preparedBoard).id)) fail(OnchainFailure.uncertain)
        currentCoroutineContext().ensureActive()
        // shortcut: retries by amount so BDK's selection moves off a claimed coin; exclude it once bark-bolt-ffi's prepare_board takes excluded outpoints.
        var amount = candidate
        repeat(3) {
            if (amount < BoardingRules.threshold(minimum) || !ready()) return null
            val prepared = quote(OnchainAction.board, amount = amount, automatic = true)
            val board = checkNotNull(prepared.preparedBoard)
            var reviewed = false
            try {
                currentCoroutineContext().ensureActive()
                if (board.inputs.none { "${it.txid}:${it.vout}" in claimed }) {
                    if (prepared.reserveLeft - keptValue < BoardingRules.reserve || prepared.net < BoardingRules.threshold(minimum) || !ready()) return null
                    reviewed = true
                    return prepared
                }
            } finally {
                if (!reviewed) withContext(NonCancellable) { wallet.discardBoard(board.id) }
            }
            if (!wallet.boardCleanupComplete(board.id)) fail(OnchainFailure.uncertain)
            // Branch-and-bound's selection is deterministic: a little less moves it off the fit that used the claimed coin.
            amount -= maxOf(amount / 100, 1_000)
        }
        if (stranded.add("${wallet.walletID}:$candidate")) warn("""Auto-board: every preparation spent a coin claimed to this phone, so no deposit is offered""")
        return null
    }
    suspend fun quote(action: OnchainAction, ids: List<String> = emptyList(), amount: Long = 0,
        destination: String? = null, automatic: Boolean = false, estimate: Boolean = true): OnchainQuote {
        if (!ready()) fail(OnchainFailure.unavailable)
        val balance = wallet.confirmed()
        var quote = OnchainQuote(action, wallet.walletID, ids.sorted(), destination, amount, confirmed = balance, automatic = automatic, createdAt = now())
        if (ids.distinct().size != ids.size) fail(OnchainFailure.invalid)
        when (action) {
            OnchainAction.board -> {
                if (amount < 0 || amount > 2_100_000_000_000_000L) fail(OnchainFailure.invalid)
                val rate = ((wallet.regularSatPerKwu() + 249) / 250).coerceAtLeast(1).toULong()
                val prepared = try { wallet.prepareBoard(amount, amount == 0L, rate) }
                    catch (error: BarkError) { throw OnchainException(boardFailure(error)) }
                try {
                    currentCoroutineContext().ensureActive()
                    if (!ready() || prepared.grossSats > Long.MAX_VALUE.toULong() ||
                        prepared.netSats + prepared.arkFeeSats != prepared.grossSats) fail(OnchainFailure.changed)
                    quote = quote.copy(amount = prepared.grossSats.toLong(), fee = prepared.arkFeeSats.toLong(),
                        chainFee = prepared.onchainFeeSats.toLong(), boardAll = amount == 0L, feeRate = rate.toLong(),
                        preparedBoard = BoardPrepared(prepared.id, prepared.txid, prepared.vsize.toLong(), prepared.inputs.map { BoardInput(it.txid, it.vout) }))
                    wallet.bindBoard(quote)
                } catch (error: Exception) {
                    withContext(NonCancellable) { wallet.discardBoard(prepared.id) }
                    throw error
                }
            }
            OnchainAction.refresh, OnchainAction.startExit -> {
                val selected = wallet.vtxos().filter { vtxoState(it.state) == "Spendable" && it.id in ids }
                if (ids.isEmpty() || selected.size != ids.size) fail(OnchainFailure.changed)
                quote = quote.copy(amount = selected.sumOf { it.amountSats.toLong() })
                if (action == OnchainAction.refresh) quote = quote.copy(fee = wallet.refreshFee(ids).feeSats.toLong())
                else {
                    if (selected.any { it.amountSats.toLong() < ExitRules.dust }) fail(OnchainFailure.invalid)
                    quote = exitEstimate(quote, estimate)
                }
            }
            OnchainAction.progress, OnchainAction.claim -> {
                val eligible = wallet.exits().filter { if (action == OnchainAction.claim) it.isClaimable else exitState(it.state).first !in ExitTimeline.terminal }
                val selected = eligible.filter { it.vtxoId in ids }
                if (ids.isEmpty() || selected.size != ids.size || (action == OnchainAction.progress && eligible.map { it.vtxoId }.toSet() != ids.toSet())) fail(OnchainFailure.changed)
                if (action == OnchainAction.claim && destination.isNullOrBlank()) fail(OnchainFailure.invalid)
                if (action == OnchainAction.claim) quote = quote.copy(destinationIsMine = wallet.destinationIsMine(checkNotNull(destination)))
                quote = quote.copy(amount = selected.sumOf { it.amountSats.toLong() })
                quote = exitEstimate(quote, estimate)
            }
            OnchainAction.cancel -> {
                if (ids.size != 1) fail(OnchainFailure.invalid)
                val exit = wallet.exits().find { it.vtxoId == ids[0] } ?: fail(OnchainFailure.changed)
                val status = wallet.status(ids[0]) ?: fail(OnchainFailure.changed)
                val state = exitState(status.state)
                if (!ExitRules.cancelable(state.first, state.second.transactions?.lastOrNull()?.status?.kind)) fail(OnchainFailure.cancellationRefused)
                quote = quote.copy(amount = exit.amountSats.toLong())
            }
        }
        return quote
    }
    private suspend fun exitEstimate(validated: OnchainQuote, estimate: Boolean): OnchainQuote {
        val unknown = validated.copy(fee = null, chainFee = null, feeRate = null, transactions = null)
        val fallbackAllowed = validated.action in setOf(OnchainAction.startExit, OnchainAction.claim)
        if (!estimate && fallbackAllowed) return unknown
        val fee = try { wallet.exitFee(validated.ids, validated.destination) }
        catch (failure: Exception) {
            if (fallbackAllowed && ExitEstimateUnavailable.allowsFallback(failure)) throw ExitEstimateUnavailable(unknown)
            throw failure
        }
        return validated.copy(fee = fee.claimFeeSats.toLong(),
            chainFee = if (validated.action == OnchainAction.claim) 0 else fee.exitBroadcastFeeSats.toLong(),
            feeRate = if (validated.action == OnchainAction.claim) null else fee.feeRateSatPerVb.toLong(),
            transactions = if (validated.action == OnchainAction.startExit) fee.txsToBroadcast.toLong() else null)
    }
    /**
     * [renewalThreshold]: the review was Home's Renew, so every VTXO must still be due (in the expiring set at this
     * threshold, spendable, not expired), checked last, under the caller's locks, just before submitting.
     */
    /**
     * [keepOnchain]: a claim to this wallet records its outputs in auto-board's handled ledger (exit.md, Decision 8)
     * before it's broadcast, so auto-board never offers them back to Ark.
     */
    /**
     * [recordClaim]: where a claim's fee, exits and destination are kept for its exits' pages, before it's broadcast.
     * [newRun]: told which exits a start or cancel just changed, inside the native call (which outlives its caller), so their
     * evidence from the last run is forgotten. A failure in either changes nothing.
     */
    suspend fun execute(reviewed: OnchainQuote, funding: AutoBoardFunding.Snapshot? = null, renewalThreshold: UInt? = null,
                        keepOnchain: (Set<String>) -> Unit = {}, kept: (String) -> Set<String> = { emptySet() },
                        recordClaim: (ExitClaimRecords.Record) -> Unit = {}, newRun: (List<String>) -> Unit = {}): String {
        if (!ready() || !reviewed.current(now()) || reviewed.walletID != wallet.walletID) fail(OnchainFailure.changed)
        if (reviewed.action == OnchainAction.board) {
            if (funding != null && (!reviewed.automatic || funding.walletID != wallet.walletID || autoBoardFunding(kept) != funding)) fail(OnchainFailure.changed)
            if (reviewed.preparedBoard == null) fail(OnchainFailure.changed)
            // Added funds leave at least the reviewed reserve; only a decrease can break it (iOS parity).
            if (reviewed.automatic && (wallet.confirmed() < reviewed.confirmed || reviewed.reserveLeft < BoardingRules.reserve)) fail(OnchainFailure.changed)
            wallet.claimBoard(reviewed)
        } else {
            if (reviewed.feesUnknown && !reviewed.hasUnknownFeeConsent) fail(OnchainFailure.changed)
            val fresh = try {
                quote(reviewed.action, reviewed.ids, reviewed.amount, reviewed.destination, reviewed.automatic,
                    estimate = !reviewed.hasUnknownFeeConsent)
            } catch (failure: ExitEstimateUnavailable) { throw failure.preserving(reviewed, now()) }
            if (!ready() || !reviewed.matches(fresh) || !reviewed.current(now())) fail(OnchainFailure.changed)
            // A sync while this waited for the locks can move the tip past an expiry; the quote above can't tell.
            if (renewalThreshold != null && (reviewed.action != OnchainAction.refresh || !renewable(reviewed.ids, renewalThreshold))) fail(OnchainFailure.changed)
        }
        try { return journal.perform(reviewed) { recordTransaction ->
            when (reviewed.action) {
                OnchainAction.board -> {
                    // This save must complete before the first SDK call that can accept/broadcast the board.
                    recordTransaction(checkNotNull(reviewed.preparedBoard).txid)
                    val result = try { wallet.submitBoard(reviewed) }
                    catch (error: BarkError) {
                        // SDK 0.7.0 throws only before bark records the board, a reorged parent included; failures after return an outcome.
                        if (error.code in setOf(BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND, BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE, BarkErrorCode.PREPARED_BOARD_CHANGED))
                            throw BoardNotSubmitted(boardFailure(error))
                        // An unreachable chain source while re-checking the inputs.
                        if (error.code == BarkErrorCode.OTHER) throw BoardNotSubmitted(OnchainFailure.boardNotAttempted)
                        throw error
                    }
                    if (result.txid != reviewed.preparedBoard.txid) fail(OnchainFailure.uncertain)
                    if (result.broadcast is BroadcastOutcome.NotAttempted) throw BoardNotSubmitted(OnchainFailure.boardNotAttempted)
                    // bark failed after it may have recorded the board. The SDK refuses a resubmit; Check status reconciles it.
                    if (result.broadcast is BroadcastOutcome.Uncertain) fail(OnchainFailure.uncertain)
                    val retry = result.broadcast is BroadcastOutcome.Failed
                    OnchainReceipt(if (retry) OnchainReceipt.broadcastFailedMessage
                        else OnchainReceipt.boardingMessage(reviewed.chainFee), result.txid, reviewed.chainFee, retry)
                }
                OnchainAction.refresh -> OnchainReceipt(wallet.refresh(reviewed.ids)?.let { "Refresh submitted to delegated round ${it.id}." } ?: "No refresh round was needed.")
                OnchainAction.startExit -> {
                    wallet.start(reviewed.ids)
                    runCatching { newRun(reviewed.ids) }
                    OnchainReceipt("Exit started. Noah progresses it automatically in the background, paying broadcast and fee-bump fees from your onchain balance.")
                }
                OnchainAction.progress -> {
                    val failures = wallet.progress(reviewed.feeRate).count { it.error != null }
                    OnchainReceipt(if (failures == 0) OnchainReceipt.progressedMessage
                        else "Exit progression returned $failures errors. Transactions may have been broadcast or fee-bumped. Refresh exit details before continuing.")
                }
                OnchainAction.claim -> {
                    val claim = wallet.drain(reviewed.ids, checkNotNull(reviewed.destination))
                    if (!reviewed.hasUnknownFeeConsent && claim.feeSats.toLong() != reviewed.fee) fail(OnchainFailure.claimFeeChanged)
                    val mine = reviewed.destinationIsMine == true
                    // The signed claim, before it's sent: its outputs for this phone's wallet, its txid for the display record.
                    val signed = try { ExitTransactions.parse(wallet.claimTransaction(claim.psbtBase64)) }
                        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                    if (mine) try {
                        val tx = checkNotNull(signed)
                        keepOnchain((0 until tx.outputs).map { "${tx.txid}:$it" }.toSet())
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { fail(OnchainFailure.keepFailed) }
                    // Kept before broadcast, so a broadcast that went through but timed out still shows what it paid; display only.
                    fun record(txid: String) = runCatching { recordClaim(ExitClaimRecords.Record(txid, claim.feeSats.toLong(), reviewed.ids, mine, reviewed.destination.takeIf { !mine })) }
                    signed?.let { record(it.txid) }
                    // Every error after entering broadcast remains uncertain, including typed BDK errors.
                    val txid = try { wallet.broadcastClaim(claim.psbtBase64) } catch (_: Exception) { fail(OnchainFailure.uncertain) }
                    recordTransaction(txid)
                    if (txid != signed?.txid) record(txid)
                    OnchainReceipt("Claim submitted. Refresh to check confirmation.", txid)
                }
                OnchainAction.cancel -> {
                    if (!wallet.cancel(reviewed.ids.single())) fail(OnchainFailure.cancellationRefused)
                    runCatching { newRun(reviewed.ids) }
                    OnchainReceipt("Exit canceled. The VTXO remains spendable.")
                }
            }
        } } finally { reviewed.preparedBoard?.let { wallet.discardBoard(it.id, includingClaimed = true) } }
    }
    suspend fun abandonUnsubmittedAutoBoard(quote: OnchainQuote): Boolean {
        if (!quote.automatic || quote.action != OnchainAction.board || quote.walletID != wallet.walletID) return false
        val prepared = quote.preparedBoard ?: return false
        if (journal.loadAll().any { it.id == quote.id || it.quote.preparedBoard?.id == prepared.id ||
                it.txid == prepared.txid || it.quote.preparedBoard?.txid == prepared.txid }) return false
        return wallet.abandonUnsubmittedBoard(quote)
    }
    suspend fun check(id: String): Boolean = checkOnchainAttempt(id, journal, object : OnchainAttemptReads {
        override val walletID = wallet.walletID
        override suspend fun transactionIDs() = wallet.transactions().map { it.txid }.toSet()
        override suspend fun transactionStatus(txid: String) = ChainStatus.from(wallet.transactionStatus(txid))
        override suspend fun boardTransactionIDs() = wallet.boardTransactionIDs()
        override suspend fun vtxoState(id: String) = vtxoState(wallet.vtxo(id).state)
        override suspend fun exitState(id: String) = wallet.status(id)?.let { exitState(it.state).first }
    })
    suspend fun refreshReceiptFees() {
        if (journal.loadAll().none { it.quote.walletID == wallet.walletID && it.quote.action == OnchainAction.board && it.receipt?.txid != null && it.receipt.onchainFee == null }) return
        journal.refreshBoardFees(wallet.walletID, wallet.transactions().mapNotNull { tx -> tx.onchainFeeSats?.let { tx.txid to it.toLong() } }.toMap())
    }
}
