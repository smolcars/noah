package com.noahwallet.send

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder

// Mirrors iOS SendDestination; pure so RN-generated fixtures run without JNI.
data class SendDestination(val destinationType: String? = null, val amount: BigDecimal? = null,
    val isAmountEditable: Boolean = true, val error: SendDestinationError? = null, val bip321: Map<String, String>? = null) {
    companion object {
        fun cleaned(value: String): String {
            val trimmed = value.trim()
            lud17(trimmed)?.let { return it }
            val payload = if (trimmed.startsWith("lightning:", true)) trimmed.drop(10) else trimmed
            val address = payload.trimStart('₿')
            return if (address != payload && isLightningAddress(address)) address else payload
        }
        /** LUD-17 `lnurlp://` mapped to HTTPS as bark maps it, then carried as the `lnurl1…` it stands for, so its URL keeps
         *  its case through Send's lowercased keys. A `.onion` host would need cleartext, so it stays unmapped (refused). */
        private fun lud17(value: String): String? {
            if (!value.startsWith("lnurlp://", true)) return null
            val rest = value.drop(9)
            val url = if (rest.startsWith('/') || rest.startsWith('\\')) null else "https://$rest".toHttpUrlOrNull()
            if (url == null || !allowsLnurl(url)) return null
            return PaymentEncoding.encodeBech32("lnurl", url.toString().toByteArray()).takeIf { it.length <= 1023 }
        }
        /** What Noah's LNURL client may request: HTTPS, no userinfo, and never a `.onion` host (that would need cleartext).
         *  Checked right before every request, and when parsing, so such a link is no destination. */
        fun allowsLnurl(url: HttpUrl?) = url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && !isOnion(url.host)
        /** HttpUrl hosts are already normalised (lower case, IDNA); a trailing dot is ignored. */
        private fun isOnion(host: String) = host.trimEnd('.').endsWith(".onion")
        /** A Lightning address's endpoint. The name is one path segment, whatever it contains; null when the domain isn't a host. */
        fun addressEndpoint(value: String): HttpUrl? {
            val at = value.lastIndexOf('@')
            return runCatching {
                HttpUrl.Builder().scheme("https").host(value.substring(at + 1)).addPathSegments(".well-known/lnurlp").addPathSegment(value.substring(0, at))
                    .build()
            }.getOrNull()
        }
        /** bark's rule: lnurl-rs 0.10's `LightningAddress` is email_address 0.2.5 with default options, so quoted local parts
         *  and domain literals count too. Lengths are UTF-8 bytes, as in Rust. Resolution stays HTTPS-only. */
        fun isLightningAddress(value: String): Boolean {
            val at = value.lastIndexOf('@')
            if (at < 0) return false
            return validLocal(value.substring(0, at)) && validDomain(value.substring(at + 1))
        }
        private fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size
        // Rust's char::is_alphanumeric: Alphabetic, or a number of any kind.
        private fun alphanumeric(c: Int) = Character.isAlphabetic(c) || Character.isDigit(c) || Character.getType(c) == Character.OTHER_NUMBER.toInt()
        private fun atom(text: String) =
            text.isNotEmpty() && text.codePoints().allMatch { it >= 0x80 || alphanumeric(it) || it.toChar() in "!#$%&'*+-/=?^_`{|}~" }
        private fun quotedText(c: Int) = c == 0x20 || c == 0x09 || c == 0x21 || c in 0x23..0x5b || c in 0x5d..0x7e || c >= 0x80
        private fun quoted(text: String): Boolean {
            val chars = text.codePoints().toArray(); var i = 0
            while (i < chars.size) {
                val c = chars[i++]
                val valid = if (c == '\\'.code) i < chars.size && chars[i++] in 0x21..0x7e else quotedText(c)
                if (!valid) return false
            }
            return true
        }
        private fun validLocal(local: String) = when {
            local.isEmpty() || utf8Size(local) > 64 -> false
            local.startsWith('"') && local.endsWith('"') -> local.length > 2 && quoted(local.substring(1, local.length - 1))
            else -> local.split('.').all(::atom)
        }
        private fun validDomain(domain: String) = when {
            domain.isEmpty() || utf8Size(domain) > 254 -> false
            domain.startsWith('[') && domain.endsWith(']') -> domain.substring(1, domain.length - 1).all { it.code in 0x21..0x5a || it.code in 0x5e..0x7e }
            else -> domain.split('.').all(::validLabel)
        }
        private fun validLabel(label: String) = label.isNotEmpty() && alphanumeric(label.codePointAt(0)) &&
            alphanumeric(label.codePointBefore(label.length)) && utf8Size(label) <= 63 && atom(label)
        fun offer(value: String): Boolean {
            val chunks = value.split('+'); val normalized = chunks.mapIndexed { index, s -> if (index == 0) s else s.trimStart() }.joinToString("")
            return chunks.all { it.isNotBlank() } && (normalized == normalized.lowercase() || normalized == normalized.uppercase()) &&
                Regex("^lno1[02-9ac-hj-np-z]+$", RegexOption.IGNORE_CASE).matches(normalized)
        }
        fun matches(actual: String, expected: String, type: String) = actual == expected ||
            (type == "ark" && actual == "testnet" && expected != "mainnet") || (type == "onchain" && actual == "testnet" && expected == "signet")
        private fun mismatch(actual: String, expected: String) = SendDestination(error = SendDestinationError.NetworkMismatch(expected, actual))
        fun parse(input: String, network: String): SendDestination {
            if (input.startsWith("bitcoin:", true)) return uri(input, network)
            val value = cleaned(input)
            // Like an `lnurlp://` one, a `.onion` domain would need cleartext, so it is no destination.
            if (isLightningAddress(value)) return if (addressEndpoint(value)?.host?.let(::isOnion) == true) SendDestination() else SendDestination("lnurl")
            return PaymentEncoding.invoice(value)?.let { invoiceDestination(it, network) } ?: otherDestination(value, network)
        }
        private fun invoiceDestination(invoice: PaymentEncoding.Invoice, network: String): SendDestination {
            if (!matches(invoice.network, network, "lightning")) return mismatch(invoice.network, network)
            if (invoice.sats != null && invoice.sats > BigDecimal.ZERO && invoice.sats < BigDecimal.ONE) {
                return SendDestination("lightning", error = SendDestinationError.SubSatoshiInvoice)
            }
            return SendDestination("lightning", invoice.sats?.takeIf { it >= BigDecimal.ONE }, (invoice.sats ?: BigDecimal.ZERO) < BigDecimal.ONE)
        }
        private fun otherDestination(value: String, network: String): SendDestination {
            if (offer(value)) return SendDestination("offer")
            PaymentEncoding.bitcoinNetwork(value)?.let { return if (matches(it, network, "onchain")) SendDestination("onchain") else mismatch(it, network) }
            return PaymentEncoding.bech32(value)?.let { bech32Destination(it, network) } ?: SendDestination()
        }
        private fun bech32Destination(decoded: PaymentEncoding.Bech32, network: String): SendDestination? {
            if (decoded.isM && decoded.prefix in listOf("ark", "tark")) {
                val actual = if (decoded.prefix == "ark") "mainnet" else "testnet"
                return if (matches(actual, network, "ark")) SendDestination("ark") else mismatch(actual, network)
            }
            return if (decoded.prefix == "lnurl" && !decoded.isM) lnurlDestination(decoded) else null
        }
        private fun lnurlDestination(decoded: PaymentEncoding.Bech32): SendDestination? {
            val text = PaymentEncoding.bytes(decoded.words)?.toString(Charsets.UTF_8)
            val url = text?.let { runCatching { URI(it) }.getOrNull() }
            // LNURL-auth logs in rather than pays: refuse it here instead of at the fetch. The query is read decoded,
            // as the LNURL client's HttpUrl reads it, so encoding or case can't hide `tag=login`.
            val http = text?.toHttpUrlOrNull()
            val login = http?.let { query -> (0 until query.querySize).any {
                query.queryParameterName(it).equals("tag", true) && query.queryParameterValue(it).equals("login", true) } } == true
            if (url?.scheme != "https" || url.host == null || !allowsLnurl(http)) return null
            return if (login) SendDestination(error = SendDestinationError.LnurlAuth) else SendDestination("lnurl")
        }
        /** Bound untrusted decimal work before scaling; keep native platforms within exact Decimal precision. */
        internal fun uriAmount(value: String): BigDecimal? {
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
            val parts = value.drop(8).split('?', limit = 2)
            val request = UriRequest(network)
            if (parts[0].isNotEmpty()) request.address(parts[0])
            if (parts.size > 1) for (pair in parts[1].split('&')) {
                val fields = pair.split('=', limit = 2)
                val key = uriDecoded(fields[0]) ?: return SendDestination(error = SendDestinationError.InvalidURI)
                val decoded = uriDecoded(fields.getOrElse(1) { "" }) ?: return SendDestination(error = SendDestinationError.InvalidURI)
                request.parameter(key, decoded)
            }
            return request.destination()
        }
        // URI query decoding preserves literal '+', unlike form decoding.
        private fun uriDecoded(text: String) = runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrNull()
    }
}

/** A BIP-321 URI's payment methods, amount and errors, read in order. */
private class UriRequest(private val network: String) {
    private val methods = linkedMapOf<String, String>()
    private val errors = mutableListOf<SendDestinationError>()
    private val seen = mutableSetOf<String>()
    private var amount: BigDecimal? = null
    private var invalid = false

    private fun fail(error: SendDestinationError) { errors.add(error); invalid = true }
    fun address(raw: String) {
        if (PaymentEncoding.bitcoinNetwork(raw) == null) fail(SendDestinationError.InvalidBitcoinAddress) else add(raw, "onchain")
    }
    private fun add(raw: String, type: String) {
        val parsed = SendDestination.parse(raw, network)
        if (parsed.destinationType == type) methods[mapOf("ark" to "arkAddress", "lightning" to "lightningInvoice", "offer" to "offer",
            "onchain" to "onchainAddress").getValue(type)] = raw
        else if (parsed.error is SendDestinationError.NetworkMismatch) fail(parsed.error)
        else if (type == "offer") errors.add(SendDestinationError.InvalidOffer)
    }
    fun parameter(key: String, decoded: String) {
        // BIP-321 `req-` parameters must be understood or refused; Send understands these two.
        val lower = key.lowercase().let { if (it == "req-ark" || it == "req-amount") it.drop(4) else it }
        if (lower in listOf("amount", "label", "message") && !seen.add(lower)) { fail(SendDestinationError.DuplicateParameter(lower)); return }
        when (lower) {
            "amount" -> amountParameter(decoded)
            "ark" -> add(decoded, "ark")
            "lightning" -> add(decoded, "lightning")
            "lno" -> add(decoded, "offer")
            "bc", "tb", "tbs", "bcrt" -> addressParameter(lower, decoded)
            "label", "message", "pop" -> Unit
            else -> if (lower.startsWith("req-")) fail(SendDestinationError.UnknownRequiredParameter(key))
        }
    }
    private fun amountParameter(decoded: String) {
        val sats = SendDestination.uriAmount(decoded)
        if (sats != null) amount = sats
        else fail(SendDestinationError.InvalidAmount)
    }
    /** A BIP-321 address parameter named for its network (`bc`, `tb`, `tbs`, `bcrt`). */
    private fun addressParameter(name: String, decoded: String) {
        val actual = PaymentEncoding.bitcoinNetwork(decoded)
        val expected = mapOf("bc" to "mainnet", "tb" to "testnet", "tbs" to "signet", "bcrt" to "regtest").getValue(name)
        if (actual != null && SendDestination.matches(actual, expected, "onchain")) add(decoded, "onchain")
        else fail(SendDestinationError.AddressNetworkMismatch(name))
    }
    fun destination(): SendDestination {
        if (invalid || methods.isEmpty()) {
            val error = if (errors.isEmpty()) SendDestinationError.NoPaymentMethods else SendDestinationError.InvalidMethods(errors.toList())
            return SendDestination(error = error)
        }
        return SendDestination("bip321", amount?.takeIf { it > BigDecimal.ZERO }, amount == null || amount == BigDecimal.ZERO, bip321 = methods)
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
    data object LnurlAuth : SendDestinationError()

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
        LnurlAuth -> "This is a login link, not a payment request" to emptyList()
        is InvalidMethods -> error("""Combined errors render their children""")
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
        @Suppress("SpreadOperator") // L10n.text is vararg; a template has at most two arguments
        return com.noahwallet.localization.L10n.text(context, source, *args.toTypedArray())
    }
}
