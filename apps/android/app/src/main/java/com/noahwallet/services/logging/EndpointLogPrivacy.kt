package com.noahwallet.services.logging

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.noahwallet.BuildConfig
import com.noahwallet.services.server.ServerEndpoint
import com.noahwallet.services.wallet.EsploraEndpoint
import com.noahwallet.services.wallet.WalletConfiguration

/** Register before entering Rust: endpoint paths can be opaque credentials. */
internal object EndpointLogPrivacy {
    @Volatile private var unavailable = false
    fun failClosed() { unavailable = true }
    private val origins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val paths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val hosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val urls = Regex("""(?i)(?<![a-z0-9+.-])[0-9+.-]*+[a-z][a-z0-9+.-]*+://[^\s<>"']*""")
    // Admit only the SDK error type/status, never provider-controlled response text.
    private val responseStatus = Regex("""HttpResponse(?:\s*\{\s*status:\s*|\()([0-9]{3})\b""")
    // Only shipped/build configuration enters this set; never use effective custom settings.
    private val publicOrigins by lazy {
        val endpoints = mutableListOf("https://mempool.space", "https://exp.host", "https://api.btcmap.org",
            "https://tiles.openfreemap.org", "https://btcmap.org", "https://maps.apple.com",
            "https://www.google.com", "https://www.openstreetmap.org", "https://openmaptiles.org",
            "https://www.geonames.org", "https://t.me", "https://github.com", "https://play.google.com")
        for (variant in listOf("mainnet", "signet", "regtest")) {
            val config = WalletConfiguration.base(variant)
            endpoints += ServerEndpoint.baseURL(variant, BuildConfig.REGTEST_SERVER_URL)
            endpoints += listOfNotNull(config.arkServer, config.esplora, config.bitcoind)
        }
        endpoints.mapNotNull(::origin).toSet()
    }
    private fun authority(value: String): String? = value.takeIf { "://" in it }?.substringAfter("://")
        ?.takeWhile { it !in "/\\?#" }
    private fun origin(value: String): String? {
        val authority = authority(value) ?: return null
        val url = runCatching { EsploraEndpoint.normalize(value.substringBefore("://") + "://" + authority) }
            .getOrNull()?.toHttpUrlOrNull() ?: return null
        return url.newBuilder().host(url.host.trimEnd('.').ifEmpty { url.host }).build().toString().removeSuffix("/")
    }
    private fun host(value: String): String? = runCatching { EsploraEndpoint.normalize(value) }
        .getOrNull()?.toHttpUrlOrNull()?.host?.trimEnd('.')?.let { if (':' in it) "[$it]" else it }
    private val plainAuthority = Regex("""(?:\[[0-9a-fA-F:.]+\]|[a-zA-Z0-9.-]+)(?::[0-9]+)?""")
    private val malformedPercent = Regex("%(?![0-9a-fA-F]{2})")
    private fun allowed(value: String, registered: Set<String>, hosts: Set<String>): Boolean {
        val authority = authority(value) ?: return false
        if (value.indexOf("://") != value.lastIndexOf("://") || '\\' in value || malformedPercent.containsMatchIn(value) || !plainAuthority.matches(authority)) return false
        val origin = origin(value) ?: return false
        if (origin in registered || origin !in publicOrigins) return false
        // Only the canonical IP spelling may inherit an allowlisted origin.
        val rawHost = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        val tail = value.substringAfter("://").drop(authority.length)
        return rawHost.lowercase().trimEnd('.') == host(value) && redactHosts(tail, hosts) == tail
    }

    fun register(endpoint: String?) {
        if (endpoint == null) return
        val url = endpoint.toHttpUrlOrNull()
        val origin = origin(endpoint)
        val host = host(endpoint)
        if (url == null || origin == null || host == null) { failClosed(); return }
        for (path in LogFile.decodedViews(url.encodedPath, percentEncoded = true) ?: listOf(url.encodedPath)) {
            path.trimEnd('/').takeIf { it.isNotEmpty() }?.let(paths::add)
        }
        hosts += host
        origins += origin
    }
    private val hostTokens = Regex("""\[[0-9a-fA-F.]*:[0-9a-fA-F:.]*(?:%[^\[\]\s]+)?\](?::[^\s<>"'()/\\?#,\];{}!$&*=^`|]+)?|[^\s<>"'()/\\?#,\[\];{}!$&*=^`|]+""")
    private fun redactHosts(text: String, registered: Set<String>): String {
        val urlRanges = urls.findAll(text).map { it.range }.toList()
        var urlIndex = 0
        return hostTokens.replace(text) { match ->
            while (urlIndex < urlRanges.size && urlRanges[urlIndex].last < match.range.first) urlIndex++
            if (urlIndex < urlRanges.size && urlRanges[urlIndex].first <= match.range.last) return@replace match.value
            val token = match.value
            if (token.none { it.isLetterOrDigit() || it.code > 127 }) return@replace token
            val address = token.substringBefore('%')
            if (("::" in address || address.count { it == ':' } == 7) &&
                address.all { it in "0123456789abcdefABCDEF:." }) return@replace "[custom-esplora]"
            val authority = token.substringAfterLast('@')
            val rawHost = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
            if (rawHost.isEmpty()) return@replace token
            // Plain words need a registration; authority-shaped and encoded tokens require inspection.
            if (rawHost.lowercase() !in registered && '.' !in token && ':' !in token && '%' !in token &&
                !rawHost.all { it in '0'..'9' } && !rawHost.startsWith("0x", ignoreCase = true) &&
                token.none { it.code > 127 }) return@replace token
            val canonical = host("https://$rawHost")
            if (canonical == null || canonical in registered) "[custom-esplora]" else token
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
        return sanitize(text, origins, paths, hosts)
    }

    // Explicit snapshots also let tests cover a clean installation without resetting global history.
    internal fun sanitize(text: String, registered: Set<String>, sensitive: Set<String> = emptySet(), hosts: Set<String> = emptySet()): String? {
        // Exported lines include a bounded tag and timestamp as well as the message.
        if (text.toByteArray(Charsets.UTF_8).size > 2 * 16 * 1024 + 128) return "[oversized log message omitted]"
        responseStatus.find(text)?.let { return "HttpResponse { status: ${it.groupValues[1]} }" }
        val views = LogFile.decodedViews(text, percentEncoded = true)
            ?: return if (text.contains("HttpResponse")) "HttpResponse" else null
        for (view in views) {
            responseStatus.find(view)?.let { return "HttpResponse { status: ${it.groupValues[1]} }" }
        }
        if (views.any { it.contains("HttpResponse") }) return "HttpResponse"
        if (registered.isEmpty()) return text
        var result = text
        for ((index, view) in views.withIndex()) {
            val endpointOnly = redactEndpoints(view, registered, sensitive, hosts)
            if (endpointOnly == "[custom-esplora]" || index > 0 && endpointOnly != view) return "[custom-esplora]"
            // Host replacement must not hide credential labels or break mnemonic runs.
            val plain = LogFile.redactPlain(view)
            if (index > 0 && plain != view) return "[redacted]"
            if (index == 0) result = if (plain == view) endpointOnly else redactEndpoints(plain, registered, sensitive, hosts)
        }
        return result
    }

    private fun redactEndpoints(text: String, registered: Set<String>, sensitive: Set<String>, hosts: Set<String>): String {
        val clean = urls.replace(text) { match -> if (!allowed(match.value, registered, hosts)) "[custom-esplora]" else match.value }
        // Inspect relative references separately: another origin never inherits our paths.
        val relative = urls.replace(clean, "")
        if (sensitive.any { relative.contains(it) }) return "[custom-esplora]"
        return redactHosts(clean, hosts)
    }
}
