package com.noahwallet.services.logging

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Register before entering Rust: endpoint paths can be opaque credentials. */
internal object EndpointLogPrivacy {
    private val origins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val paths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val routes = setOf("api", "tx", "txs", "raw", "hex", "status", "outspend", "outspends", "address",
        "scripthash", "utxo", "chain", "mempool", "block", "blocks", "block-height", "tip", "height", "hash",
        "header", "txids", "txid", "merkle-proof", "merkleblock-proof", "fee-estimates", "recent")
    private val urls = Regex("""(?i)\bhttps?://[^\s<>"']*""")
    // esplora-client 0.12.3 Display uses Rust Debug: retain the error variant and HTTP status.
    private val responseBodies = Regex("""(HttpResponse \{ status: [0-9]+, message: )"(?:\\.|[^"\\]++)*+"""")
    private fun origin(value: String): String? = value.toHttpUrlOrNull()?.newBuilder()
        ?.encodedPath("/")?.query(null)?.fragment(null)?.username("")?.password("")?.build()?.toString()?.removeSuffix("/")
    fun register(endpoint: String?) {
        val url = endpoint?.toHttpUrlOrNull() ?: return
        for (path in LogFile.decodedViews(url.encodedPath, percentEncoded = true) ?: listOf(url.encodedPath)) {
            if (path != "/") paths.add(path.trimEnd('/'))
            paths.addAll(path.split('/').filter { it.isNotEmpty() && it !in routes })
        }
        origin(endpoint)?.let(origins::add)
    }
    fun sdkMessage(text: String): String {
        if (text.toByteArray(Charsets.UTF_8).size > 16 * 1024) return "[oversized log message omitted]"
        return redact(responseBodies.replace(text, "$1\"[provider response omitted]\""))
    }
    fun redact(text: String): String {
        if (origins.isEmpty()) return text
        // Exported lines include a bounded tag and timestamp as well as the message.
        if (text.toByteArray(Charsets.UTF_8).size > 2 * 16 * 1024 + 128) return "[oversized log message omitted]"
        val views = LogFile.decodedViews(text, percentEncoded = true) ?: return "[redacted]"
        var result = text
        for ((index, view) in views.withIndex()) {
            val clean = urls.replace(view) { match -> origin(match.value)?.takeIf { it in origins } ?: match.value }
            // Decoding loses offsets; omit the message when any relative path or segment matches.
            if (paths.any { clean.contains(it) } || index > 0 && clean != view) return "[redacted]"
            if (index == 0) result = clean
        }
        return result
    }
}
