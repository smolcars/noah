package com.noahwallet.ui

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
        val wordList get() = com.noahwallet.services.storage.StoredMnemonic.wordList
        fun normalizedWords(text: String) = com.noahwallet.services.storage.StoredMnemonic.words(text)
        fun isValid(phrase: String) = normalizedWords(phrase).size == 12 &&
            com.noahwallet.services.storage.StoredMnemonic.normalize(phrase) != null
    }
}
