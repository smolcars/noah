package com.noahwallet.ui

/** Local-only BIP39 English input. Never put this object in saved state or logs. */
class RecoveryPhraseInput {
    private val entries = MutableList(12) { "" }
    val words: List<String> get() = entries.toList()
    var wrongWordCount = false; private set
    val wordCount get() = entries.count { it.isNotEmpty() }
    val isComplete get() = wordCount == entries.size && !wrongWordCount
    val phrase get() = entries.joinToString(" ")

    fun update(text: String, index: Int) {
        val incoming = normalizedWords(text)
        if (incoming.size > 1) {
            if (incoming.size !in listOf(12, 24)) { wrongWordCount = true; return }
            entries.clear(); entries.addAll(incoming)
        } else if (index in entries.indices) entries[index] = incoming.firstOrNull().orEmpty()
        wrongWordCount = false
    }
    fun selectWordCount(count: Int) {
        require(count in listOf(12, 24))
        entries.clear(); entries.addAll(List(count) { "" }); wrongWordCount = false
    }
    fun suggestions(index: Int): List<String> {
        val prefix = entries.getOrNull(index) ?: return emptyList()
        return if (prefix.isEmpty() || prefix in wordList) emptyList()
        else wordList.filter { it.startsWith(prefix) }.take(5)
    }
    fun clear() { selectWordCount(12) }
    override fun toString() = "RecoveryPhraseInput(<redacted>)"

    companion object {
        val wordList get() = com.noahwallet.services.storage.StoredMnemonic.wordList
        fun normalizedWords(text: String) = com.noahwallet.services.storage.StoredMnemonic.words(text)
        fun isValid(phrase: String) = normalizedWords(phrase).size in listOf(12, 24) &&
            com.noahwallet.services.storage.StoredMnemonic.normalize(phrase) != null
    }
}
