package com.noahwallet.history

import android.content.Intent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The payment a "Payment received" notification opens in Activity: a Lightning receive by its payment hash, or an Ark
 * receive by the coins the server saw arrive (apps/fixtures/notification-tap.json). The notification carries it as
 * JSON, the same `data` the server puts in the push.
 */
data class PaymentTap(val paymentHash: String? = null, val vtxoIds: List<String> = emptyList()) {
    /** Received [outputVtxos], not spent ones: a later payment that spends the coins isn't this one. */
    fun matches(paymentHash: String?, outputVtxos: List<String>?): Boolean =
        (this.paymentHash != null && paymentHash == this.paymentHash) || outputVtxos.orEmpty().any { it in vtxoIds }

    fun matches(row: HistoryTransaction) = matches(row.paymentHash, row.outputVtxos)

    companion object {
        /** The intent extra that holds a notification's data. */
        const val EXTRA = "com.noahwallet.notification_data"

        /** A Lightning receive the app announces itself. */
        fun lightningData(paymentHash: String) =
            JsonObject(mapOf("notification_type" to JsonPrimitive("lightning_payment_received"), "payment_hash" to JsonPrimitive(paymentHash)))
                .toString()

        /** Null for anything that names no payment: a background command, older servers' `{}` or malformed data. */
        fun of(data: String?): PaymentTap? {
            val value = data?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
            fun text(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
            return when (text("notification_type")) {
                "lightning_payment_received" -> text("payment_hash")?.let { PaymentTap(paymentHash = it) }
                "ark_payment_received" -> (value["vtxo_ids"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { id -> id.isString }?.contentOrNull }
                    .takeIf { it.isNotEmpty() }?.let { PaymentTap(vtxoIds = it) }
                else -> null
            }
        }

        fun of(intent: Intent): PaymentTap? = of(intent.getStringExtra(EXTRA))
    }
}
