package com.noahwallet.services.logging

import android.icu.text.IDNA
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.noahwallet.BuildConfig
import com.noahwallet.services.server.ServerEndpoint
import com.noahwallet.services.wallet.EsploraEndpoint
import com.noahwallet.services.wallet.WalletConfiguration

/** Register before entering Rust: endpoint paths can be opaque credentials. */
internal object EndpointLogPrivacy {
    @Volatile private var unavailable = false
    fun failClosed() { unavailable = true }
    val requiresSerializedGuard: Boolean get() = unavailable || origins.isNotEmpty()
    private val origins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val tokens = mutableSetOf<String>()
    private var matcher: SecretMatcher? = null
    private var policy: Triple<Set<String>, SecretMatcher, Set<String>>? = null
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

    @Synchronized fun register(endpoint: String?) {
        if (endpoint == null) return
        if (hasExcessiveCombiningRun(endpoint)) { failClosed(); return }
        val url = endpoint.toHttpUrlOrNull()
        if (url == null) { failClosed(); return }
        // Selection validation rejects credentials/query; protect them in legacy registrations too.
        val base = url.newBuilder().username("").password("").query(null).fragment(null).build().toString()
        val origin = origin(base)
        val host = host(base)
        if (origin == null || host == null) { failClosed(); return }
        val sensitive = mutableSetOf<String>()
        fun add(value: String, opaque: Boolean = false) {
            for (view in LogFile.decodedViews(value, percentEncoded = true, compatibility = true) ?: listOf(value)) {
                val token = SecretMatcher.normalize(view) ?: run { failClosed(); return }
                if (token.isNotEmpty() && (!opaque || token.length >= 6 && token.lowercase(java.util.Locale.ROOT) !in commonWords)) sensitive += view
            }
        }
        add(host)
        // OkHttp preserves IDNA2008 deviation characters; JDK IDNA2003 cannot decode them.
        val info = IDNA.Info()
        val unicodeHost = if (':' in host) host else IDNA.getUTS46Instance(IDNA.NONTRANSITIONAL_TO_UNICODE)
            .nameToUnicode(host, StringBuilder(), info).toString()
        if (info.hasErrors()) { failClosed(); return }
        add(unicodeHost)
        for (name in listOf(host, unicodeHost).filter { ':' !in it }) {
            name.split('.').dropLast(1).filter { it.length >= 6 && it !in publicNames }.forEach { add(it) }
        }
        add("$host:${url.port}")
        listOf(url.encodedUsername, url.encodedPassword).filter { it.length >= 6 }.forEach { add(it) }
        if (url.encodedUsername.isNotEmpty() && url.encodedPassword.isNotEmpty() && url.encodedUsername.length + url.encodedPassword.length + 1 >= 6) add("${url.encodedUsername}:${url.encodedPassword}")
        for (view in LogFile.decodedViews(url.encodedPath, percentEncoded = true, compatibility = true) ?: listOf(url.encodedPath)) {
            view.split('/').forEach { add(it, opaque = true) }
        }
        url.encodedQuery?.split('&')?.filter { '=' in it }?.forEach { add(it.substringAfter('='), opaque = true) }
        if (tokens.addAll(sensitive)) matcher = null
        hosts += host
        origins += origin
        policy = null
    }
    private val commonWords = setOf("api", "block", "blocks", "tip", "height", "hash", "tx", "txs", "transaction", "transactions", "address", "scripthash", "mempool", "v0", "v1", "v2", "testnet", "signet", "mainnet", "regtest", "esplora", "fee-estimates", "outspend", "outspends", "status", "hex", "raw", "merkle-proof", "merkleblock-proof", "utxo", "broadcast")
    private val publicNames by lazy {
        setOf("example", "localhost", "com", "org", "net", "edu", "gov", "co", "ac") +
            publicOrigins.flatMap { authority(it).orEmpty().split('.') }
    }

    /** Aho–Corasick over boundary-preserving and normalized UTF-16 views; terminal failure links need only a hit bit. */
    internal class SecretMatcher(tokens: Set<String>) {
        private class Node {
            val edges = mutableMapOf<Char, Int>()
            var failure = 0
            var terminal = false
        }
        private val nodes = mutableListOf(Node())
        init {
            val views = mutableSetOf<String>()
            for (token in tokens) {
                val normalized = normalize(token) ?: continue
                views += listOf(token, fold(token), normalized, withoutMarks(normalized))
            }
            for (token in views) {
                var state = 0
                for (unit in token) {
                    state = nodes[state].edges.getOrPut(unit) { nodes.add(Node()); nodes.lastIndex }
                }
                if (state != 0) nodes[state].terminal = true
            }
            val queue = java.util.ArrayDeque(nodes[0].edges.values)
            while (queue.isNotEmpty()) {
                val state = queue.removeFirst()
                for ((unit, next) in nodes[state].edges) {
                    var fallback = nodes[state].failure
                    while (fallback != 0 && unit !in nodes[fallback].edges) fallback = nodes[fallback].failure
                    nodes[next].failure = nodes[fallback].edges[unit] ?: 0
                    nodes[next].terminal = nodes[next].terminal || nodes[nodes[next].failure].terminal
                    queue.addLast(next)
                }
            }
        }
        fun contains(text: String): Boolean {
            if (hasExcessiveCombiningRun(text)) return true
            if (nodes.size == 1) return false
            if (containsView(text) || containsView(fold(text))) return true
            val normalized = normalize(text) ?: return true
            // Reordering can insert a following mark inside an accented token's decomposition.
            return containsView(normalized) || containsView(withoutMarks(normalized))
        }
        private fun containsView(text: String): Boolean {
            var state = 0
            for (unit in text) {
                while (state != 0 && unit !in nodes[state].edges) state = nodes[state].failure
                state = nodes[state].edges[unit] ?: 0
                if (nodes[state].terminal) return true
            }
            return false
        }
        companion object {
            private fun fold(text: String) = text.lowercase(java.util.Locale.ROOT).uppercase(java.util.Locale.ROOT)
            private fun withoutMarks(text: String) = buildString {
                var index = 0
                while (index < text.length) {
                    val scalar = text.codePointAt(index)
                    index += Character.charCount(scalar)
                    if (!isMark(scalar)) appendCodePoint(scalar)
                }
            }
            fun normalize(text: String): String? {
                if (hasExcessiveCombiningRun(text)) return null
                val folded = fold(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD))
                if (hasExcessiveCombiningRun(folded)) return null
                return java.text.Normalizer.normalize(folded, java.text.Normalizer.Form.NFKD)
            }
        }
    }
    private fun isMark(scalar: Int) = when (Character.getType(scalar)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    // Per-scalar decomposition has bounded size; never reorder an unbounded whole-string run.
    fun hasExcessiveCombiningRun(text: String): Boolean {
        var run = 0
        var decomposedRun = 0
        var index = 0
        while (index < text.length) {
            val scalar = text.codePointAt(index)
            index += Character.charCount(scalar)
            if (scalar < 128) { run = 0; decomposedRun = 0; continue }
            run = if (isMark(scalar)) run + 1 else 0
            if (run > 32) return true
            val decomposed = java.text.Normalizer.normalize(String(Character.toChars(scalar)), java.text.Normalizer.Form.NFKD)
            var offset = 0
            while (offset < decomposed.length) {
                val part = decomposed.codePointAt(offset)
                offset += Character.charCount(part)
                decomposedRun = if (isMark(part)) decomposedRun + 1 else 0
                if (decomposedRun > 32) return true
            }
        }
        return false
    }

    private val hostTokens = Regex("""\[[0-9a-fA-F.]*:[0-9a-fA-F:.]*(?:%[^\[\]\s]+)?\](?::[^\s<>"'()/\\?#,\];{}!$&*=^`|]+)?|[^\s<>"'()/\\?#,\[\];{}!$&*=^`|]+""")
    private fun redactHosts(text: String, registered: Set<String>, failClosed: Boolean = true): String {
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
            if (canonical == null && failClosed || canonical != null && canonical in registered) "[custom-esplora]" else token
        }
    }
    fun redact(text: String): String = sanitize(text) ?: "[redacted]"

    private val recordHeader = Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} [VDIWE] """)
    private val recordStart = Regex(recordHeader.pattern + """\[""")
    /** Keep continuation lines with their endpoint/error context, including historical files. */
    fun redactLogLines(lines: List<String>): List<String> = redactLogLines(lines, ::sanitizeLogRecord)

    // Explicit snapshots isolate read-back regressions without resetting durable global history.
    internal fun redactLogLines(lines: List<String>, registered: Set<String>, matcher: SecretMatcher, hosts: Set<String>): List<String> =
        redactLogLines(lines) { sanitize(it, registered, matcher, hosts, recordReadBack = recordHeader.containsMatchIn(it)) }

    private fun redactLogLines(lines: List<String>, sanitizeRecord: (String) -> String?): List<String> {
        val result = mutableListOf<String>()
        val record = mutableListOf<String>()
        fun flush() {
            if (record.isEmpty()) return
            result += (sanitizeRecord(record.joinToString("\n")) ?: "[redacted]").lines()
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
        val snapshot = snapshot()
        return sanitize(text, snapshot.first, snapshot.second, snapshot.third)
    }

    /** Only stored headers tolerate authority canonicalization failures (08/09 hours). */
    private fun sanitizeLogRecord(text: String): String? {
        if (unavailable) return "[custom-esplora]"
        val (registered, matcher, hosts) = snapshot()
        return sanitize(text, registered, matcher, hosts, recordReadBack = recordHeader.containsMatchIn(text))
    }

    private fun snapshot(): Triple<Set<String>, SecretMatcher, Set<String>> = synchronized(this) {
        policy ?: run {
            val compiled = matcher ?: SecretMatcher(tokens).also { matcher = it }
            Triple(origins.toSet(), compiled, hosts.toSet()).also { policy = it }
        }
    }

    /** Formatting can join safe fields; only guard tokens, preserving timestamps/provider summaries. */
    fun redactRecord(text: String): String {
        if (unavailable) return "[custom-esplora]"
        val snapshot = snapshot()
        if (snapshot.first.isEmpty()) return text
        if (hasExcessiveCombiningRun(text)) return "[custom-esplora]"
        if (text.toByteArray(Charsets.UTF_8).size > 2 * 16 * 1024 + 128) return "[redacted]"
        val views = LogFile.decodedViews(text, percentEncoded = true, compatibility = true) ?: return "[redacted]"
        return if (views.any(snapshot.second::contains)) "[custom-esplora]" else text
    }

    // Explicit snapshots also let tests cover a clean installation without resetting global history.
    internal fun sanitize(text: String, registered: Set<String>, matcher: SecretMatcher = SecretMatcher(emptySet()), hosts: Set<String> = emptySet()): String? {
        return sanitize(text, registered, matcher, hosts, recordReadBack = false)
    }

    private fun sanitize(text: String, registered: Set<String>, matcher: SecretMatcher, hosts: Set<String>, recordReadBack: Boolean): String? {
        if (registered.isNotEmpty() && hasExcessiveCombiningRun(text)) return "[custom-esplora]"
        // Exported lines include a bounded tag and timestamp as well as the message.
        if (text.toByteArray(Charsets.UTF_8).size > 2 * 16 * 1024 + 128) return "[oversized log message omitted]"
        fun summary(value: String) = if (matcher.contains(value)) "[custom-esplora]" else value
        responseStatus.find(text)?.let {
            return summary("HttpResponse { status: ${it.groupValues[1]} }")
        }
        val views = LogFile.decodedViews(text, percentEncoded = true, compatibility = registered.isNotEmpty())
            ?: return if (text.contains("HttpResponse")) summary("HttpResponse") else null
        for (view in views) {
            responseStatus.find(view)?.let {
                return summary("HttpResponse { status: ${it.groupValues[1]} }")
            }
        }
        if (views.any { it.contains("HttpResponse") }) return summary("HttpResponse")
        if (registered.isEmpty()) return text
        var result = text
        for ((index, view) in views.withIndex()) {
            val endpointOnly = redactEndpoints(view, registered, matcher, hosts, recordReadBack)
            if (endpointOnly == "[custom-esplora]" || index > 0 && endpointOnly != view) return "[custom-esplora]"
            // Host replacement must not hide credential labels or break mnemonic runs.
            val plain = LogFile.redactPlain(view)
            if (index > 0 && plain != view) return "[redacted]"
            if (index == 0) result = if (plain == view) endpointOnly else redactEndpoints(plain, registered, matcher, hosts, recordReadBack)
        }
        return result
    }

    private fun redactEndpoints(text: String, registered: Set<String>, matcher: SecretMatcher, hosts: Set<String>, recordReadBack: Boolean): String {
        // Match complete raw/decoded views before authority replacement can hide a boundary token.
        if (recordReadBack && matcher.contains(text)) return "[custom-esplora]"
        val prefix = if (recordReadBack) recordHeader.find(text)?.value.orEmpty() else ""
        val body = text.drop(prefix.length)
        val clean = urls.replace(body) { match -> if (!allowed(match.value, registered, hosts)) "[custom-esplora]" else match.value }
        val safe = redactHosts(prefix, hosts, failClosed = false) + redactHosts(clean, hosts)
        return if (matcher.contains(safe)) "[custom-esplora]" else safe
    }
}
