package com.noahwallet.ui.receive

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.services.wallet.ReceiveRequest
import kotlinx.serialization.Serializable

/** The five ways to pay you, in the order "Ways to pay you" lists them. */
@Serializable
enum class ReceiveFormat { lightningAddress, request, invoice, ark, onchain }

/**
 * What each format shows, copies and shares: exactly its own payload. The Lightning address is the literal
 * address (as the QR hub shows it), the payment request is today's BIP-321 URI, and the invoice exists only
 * once an amount has been added.
 */
internal object ReceiveFormats {
    fun payload(format: ReceiveFormat, request: ReceiveRequest?, address: String?): String? = when (format) {
        ReceiveFormat.lightningAddress -> address?.takeIf { it.isNotEmpty() }
        ReceiveFormat.request -> request?.let { runCatching { it.uri() }.getOrNull() }
        ReceiveFormat.invoice -> request?.lightningInvoice?.takeIf { request.amountSat != null }
        ReceiveFormat.ark -> request?.arkAddress
        ReceiveFormat.onchain -> request?.onchainAddress
    }

    /** H keeps the logo safest; long payloads (the request with its invoice) use M so the modules stay large. */
    fun correction(payload: String): ErrorCorrectionLevel = if (payload.length > 90) ErrorCorrectionLevel.M else ErrorCorrectionLevel.H

    /** Adding an amount shows the payment request; clearing it returns from the request or invoice to the address. */
    fun afterAmount(shown: ReceiveFormat, hasAmount: Boolean): ReceiveFormat = when {
        hasAmount -> ReceiveFormat.request
        shown == ReceiveFormat.request || shown == ReceiveFormat.invoice -> ReceiveFormat.lightningAddress
        else -> shown
    }

    /** The row's value preview: the payload with its middle elided, so both ends stay checkable. */
    fun preview(payload: String): String = if (payload.codePointCount(0, payload.length) <= 32) payload else
        payload.substring(0, payload.offsetByCodePoints(0, 16)) + "…" + payload.substring(payload.offsetByCodePoints(payload.length, -10))
}

/**
 * Money seen arriving while Receive is visible, from the wallet's own records. [startedAt] (elapsedRealtime)
 * times the motion so a pose change resumes it in place; on-chain arrivals keep their [txid] so the block row
 * can follow the confirmation, and the coins their transaction spends ([inputs], "txid:vout") so a replacement
 * can be recognised.
 */
@Serializable
data class ReceiveArrival(val rail: Rail, val sats: Long, val startedAt: Long, val txid: String? = null, val inputs: List<String> = emptyList()) {
    @Serializable enum class Rail { ark, lightning, onchain }
}

internal object ReceiveArrivals {
    /** A new arrival takes the card when it's free or showing an on-chain arrival, whose block row carries on separately. */
    fun takesCard(current: ReceiveArrival?): Boolean = current == null || current.rail == ReceiveArrival.Rail.onchain

    /**
     * A new unconfirmed incoming on-chain transaction for this request: paying its Bitcoin address, or, if no output
     * can be tied to it, any new unconfirmed incoming on-chain transaction. [known] holds the transactions seen when
     * Receive appeared. An already-mined deposit (a reconnect can surface one) is history, not an arrival.
     */
    fun onchain(rows: List<HistoryTransaction>, known: Set<String>, address: String?, amount: (String, String) -> Long?): Pair<HistoryTransaction, Long>? {
        val fresh = rows.filter { it.source == "onchain-wallet" && it.direction == "incoming" && (it.balanceChangeSat ?: 0) > 0 && it.txid != null &&
            it.hasConfirmation != true && it.id !in known }
        val paying = address?.let { target -> fresh.firstNotNullOfOrNull { row -> row.txHex?.let { amount(target,
            it) }?.takeIf { it > 0 }?.let { row to it } } }
        return paying ?: fresh.firstOrNull()?.let { it to (it.balanceChangeSat ?: it.amount) }
    }

    /**
     * The next arrival waiting in the records, and the records once it's taken: a new unconfirmed deposit first,
     * then a payment to your Lightning address. Replacements of deposits already shown are taken first, as seen
     * with no arrival (null). Null when nothing is waiting.
     */
    @Suppress("LongParameterList") // a pure rule over the wallet's records, which ReceiveFormatsTest drives argument by argument
    fun next(rows: List<HistoryTransaction>, records: ReceiveRecords, address: String?, ownArk: Set<String>, ownHashes: Set<String>,
        amount: (String, String) -> Long?, spends: (String) -> List<String>?, now: Long): Pair<ReceiveRecords, ReceiveArrival?>? {
        val seen = replaced(rows, records, spends)
        onchain(rows, seen.known, address, amount)?.let { (row, sats) ->
            val inputs = row.txHex?.let(spends).orEmpty()
            return seen.copy(known = seen.known + row.id, spent = seen.spent + inputs) to ReceiveArrival(ReceiveArrival.Rail.onchain, sats, now, row.txid,
                inputs)
        }
        viaAddress(rows, seen.settled, ownArk, ownHashes)?.let { row ->
            val rail = if (row.movementKind == "lightning-receive") ReceiveArrival.Rail.lightning else ReceiveArrival.Rail.ark
            return seen.copy(settled = seen.settled + row.id) to ReceiveArrival(rail, row.amount, now)
        }
        return if (seen != records) seen to null else null
    }

    /**
     * The records with every replacement of a deposit shown this session marked seen: a new on-chain transaction
     * spending one of the coins a shown deposit spent ([ReceiveRecords.spent]), as RBF does. Its own coins are kept
     * too, so a replacement of the replacement is caught as well.
     */
    fun replaced(rows: List<HistoryTransaction>, records: ReceiveRecords, spends: (String) -> List<String>?): ReceiveRecords {
        var seen = records
        while (true) {
            val replacement = rows.firstOrNull { row ->
                row.source == "onchain-wallet" && row.txid != null && row.id !in seen.known && row.txHex?.let(spends).orEmpty().any { it in seen.spent }
            } ?: return seen
            seen = seen.copy(known = seen.known + replacement.id, spent = seen.spent + replacement.txHex?.let(spends).orEmpty())
        }
    }

    /**
     * The deposit the block row follows, as the records now stand: itself while it's there. If it's gone, a new
     * incoming transaction spending one of the same coins replaced it (as RBF does) and takes over, confirmed or not;
     * its row comes back too, to be marked seen, since it's the same deposit, not a new arrival. Without that evidence
     * nothing is assumed: the wait just ends, and any new deposit is an arrival of its own.
     */
    fun followed(tracked: ReceiveArrival, rows: List<HistoryTransaction>, known: Set<String>, spends: (String) -> List<String>?): Pair<ReceiveArrival?,
        HistoryTransaction?> {
        if (rows.any { it.source == "onchain-wallet" && it.txid == tracked.txid }) return tracked to null
        if (tracked.inputs.isEmpty()) return null to null
        val replacement = rows.firstOrNull { row ->
            row.source == "onchain-wallet" && row.direction == "incoming" && (row.balanceChangeSat ?: 0) > 0 && row.txid != null &&
                row.id !in known && row.txHex?.let(spends).orEmpty().any { it in tracked.inputs }
        } ?: return null to null
        return tracked.copy(txid = replacement.txid, sats = replacement.balanceChangeSat ?: replacement.amount,
            inputs = replacement.txHex?.let(spends).orEmpty()) to replacement
    }

    /** The receipts already complete when Receive appeared: a pending one isn't, so its later success is announced. */
    fun settled(rows: List<HistoryTransaction>): List<String> = rows.filter { it.movementStatus == "successful" }.map { it.id }

    /**
     * Ark and Lightning payments that arrive through your Lightning address rather than this screen's own
     * requests: bark records them as successful receives that weren't [settled] already, including one that was
     * still pending when Receive appeared. Receives to this screen's own Ark addresses ([ownArk]) or invoices
     * ([ownHashes]) come through today's completion instead, so they're never announced twice.
     */
    fun viaAddress(rows: List<HistoryTransaction>, settled: Set<String>, ownArk: Set<String>, ownHashes: Set<String>): HistoryTransaction? =
        rows.firstOrNull { row ->
            row.id !in settled && row.direction == "incoming" && row.movementStatus == "successful" && row.amount > 0 &&
                row.movementKind in setOf("arkoor-receive", "lightning-receive") &&
                row.receivedOn.orEmpty().none { it.destination in ownArk } && paymentHash(row.metadataJson) !in ownHashes
        }

    private fun paymentHash(metadata: String?): String? = metadata?.let {
        runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject)?.get("payment_hash")
            ?.let { value -> (value as? kotlinx.serialization.json.JsonPrimitive)?.content } }.getOrNull()
    }
}
