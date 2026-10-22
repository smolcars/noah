package com.noahwallet.ui

import java.security.MessageDigest

/** Retain only a digest, never another copy of the pasted recovery phrase. */
internal class RestoreClipboard(private val read: () -> String?, private val clear: () -> Unit) {
    private var accepted: ByteArray? = null
    /** The Paste button's read; the text is never kept. */
    fun text(): String? = read()
    fun accepted(text: String) { accepted = digest(text); clearIfUnchanged() }
    fun clearIfUnchanged() {
        val expected = accepted ?: return
        val current = read() ?: return
        if (MessageDigest.isEqual(expected, digest(current))) clear()
    }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
}
