package com.noahwallet.services.logging

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Register before entering Rust: endpoint paths can be opaque credentials. */
internal object EndpointLogPrivacy {
    @Volatile private var unavailable = false
    fun failClosed() { unavailable = true }
    private val origins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val paths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val urls = Regex("""(?i)https?://[^\s<>"']*""")
    // Admit only the SDK error type/status, never provider-controlled response text.
    private val responseStatus = Regex("""HttpResponse(?:\s*\{\s*status:\s*|\()([0-9]{3})\b""")
    private fun origin(value: String): String? = value.toHttpUrlOrNull()?.let { url ->
        url.newBuilder().host(url.host.trimEnd('.').ifEmpty { url.host }).encodedPath("/").query(null).fragment(null)
            .username("").password("").build().toString().removeSuffix("/")
    }
    fun register(endpoint: String?) {
        val url = endpoint?.toHttpUrlOrNull() ?: return
        for (path in LogFile.decodedViews(url.encodedPath, percentEncoded = true) ?: listOf(url.encodedPath)) {
            path.trimEnd('/').takeIf { it.isNotEmpty() }?.let(paths::add)
        }
        origin(endpoint)?.let {
            origins.add(it)
            if (url.port == if (url.scheme == "http") 80 else 443) origins.add("$it:${url.port}")
        }
    }
    private fun matches(value: String): Boolean {
        if (origin(value)?.let { it in origins } == true) return true
        // Match a known authority before log delimiters, never another host/port prefix.
        val lower = value.lowercase()
        return origins.any { origin ->
            if (!lower.startsWith(origin)) false else {
                val next = lower.drop(origin.length).dropWhile { it == '.' }.firstOrNull()
                next == null || !next.isLetterOrDigit() && next !in "-_%:@"
            }
        }
    }
    fun redact(text: String): String = sanitize(text) ?: "[redacted]"

    private val recordStart = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} [VDIWE] \[""")
    /** Keep continuation lines with their endpoint/error context, including historical files. */
    fun redactLogLines(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        val record = mutableListOf<String>()
        fun flush() {
            if (record.isEmpty()) return
            result += redact(record.joinToString("\n")).lines()
            record.clear()
        }
        for (line in lines) {
            if (recordStart.containsMatchIn(line)) flush()
            record += line
        }
        flush()
        return result
    }

    fun sanitize(text: String): String? {
        if (unavailable) return "[custom-esplora]"
        // Exported lines include a bounded tag and timestamp as well as the message.
        if (text.toByteArray(Charsets.UTF_8).size > 2 * 16 * 1024 + 128) return "[oversized log message omitted]"
        responseStatus.find(text)?.let { return "HttpResponse { status: ${it.groupValues[1]} }" }
        val views = LogFile.decodedViews(text, percentEncoded = true)
            ?: return if (text.contains("HttpResponse")) "HttpResponse" else null
        for (view in views) {
            responseStatus.find(view)?.let { return "HttpResponse { status: ${it.groupValues[1]} }" }
        }
        if (views.any { it.contains("HttpResponse") }) return "HttpResponse"
        if (origins.isEmpty()) return text
        var result = text
        for ((index, view) in views.withIndex()) {
            val clean = urls.replace(view) { match -> if (matches(match.value)) "[custom-esplora]" else match.value }
            // Inspect relative references separately: another origin never inherits our paths.
            val relative = urls.replace(clean, "")
            if (paths.any { relative.contains(it) }) return "[custom-esplora]"
            if (index > 0 && clean != view) return "[custom-esplora]"
            if (index == 0) result = clean
        }
        return result
    }
}
