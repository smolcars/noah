package com.noahwallet.onchain

import java.security.MessageDigest

/**
 * Reads a raw Bitcoin transaction: its weight (for a package's fee), its txid (to know a claim's outputs before it's
 * broadcast) and the shape the exit picture needs (which output the next transaction spends, how many branches).
 */
internal object ExitTransactions {
    data class Parsed(val txid: String, val weight: Long, val inputs: List<Pair<String, Long>>, val outputs: Int)

    /** bark's canonical CPFP child (estimate.rs `canonical_cpfp_child_weight`): P2A and P2TR key-spend in, P2TR out. */
    const val canonicalChildWeight = 609L

    /** A package's fee at [satPerKwu] for a zero-fee parent of [parentWeight] (bark's `package_fee`, rounded up). */
    fun packageFee(parentWeight: Long, satPerKwu: Long, childWeight: Long = canonicalChildWeight) =
        (satPerKwu * (parentWeight + childWeight) + 999) / 1000

    fun parse(hex: String): Parsed? = try { read(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) } catch (_: Exception) { null }

    private fun read(bytes: ByteArray): Parsed {
        var at = 0
        fun take(n: Int): ByteArray { require(n >= 0 && at + n <= bytes.size); return bytes.copyOfRange(at, at + n).also { at += n } }
        fun little(n: Int) = take(n).foldIndexed(0L) { i, sum, b -> sum or ((b.toLong() and 0xff) shl (8 * i)) }
        fun varint(): Long = when (val first = little(1)) { 0xfdL -> little(2); 0xfeL -> little(4); 0xffL -> little(8); else -> first }
        val base = java.io.ByteArrayOutputStream()
        base.write(take(4))
        val segwit = bytes.size > 6 && bytes[4] == 0.toByte() && bytes[5] != 0.toByte()
        if (segwit) at += 2
        var mark = at
        val inputs = (0 until varint()).map {
            val txid = take(32).reversedArray().joinToString("") { "%02x".format(it) }
            val vout = little(4); take(varint().toInt()); take(4)
            txid to vout
        }
        val outputs = varint().toInt()
        repeat(outputs) { take(8); take(varint().toInt()) }
        base.write(bytes, mark, at - mark)
        if (segwit) repeat(inputs.size) { repeat(varint().toInt()) { take(varint().toInt()) } }
        mark = at
        take(4)
        base.write(bytes, mark, 4)
        require(at == bytes.size)
        val stripped = base.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256")
        val txid = sha.digest(sha.digest(stripped)).reversedArray().joinToString("") { "%02x".format(it) }
        return Parsed(txid, stripped.size * 3L + bytes.size, inputs, outputs)
    }
}
