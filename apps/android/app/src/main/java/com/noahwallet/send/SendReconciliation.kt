package com.noahwallet.send

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import com.noahwallet.history.HistoryRules
import com.noahwallet.services.wallet.ChainStatus

/** Receipts bind their movement exactly: SDK 0.6.0 returns it for Ark sends and offboards, and a
 *  BOLT11 hash binds Lightning. Weak history correlation remains only for attempts with no result,
 *  e.g. a crash before the receipt was saved (FFI item 19's pre-ID window). */
object SendReconciliation {
    /** The exact movement for history metadata, never a destination/amount/time match.
     *  Movements that predate the attempt (an inherited Lightning checkpoint) are left untouched. */
    fun metadataMovementID(receipt: SendReceipt, snapshot: SendSnapshot): UInt? = receipt.movementID?.takeIf { it !in snapshot.movementIDs }
    fun isLightning(movement: Movement, hash: String) = movement.subsystemName == "bark.lightning_send" && movement.subsystemKind == "send" && movement.paymentHash == hash
    fun matches(movement: Movement, payment: SendPayment, hash: String?): Boolean {
        if (payment.source == SendSource.onchain) return false
        val subsystem = movement.subsystemName.lowercase(); val kind = movement.subsystemKind.lowercase()
        when (payment.method) {
            "ark" -> if (subsystem != "bark.arkoor" || kind != "send") return false
            "lightning", "lnurl", "offer" -> if (subsystem != "bark.lightning_send" || kind != "send") return false
            "onchain" -> if (payment.source != SendSource.offchain || subsystem !in listOf("bark.offboard", "bark.round") || kind != if (payment.max) "offboard" else "send_onchain") return false
            else -> return false
        }
        if (hash != null) return movement.paymentHash == hash
        if (movement.intendedBalanceSats >= 0 && movement.effectiveBalanceSats >= 0 || payment.method == "lightning") return false
        return movement.sentToAddresses.any { it.paymentMethod.value == payment.destination && (payment.max || it.amountSats == payment.amount.toULong()) } ||
            payment.method == "offer" && movement.lightningOffer == payment.destination && movement.sentToAddresses.sumOf { it.amountSats } == payment.amount.toULong()
    }
    /** What a successful send's effective balance took beyond [actual]. A retried invoice's reused movement keeps its first
     *  attempt's fee field, so this is its fee; null when bark's numbers can't give one. */
    fun lightningFee(movement: Movement, actual: Long): Long? = lightningFee(movement.effectiveBalanceSats, actual)
    fun lightningFee(effective: Long, actual: Long): Long? =
        runCatching { Math.subtractExact(Math.negateExact(effective), actual) }.getOrNull()?.takeIf { it >= 0 }
    private fun receipt(movement: Movement, actual: Long, hash: String) = SendReceipt(actual, paymentHash = hash, movementID = movement.id,
        fee = lightningFee(movement, actual) ?: movement.offchainFeeSats.toLong(), proof = SendReceipt.Proof.actualMovement)
    /** Whether [invoice] sets its own amount, so every attempt on its hash sent that amount. */
    fun fixesAmount(invoice: String?) = (invoice?.let(PaymentEncoding::invoice)?.sats?.signum() ?: 0) > 0
    /** An invoice without its own amount may have been paid by a retry for another amount, and bark's reused movement keeps
     *  the first attempt's numbers: nothing on it proves what was sent or paid in fees, so the receipt shows neither. */
    private fun alreadyPaid(receipt: SendReceipt, fixed: Boolean) = SendResolution.AlreadyPaid(if (fixed) receipt else receipt.copy(amount = 0, fee = null))
    fun lightning(candidates: List<Movement>, hash: String, amount: Long, snapshot: SendSnapshot, fixed: Boolean): SendResolution {
        val movement = candidates.singleOrNull()?.takeIf { isLightning(it, hash) } ?: return SendResolution.Uncertain
        val actual = runCatching { movement.sentToAddresses.fold(0L) { sum, x -> Math.addExact(sum, x.amountSats.toLong().also { require(it >= 0) }) } }.getOrNull() ?: return SendResolution.Uncertain
        if (movement.status == "successful" && actual > 0) {
            val receipt = receipt(movement, actual, hash)
            if (movement.id in snapshot.movementIDs) return alreadyPaid(receipt, fixed)
            return if (actual == amount) SendResolution.Paid(receipt) else SendResolution.Uncertain
        }
        if (actual != amount) return SendResolution.Uncertain
        return if (movement.status in listOf("failed", "canceled")) SendResolution.Failed else SendResolution.Uncertain
    }
    /**
     * This attempt's outcome from its hash's movement. Retrying a failed invoice, bark 0.8 reuses the hash's finished
     * movement (async-payments.md, "Reused hashes"), so a movement already in the snapshot is never read by its status
     * alone. Success is this attempt's when bark had no paid record before it ([SendSnapshot.invoicePaid]; journals
     * without it go by whether the movement completed after [startedAt]), and its fee is what the movement's effective
     * balance took beyond the amount: the fee field keeps the first attempt's. Failure needs the movement to have
     * completed after [startedAt]; until then the attempt may still be running.
     */
    fun lightningAttempt(candidates: List<Movement>, hash: String, amount: Long, snapshot: SendSnapshot, startedAt: Long?, fixed: Boolean): SendResolution {
        val movement = candidates.singleOrNull()?.takeIf { isLightning(it, hash) } ?: return SendResolution.Uncertain
        if (movement.id !in snapshot.movementIDs) return lightning(candidates, hash, amount, snapshot, fixed)
        val actual = runCatching { movement.sentToAddresses.fold(0L) { sum, x -> Math.addExact(sum, x.amountSats.toLong().also { require(it >= 0) }) } }.getOrNull() ?: return SendResolution.Uncertain
        val completedSince = startedAt?.let { start -> movement.completedAt?.let { HistoryRules.parseDate(it) }?.time?.let { it > start } }
        if (movement.status == "successful" && actual > 0) {
            val receipt = receipt(movement, actual, hash)
            return when (snapshot.invoicePaid ?: completedSince?.not()) {
                true -> alreadyPaid(receipt, fixed)
                false -> if (actual == amount && lightningFee(movement, actual) != null) SendResolution.Paid(receipt) else SendResolution.Uncertain
                null -> SendResolution.Uncertain
            }
        }
        if (actual != amount || movement.status !in listOf("failed", "canceled")) return SendResolution.Uncertain
        return if (completedSince == true) SendResolution.Failed else SendResolution.Uncertain
    }
    fun ark(attempt: SendAttempt, movements: List<Movement>): SendResolution {
        val payment = attempt.payment
        if (payment.source == SendSource.onchain) return SendResolution.Uncertain
        val hash = if (payment.method in listOf("lightning", "lnurl")) attempt.paymentHash ?: PaymentEncoding.invoice(payment.destination)?.hash else null
        val candidates = movements.filter {
            matches(it, payment, hash) && (hash != null || it.id !in attempt.snapshot.movementIDs &&
                (HistoryRules.parseDate(it.createdAt)?.time ?: Long.MIN_VALUE) >= attempt.startedAt)
        }
        return if (hash != null) lightningAttempt(candidates, hash, payment.amount, attempt.snapshot, attempt.startedAt,
            fixesAmount(attempt.snapshot.resolvedInvoice ?: payment.destination)) else SendResolution.Candidates(candidates.map { it.id })
    }
    /** SDK 0.6.0 also returns the movement id, which must agree with the txid; journals saved before it bind by txid alone. */
    fun offboardReceipt(receipt: SendReceipt, payment: SendPayment, movements: List<Movement>): SendReceipt {
        if (receipt.proof != SendReceipt.Proof.submitReturnedTxid || receipt.txid == null) return receipt
        val matches = movements.filter {
            (receipt.movementID == null || it.id == receipt.movementID) && it.subsystemName == "bark.offboard" && it.subsystemKind == (if (payment.max) "offboard" else "send_onchain") &&
                runCatching { Json.parseToJsonElement(it.metadataJson).jsonObject["offboard_txid"]?.jsonPrimitive?.content }.getOrNull() == receipt.txid
        }
        val movement = matches.singleOrNull() ?: return receipt
        val amount = runCatching { SendTransaction.amount(payment.destination, Json.parseToJsonElement(movement.metadataJson).jsonObject.getValue("offboard_tx").jsonPrimitive.content) }.getOrNull() ?: return receipt
        if (amount <= 0 || !payment.max && amount != payment.amount) return receipt
        return receipt.copy(amount = amount, amountIsPending = false, fee = movement.offchainFeeSats.toLong(), movementID = movement.id)
    }
    fun withChainStatus(resolution: SendResolution, source: SendSource?, status: ChainStatus): SendResolution {
        if (resolution !is SendResolution.Paid) return resolution
        val receipt = resolution.receipt
        // Offboards may never be in BDK's own graph. Preserve the existing competing-spend result.
        val updated = if (source == SendSource.offchain && receipt.status != SendReceipt.Status.conflicted)
            receipt.copy(status = when {
                status.kind == ChainStatus.Kind.confirmed -> SendReceipt.Status.confirmed
                receipt.status == SendReceipt.Status.confirmed -> SendReceipt.Status.submitted
                else -> receipt.status
            })
        else receipt
        // For BDK sends this is display evidence only: cached confirmation/conflict and retry locks still govern.
        return SendResolution.Paid(updated.copy(chainStatus = status, statusError = null))
    }
    suspend fun onchainStatus(attempt: SendAttempt, sync: suspend () -> Unit,
        transactions: suspend () -> List<WalletTransaction>, offboardHistory: suspend () -> List<Movement>,
        status: suspend (String) -> ChainStatus): SendResolution {
        val resolution = if (attempt.payment.source == SendSource.onchain) {
            sync() // A failed BDK sync must not promote cached evidence.
            onchain(attempt, transactions())
        } else {
            val receipt = attempt.receipt ?: return SendResolution.Uncertain
            SendResolution.Paid(offboardReceipt(receipt, attempt.payment, offboardHistory()))
        }
        val txid = (resolution as? SendResolution.Paid)?.receipt?.txid ?: return resolution
        return try { withChainStatus(resolution, attempt.payment.source, status(txid)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // The optional chain lookup must not discard newer BDK reorg or conflict evidence.
            SendResolution.Paid(resolution.receipt.copy(statusError = ChainStatus.errorText))
        }
    }
    fun onchain(attempt: SendAttempt, transactions: List<WalletTransaction>): SendResolution {
        val receipt = attempt.receipt
        val originalTxid = receipt?.txid ?: attempt.broadcastingTxid
        val original = transactions.firstOrNull { it.txid == originalTxid }
        val preparedInputs = attempt.quote?.prepared?.takeIf { it.txid == originalTxid }?.inputs
        val inputs = if (preparedInputs != null) preparedInputs.takeIf { points ->
            points.isNotEmpty() && points.all { Regex("[0-9a-f]{64}").matches(it.txid) }
        } else original?.let { runCatching { SendTransaction.parse(it.txHex).inputs }.getOrNull() }
        val conflict = inputs?.toSet()?.let { points -> transactions.firstOrNull { tx ->
            tx.txid != originalTxid && tx.confirmation != null &&
                runCatching { SendTransaction.parse(tx.txHex).inputs.any { it in points } }.getOrDefault(false)
        }?.txid }
        if (receipt != null && receipt.proof in listOf(SendReceipt.Proof.submitReturnedTxid, SendReceipt.Proof.preparedTxid) && receipt.txid != null) {
            val status = when {
                conflict != null -> SendReceipt.Status.conflicted
                original != null -> if (original.confirmation == null) SendReceipt.Status.submitted else SendReceipt.Status.confirmed
                attempt.payment.source == SendSource.offchain -> SendReceipt.Status.submitted
                else -> SendReceipt.Status.unlocated
            }
            return SendResolution.Paid(receipt.copy(status = status, confirmedConflictTxid = conflict,
                fee = original?.onchainFeeSats?.toLong() ?: receipt.fee,
                broadcastRetryPending = original?.confirmation == null && receipt.broadcastRetryPending))
        }
        val txid = attempt.broadcastingTxid; val quote = attempt.quote
        if (txid != null && quote?.prepared?.txid == txid && attempt.payment.method == "onchain" && attempt.payment.source == SendSource.onchain) {
            if (original == null && conflict == null) return SendResolution.Uncertain
            return SendResolution.Paid(SendReceipt(quote.amount, txid = txid,
                status = if (conflict != null) SendReceipt.Status.conflicted else if (original?.confirmation == null) SendReceipt.Status.submitted else SendReceipt.Status.confirmed,
                fee = quote.fee, proof = SendReceipt.Proof.preparedTxid, broadcastRetryPending = original?.confirmation == null,
                confirmedConflictTxid = conflict))
        }
        if (attempt.payment.method != "onchain" || attempt.payment.source != SendSource.onchain || attempt.payment.max || attempt.snapshot.bdkTxids == null) return SendResolution.Uncertain
        val candidates = transactions.filter { it.txid !in attempt.snapshot.bdkTxids && it.balanceChangeSats < 0 && SendTransaction.amount(attempt.payment.destination, it.txHex) == attempt.payment.amount }.map { it.txid }
        return if (candidates.isEmpty()) SendResolution.Uncertain else SendResolution.PossibleTransactions(candidates.sorted())
    }
}

/** Strict consensus-output reader; malformed evidence never authorizes another payment. */
object SendTransaction {
    data class Output(val amount: ULong, val script: ByteArray)
    data class Parsed(val inputs: List<SendInput>, val outputs: List<Output>)
    fun amount(address: String, hex: String): Long {
        val script = PaymentEncoding.bitcoinScript(address) ?: throw SendFailure(SendFailure.Reason.unresolved)
        return parse(hex).outputs.filter { it.script.contentEquals(script) }.fold(0L) { amount, output ->
            require(output.amount <= Long.MAX_VALUE.toULong())
            Math.addExact(amount, output.amount.toLong())
        }
    }
    fun parse(hex: String): Parsed {
        require(hex.length % 2 == 0)
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(); var cursor = 0
        fun take(count: Int): ByteArray { require(count >= 0 && count <= bytes.size - cursor); return bytes.copyOfRange(cursor, cursor + count).also { cursor += count } }
        fun integer(count: Int): ULong = take(count).mapIndexed { i, x -> (x.toInt() and 255).toULong() shl (i * 8) }.fold(0uL) { a, x -> a or x }
        fun size(): Int { val tag = integer(1); val value = if (tag < 253u) tag else integer(if (tag == 253uL) 2 else if (tag == 254uL) 4 else 8); require(value <= bytes.size.toULong()); return value.toInt() }
        take(4)
        val witness = bytes.size > cursor + 1 && bytes[cursor] == 0.toByte() && bytes[cursor + 1] == 1.toByte()
        if (witness) take(2)
        val inputs = size(); require(inputs > 0)
        val outpoints = List(inputs) {
            val txid = PaymentEncoding.hex(take(32).reversedArray())
            val vout = integer(4).toUInt()
            take(size()); take(4)
            SendInput(txid, vout)
        }
        val outputs = List(size()) { val value = integer(8); Output(value, take(size())) }
        if (witness) repeat(inputs) { repeat(size()) { take(size()) } }
        take(4); require(cursor == bytes.size)
        return Parsed(outpoints, outputs)
    }
}
