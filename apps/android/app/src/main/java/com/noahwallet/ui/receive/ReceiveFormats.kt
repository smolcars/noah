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
 * can follow the confirmation.
 */
@Serializable
data class ReceiveArrival(val rail: Rail, val sats: Long, val startedAt: Long, val txid: String? = null) {
    @Serializable enum class Rail { ark, lightning, onchain }
}

internal object ReceiveArrivals {
    /**
     * A new unconfirmed incoming on-chain transaction for this request: paying its Bitcoin address, or, if no output
     * can be tied to it, any new unconfirmed incoming on-chain transaction. [known] holds the transactions seen when
     * Receive appeared. An already-mined deposit (a reconnect can surface one) is history, not an arrival.
     */
    fun onchain(rows: List<HistoryTransaction>, known: Set<String>, address: String?, amount: (String, String) -> Long?): Pair<HistoryTransaction, Long>? {
        val fresh = rows.filter { it.source == "onchain-wallet" && it.direction == "incoming" && (it.balanceChangeSat ?: 0) > 0 && it.txid != null &&
            it.hasConfirmation != true && it.id !in known }
        val paying = address?.let { target -> fresh.firstNotNullOfOrNull { row -> row.txHex?.let { amount(target, it) }?.takeIf { it > 0 }?.let { row to it } } }
        return paying ?: fresh.firstOrNull()?.let { it to (it.balanceChangeSat ?: it.amount) }
    }

    /**
     * Ark and Lightning payments that arrive through your Lightning address rather than this screen's own
     * requests: bark records them as new successful receives. Receives to this screen's own Ark addresses
     * ([ownArk]) or invoices ([ownHashes]) come through today's completion instead, so they're never announced twice.
     */
    fun viaAddress(rows: List<HistoryTransaction>, known: Set<String>, ownArk: Set<String>, ownHashes: Set<String>): HistoryTransaction? =
        rows.firstOrNull { row ->
            row.id !in known && row.direction == "incoming" && row.movementStatus == "successful" && row.amount > 0 &&
                row.movementKind in setOf("arkoor-receive", "lightning-receive") &&
                row.receivedOn.orEmpty().none { it.destination in ownArk } && paymentHash(row.metadataJson) !in ownHashes
        }

    private fun paymentHash(metadata: String?): String? = metadata?.let {
        runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject)?.get("payment_hash")
            ?.let { value -> (value as? kotlinx.serialization.json.JsonPrimitive)?.content } }.getOrNull()
    }
}
