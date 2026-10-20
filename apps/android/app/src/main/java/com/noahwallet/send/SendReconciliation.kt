package com.noahwallet.send

import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.json.*
import com.noahwallet.history.HistoryRules
import com.noahwallet.services.wallet.ChainStatus

object SendReconciliation {
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
    fun lightning(candidates: List<Movement>, hash: String, amount: Long, snapshot: SendSnapshot): SendResolution {
        val movement = candidates.singleOrNull()?.takeIf { isLightning(it, hash) } ?: return SendResolution.Uncertain
        val actual = runCatching { movement.sentToAddresses.fold(0L) { sum, x -> Math.addExact(sum, x.amountSats.toLong().also { require(it >= 0) }) } }.getOrNull() ?: return SendResolution.Uncertain
        if (movement.status == "successful" && actual > 0) {
            val receipt = SendReceipt(actual, paymentHash = hash, movementID = movement.id, fee = movement.offchainFeeSats.toLong(), proof = SendReceipt.Proof.actualMovement)
            if (movement.id in snapshot.movementIDs) return SendResolution.AlreadyPaid(receipt)
            return if (actual == amount) SendResolution.Paid(receipt) else SendResolution.Uncertain
        }
        if (actual != amount) return SendResolution.Uncertain
        return if (movement.status in listOf("failed", "canceled")) SendResolution.Failed else SendResolution.Uncertain
    }
    fun ark(attempt: SendAttempt, movements: List<Movement>): SendResolution {
        val payment = attempt.payment
        if (payment.source == SendSource.onchain) return SendResolution.Uncertain
        val hash = if (payment.method in listOf("lightning", "lnurl")) attempt.paymentHash ?: PaymentEncoding.invoice(payment.destination)?.hash else null
        val candidates = movements.filter {
            matches(it, payment, hash) && (hash != null || it.id !in attempt.snapshot.movementIDs &&
                (HistoryRules.parseDate(it.createdAt)?.time ?: Long.MIN_VALUE) >= attempt.startedAt)
        }
        return if (hash != null) lightning(candidates, hash, payment.amount, attempt.snapshot) else SendResolution.Candidates(candidates.map { it.id })
    }
    fun offboardReceipt(receipt: SendReceipt, payment: SendPayment, movements: List<Movement>): SendReceipt {
        if (receipt.proof != SendReceipt.Proof.submitReturnedTxid || receipt.txid == null) return receipt
        val matches = movements.filter {
            it.subsystemName == "bark.offboard" && it.subsystemKind == (if (payment.max) "offboard" else "send_onchain") &&
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
