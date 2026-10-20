package com.noahwallet.services.logging

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Register before entering Rust: endpoint paths can be opaque credentials. */
internal object EndpointLogPrivacy {
    private val origins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val paths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val urls = Regex("""(?i)\bhttps?://[^\s<>"']*""")
    // Admit only the SDK error type/status, never provider-controlled response text.
    private val responseStatus = Regex("""HttpResponse(?:\s*\{\s*status:\s*|\()([0-9]{3})\b""")
    private fun origin(value: String): String? = value.toHttpUrlOrNull()?.newBuilder()
        ?.encodedPath("/")?.query(null)?.fragment(null)?.username("")?.password("")?.build()?.toString()?.removeSuffix("/")
    fun register(endpoint: String?) {
        val url = endpoint?.toHttpUrlOrNull() ?: return
        for (path in LogFile.decodedViews(url.encodedPath, percentEncoded = true) ?: listOf(url.encodedPath)) {
            if (path != "/") paths.add(path.trimEnd('/'))
        }
        origin(endpoint)?.let(origins::add)
    }
    fun redact(text: String): String {
        // Exported lines include a bounded tag and timestamp as well as the message.
        if (text.toByteArray(Charsets.UTF_8).size > 2 * 16 * 1024 + 128) return "[oversized log message omitted]"
        val views = LogFile.decodedViews(text, percentEncoded = true) ?: return "[redacted]"
        for (view in views) {
            responseStatus.find(view)?.let { return "HttpResponse { status: ${it.groupValues[1]} }" }
        }
        if (origins.isEmpty()) return text
        var result = text
        for ((index, view) in views.withIndex()) {
            val clean = urls.replace(view) { match -> if (origin(match.value)?.let { it in origins } == true) "[custom-esplora]" else match.value }
            // Inspect relative references separately: another origin never inherits our paths.
            val relative = urls.replace(clean, "")
            if (paths.any { relative.contains(it) }) return "[custom-esplora]"
            if (index > 0 && clean != view) return "[custom-esplora]"
            if (index == 0) result = clean
        }
        return result
    }
}
