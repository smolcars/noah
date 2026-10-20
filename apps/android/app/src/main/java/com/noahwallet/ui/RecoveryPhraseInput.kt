package com.noahwallet.ui

import java.text.Normalizer
import java.util.Locale
import java.security.MessageDigest

/** Local-only BIP39 English input. Never put this object in saved state or logs. */
class RecoveryPhraseInput {
    private val entries = MutableList(12) { "" }
    val words: List<String> get() = entries.toList()
    var wrongWordCount = false; private set
    val wordCount get() = entries.count { it.isNotEmpty() }
    val isComplete get() = wordCount == 12 && !wrongWordCount
    val phrase get() = entries.joinToString(" ")

    fun update(text: String, index: Int) {
        val incoming = normalizedWords(text)
        if (incoming.size > 1) {
            if (incoming.size != 12) { wrongWordCount = true; return }
            incoming.forEachIndexed { i, word -> entries[i] = word }
        } else entries[index] = incoming.firstOrNull().orEmpty()
        wrongWordCount = false
    }
    fun suggestions(index: Int): List<String> {
        val prefix = entries[index]
        return if (prefix.isEmpty() || prefix in wordList) emptyList()
        else wordList.filter { it.startsWith(prefix) }.take(5)
    }
    fun clear() { entries.fill(""); wrongWordCount = false }
    override fun toString() = "RecoveryPhraseInput(<redacted>)"

    companion object {
        // Standard bitcoin/bips bip-0039/english.txt, also bundled by the iOS app.
        val wordList: List<String> by lazy {
            checkNotNull(RecoveryPhraseInput::class.java.getResourceAsStream("/bip39-english.txt"))
                .bufferedReader().use { it.readLines().filter(String::isNotEmpty) }.also { check(it.size == 2048) }
        }
        fun normalizedWords(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT).split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)

        fun isValid(phrase: String): Boolean {
            val words = normalizedWords(phrase)
            if (words.size != 12) return false
            val indices = words.map { wordList.binarySearch(it) }
            if (indices.any { it < 0 }) return false
            val entropy = ByteArray(16)
            for (bit in 0 until 128) {
                val value = (indices[bit / 11] shr (10 - bit % 11)) and 1
                entropy[bit / 8] = (entropy[bit / 8].toInt() or (value shl (7 - bit % 8))).toByte()
            }
            return try {
                val checksum = (MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 255) shr 4
                (indices.last() and 15) == checksum
            }
            finally { entropy.fill(0) }
        }
    }
}
