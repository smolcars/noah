package com.noahwallet.history

import com.noahwallet.formatting.FiatCurrency
import io.github.niteshbalusu11.bark.Movement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Locale

internal fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

@Serializable
data class RepeatPayment(
    val destination: String, val comment: String, val amountMode: String, val amountInput: String,
    val amountSat: Long? = null, val fiatCurrency: String? = null,
) {
    @Serializable data class Prefill(val amountInput: String, val amountMode: String)
    fun prefill(preferredCurrency: String) = if (amountMode == "FIAT" && fiatCurrency == preferredCurrency)
        Prefill(amountInput, "FIAT") else Prefill((amountSat ?: 0).toString(), "SATS")

    companion object {
        fun normalizeLightningAddress(value: String): String? {
            val normalized = value.trim().lowercase(Locale.ROOT).removePrefix("lightning:")
            return normalized.takeIf { it.split('@').let { parts -> parts.size == 2 && parts.all(String::isNotEmpty) } }
        }
        fun parse(value: JsonElement?): RepeatPayment? {
            val obj = value as? JsonObject ?: return null
            val version = obj["version"] as? JsonPrimitive ?: return null
            if (version.isString || version.doubleOrNull != 1.0) return null
            val destination = obj.string("destination")?.let(::normalizeLightningAddress) ?: return null
            val mode = obj.string("amount_mode")?.takeIf { it in listOf("fiat", "sats") } ?: return null
            val input = obj.string("amount_value") ?: return null
            val text = input.trim()
            val radix = when (text.take(2).lowercase(Locale.ROOT)) { "0x" -> 16; "0b" -> 2; "0o" -> 8; else -> null }
            val number = if (radix != null) text.drop(2).toULongOrNull(radix)?.toDouble() else text.toDoubleOrNull()
            if (number == null || !number.isFinite() || number <= 0) return null
            val currency = obj.string("fiat_currency")
            if (mode == "fiat" && currency !in FiatCurrency.supported) return null
            return RepeatPayment(destination, obj.string("comment") ?: "", mode.uppercase(Locale.ROOT), input,
                fiatCurrency = currency.takeIf { mode == "fiat" })
        }
        fun resolve(metadata: RepeatPayment?, destination: String?, paymentMethod: String?, amountSat: Long): RepeatPayment? {
            if (amountSat <= 0) return null
            if (metadata != null) return metadata.copy(amountSat = amountSat)
            val target = when (paymentMethod) {
                "lightning-address" -> destination?.let(::normalizeLightningAddress)
                "ark" -> destination?.trim()?.takeIf(String::isNotEmpty)
                else -> null
            } ?: return null
            return RepeatPayment(target, "", "SATS", amountSat.toString(), amountSat)
        }
        fun usesDirectArkRoute(method: String, comment: String?) = method == "ark" && comment.isNullOrEmpty()
        fun newArkoorMovementID(existing: Set<UInt>, movements: List<Movement>, destination: String, amountSat: Long): UInt? =
            movements.filter { it.id !in existing && it.status == "successful" &&
                it.subsystemName.equals("bark.arkoor", true) && it.subsystemKind.equals("send", true) &&
                it.sentToAddresses.any { sent -> sent.paymentMethod.value == destination && sent.amountSats.toLong() == amountSat }
            }.maxOfOrNull { it.id }
    }
}

@Serializable
data class HistoryMetadata(
    val offboardTxid: String? = null, val onchainFeeSat: Long? = null,
    val chainAnchor: String? = null, val repeatPayment: RepeatPayment? = null, val paymentPreimage: String? = null,
    /** The round a renewal joined: bark records its funding transaction. */
    val fundingTxid: String? = null,
) {
    companion object {
        fun parse(json: String): HistoryMetadata {
            val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return HistoryMetadata()
            val fee = (obj["onchain_fee_sat"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            return HistoryMetadata(obj.string("offboard_txid"), fee, obj.string("chain_anchor"),
                RepeatPayment.parse((obj["noah"] as? JsonObject)?.get("repeat_payment")), obj.string("payment_preimage"),
                obj.string("funding_txid"))
        }
    }
}
