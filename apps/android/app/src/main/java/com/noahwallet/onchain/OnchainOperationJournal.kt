package com.noahwallet.onchain

import com.noahwallet.services.storage.syncDirectory
import com.noahwallet.services.wallet.ChainStatus
import io.github.niteshbalusu11.bark.BarkError
import io.github.niteshbalusu11.bark.BarkErrorCode
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Fsync, rename, fsync directory. A failed save must prevent SDK submission. */
internal class OnchainFile(val file: File, private val syncParent: (File) -> Unit = ::syncDirectory) {
    fun write(text: String) {
        val temporary = File(file.parentFile, ".${file.name}-${java.util.UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(temporary.renameTo(file)) { "Cannot publish wallet journal" }
            syncParent(checkNotNull(file.parentFile))
        } finally { temporary.delete() }
    }
}

/** Call-specific v0.4.0 allowlist. Never classify by diagnostic text. */
object OnchainSubmission {
    fun preBroadcast(error: Exception, action: OnchainAction): OnchainFailure? {
        if (error is OnchainException && error.reason in setOf(OnchainFailure.claimFeeChanged, OnchainFailure.cancellationRefused)) return error.reason
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
        const val overrideWarning = "This does not cancel the original operation. Submitting again may spend additional funds or fees. Noah keeps this attempt and checks its status as your wallet syncs."
        const val overrideConfirmation = "I understand this may repeat the operation"
    }
    @Serializable data class OverrideAudit(
        val overriddenAt: Long, val title: String = overrideTitle, val warning: String = overrideWarning,
        val confirmation: String = overrideConfirmation, val statusMessage: String = OnchainFailure.uncertain.text,
        val acknowledgedAt: Long? = null,
    )
    @Serializable data class Attempt(val quote: OnchainQuote, val receipt: OnchainReceipt? = null,
        val txid: String? = null, val overrideAudit: OverrideAudit? = null) {
        val id get() = quote.id
        fun blocks(action: OnchainAction, ids: List<String>): Boolean {
            if (overrideAudit != null || quote.action != action) return false
            // Bark skips active starts and already-canceled cancels. Claims/CPFP can spend again.
            if (receipt == null && action in setOf(OnchainAction.startExit, OnchainAction.cancel)) return false
            return action == OnchainAction.board || quote.ids.any { it in ids }
        }
    }
    data class Evidence(val walletID: String, val vtxoStates: Map<String, String> = emptyMap(),
        val exitStates: Map<String, String?> = emptyMap(), val transactionIDs: Set<String> = emptySet(),
        val chainStatuses: Map<String, ChainStatus> = emptyMap(), val boardTransactionIDs: Set<String> = emptySet()) {
        fun receipt(attempt: Attempt): OnchainReceipt? {
            if (walletID != attempt.quote.walletID) return null
            if (attempt.txid != null && (attempt.txid in transactionIDs || attempt.txid in boardTransactionIDs ||
                    chainStatuses[attempt.txid]?.kind in setOf(ChainStatus.Kind.inMempool, ChainStatus.Kind.confirmed))) {
                val prepared = attempt.quote.preparedBoard
                val status = chainStatuses[attempt.txid]
                val retry = prepared != null && status?.kind != ChainStatus.Kind.confirmed
                return OnchainReceipt(if (retry) "Boarding submitted, but delivery is unconfirmed. Noah will keep retrying the broadcast. Do not board again."
                    else "The recorded transaction was found. Review its network status.", attempt.txid,
                    prepared?.let { attempt.quote.chainFee }, retry, status)
            }
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
            return if (resolved) OnchainReceipt("The selected items have reached a state that resolves this attempt. Review their current status and history before continuing.") else null
        }
    }
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun read(): List<Attempt> {
        if (!storage.file.exists()) return emptyList()
        val element = json.parseToJsonElement(storage.file.readText())
        return if (element is JsonObject) listOf(json.decodeFromJsonElement<Attempt>(element))
            else json.decodeFromJsonElement<List<Attempt>>(element)
    }
    private fun save(attempts: List<Attempt>) {
        val retained = attempts.filter { it.overrideAudit != null }.sortedWith(
            compareByDescending<Attempt> { it.overrideAudit!!.overriddenAt }.thenByDescending { it.id })
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
        } catch (error: Exception) {
            val failure = if (quote.preparedBoard != null) (error as? BoardNotSubmitted)?.reason
                else OnchainSubmission.preBroadcast(error, quote.action)
            if (failure != null) { save(read().filter { it.id != quote.id }); fail(failure) }
            fail(OnchainFailure.uncertain)
        }
    }
    suspend fun reconcile(id: String, evidence: Evidence): Boolean = mutex.withLock {
        val attempts = read()
        val attempt = attempts.find { it.id == id && it.receipt == null } ?: return@withLock false
        val receipt = evidence.receipt(attempt) ?: return@withLock false
        save(attempts.map { if (it.id == id) it.copy(receipt = receipt) else it }); true
    }
    suspend fun refreshBoardFees(walletID: String, fees: Map<String, Long>) = mutex.withLock {
        val attempts = read()
        val updated = attempts.map { attempt ->
            val receipt = attempt.receipt
            val fee = fees[receipt?.txid]
            if (attempt.quote.walletID == walletID && attempt.quote.action == OnchainAction.board && receipt != null && receipt.onchainFee == null && fee != null)
                attempt.copy(receipt = receipt.copy(onchainFee = fee, message = OnchainReceipt.boardingMessage(fee))) else attempt
        }
        if (updated != attempts) save(updated)
    }
    suspend fun refreshStatus(id: String, status: ChainStatus?, error: String? = null) = mutex.withLock {
        val attempts = read()
        val updated = attempts.map { attempt ->
            val receipt = attempt.receipt
            if (attempt.id != id || receipt == null) attempt else {
                val confirmed = status?.kind == ChainStatus.Kind.confirmed
                attempt.copy(receipt = receipt.copy(chainStatus = status ?: receipt.chainStatus, statusError = error,
                    broadcastRetryPending = receipt.broadcastRetryPending && !confirmed,
                    message = if (confirmed && attempt.quote.action == OnchainAction.board) OnchainReceipt.boardingMessage(receipt.onchainFee) else receipt.message))
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
        if (attempts.none { it.id == id && it.receipt == null && it.overrideAudit == null }) fail(OnchainFailure.busy)
        save(attempts.map { if (it.id == id) it.copy(overrideAudit = OverrideAudit(now)) else it })
    }
}

/** No sync, broadcast or progression capability on the Check status seam. */
interface OnchainAttemptReads {
    val walletID: String
    suspend fun transactionIDs(): Set<String>
    suspend fun transactionStatus(txid: String): ChainStatus? = null
    suspend fun boardTransactionIDs(): Set<String> = emptySet()
    suspend fun vtxoState(id: String): String
    suspend fun exitState(id: String): String?
}
suspend fun checkOnchainAttempt(id: String, journal: OnchainOperationJournal, reads: OnchainAttemptReads): Boolean {
    val attempt = journal.loadAll().find { it.id == id } ?: fail(OnchainFailure.changed)
    if (attempt.quote.walletID != reads.walletID) fail(OnchainFailure.changed)
    val txid = attempt.receipt?.txid ?: attempt.txid
    var lookupError: Exception? = null
    val status = try { txid?.let { reads.transactionStatus(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { lookupError = error; null }
    val transactions = reads.transactionIDs()
    val boards = if (attempt.quote.action == OnchainAction.board && txid != null) reads.boardTransactionIDs() else emptySet()
    val vtxos = mutableMapOf<String, String>()
    val exits = mutableMapOf<String, String?>()
    for (item in attempt.quote.ids) {
        if (attempt.quote.action == OnchainAction.refresh) vtxos[item] = reads.vtxoState(item)
        exits[item] = reads.exitState(item)
    }
    val resolved = journal.reconcile(id, OnchainOperationJournal.Evidence(reads.walletID, vtxos, exits, transactions,
        if (txid != null && status != null) mapOf(txid to status) else emptyMap(), boards))
    // Exact local evidence remains useful even when the optional chain source is unavailable.
    journal.refreshStatus(id, status, if (lookupError != null) ChainStatus.errorText else null)
    if (!resolved && attempt.receipt == null) lookupError?.let { throw it }
    return resolved || attempt.receipt != null
}
