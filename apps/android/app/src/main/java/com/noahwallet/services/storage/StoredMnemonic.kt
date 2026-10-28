package com.noahwallet.services.storage

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** RN stores the input verbatim; bark parses English BIP39 with normalized whitespace/case. */
object StoredMnemonic {
    val wordList: List<String> by lazy {
        checkNotNull(StoredMnemonic::class.java.getResourceAsStream("/bip39-english.txt"))
            .bufferedReader().use { it.readLines().filter(String::isNotEmpty) }.also { check(it.size == 2048) }
    }
    private val separators =
        Regex("[\\x{0009}-\\x{000D}\\x{0020}\\x{0085}\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}]+")
    fun words(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT).split(separators).filter(String::isNotEmpty)

    fun normalize(value: String): String? {
        val words = words(value)
        if (words.size != 12 && words.size != 24) return null
        val indices = words.map { wordList.binarySearch(it) }
        if (indices.any { it < 0 }) return null
        val checksumBits = words.size / 3
        val entropyBits = words.size * 11 - checksumBits
        val entropy = ByteArray(entropyBits / 8)
        for (bit in 0 until entropyBits) {
            val value = (indices[bit / 11] shr (10 - bit % 11)) and 1
            entropy[bit / 8] = (entropy[bit / 8].toInt() or (value shl (7 - bit % 8))).toByte()
        }
        return try {
            val checksum = (MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 255) shr (8 - checksumBits)
            if ((indices.last() and ((1 shl checksumBits) - 1)) == checksum) words.joinToString(" ") else null
        } finally { entropy.fill(0) }
    }

    // Backup encryption uses the exact RN credential string; validation must not rewrite it.
    fun validate(result: SecretResult): SecretResult = when (result) {
        is SecretResult.Found -> normalize(result.value)?.let { SecretResult.Found(result.value) }
            ?: SecretResult.Unavailable(SecretFailure.invalidMnemonic)
        else -> result
    }

    /** An unreadable or disagreeing source blocks all use; absence alone permits another source. */
    fun agree(sources: List<SecretResult>): SecretResult {
        sources.filterIsInstance<SecretResult.Unavailable>().firstOrNull()?.let { return it }
        val found = sources.filterIsInstance<SecretResult.Found>()
        if (found.map { it.value }.distinct().size > 1) return SecretResult.Unavailable(SecretFailure.seedConflict)
        return found.firstOrNull() ?: SecretResult.NotFound
    }
}
