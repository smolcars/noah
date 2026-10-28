package com.noahwallet.onchain

import com.noahwallet.services.storage.syncDirectory
import com.noahwallet.services.wallet.ChainStatus
import io.github.niteshbalusu11.bark.BarkError
import io.github.niteshbalusu11.bark.BarkErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream

/** Fsync, rename, fsync directory. A failed save must prevent SDK submission. */
internal class OnchainFile(val file: File, private val syncParent: (File) -> Unit = ::syncDirectory) {
    fun write(text: String) {
        val temporary = File(file.parentFile, ".${file.name}-${java.util.UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(temporary.renameTo(file)) { """Cannot publish wallet journal""" }
            syncParent(checkNotNull(file.parentFile))
        } finally { temporary.delete() }
    }
}

/** Call-specific v0.4.0 allowlist. Never classify by diagnostic text. */
object OnchainSubmission {
    fun preBroadcast(error: Exception, action: OnchainAction): OnchainFailure? {
        if (error is OnchainException && error.reason in setOf(OnchainFailure.claimFeeChanged, OnchainFailure.cancellationRefused,
                OnchainFailure.keepFailed)) return error.reason
        // BDK CreateTxError occurs before board sign/store/cosign. Other calls still return OTHER.
        if (action == OnchainAction.board && error is BarkError && error.code in
            setOf(BarkErrorCode.INSUFFICIENT_FUNDS, BarkErrorCode.DUST)) return OnchainFailure.constructionFailed
        return null
    }
}

class OnchainOperationJournal internal constructor(private val storage: OnchainFile) {
    constructor(file: File) : this(OnchainFile(file))
    companion object {
        const val maxOverriddenAttempts = 100
        const val overrideTitle = "Override repeat protection?"
        @Suppress("MaxLineLength") // one translation source string
        const val overrideWarning = "This does not cancel the original operation. Submitting again may spend additional funds or fees. Noah keeps this attempt and checks its status as your wallet syncs."
        const val overrideConfirmation = "I understand this may repeat the operation"
    }
    @Serializable data class OverrideAudit(
        val overriddenAt: Long, val title: String = overrideTitle, val warning: String = overrideWarning,
        val confirmation: String = overrideConfirmation, val statusMessage: String = OnchainFailure.uncertain.text,
        val acknowledgedAt: Long? = null,
    )
    /**
     * [uncertainBoard]: the SDK answered BroadcastOutcome.Uncertain, so bark may have recorded this board. No override
     * unlocks it; only Check status can, by finding the board or proving nothing was boarded.
     */
    @Serializable data class Attempt(val quote: OnchainQuote, val receipt: OnchainReceipt? = null,
        val txid: String? = null, val overrideAudit: OverrideAudit? = null, val uncertainBoard: Boolean = false) {
        val id get() = quote.id
        fun blocks(action: OnchainAction, ids: List<String>): Boolean {
            if ((overrideAudit != null && !uncertainBoard) || quote.action != action) return false
            if (action == OnchainAction.board && confirmedBoard()) return false
            // Bark skips active starts and already-canceled cancels. Claims/CPFP can spend again.
            if (receipt == null && action in setOf(OnchainAction.startExit, OnchainAction.cancel)) return false
            return action == OnchainAction.board || quote.ids.any { it in ids }
        }
        /** A board whose recorded transaction confirmed, with no broadcast retry left. */
        private fun confirmedBoard() = receipt?.txid != null && (txid == null || txid == receipt.txid) &&
            (quote.preparedBoard == null || quote.preparedBoard.txid == receipt.txid) &&
            !receipt.broadcastRetryPending && receipt.chainStatus?.kind == ChainStatus.Kind.confirmed
        /** An unresolved attempt the user may still override. */
        internal fun overridable() = receipt == null && overrideAudit == null && !uncertainBoard
    }
    /**
     * [boardTransactionIDs]: funding txids bark's pending boards (checkpoints) and stored board VTXOs name;
     * [boardMovementTransactionIDs]: those BOARD movements name in their `chain_anchor`; [unspent]: the wallet's unspent
     * outpoints ("txid:vout"), read only for an unresolved board.
     */
    data class Evidence(val walletID: String, val vtxoStates: Map<String, String> = emptyMap(),
        val exitStates: Map<String, String?> = emptyMap(), val transactionIDs: Set<String> = emptySet(),
        val chainStatuses: Map<String, ChainStatus> = emptyMap(), val boardTransactionIDs: Set<String> = emptySet(),
        val unspent: Set<String>? = null, val boardMovementTransactionIDs: Set<String> = emptySet()) {
        /** Only a BOARD movement names the board: an orphan bark wrote before its checkpoint, locked like Uncertain. */
        fun orphan(attempt: Attempt) = attempt.quote.action == OnchainAction.board && attempt.overrideAudit == null &&
            attempt.txid in boardMovementTransactionIDs && receipt(attempt) == null
        /**
         * An uncertain or orphaned board nothing can complete: no pending board, checkpoint or wallet transaction names its
         * funding txid, the chain source says it doesn't know it, and every input is still an unspent coin of this wallet.
         */
        fun nothingBoarded(attempt: Attempt): Boolean {
            val prepared = attempt.quote.preparedBoard ?: return false
            // An orphan movement doesn't block: the server saw only the unsigned tx, the signed bytes died with the preparation, and bark broadcasts only from a checkpoint.
            return (attempt.uncertainBoard || orphan(attempt)) && attempt.overrideAudit == null && walletID == attempt.quote.walletID &&
                attempt.txid == prepared.txid && prepared.txid !in transactionIDs && prepared.txid !in boardTransactionIDs &&
                chainStatuses[prepared.txid]?.kind == ChainStatus.Kind.notFound && unspent != null &&
                prepared.inputs.isNotEmpty() && prepared.inputs.all { "${it.txid}:${it.vout}" in unspent }
        }
        fun receipt(attempt: Attempt): OnchainReceipt? {
            if (walletID != attempt.quote.walletID) return null
            if (attempt.txid != null && found(attempt.txid)) return foundReceipt(attempt, attempt.txid)
            return resolvedReceipt(attempt)
        }
        private fun found(txid: String) = txid in transactionIDs || txid in boardTransactionIDs ||
            chainStatuses[txid]?.kind in setOf(ChainStatus.Kind.inMempool, ChainStatus.Kind.confirmed)
        private fun foundReceipt(attempt: Attempt, txid: String): OnchainReceipt {
            val prepared = attempt.quote.preparedBoard
            val status = chainStatuses[txid]
            val retry = prepared != null && status?.kind != ChainStatus.Kind.confirmed
            return OnchainReceipt(
                if (retry) "Boarding submitted, but delivery is unconfirmed. Noah will keep retrying the broadcast. Do not board again."
                else "The recorded transaction was found. Review its network status.", txid,
                prepared?.let { attempt.quote.chainFee }, retry, status)
        }
        /** The selected items reached a state only this attempt's success explains. */
        private fun resolvedReceipt(attempt: Attempt): OnchainReceipt? {
            val ids = attempt.quote.ids
            if (ids.isEmpty()) return null
            val resolved = when (attempt.quote.action) {
                OnchainAction.startExit -> ids.all { exitStates[it] != null }
                OnchainAction.cancel -> ids.all { exitStates[it] == "Canceled" }
                OnchainAction.claim -> ids.all { exitStates[it] in setOf("ClaimInProgress", "Claimed") }
                OnchainAction.refresh -> ids.all { vtxoStates[it] in setOf("Spent", "Exited") }
                OnchainAction.progress -> ids.all { exitStates[it] in ExitTimeline.terminal + "Claimable" + "ClaimInProgress" }
                OnchainAction.board -> false // Never infer a board from an amount or missing transaction.
            }
            return if (resolved) {
                @Suppress("MaxLineLength") // one translation source string
                OnchainReceipt("The selected items have reached a state that resolves this attempt. Review their current status and history before continuing.")
            } else null
        }
    }
    private val mutex = Mutex()
    private val statusErrors = mutableMapOf<String, String>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun read(): List<Attempt> {
        if (!storage.file.exists()) return emptyList()
        val element = json.parseToJsonElement(storage.file.readText())
        val saved = if (element is JsonObject) listOf(json.decodeFromJsonElement<Attempt>(element))
        else json.decodeFromJsonElement<List<Attempt>>(element)
        return saved.map { it.copy(receipt = it.receipt?.copy(statusError = statusErrors[it.id])) }
    }
    private fun save(attempts: List<Attempt>) {
        val retained = attempts.filter { it.overrideAudit != null }
            .sortedWith(compareByDescending<Attempt> { it.overrideAudit!!.overriddenAt }.thenByDescending { it.id })
            .take(maxOverriddenAttempts).map { it.id }.toSet()
        storage.write(json.encodeToString(attempts.filter { it.overrideAudit == null || it.id in retained }))
    }
    suspend fun loadAll(): List<Attempt> = mutex.withLock { read() }
    private fun recordTransaction(id: String, txid: String) {
        val attempts = read()
        check(attempts.any { it.id == id })
        save(attempts.map { if (it.id == id) it.copy(txid = txid) else it })
    }
    suspend fun perform(quote: OnchainQuote, action: suspend ((String) -> Unit) -> OnchainReceipt): String = mutex.withLock {
        val previous = read()
        if (previous.any { it.id == quote.id }) fail(OnchainFailure.changed)
        if (previous.any { it.quote.walletID == quote.walletID && it.blocks(quote.action, quote.ids) }) fail(OnchainFailure.busy)
        save(previous + Attempt(quote))
        try {
            val receipt = action { txid -> recordTransaction(quote.id, txid) }
            save(read().map { if (it.id == quote.id) it.copy(receipt = receipt) else it })
            receipt.message
        } catch (error: ExitNotPossible) {
            // bark refused the start: bark's exits list shows any exit it registered first, and starting again skips those.
            save(read().filter { it.id != quote.id }); throw error
        } catch (_: BoardUncertain) {
            save(read().map { if (it.id == quote.id) it.copy(uncertainBoard = true) else it }); fail(OnchainFailure.uncertain)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) { // uncertain unless proven not submitted
            val failure = if (quote.preparedBoard != null) (error as? BoardNotSubmitted)?.reason
            else OnchainSubmission.preBroadcast(error, quote.action)
            if (failure != null) { save(read().filter { it.id != quote.id }); fail(failure) }
            fail(OnchainFailure.uncertain)
        }
    }
    suspend fun reconcile(id: String, evidence: Evidence): Boolean = mutex.withLock {
        val attempts = read()
        val attempt = attempts.find { it.id == id && it.receipt == null } ?: return@withLock false
        // Proven never recorded: the attempt goes, so a fresh review can board these coins.
        if (evidence.nothingBoarded(attempt)) { save(attempts.filter { it.id != id }); return@withLock true }
        val receipt = evidence.receipt(attempt)
        if (receipt == null) {
            // An orphan that can't be released yet (a crash's, say) loses its override until a check releases it.
            if (evidence.orphan(attempt) && !attempt.uncertainBoard) save(attempts.map { if (it.id == id) it.copy(uncertainBoard = true) else it })
            return@withLock false
        }
        save(attempts.map { if (it.id == id) it.copy(receipt = receipt) else it }); true
    }
    suspend fun refreshBoardFees(walletID: String, fees: Map<String, Long>) = mutex.withLock {
        val attempts = read()
        val updated = attempts.map { attempt ->
            val receipt = attempt.receipt
            val fee = fees[receipt?.txid]
            val board = attempt.quote.walletID == walletID && attempt.quote.action == OnchainAction.board
            when {
                !board || fee == null -> attempt
                receipt == null || receipt.onchainFee != null -> attempt
                else -> attempt.copy(receipt = receipt.copy(onchainFee = fee, message = OnchainReceipt.boardingMessage(fee)))
            }
        }
        if (updated != attempts) save(updated)
    }
    suspend fun refreshStatus(id: String, status: ChainStatus?, error: String? = null) = mutex.withLock {
        if (error == null) statusErrors.remove(id) else statusErrors[id] = error
        val attempts = read()
        val updated = attempts.map { attempt ->
            val receipt = attempt.receipt
            if (attempt.id != id || receipt == null) attempt else {
                val confirmed = status?.kind == ChainStatus.Kind.confirmed
                attempt.copy(receipt = receipt.copy(chainStatus = status ?: receipt.chainStatus, statusError = error,
                    broadcastRetryPending = receipt.broadcastRetryPending && !confirmed,
                    message = if (confirmed && attempt.quote.action == OnchainAction.board) OnchainReceipt.boardingMessage(receipt.onchainFee)
                    else receipt.message))
            }
        }
        if (updated != attempts) save(updated)
    }
    suspend fun acknowledge(id: String, now: Long = System.currentTimeMillis()) = mutex.withLock {
        val attempts = read()
        val attempt = attempts.find { it.id == id && it.receipt != null } ?: fail(OnchainFailure.uncertain)
        if (attempt.receipt?.broadcastRetryPending == true) fail(OnchainFailure.busy)
        save(if (attempt.overrideAudit == null) attempts.filter { it.id != id } else attempts.map {
            if (it.id == id) it.copy(overrideAudit = attempt.overrideAudit.copy(acknowledgedAt = now)) else it })
    }
    /** Caller must display the exact destructive consent above. Retain the original quote and IDs. */
    suspend fun overrideUncertain(id: String, now: Long = System.currentTimeMillis()) = mutex.withLock {
        val attempts = read()
        if (attempts.none { it.id == id && it.overridable() }) fail(OnchainFailure.busy)
        save(attempts.map { if (it.id == id) it.copy(overrideAudit = OverrideAudit(now)) else it })
    }
}

/** No sync, broadcast or progression capability on the Check status seam. */
interface OnchainAttemptReads {
    val walletID: String
    suspend fun transactionIDs(): Set<String>
    suspend fun transactionStatus(txid: String): ChainStatus? = null
    suspend fun boardTransactionIDs(): Set<String> = emptySet()
    suspend fun boardMovementTransactionIDs(): Set<String> = emptySet()
    /** The wallet's unspent outpoints ("txid:vout"); null when unknown. */
    suspend fun unspentOutpoints(): Set<String>? = null
    suspend fun vtxoState(id: String): String
    suspend fun exitState(id: String): String?
}
suspend fun checkOnchainAttempt(id: String, journal: OnchainOperationJournal, reads: OnchainAttemptReads): Boolean {
    val attempt = journal.loadAll().find { it.id == id } ?: fail(OnchainFailure.changed)
    if (attempt.quote.walletID != reads.walletID) fail(OnchainFailure.changed)
    val txid = attempt.receipt?.txid ?: attempt.txid
    val (status, lookupError) = chainStatus(reads, txid)
    val transactions = reads.transactionIDs()
    val board = if (attempt.quote.action == OnchainAction.board && txid != null) boardReads(attempt, reads) else BoardReads()
    val (vtxos, exits) = itemStates(attempt, reads)
    val resolved = journal.reconcile(id, OnchainOperationJournal.Evidence(reads.walletID, vtxos, exits, transactions,
        if (txid != null && status != null) mapOf(txid to status) else emptyMap(), board.boards, board.unspent, board.movements))
    // Exact local evidence remains useful even when the optional chain source is unavailable.
    journal.refreshStatus(id, status, if (lookupError != null) ChainStatus.errorText else null)
    if (!resolved && attempt.receipt == null) lookupError?.let { throw it }
    return resolved || attempt.receipt != null
}
private class BoardReads(val boards: Set<String> = emptySet(), val movements: Set<String> = emptySet(), val unspent: Set<String>? = null)
private suspend fun boardReads(attempt: OnchainOperationJournal.Attempt, reads: OnchainAttemptReads): BoardReads {
    val boards = reads.boardTransactionIDs()
    val movements = reads.boardMovementTransactionIDs()
    // Unknown coins can't prove a board never happened, so it stays locked.
    val unspent = if (attempt.receipt == null) unspentOutpoints(reads) else null
    return BoardReads(boards, movements, unspent)
}
/** The selected items' VTXO states (for a refresh) and exit states, read in order. */
private suspend fun itemStates(attempt: OnchainOperationJournal.Attempt, reads: OnchainAttemptReads): Pair<Map<String, String>, Map<String, String?>> {
    val vtxos = mutableMapOf<String, String>()
    val exits = mutableMapOf<String, String?>()
    for (item in attempt.quote.ids) {
        if (attempt.quote.action == OnchainAction.refresh) vtxos[item] = reads.vtxoState(item)
        exits[item] = reads.exitState(item)
    }
    return vtxos to exits
}
/** The optional chain source's status for [txid], or why it couldn't say; its failure is reported after local evidence. */
private suspend fun chainStatus(reads: OnchainAttemptReads, txid: String?): Pair<ChainStatus?, Exception?> = try {
    txid?.let { reads.transactionStatus(it) } to null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (@Suppress("TooGenericExceptionCaught") error: Exception) { // the chain source's failure, kept to report after local evidence
    null to error
}
private suspend fun unspentOutpoints(reads: OnchainAttemptReads): Set<String>? = try { reads.unspentOutpoints() }
catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
