package com.noahwallet.services.logging

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Register before entering Rust: endpoint paths can be opaque credentials. */
internal object EndpointLogPrivacy {
    private val origins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val urls = Regex("""(?i)\bhttps?://[^\s<>"']*""")
    private fun origin(value: String): String? = value.toHttpUrlOrNull()?.newBuilder()
        ?.encodedPath("/")?.query(null)?.fragment(null)?.username("")?.password("")?.build()?.toString()?.removeSuffix("/")
    fun register(endpoint: String?) { endpoint?.let(::origin)?.let(origins::add) }
    fun redact(text: String): String = if (origins.isEmpty()) text else urls.replace(text) { match ->
        origin(match.value)?.takeIf { it in origins } ?: match.value
    }
}
