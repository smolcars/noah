package com.noahwallet.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Serializable
data class HistoryTransaction(
    val id: String, val type: String, val amount: Long, val date: String, val direction: String,
    val dateLabel: String? = null, val sortTimestamp: Double? = null, val sortHeight: UInt? = null,
    val source: String? = null, val txid: String? = null, val txHex: String? = null,
    val destination: String? = null, val btcPrice: Double? = null, val movementId: UInt? = null,
    val movementStatus: String? = null, val movementKind: String? = null,
    val subsystemName: String? = null, val subsystemKind: String? = null, val metadataJson: String? = null,
    val intendedBalanceSat: Long? = null, val effectiveBalanceSat: Long? = null,
    val offchainFeeSat: Long? = null, val onchainFeeSat: Long? = null, val chainAnchor: String? = null,
    val sentTo: List<Destination>? = null, val receivedOn: List<Destination>? = null,
    val inputVtxos: List<String>? = null, val outputVtxos: List<String>? = null, val exitedVtxos: List<String>? = null,
    val balanceChangeSat: Long? = null, val hasOnchainFee: Boolean? = null, val hasConfirmation: Boolean? = null,
    val confirmationHeight: UInt? = null, val confirmationHash: String? = null, val repeatPayment: RepeatPayment? = null,
    val preimage: String? = null, val recurring: Recurring? = null,
    /** An exit fee child this wallet paid on-chain: the VTXO that exit is for (see [HistoryMapping.exitFees]). */
    val exitFeeFor: String? = null,
) {
    @Serializable data class Destination(val destination: String, @SerialName("amount_sat") val amountSat: Long, val paymentMethod: String? = null)
    @Serializable data class Accounting(val direction: String, val amount: Long)
    /** The recurring schedule this payment ran for, from the recurring journal (see [HistoryMapping.joinRecurring]). */
    @Serializable data class Recurring(val scheduleID: String, val label: String, val interval: com.noahwallet.recurring.RecurringInterval)
    private fun method(type: String) = (receivedOn.orEmpty() + sentTo.orEmpty()).firstOrNull { it.paymentMethod == type }?.destination
    /** The BOLT11 invoice or BOLT12 offer bark stored as this payment's destination. */
    val invoice get() = method("invoice")
    val offer get() = method("offer")
    /** Decoded from the stored BOLT11 invoice, else the hash bark recorded in the metadata. */
    val paymentHash: String? get() = invoice?.let { com.noahwallet.send.PaymentEncoding.invoice(it)?.hash }
        ?: metadataJson?.let {
            runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject)?.string("payment_hash")
            }.getOrNull()
        }
    /** What the payee wrote in the offer or invoice; null when there's none. */
    val description: String? get() = offer?.let(com.noahwallet.send.PaymentEncoding::offerDescription)
        ?: invoice?.let { com.noahwallet.send.PaymentEncoding.invoice(it)?.description }
    /**
     * The amount the record proves. An invoice without its own amount may have been retried for another amount, and
     * bark's reused movement keeps the first attempt's destination amount, so a settled send to one proves only what
     * left the wallet: its effective debit, fee included. Null when it proves none.
     */
    val provenAmount: Long? get() = if (subsystemName == "bark.lightning_send" && movementStatus == "successful" &&
        invoice?.let { !com.noahwallet.send.SendReconciliation.fixesAmount(it) } == true)
        effectiveBalanceSat?.takeIf { it < 0 && it != Long.MIN_VALUE }?.let { -it } else amount
    /** A Lightning send that failed after leaving the wallet and whose coins bark revoked back (SendProgressRules.refund). */
    val isRefunded get() = movementStatus == "failed" && subsystemName == "bark.lightning_send" &&
        !outputVtxos.isNullOrEmpty() && exitedVtxos.isNullOrEmpty()
    /** A round refresh that paid a fee; free ones never reach history. */
    val isRenewal get() = movementKind == "refresh"
    /** Rows today's CSV export includes: the newly shown failed payments moved nothing, so they stay out. */
    val exported get() = movementStatus != "failed" || movementKind in listOf("onboard", "offboard")
    val isCanceled get() = movementStatus == "canceled"
    val isInternalTransfer get() = movementKind == "onboard"
    val canRepeat get() = direction == "outgoing" && movementStatus == "successful" && repeatPayment != null
    val displayLabel: String get() {
        val label = if (exitFeeFor != null) "Exit fee" else when (movementKind) {
            "onboard" -> "Board"; "offboard" -> "Offboard"; "exit" -> "Ark Exit"
            else -> when (type) { "Bolt11", "Lnurl" -> "Lightning"; "Arkoor" -> "Ark"; else -> type }
        }
        return if (isCanceled) "Canceled $label" else label
    }
    val accounting get() = when {
        isCanceled -> Accounting("None", 0)
        isInternalTransfer -> Accounting("Transfer", amount)
        direction == "outgoing" -> Accounting("Outgoing", -amount)
        else -> Accounting("Incoming", amount)
    }
    val movementKindLabel get() = when (movementKind) {
        "arkoor-receive" -> "Ark Receive"; "onboard" -> "Board"; "offboard" -> "Offboard"
        "send-onchain" -> "Onchain Send"; "exit" -> "Ark Exit"; "lightning-receive" -> "Lightning Receive"
        else -> movementKind
    }
    val confirmationLabel get() = if (dateLabel == null) null else if (hasConfirmation == true)
        "Confirmed at block ${confirmationHeight ?: 0u}" else "Unconfirmed"
    val timestamp get() = HistoryRules.parseDate(date)
    val statusLabel get() = movementStatus?.replaceFirstChar { it.titlecase(Locale.ROOT) }
    val receiptStatus get() = if (movementStatus == "successful" || hasConfirmation == true) "Completed"
    else statusLabel ?: if (source == "onchain-wallet" || balanceChangeSat != null) "Unconfirmed" else "Recorded"
    fun explorerURL(variant: String): String? {
        if (type != "Onchain" && source != "onchain-wallet" && balanceChangeSat == null) return null
        val id = txid?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: return null
        return "https://mempool.space/${if (variant == "mainnet") "" else "signet/"}tx/" +
            java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    }
}

enum class HistoryFilter {
    All, Lightning, Ark, Onchain;
    fun includes(row: HistoryTransaction) = when (this) {
        All -> true; Lightning -> row.type in listOf("Bolt11", "Lnurl"); Ark -> row.type == "Arkoor"; Onchain -> row.type == "Onchain"
    }
}

object HistoryRules {
    fun parseDate(value: String): Date? {
        // SimpleDateFormat treats S as integer milliseconds, so scale fractions explicitly.
        val match = Regex("^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})?$")
            .matchEntire(value) ?: return null
        val fraction = match.groupValues[3].padEnd(3, '0').take(3)
        val zone = match.groupValues[4].ifEmpty { "Z" }.let {
            if (it.length == 5) it.take(3) + ":" + it.takeLast(2) else it
        }
        val normalized = "${match.groupValues[1]}T${match.groupValues[2]}.$fraction$zone"
        val position = java.text.ParsePosition(0)
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
            .apply { isLenient = false }.parse(normalized, position)
        return parsed?.takeIf { position.index == normalized.length }
    }
    fun isoDate(date: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("""UTC""") }.format(date)

    fun merge(movements: List<HistoryTransaction>, onchain: List<HistoryTransaction>): List<HistoryTransaction> {
        val byTxid = onchain.filter { !it.txid.isNullOrEmpty() }.associateBy { it.txid }
        val mergedIDs = mutableSetOf<String>()
        val merged = movements.map { movement ->
            val chain = byTxid[movement.txid]
            if (movement.movementKind !in listOf("onboard", "offboard") || chain == null) movement
            else {
                mergedIDs.add(chain.id)
                movement.copy(txHex = chain.txHex, btcPrice = movement.btcPrice ?: chain.btcPrice,
                    balanceChangeSat = chain.balanceChangeSat, hasOnchainFee = chain.hasOnchainFee,
                    onchainFeeSat = movement.onchainFeeSat ?: chain.onchainFeeSat, hasConfirmation = chain.hasConfirmation,
                    confirmationHeight = chain.confirmationHeight, confirmationHash = chain.confirmationHash, sortHeight = chain.sortHeight)
            }
        }
        return merged + onchain.filter { it.id !in mergedIDs }
    }
    fun precedes(a: HistoryTransaction, b: HistoryTransaction): Boolean {
        val aChain = a.source == "onchain-wallet"; val bChain = b.source == "onchain-wallet"
        val aPending = aChain && a.hasConfirmation != true; val bPending = bChain && b.hasConfirmation != true
        if (aPending != bPending) return aPending
        fun timestamp(row: HistoryTransaction): Double = row.sortTimestamp?.takeIf { it.isFinite() }
            ?: row.timestamp?.time?.toDouble() ?: 0.0
        val aTime = timestamp(a); val bTime = timestamp(b)
        if (aTime != bTime) return aTime > bTime
        if ((a.sortHeight ?: 0u) != (b.sortHeight ?: 0u)) return (a.sortHeight ?: 0u) > (b.sortHeight ?: 0u)
        return a.id < b.id
    }
    fun sorted(rows: List<HistoryTransaction>): List<HistoryTransaction> = rows.sortedWith { a, b ->
        when { precedes(a, b) -> -1; precedes(b, a) -> 1; else -> 0 }
    }
    fun unified(rows: List<HistoryTransaction>) = sorted(merge(rows.filter { it.source != "onchain-wallet" }, rows.filter { it.source == "onchain-wallet" }))
}
