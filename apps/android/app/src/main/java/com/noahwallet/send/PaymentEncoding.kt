package com.noahwallet.send

import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest

/** Checksum and amount decoding; Bark verifies payment semantics and signatures. */
object PaymentEncoding {
    private const val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    data class Bech32(val prefix: String, val words: List<Int>, val isM: Boolean)
    fun bech32(value: String): Bech32? {
        if (value != value.lowercase() && value != value.uppercase()) return null
        val lower = value.lowercase(); val split = lower.lastIndexOf('1')
        if (split < 1) return null
        val prefix = lower.take(split); val encoded = lower.drop(split + 1)
        if (encoded.length !in 6..8000 || prefix.any { it.code !in 33..126 }) return null
        val words = encoded.map { alphabet.indexOf(it) }
        if (words.any { it < 0 }) return null
        val check = polymod(prefix, words)
        if (check != 1 && check != 0x2bc830a3) return null
        return Bech32(prefix, words.dropLast(6), check != 1)
    }
    /** Bech32 (not bech32m), lowercase: how an `lnurl1…` carries its URL. */
    fun encodeBech32(prefix: String, data: ByteArray): String {
        val words = ArrayList<Int>(); var accumulator = 0; var bits = 0
        for (byte in data) {
            accumulator = (accumulator shl 8) or (byte.toInt() and 255); bits += 8
            while (bits >= 5) { bits -= 5; words.add((accumulator shr bits) and 31) }
            accumulator = accumulator and ((1 shl bits) - 1)
        }
        if (bits > 0) words.add((accumulator shl (5 - bits)) and 31)
        val check = polymod(prefix, words + List(6) { 0 }) xor 1
        return prefix + "1" + (words + List(6) { (check shr (5 * (5 - it))) and 31 }).joinToString("") { alphabet[it].toString() }
    }
    private fun polymod(prefix: String, words: List<Int>): Int {
        var check = 1
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        for (word in prefix.map { it.code shr 5 } + 0 + prefix.map { it.code and 31 } + words) {
            val top = check ushr 25
            check = ((check and 0x1ffffff) shl 5) xor word
            for (i in 0..4) if ((top shr i) and 1 != 0) check = check xor generators[i]
        }
        return check
    }
    fun bytes(words: List<Int>): ByteArray? {
        var accumulator = 0; var bits = 0; val output = ArrayList<Byte>()
        for (word in words) {
            accumulator = ((accumulator shl 5) or word) and 0xffffff; bits += 5
            if (bits >= 8) { bits -= 8; output.add(((accumulator shr bits) and 255).toByte()) }
        }
        if (bits >= 5 || (accumulator shl (8 - bits)) and 255 != 0) return null
        return output.toByteArray()
    }
    private fun base58(value: String): ByteArray? {
        if (value.isEmpty() || value.length > 60) return null
        var number = BigInteger.ZERO
        for (char in value) {
            val index = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".indexOf(char)
            if (index < 0) return null
            number = number * BigInteger.valueOf(58) + BigInteger.valueOf(index.toLong())
        }
        val decoded = ByteArray(value.takeWhile { it == '1' }.length) + number.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        if (decoded.size != 25 || !decoded.takeLast(4).toByteArray().contentEquals(sha256(sha256(decoded.copyOfRange(0, 21))).copyOf(4))) return null
        return decoded.copyOfRange(0, 21)
    }
    fun bitcoinNetwork(value: String): String? {
        val decoded = bech32(value)
        if (decoded != null && value.length <= 90) {
            val version = decoded.words.firstOrNull() ?: return null
            val program = bytes(decoded.words.drop(1)) ?: return null
            if (version !in 0..16 || program.size !in 2..40 ||
                (if (version == 0) decoded.isM || program.size !in listOf(20, 32) else !decoded.isM)) return null
            return mapOf("bc" to "mainnet", "tb" to "testnet", "bcrt" to "regtest")[decoded.prefix]
        }
        return when (base58(value)?.first()?.toInt()?.and(255)) { 0, 5 -> "mainnet"; 111, 196 -> "testnet"; else -> null }
    }
    fun bitcoinScript(value: String): ByteArray? {
        if (bitcoinNetwork(value) == null) return null
        bech32(value)?.let {
            val version = it.words.first(); val program = bytes(it.words.drop(1)) ?: return null
            return byteArrayOf((if (version == 0) 0 else 0x50 + version).toByte(), program.size.toByte()) + program
        }
        val decoded = base58(value) ?: return null
        return if (decoded[0].toInt() and 255 in listOf(0, 111)) byteArrayOf(0x76, 0xa9.toByte(), 0x14) + decoded.drop(1).toByteArray() + byteArrayOf(0x88.toByte(), 0xac.toByte())
        else byteArrayOf(0xa9.toByte(), 0x14) + decoded.drop(1).toByteArray() + byteArrayOf(0x87.toByte())
    }
    data class Invoice(val network: String, val sats: BigDecimal?, val hash: String, val expires: Long, val descriptionHash: String?,
        val description: String? = null)
    fun invoice(value: String): Invoice? {
        val decoded = bech32(value) ?: return null
        if (decoded.isM || decoded.words.size < 111) return null
        val networkPrefix = listOf("lnbcrt", "lntbs", "lnbc", "lntb").firstOrNull { decoded.prefix.startsWith(it) } ?: return null
        val hrp = decoded.prefix.removePrefix(networkPrefix)
        var amount: BigDecimal? = null
        if (hrp.isNotEmpty()) {
            val unit = hrp.last(); val suffix = unit in "munp"; val digits = if (suffix) hrp.dropLast(1) else hrp
            if (digits.isEmpty() || digits.any { it !in '0'..'9' } || (unit == 'p' && !digits.endsWith('0'))) return null
            amount = digits.toBigDecimalOrNull()?.multiply(if (suffix) mapOf('m' to "100000", 'u' to "100", 'n' to "0.1", 'p' to "0.0001").getValue(unit).toBigDecimal() else BigDecimal(100000000)) ?: return null
        }
        val payload = decoded.words.dropLast(104)
        var index = 7; var hash: String? = null; var descriptionHash: String? = null; var expiry = 3600L; var description: String? = null
        while (index + 3 <= payload.size) {
            val tag = payload[index]; val count = payload[index + 1] * 32 + payload[index + 2]; index += 3
            if (index + count > payload.size) return null
            val data = payload.subList(index, index + count); index += count
            if (tag == 1 || tag == 23) bytes(data)?.takeIf { it.size == 32 }?.let { if (tag == 1) hash = hex(it) else descriptionHash = hex(it) }
            if (tag == 6) { if (data.size > 12) return null; expiry = data.fold(0L) { acc, x -> acc * 32 + x } }
            if (tag == 13) description = utf8(data)
        }
        if (hash == null || index != payload.size) return null
        val timestamp = payload.take(7).fold(0L) { acc, x -> acc * 32 + x }
        return Invoice(mapOf("lnbc" to "mainnet", "lntb" to "testnet", "lntbs" to "signet", "lnbcrt" to "regtest").getValue(networkPrefix), amount, hash!!, timestamp + expiry, descriptionHash, description)
    }
    /** A BOLT12 offer's `offer_description` (TLV 10): bech32 words without a checksum, `+` joins split parts. */
    fun offerDescription(offer: String): String? {
        val value = offer.replace(Regex("\\+\\s*"), "").lowercase()
        if (!value.startsWith("lno1")) return null
        val words = value.drop(4).map { alphabet.indexOf(it) }.takeIf { it.none { word -> word < 0 } } ?: return null
        val tlv = bytes(words) ?: return null
        var index = 0
        fun bigSize(): Long? {
            val first = tlv.getOrNull(index++)?.toInt()?.and(255) ?: return null
            val width = when (first) { 0xfd -> 2; 0xfe -> 4; 0xff -> 8; else -> return first.toLong() }
            if (index + width > tlv.size) return null
            return (0 until width).fold(0L) { acc, i -> (acc shl 8) or (tlv[index + i].toLong() and 255) }.also { index += width }
        }
        while (index < tlv.size) {
            val type = bigSize() ?: return null; val length = bigSize() ?: return null
            if (length < 0 || length > tlv.size - index) return null
            if (type == 10L) return utf8(tlv.copyOfRange(index, index + length.toInt()))
            index += length.toInt()
        }
        return null
    }
    private fun utf8(words: List<Int>) = bytes(words)?.let(::utf8)
    private fun utf8(data: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString()
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
