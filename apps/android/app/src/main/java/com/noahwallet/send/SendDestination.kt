package com.noahwallet.send

import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder

// Mirrors iOS SendDestination; pure so RN-generated fixtures run without JNI.
data class SendDestination(val destinationType: String? = null, val amount: BigDecimal? = null,
    val isAmountEditable: Boolean = true, val error: SendDestinationError? = null, val bip321: Map<String, String>? = null) {
    companion object {
        fun cleaned(value: String) = value.trim().let { if (it.startsWith("lightning:", true)) it.drop(10) else it }
        fun isLightningAddress(value: String) = Regex("^[a-z0-9._-]+@[a-z0-9.-]+\\.[a-z]{2,4}$").matches(value.lowercase())
        fun offer(value: String): Boolean {
            val chunks = value.split('+'); val normalized = chunks.mapIndexed { index, s -> if (index == 0) s else s.trimStart() }.joinToString("")
            return chunks.all { it.isNotBlank() } && (normalized == normalized.lowercase() || normalized == normalized.uppercase()) && Regex("^lno1[02-9ac-hj-np-z]+$", RegexOption.IGNORE_CASE).matches(normalized)
        }
        fun matches(actual: String, expected: String, type: String) = actual == expected ||
            (type == "ark" && actual == "testnet" && expected != "mainnet") || (type == "onchain" && actual == "testnet" && expected == "signet")
        private fun mismatch(actual: String, expected: String) = SendDestination(error = SendDestinationError.NetworkMismatch(expected, actual))
        fun parse(input: String, network: String): SendDestination {
            if (input.startsWith("bitcoin:", true)) return uri(input, network)
            val value = cleaned(input)
            if (isLightningAddress(value)) return SendDestination("lnurl")
            PaymentEncoding.invoice(value)?.let { invoice ->
                if (!matches(invoice.network, network, "lightning")) return mismatch(invoice.network, network)
                if (invoice.sats != null && invoice.sats > BigDecimal.ZERO && invoice.sats < BigDecimal.ONE) return SendDestination("lightning", error = SendDestinationError.SubSatoshiInvoice)
                return SendDestination("lightning", invoice.sats?.takeIf { it >= BigDecimal.ONE }, (invoice.sats ?: BigDecimal.ZERO) < BigDecimal.ONE)
            }
            if (offer(value)) return SendDestination("offer")
            PaymentEncoding.bitcoinNetwork(value)?.let { return if (matches(it, network, "onchain")) SendDestination("onchain") else mismatch(it, network) }
            PaymentEncoding.bech32(value)?.let { decoded ->
                if (decoded.isM && decoded.prefix in listOf("ark", "tark")) {
                    val actual = if (decoded.prefix == "ark") "mainnet" else "testnet"
                    return if (matches(actual, network, "ark")) SendDestination("ark") else mismatch(actual, network)
                }
                if (decoded.prefix == "lnurl" && !decoded.isM) {
                    val url = PaymentEncoding.bytes(decoded.words)?.toString(Charsets.UTF_8)?.let { runCatching { URI(it) }.getOrNull() }
                    if (url?.scheme == "https" && url.host != null) return SendDestination("lnurl")
                }
            }
            return SendDestination()
        }
        /** Bound untrusted decimal work before scaling; keep native platforms within exact Decimal precision. */
        private fun uriAmount(value: String): BigDecimal? {
            if (value.length > 96 || !Regex("^[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,2})?$").matches(value)) return null
            val parts = value.lowercase().split('e')
            val mantissa = parts[0].removePrefix("+").split('.', limit = 2)
            val integer = mantissa[0].trimStart('0').ifEmpty { "0" }
            val fraction = mantissa.getOrElse(1) { "" }.trimEnd('0')
            val exponent = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val scale = fraction.length - exponent
            if ((integer + fraction).trimStart('0').length > 32 || exponent !in -32..32 || scale !in -32..32) return null
            val normalized = integer + (if (fraction.isEmpty()) "" else ".$fraction") + "e$exponent"
            return try {
                val btc = normalized.toBigDecimalOrNull() ?: return null
                if (btc < BigDecimal.ZERO || btc > BigDecimal(21000000)) null
                // RN parses a JS number, then uses Math.round(btc * 100_000_000).
                else BigDecimal.valueOf(Math.round(normalized.toDouble() * 100_000_000))
            } catch (_: ArithmeticException) { null }
        }
        private fun uri(value: String, network: String): SendDestination {
            val parts = value.drop(8).split('?', limit = 2); val methods = linkedMapOf<String, String>()
            val errors = mutableListOf<SendDestinationError>(); val seen = mutableSetOf<String>(); var amount: BigDecimal? = null; var invalid = false
            fun add(raw: String, type: String) {
                val parsed = parse(raw, network)
                if (parsed.destinationType == type) methods[mapOf("ark" to "arkAddress", "lightning" to "lightningInvoice", "offer" to "offer", "onchain" to "onchainAddress").getValue(type)] = raw
                else if (parsed.error is SendDestinationError.NetworkMismatch) { errors.add(parsed.error); invalid = true }
                else if (type == "offer") errors.add(SendDestinationError.InvalidOffer)
            }
            if (parts[0].isNotEmpty()) {
                if (PaymentEncoding.bitcoinNetwork(parts[0]) == null) { errors.add(SendDestinationError.InvalidBitcoinAddress); invalid = true } else add(parts[0], "onchain")
            }
            if (parts.size > 1) for (pair in parts[1].split('&')) {
                val fields = pair.split('=', limit = 2)
                // URI query decoding preserves literal '+', unlike form decoding.
                val key = runCatching { URLDecoder.decode(fields[0].replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return SendDestination(error = SendDestinationError.InvalidURI)
                val decoded = runCatching { URLDecoder.decode(fields.getOrElse(1) { "" }.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return SendDestination(error = SendDestinationError.InvalidURI)
                val lower = key.lowercase()
                if (lower in listOf("amount", "label", "message") && !seen.add(lower)) { errors.add(SendDestinationError.DuplicateParameter(lower)); invalid = true; continue }
                when (lower) {
                    "amount" -> {
                        val sats = uriAmount(decoded)
                        if (sats != null) amount = sats
                        else { errors.add(SendDestinationError.InvalidAmount); invalid = true }
                    }
                    "ark" -> add(decoded, "ark")
                    "lightning" -> add(decoded, "lightning")
                    "lno" -> add(decoded, "offer")
                    "bc", "tb", "tbs", "bcrt" -> {
                        val actual = PaymentEncoding.bitcoinNetwork(decoded); val expected = mapOf("bc" to "mainnet", "tb" to "testnet", "tbs" to "signet", "bcrt" to "regtest").getValue(lower)
                        if (actual != null && matches(actual, expected, "onchain")) add(decoded, "onchain") else { errors.add(SendDestinationError.AddressNetworkMismatch(lower)); invalid = true }
                    }
                    "label", "message", "pop" -> Unit
                    else -> if (lower.startsWith("req-")) { errors.add(SendDestinationError.UnknownRequiredParameter(key)); invalid = true }
                }
            }
            if (invalid || methods.isEmpty()) return SendDestination(error = if (errors.isEmpty()) SendDestinationError.NoPaymentMethods else SendDestinationError.InvalidMethods(errors.toList()))
            return SendDestination("bip321", amount?.takeIf { it > BigDecimal.ZERO }, amount == null || amount == BigDecimal.ZERO, bip321 = methods)
        }
    }
}

/** Identifiers and arguments survive language changes; rendering happens at the UI boundary. */
sealed class SendDestinationError : Exception() {
    data class NetworkMismatch(val expected: String, val actual: String) : SendDestinationError()
    data object SubSatoshiInvoice : SendDestinationError()
    data object InvalidOffer : SendDestinationError()
    data object InvalidBitcoinAddress : SendDestinationError()
    data object InvalidURI : SendDestinationError()
    data object InvalidAmount : SendDestinationError()
    data object NoPaymentMethods : SendDestinationError()
    data class DuplicateParameter(val key: String) : SendDestinationError()
    data class AddressNetworkMismatch(val key: String) : SendDestinationError()
    data class UnknownRequiredParameter(val key: String) : SendDestinationError()
    data class InvalidMethods(val errors: List<SendDestinationError>) : SendDestinationError()

    private val template: Pair<String, List<String>> get() = when (this) {
        is NetworkMismatch -> "Network mismatch: expected {expected}, got {actual}" to listOf(expected, actual)
        SubSatoshiInvoice -> "Invoice amount is less than 1 satoshi." to emptyList()
        InvalidOffer -> "Invalid BOLT12 offer" to emptyList()
        InvalidBitcoinAddress -> "Invalid bitcoin address" to emptyList()
        InvalidURI -> "Invalid BIP-321 URI" to emptyList()
        InvalidAmount -> "Invalid amount format" to emptyList()
        NoPaymentMethods -> "No valid payment methods found" to emptyList()
        is DuplicateParameter -> "Multiple {parameter} parameters not allowed" to listOf(key)
        is AddressNetworkMismatch -> "Address network mismatch for {parameter} parameter" to listOf(key)
        is UnknownRequiredParameter -> "Unknown required parameter: {key}" to listOf(key)
        is InvalidMethods -> error("Combined errors render their children")
    }
    override val message: String get() {
        if (this is InvalidMethods) return errors.joinToString(", ") { it.message }
        val (source, args) = template
        var index = 0
        // Android's ICU regex needs the closing brace escaped too (the JVM's doesn't).
        return Regex("\\{\\w+\\}").replace(source) { args[index++] }
    }
    fun localized(context: android.content.Context): String {
        if (this is InvalidMethods) return errors.joinToString(", ") { it.localized(context) }
        val (source, args) = template
        return com.noahwallet.localization.L10n.text(context, source, *args.toTypedArray())
    }
}
