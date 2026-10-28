package com.noahwallet.onchain

import com.noahwallet.services.wallet.ChainStatus
import com.noahwallet.services.wallet.ReviewClock
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*

internal class OnchainOperations(
    private val wallet: OnchainWalletAccess, private val journal: OnchainOperationJournal,
    private val ready: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    /** Review deadlines' clock ([ReviewClock]); [now] stays the wall time reviews record. */
    private val elapsed: () -> Long = ReviewClock::now,
    /** VTXOs bark refused to exit, with their reason ([ExitErrors.refused] in the app). */
    private val refused: MutableMap<String, String> = mutableMapOf(),
) {
    /** A refusal for [ids], noted against its VTXO. */
    private fun refusal(error: Exception, ids: List<String>) = ExitErrors.refusal(error,
        ids)?.also { r -> r.vtxoId?.let { refused[it] = checkNotNull(r.message) } }
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
        // Every origin: a step another of this wallet's exits sent is still this wallet's child; another wallet's isn't in its transactions.
        val children = exits.flatMap { exit -> exit.packages.mapNotNull { it.childTxid } }.toSet()
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
    /** Decision 4: each VTXO's own exit estimate (bark's, for it alone), or null where bark can't estimate it now or refuses it. */
    // shortcut: the standalone estimate, so siblings carry their shared path each; split it once bark-bolt-ffi's estimate has per-transaction data.
    suspend fun exitCosts(ids: List<String>): Map<String, ExitCosts.Cost?> {
        if (!ready()) fail(OnchainFailure.unavailable)
        return ids.associateWith { id ->
            try {
                wallet.exitFee(listOf(id), null).let { ExitCosts.Cost(it.exitBroadcastFeeSats.toLong(), it.claimFeeSats.toLong(), it.txsToBroadcast.toLong()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { refusal(failure, listOf(id)); null } // FFI: an unknown cost
        }
    }
    /** What bark's "All" estimate leaves out (SDK 0.7.0): non-standard and dust VTXOs, which need renewing before they can exit. */
    suspend fun exitExcluded(): Set<String> {
        if (!ready()) fail(OnchainFailure.unavailable)
        return wallet.exitFee(emptyList(), null).excludedVtxos.map { it.vtxoId }.toSet()
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
     * Sizes the review from what auto-board may move: the confirmed balance less coins claimed to this phone. Both
     * preparations exclude those coins (SDK 0.7.0), so they stay on-chain beside the reserve.
     */
    suspend fun autoBoardQuote(kept: (String) -> Set<String> = { emptySet() }): OnchainQuote? {
        if (!ready()) fail(OnchainFailure.unavailable)
        val minimum = wallet.minimum() ?: fail(OnchainFailure.unavailable)
        val claimed = kept(wallet.walletID)
        val keptValue = if (claimed.isEmpty()) 0 else AutoBoardFunding.keptValue(wallet.utxos(), claimed)
        val balance = wallet.confirmed() - keptValue
        if (balance <= BoardingRules.reserve + BoardingRules.threshold(minimum)) return null
        val probe = quote(OnchainAction.board, automatic = true, excluded = claimed)
        val candidate = try {
            // Fixed-amount boarding adds a 43-vB Taproot change output to the confirmed-input drain.
            probe.amount - BoardingRules.reserve - 43 * checkNotNull(probe.feeRate)
        } finally { withContext(NonCancellable) { wallet.discardBoard(checkNotNull(probe.preparedBoard).id) } }
        if (!wallet.boardCleanupComplete(checkNotNull(probe.preparedBoard).id)) fail(OnchainFailure.uncertain)
        currentCoroutineContext().ensureActive()
        if (candidate < BoardingRules.threshold(minimum) || !ready()) return null
        val prepared = quote(OnchainAction.board, amount = candidate, automatic = true, excluded = claimed)
        var reviewed = false
        try {
            currentCoroutineContext().ensureActive()
            // The input check is cheap defence; bark was told not to spend them.
            val tooSmall = prepared.reserveLeft - keptValue < BoardingRules.reserve || prepared.net < BoardingRules.threshold(minimum)
            if (tooSmall || !ready() || checkNotNull(prepared.preparedBoard).inputs.any { "${it.txid}:${it.vout}" in claimed }) return null
            reviewed = true
            return prepared
        } finally {
            if (!reviewed) withContext(NonCancellable) { wallet.discardBoard(checkNotNull(prepared.preparedBoard).id) }
        }
    }
    /** [excluded]: outpoints ("txid:vout") a board never spends; manual Move to Ark passes none. */
    @Suppress("LongParameterList") // optional quote inputs; each caller passes its own
    suspend fun quote(action: OnchainAction, ids: List<String> = emptyList(), amount: Long = 0,
        destination: String? = null, automatic: Boolean = false, estimate: Boolean = true, excluded: Set<String> = emptySet()): OnchainQuote {
        if (!ready()) fail(OnchainFailure.unavailable)
        val balance = wallet.confirmed()
        val quote = OnchainQuote(action, wallet.walletID, ids.sorted(), destination, amount, confirmed = balance, automatic = automatic, createdAt = now(),
            reviewedAt = elapsed())
        if (ids.distinct().size != ids.size) fail(OnchainFailure.invalid)
        return when (action) {
            OnchainAction.board -> boardQuote(quote, amount, excluded)
            OnchainAction.refresh, OnchainAction.startExit -> vtxoQuote(quote, ids, estimate)
            OnchainAction.progress, OnchainAction.claim -> exitQuote(quote, ids, destination, estimate)
            OnchainAction.cancel -> cancelQuote(quote, ids)
        }
    }
    private suspend fun boardQuote(base: OnchainQuote, amount: Long, excluded: Set<String>): OnchainQuote {
        if (amount < 0 || amount > 2_100_000_000_000_000L) fail(OnchainFailure.invalid)
        val rate = ((wallet.regularSatPerKwu() + 249) / 250).coerceAtLeast(1).toULong()
        val prepared = try { wallet.prepareBoard(amount, amount == 0L, rate, excluded) }
        catch (error: BarkError) { throw OnchainException(boardFailure(error)) }
        var bound = false
        try {
            currentCoroutineContext().ensureActive()
            if (!ready() || prepared.grossSats > Long.MAX_VALUE.toULong() ||
                prepared.netSats + prepared.arkFeeSats != prepared.grossSats) fail(OnchainFailure.changed)
            val quote = base.copy(amount = prepared.grossSats.toLong(), fee = prepared.arkFeeSats.toLong(),
                chainFee = prepared.onchainFeeSats.toLong(), boardAll = amount == 0L, feeRate = rate.toLong(),
                preparedBoard = BoardPrepared(prepared.id, prepared.txid, prepared.vsize.toLong(),
                    prepared.inputs.map { BoardInput(it.txid, it.vout) }))
            wallet.bindBoard(quote)
            bound = true
            return quote
        } finally {
            if (!bound) withContext(NonCancellable) { wallet.discardBoard(prepared.id) }
        }
    }
    private suspend fun vtxoQuote(base: OnchainQuote, ids: List<String>, estimate: Boolean): OnchainQuote {
        val selected = wallet.vtxos().filter { vtxoState(it.state) == "Spendable" && it.id in ids }
        if (ids.isEmpty() || selected.size != ids.size) fail(OnchainFailure.changed)
        val quote = base.copy(amount = selected.sumOf { it.amountSats.toLong() })
        if (base.action == OnchainAction.refresh) return quote.copy(fee = wallet.refreshFee(ids).feeSats.toLong())
        if (selected.any { it.amountSats.toLong() < ExitRules.dust }) fail(OnchainFailure.invalid)
        return exitEstimate(quote, estimate)
    }
    private suspend fun exitQuote(base: OnchainQuote, ids: List<String>, destination: String?, estimate: Boolean): OnchainQuote {
        val claim = base.action == OnchainAction.claim
        val eligible = wallet.exits().filter { if (claim) it.isClaimable else exitState(it.state).first !in ExitTimeline.terminal }
        val selected = eligible.filter { it.vtxoId in ids }
        // Moving exits forward covers every pending one.
        val everyPending = claim || eligible.map { it.vtxoId }.toSet() == ids.toSet()
        if (ids.isEmpty() || selected.size != ids.size || !everyPending) fail(OnchainFailure.changed)
        if (claim && destination.isNullOrBlank()) fail(OnchainFailure.invalid)
        var quote = base
        if (claim) quote = quote.copy(destinationIsMine = wallet.destinationIsMine(checkNotNull(destination)))
        quote = quote.copy(amount = selected.sumOf { it.amountSats.toLong() })
        return exitEstimate(quote, estimate)
    }
    private suspend fun cancelQuote(base: OnchainQuote, ids: List<String>): OnchainQuote {
        if (ids.size != 1) fail(OnchainFailure.invalid)
        val exit = wallet.exits().find { it.vtxoId == ids[0] } ?: fail(OnchainFailure.changed)
        val status = wallet.status(ids[0]) ?: fail(OnchainFailure.changed)
        val state = exitState(status.state)
        if (!ExitRules.cancelable(state.first, state.second.transactions?.lastOrNull()?.status?.kind)) fail(OnchainFailure.cancellationRefused)
        return base.copy(amount = exit.amountSats.toLong())
    }
    private suspend fun exitEstimate(validated: OnchainQuote, estimate: Boolean): OnchainQuote {
        val unknown = validated.copy(fee = null, chainFee = null, feeRate = null, transactions = null)
        val fallbackAllowed = validated.action in setOf(OnchainAction.startExit, OnchainAction.claim)
        if (!estimate && fallbackAllowed) return unknown
        val fee = try { wallet.exitFee(validated.ids, validated.destination) }
        catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // FFI: classified below, else rethrown as is
            // Starting would fail the same way, so no "Continue without estimate".
            throw refusal(failure, validated.ids)
                ?: if (fallbackAllowed && ExitEstimateUnavailable.allowsFallback(failure)) ExitEstimateUnavailable(unknown) else failure
        }
        return validated.copy(fee = fee.claimFeeSats.toLong(),
            chainFee = if (validated.action == OnchainAction.claim) 0 else fee.exitBroadcastFeeSats.toLong(),
            feeRate = if (validated.action == OnchainAction.claim) null else fee.feeRateSatPerVb.toLong(),
            transactions = if (validated.action == OnchainAction.startExit) fee.txsToBroadcast.toLong() else null)
    }
    /**
     * [renewalThreshold]: the review was Home's Renew, so every VTXO must still be due (in the expiring set at this
     * threshold, spendable, not expired), checked last, under the caller's locks, just before submitting.
     *
     * [keepOnchain]: a claim to this wallet records its outputs in auto-board's handled ledger (exit.md, Decision 8)
     * before it's broadcast, so auto-board never offers them back to Ark.
     *
     * [recordClaim]: where a claim's fee, exits and destination are kept for its exits' pages, before it's broadcast.
     * [newRun]: told which exits a start or cancel just changed, inside the native call (which outlives its caller), so their
     * evidence from the last run is forgotten. A failure in either changes nothing.
     */
    @Suppress("LongParameterList") // optional hooks; each caller passes only the ones it records
    suspend fun execute(reviewed: OnchainQuote, funding: AutoBoardFunding.Snapshot? = null, renewalThreshold: UInt? = null,
        keepOnchain: (Set<String>) -> Unit = {}, kept: (String) -> Set<String> = { emptySet() },
        recordClaim: (ExitClaimRecords.Record) -> Unit = {}, newRun: (List<String>) -> Unit = {},
        shortOfFunds: (Map<String, Long>) -> Unit = {}): String {
        if (!ready() || !reviewed.current(elapsed(), now()) || reviewed.walletID != wallet.walletID) fail(OnchainFailure.changed)
        if (reviewed.action == OnchainAction.board) requireBoardCurrent(reviewed, funding, kept)
        else requireExitCurrent(reviewed, renewalThreshold)
        try {
            return journal.perform(reviewed) { recordTransaction ->
                when (reviewed.action) {
                    OnchainAction.board -> submitBoard(reviewed, recordTransaction)
                    OnchainAction.refresh ->
                        OnchainReceipt(wallet.refresh(reviewed.ids)?.let { "Refresh submitted to delegated round ${it.id}." } ?: "No refresh round was needed.")
                    OnchainAction.startExit -> startExits(reviewed, newRun)
                    OnchainAction.progress -> progress(reviewed, shortOfFunds)
                    OnchainAction.claim -> claim(reviewed, ClaimHooks(keepOnchain, recordClaim, recordTransaction))
                    OnchainAction.cancel -> cancelExit(reviewed, newRun)
                }
            }
        } finally { reviewed.preparedBoard?.let { wallet.discardBoard(it.id, includingClaimed = true) } }
    }
    private suspend fun progress(reviewed: OnchainQuote, shortOfFunds: (Map<String, Long>) -> Unit): OnchainReceipt {
        val failures = progressed(reviewed.feeRate, shortOfFunds)
        if (failures == 0) return OnchainReceipt(OnchainReceipt.progressedMessage)
        @Suppress("MaxLineLength") // one translation source string
        val message = "Exit progression returned $failures errors. Transactions may have been broadcast or fee-bumped. Refresh exit details before continuing."
        return OnchainReceipt(message)
    }
    private suspend fun cancelExit(reviewed: OnchainQuote, newRun: (List<String>) -> Unit): OnchainReceipt {
        if (!wallet.cancel(reviewed.ids.single())) fail(OnchainFailure.cancellationRefused)
        runCatching { newRun(reviewed.ids) }
        return OnchainReceipt("Exit canceled. The VTXO remains spendable.")
    }
    private suspend fun requireBoardCurrent(reviewed: OnchainQuote, funding: AutoBoardFunding.Snapshot?, kept: (String) -> Set<String>) {
        if (funding != null) {
            val sameFunding = reviewed.automatic && funding.walletID == wallet.walletID
            if (!sameFunding || autoBoardFunding(kept) != funding) fail(OnchainFailure.changed)
        }
        if (reviewed.preparedBoard == null) fail(OnchainFailure.changed)
        // Added funds leave at least the reviewed reserve; only a decrease can break it (iOS parity).
        if (reviewed.automatic && reserveBroken(reviewed)) fail(OnchainFailure.changed)
        wallet.claimBoard(reviewed)
    }
    private suspend fun reserveBroken(reviewed: OnchainQuote) = wallet.confirmed() < reviewed.confirmed || reviewed.reserveLeft < BoardingRules.reserve
    private suspend fun requireExitCurrent(reviewed: OnchainQuote, renewalThreshold: UInt?) {
        if (reviewed.feesUnknown && !reviewed.hasUnknownFeeConsent) fail(OnchainFailure.changed)
        val fresh = try {
            quote(reviewed.action, reviewed.ids, reviewed.amount, reviewed.destination, reviewed.automatic,
                estimate = !reviewed.hasUnknownFeeConsent)
        } catch (failure: ExitEstimateUnavailable) { throw failure.preserving(reviewed, elapsed(), now()) }
        if (!ready() || !reviewed.matches(fresh) || !reviewed.current(elapsed(), now())) fail(OnchainFailure.changed)
        // A sync while this waited for the locks can move the tip past an expiry; the quote above can't tell.
        if (renewalThreshold != null && (reviewed.action != OnchainAction.refresh || !renewable(reviewed.ids,
                renewalThreshold))) fail(OnchainFailure.changed)
    }
    private suspend fun submitBoard(reviewed: OnchainQuote, recordTransaction: (String) -> Unit): OnchainReceipt {
        // This save must complete before the first SDK call that can accept/broadcast the board.
        recordTransaction(checkNotNull(reviewed.preparedBoard).txid)
        val result = try { wallet.submitBoard(reviewed) }
        catch (error: BarkError) { throw boardRejection(error) }
        requireBroadcast(reviewed, result)
        val retry = result.broadcast is BroadcastOutcome.Failed
        return OnchainReceipt(if (retry) OnchainReceipt.broadcastFailedMessage
        else OnchainReceipt.boardingMessage(reviewed.chainFee), result.txid, reviewed.chainFee, retry)
    }
    private fun requireBroadcast(reviewed: OnchainQuote, result: BoardResult) {
        if (result.txid != checkNotNull(reviewed.preparedBoard).txid) fail(OnchainFailure.uncertain)
        if (result.broadcast is BroadcastOutcome.NotAttempted) throw BoardNotSubmitted(OnchainFailure.boardNotAttempted)
        // bark failed after it may have recorded the board. The SDK refuses a resubmit; Check status reconciles it.
        if (result.broadcast is BroadcastOutcome.Uncertain) throw BoardUncertain()
    }
    /** SDK 0.7.0 throws only before bark records the board, a reorged parent included; failures after return an outcome. */
    private fun boardRejection(error: BarkError): Exception = when (error.code) {
        BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND, BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE,
        BarkErrorCode.PREPARED_BOARD_CHANGED -> BoardNotSubmitted(boardFailure(error))
        // An unreachable chain source while re-checking the inputs.
        BarkErrorCode.OTHER -> BoardNotSubmitted(OnchainFailure.boardNotAttempted)
        else -> error
    }
    private suspend fun startExits(reviewed: OnchainQuote, newRun: (List<String>) -> Unit): OnchainReceipt {
        // bark refuses a VTXO after registering the ones before it, which it skips when they're started again.
        val refusedStart = try { wallet.start(reviewed.ids); null } catch (error: BarkError) { refusal(error, reviewed.ids) ?: throw error }
        runCatching { newRun(reviewed.ids) }
        refusedStart?.let { throw it }
        @Suppress("MaxLineLength") // one translation source string
        val started = "Exit started. Noah progresses it on its own, including in the background when the system lets it, paying broadcast and fee-bump fees from your onchain balance."
        return OnchainReceipt(started)
    }
    private class ClaimHooks(val keepOnchain: (Set<String>) -> Unit, val recordClaim: (ExitClaimRecords.Record) -> Unit,
        val recordTransaction: (String) -> Unit)
    private suspend fun claim(reviewed: OnchainQuote, hooks: ClaimHooks): OnchainReceipt {
        val claim = wallet.drain(reviewed.ids, checkNotNull(reviewed.destination))
        if (!reviewed.hasUnknownFeeConsent && claim.feeSats.toLong() != reviewed.fee) fail(OnchainFailure.claimFeeChanged)
        val mine = reviewed.destinationIsMine == true
        // The signed claim, before it's sent: its outputs for this phone's wallet, its txid for the display record.
        val signed = signedClaim(claim.psbtBase64)
        if (mine) keepClaimOutputs(signed, hooks.keepOnchain)
        // Kept before broadcast, so a broadcast that went through but timed out still shows what it paid; display only.
        fun record(txid: String) = runCatching { hooks.recordClaim(ExitClaimRecords.Record(txid, claim.feeSats.toLong(), reviewed.ids, mine,
            reviewed.destination.takeIf { !mine })) }
        signed?.let { record(it.txid) }
        // Every error after entering broadcast remains uncertain, including typed BDK errors.
        val txid = try { wallet.broadcastClaim(claim.psbtBase64) } catch (_: Exception) { fail(OnchainFailure.uncertain) }
        hooks.recordTransaction(txid)
        if (txid != signed?.txid) record(txid)
        return OnchainReceipt("Claim submitted. Refresh to check confirmation.", txid)
    }
    private suspend fun signedClaim(psbt: String) = try { ExitTransactions.parse(wallet.claimTransaction(psbt)) }
    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    private fun keepClaimOutputs(signed: ExitTransactions.Parsed?, keepOnchain: (Set<String>) -> Unit) {
        try {
            val tx = checkNotNull(signed)
            keepOnchain((0 until tx.outputs).map { "${tx.txid}:$it" }.toSet())
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { fail(OnchainFailure.keepFailed) }
    }
    /** One exit progress at [feeRate] (null: bark's own), telling [shortOfFunds] what it reported short; how many failed otherwise. */
    private suspend fun progressed(feeRate: Long?, shortOfFunds: (Map<String, Long>) -> Unit): Int {
        val statuses = wallet.progress(feeRate)
        // Short of confirmed funds is bark's own evidence, with what it needs; bark 0.8.0's CPFP never reports it.
        val short = statuses.mapNotNull { status -> status.error?.takeIf { it.code == BarkErrorCode.EXIT_INSUFFICIENT_CONFIRMED_FUNDS }
            ?.let { status.vtxoId to (it.neededSats?.toLong() ?: 0) } }.toMap()
        runCatching { shortOfFunds(short) }
        return statuses.count { status -> status.error?.let { it.code != BarkErrorCode.EXIT_INSUFFICIENT_CONFIRMED_FUNDS } == true }
    }
    /**
     * Background jobs' exit progress, apart from bark's daemon: nothing without a pending exit, else one call at bark's
     * own fee rate, recorded in [funding] like a completed Move exit forward. [admitted] throws once the
     * job's budget is gone, so a late step never starts the call. True when it called bark.
     */
    suspend fun progressPending(funding: ExitFunding, admitted: () -> Unit): Boolean {
        if (wallet.exits().all { exitState(it.state).first in ExitTimeline.terminal }) return false
        admitted()
        val failures = try { progressed(null, funding::reported) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (@Suppress("TooGenericExceptionCaught") error: Exception) { // recorded as an incomplete check, then rethrown
            funding.checked(emptyList(), completed = false); throw error
        }
        funding.checked(parkedExits(), completed = failures == 0)
        return true
    }
    /** Read after the call: what's still parked is what bark tried and couldn't pay. */
    private suspend fun parkedExits(): List<ExitModel> = try { wallet.allExits().map(::exitModel) }
    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
    suspend fun abandonUnsubmittedAutoBoard(quote: OnchainQuote): Boolean {
        if (!quote.automatic || quote.action != OnchainAction.board || quote.walletID != wallet.walletID) return false
        val prepared = quote.preparedBoard ?: return false
        val journaled = journal.loadAll().any {
            it.id == quote.id || it.quote.preparedBoard?.id == prepared.id || it.txid == prepared.txid || it.quote.preparedBoard?.txid == prepared.txid
        }
        if (journaled) return false
        return wallet.abandonUnsubmittedBoard(quote)
    }
    suspend fun check(id: String): Boolean = checkOnchainAttempt(id, journal, object : OnchainAttemptReads {
        override val walletID = wallet.walletID
        override suspend fun transactionIDs() = wallet.transactions().map { it.txid }.toSet()
        override suspend fun transactionStatus(txid: String) = ChainStatus.from(wallet.transactionStatus(txid))
        override suspend fun boardTransactionIDs() = wallet.boardTransactionIDs()
        override suspend fun boardMovementTransactionIDs() = wallet.boardMovementTransactionIDs()
        override suspend fun unspentOutpoints() = wallet.utxos().filterIsInstance<OnchainUtxo.Local>().map { "${it.outpoint.txid}:${it.outpoint.vout}" }.toSet()
        override suspend fun vtxoState(id: String) = vtxoState(wallet.vtxo(id).state)
        override suspend fun exitState(id: String) = wallet.status(id)?.let { exitState(it.state).first }
    })
    suspend fun refreshReceiptFees() {
        val missingFee = journal.loadAll().any {
            it.quote.walletID == wallet.walletID && it.quote.action == OnchainAction.board && it.receipt?.txid != null && it.receipt.onchainFee == null
        }
        if (!missingFee) return
        journal.refreshBoardFees(wallet.walletID, wallet.transactions().mapNotNull { tx -> tx.onchainFeeSats?.let { tx.txid to it.toLong() } }.toMap())
    }
}
