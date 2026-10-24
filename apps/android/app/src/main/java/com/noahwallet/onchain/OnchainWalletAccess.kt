package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Native access exists only while WalletSession owns a counted, shielded call. */
internal interface OnchainWalletAccess {
    val walletID: String
    suspend fun utxos(): List<OnchainUtxo> = error("""Unsupported""")
    suspend fun hasPendingBoards(): Boolean = error("""Unsupported""")
    suspend fun abandonUnsubmittedBoard(quote: OnchainQuote): Boolean = false
    suspend fun confirmed(): Long = error("""Unsupported""")
    suspend fun tip(): Long = error("""Unsupported""")
    suspend fun vtxos(): List<Vtxo> = error("""Unsupported""")
    suspend fun expiring(threshold: UInt): Set<String> = error("""Unsupported""")
    suspend fun vtxo(id: String): Vtxo = error("""Unsupported""")
    suspend fun exits(): List<ExitVtxo> = error("""Unsupported""")
    suspend fun status(id: String): ExitTransactionStatus? = error("""Unsupported""")
    suspend fun pendingTotal(): Long = error("""Unsupported""")
    suspend fun claimableHeight(): Long? = error("""Unsupported""")
    suspend fun transactions(): List<WalletTransaction> = error("""Unsupported""")
    suspend fun depositAddress(): String = error("""Unsupported""")
    suspend fun destinationIsMine(address: String): Boolean = error("""Unsupported""")
    suspend fun minimum(): Long? = error("""Unsupported""")
    suspend fun regularSatPerKwu(): Long = error("""Unsupported""")
    suspend fun refreshFee(ids: List<String>): FeeEstimate = error("""Unsupported""")
    suspend fun exitFee(ids: List<String>, destination: String?): EmergencyExitFeeEstimate = error("""Unsupported""")
    /** [excluded]: outpoints ("txid:vout") bark's coin selection never spends. */
    suspend fun prepareBoard(amount: Long, all: Boolean, rate: ULong, excluded: Set<String> = emptySet()): PreparedBoard = error("""Unsupported""")
    fun boardCleanupComplete(id: String): Boolean = true
    fun bindBoard(quote: OnchainQuote) {}
    fun claimBoard(quote: OnchainQuote) {}
    suspend fun submitBoard(quote: OnchainQuote): BoardResult = error("""Unsupported""")
    suspend fun discardBoard(id: String, includingClaimed: Boolean = false) {}
    suspend fun allExits(): List<ExitTransactionStatus> = error("""Unsupported""")
    suspend fun transactionStatus(txid: String): TransactionStatus = error("""Unsupported""")
    suspend fun boardTransactionIDs(): Set<String> = emptySet()
    suspend fun boardMovementTransactionIDs(): Set<String> = emptySet()
    suspend fun refresh(ids: List<String>): RoundState? = error("""Unsupported""")
    suspend fun start(ids: List<String>): Unit = error("""Unsupported""")
    suspend fun progress(feeRate: Long?): List<ExitProgressStatus> = error("""Unsupported""")
    suspend fun drain(ids: List<String>, destination: String): ExitClaimTransaction = error("""Unsupported""")
    /** The signed claim transaction a drain's PSBT holds, as hex, before it's broadcast. */
    suspend fun claimTransaction(psbt: String): String = error("""Unsupported""")
    suspend fun broadcastClaim(psbt: String): String = error("""Unsupported""")
    suspend fun cancel(id: String): Boolean = error("""Unsupported""")
}

internal class BarkOnchainAccess(private val wallet: Wallet, private val onchain: OnchainWallet, warn: (String) -> Unit) : OnchainWalletAccess {
    private val preparations = BoardPreparations(wallet::discardPreparedBoard, { wallet.submitPreparedBoard(it); Unit }, warn)
    suspend fun retryDiscards() = preparations.retryDiscards()
    suspend fun discardAll() = preparations.discardAll()
    override val walletID get() = wallet.fingerprint()
    override suspend fun utxos() = onchain.utxos()
    override suspend fun hasPendingBoards() = wallet.pendingBoards().isNotEmpty()
    override suspend fun abandonUnsubmittedBoard(quote: OnchainQuote) = preparations.abandonUnsubmitted(quote)
    override suspend fun confirmed() = onchain.balance().confirmedSats.toLong()
    override suspend fun tip() = onchain.tipHeight().toLong()
    override suspend fun vtxos() = wallet.vtxos()
    override suspend fun expiring(threshold: UInt) = wallet.getExpiringVtxos(threshold).map { it.id }.toSet()
    override suspend fun vtxo(id: String) = wallet.getVtxoById(id)
    override suspend fun exits() = wallet.getExitVtxos()
    override suspend fun status(id: String) = wallet.getExitStatus(id, includeHistory = true, includeTransactions = true)
    override suspend fun pendingTotal() = wallet.pendingExitsTotalSats().toLong()
    override suspend fun claimableHeight() = wallet.allExitsClaimableAtHeight()?.toLong()
    override suspend fun transactions() = onchain.transactions()
    override suspend fun depositAddress() = onchain.newAddress()
    override suspend fun destinationIsMine(address: String): Boolean {
        validateClaimDestination(address, com.noahwallet.BuildConfig.APP_VARIANT)
        return onchain.isMine(address)
    }
    override suspend fun minimum() = wallet.arkInfo()?.minBoardAmountSats?.toLong()
    override suspend fun regularSatPerKwu() = onchain.feeRates().regularSatPerKwu.toLong()
    override suspend fun refreshFee(ids: List<String>) = wallet.estimateRefreshFee(ids)
    override suspend fun exitFee(ids: List<String>, destination: String?) = wallet.estimateEmergencyExitFee(ids, feeRateSatPerVb = null, destination = destination)
    override suspend fun prepareBoard(amount: Long, all: Boolean, rate: ULong, excluded: Set<String>): PreparedBoard {
        val outpoints = excluded.mapNotNull { point -> point.substringAfterLast(':').toUIntOrNull()?.let { OutPoint(point.substringBeforeLast(':'), it) } }
        val prepared = wallet.prepareBoard(if (all) OnchainSendAmount.DrainAll else OnchainSendAmount.Amount(amount.toULong()), rate, true, outpoints)
        preparations.retain(prepared.id)
        return prepared
    }
    override fun boardCleanupComplete(id: String) = !preparations.contains(id)
    override fun bindBoard(quote: OnchainQuote) = preparations.bind(quote)
    override fun claimBoard(quote: OnchainQuote) = preparations.claim(quote)
    override suspend fun submitBoard(quote: OnchainQuote): BoardResult {
        preparations.consume(quote)
        return wallet.submitPreparedBoard(checkNotNull(quote.preparedBoard).id)
    }
    override suspend fun discardBoard(id: String, includingClaimed: Boolean) = preparations.discard(id, includingClaimed)
    // Transactions come from bark's own store (no chain query): the picture, Details and fees read them.
    override suspend fun allExits() = wallet.listAllExits(includeHistory = true, includeTransactions = true)
    override suspend fun transactionStatus(txid: String) = wallet.transactionStatus(txid)
    override suspend fun boardTransactionIDs(): Set<String> = wallet.pendingBoards().map { it.txid }.toSet() +
        wallet.history().filter { it.subsystemName == "bark.board" }.flatMap { movement ->
            // Board movement output IDs identify the funding transaction, never its amount.
            movement.outputVtxoIds.mapNotNull { id -> runCatching { wallet.getVtxoById(id).anchorPoint.txid }.getOrNull() }
        }
    override suspend fun boardMovementTransactionIDs() = boardMovementTxids(wallet.history())
    override suspend fun refresh(ids: List<String>) = wallet.refreshVtxosDelegated(ids)
    override suspend fun start(ids: List<String>) = wallet.startExitForVtxos(ids)
    override suspend fun progress(feeRate: Long?) = wallet.progressExits(feeRate?.toULong())
    override suspend fun drain(ids: List<String>, destination: String) = wallet.drainExits(vtxoIds = ids, address = destination, feeRateSatPerVb = null)
    override suspend fun claimTransaction(psbt: String) = extractTxFromPsbt(psbt)
    override suspend fun broadcastClaim(psbt: String) = wallet.broadcastTx(extractTxFromPsbt(psbt))
    override suspend fun cancel(id: String) = wallet.cancelExit(id).canceled
}

/**
 * The funding txids BOARD movements name in their own `chain_anchor`, as the SDK's uncertain check reads them. bark writes the
 * movement before its checkpoint and the VTXO, so a partial record has one even when nothing else names the board.
 */
internal fun boardMovementTxids(history: List<Movement>): Set<String> =
    history.filter { it.subsystemName == "bark.board" }.mapNotNull { chainAnchorTxid(it.metadataJson) }.toSet()

/** The txid of a movement's `chain_anchor` outpoint ("txid:vout"), exactly; null when it has none. */
internal fun chainAnchorTxid(metadataJson: String): String? =
    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(metadataJson).jsonObject["chain_anchor"]?.jsonPrimitive?.content }
        .getOrNull()?.let { Regex("([0-9a-f]{64}):[0-9]+").matchEntire(it)?.groupValues?.get(1) }

internal fun vtxoModel(value: Vtxo, tip: Long, expiring: Set<String>): VtxoModel = VtxoModel(
    value.id, value.amountSats.toLong(), value.expiryHeight.toLong(), vtxoState(value.state), value.kind,
    value.exitDepth.toLong(), value.exitTxWeightWu.toLong(), value.registered, value.serverPubkey,
    value.exitDelta.toLong(), "${value.anchorPoint.txid}:${value.anchorPoint.vout}", "${value.point.txid}:${value.point.vout}", value.id in expiring, tip)
internal fun vtxoState(state: VtxoState) = when (state) {
    VtxoState.Spendable -> "Spendable"; is VtxoState.Locked -> "Locked"; VtxoState.Spent -> "Spent"
    VtxoState.Exited -> "Exited"; VtxoState.Unknown -> "Unknown"
}
internal fun exitState(value: ExitState): Pair<String, ExitDetails> {
    fun block(b: BlockRef) = ExitDetails.Block(b.height.toLong(), b.hash)
    return when (value) {
        is ExitState.Start -> "Start" to ExitDetails(kind = "start", tip_height = value.tipHeight.toLong())
        is ExitState.Processing -> "Processing" to ExitDetails(kind = "processing", tip_height = value.tipHeight.toLong(), transactions = value.transactions.map { tx ->
            val status = when (val s = tx.status) {
                ExitTxStatus.VerifyInputs -> ExitDetails.Status("verify-inputs")
                is ExitTxStatus.AwaitingInputConfirmation -> ExitDetails.Status("awaiting-input-confirmation")
                ExitTxStatus.AwaitingCpfpBroadcast -> ExitDetails.Status("awaiting-cpfp-broadcast")
                is ExitTxStatus.AwaitingConfirmation -> ExitDetails.Status("awaiting-confirmation", s.childTxid, origin = exitOrigin(s.origin))
                is ExitTxStatus.Confirmed -> ExitDetails.Status("confirmed", s.childTxid, block(s.block), exitOrigin(s.origin))
            }
            ExitDetails.Transaction(tx.txid, status)
        })
        is ExitState.AwaitingDelta -> "AwaitingDelta" to ExitDetails(kind = "awaiting-delta", tip_height = value.tipHeight.toLong(), confirmed_block = block(value.confirmedBlock), claimable_height = value.claimableHeight.toLong())
        is ExitState.Claimable -> "Claimable" to ExitDetails(kind = "claimable", tip_height = value.tipHeight.toLong(), claimable_since = block(value.claimableSince), last_scanned_block = value.lastScannedBlock?.let(::block))
        is ExitState.ClaimInProgress -> "ClaimInProgress" to ExitDetails(kind = "claim-in-progress", tip_height = value.tipHeight.toLong(), claimable_since = block(value.claimableSince), claim_txid = value.claimTxid)
        is ExitState.Claimed -> "Claimed" to ExitDetails(kind = "claimed", tip_height = value.tipHeight.toLong(), block = block(value.block), txid = value.txid)
        is ExitState.VtxoAlreadySpent -> "VtxoAlreadySpent" to ExitDetails(kind = "vtxo-already-spent", tip_height = value.tipHeight.toLong())
        is ExitState.VtxoSwept -> "VtxoSwept" to ExitDetails(kind = "vtxo-swept", tip_height = value.tipHeight.toLong())
        is ExitState.Canceled -> "Canceled" to ExitDetails(kind = "canceled", tip_height = value.tipHeight.toLong())
    }
}

/** Who paid a transaction's fee child: this wallet, or another wallet's child seen in the mempool or a block. */
internal fun exitOrigin(origin: ExitTxOrigin) = when (origin) {
    is ExitTxOrigin.Wallet -> "wallet"; ExitTxOrigin.Mempool -> "mempool"; is ExitTxOrigin.Block -> "block"
}

internal fun validateClaimDestination(address: String, network: String) {
    val actual = com.noahwallet.send.PaymentEncoding.bitcoinNetwork(address)
    if (actual == null || !(actual == network || actual == "testnet" &&
            (network == "signet" || network == "regtest" && !address.startsWith("tb1", ignoreCase = true))))
        fail(OnchainFailure.invalid)
}
