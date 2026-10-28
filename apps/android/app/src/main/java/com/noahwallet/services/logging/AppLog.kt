package com.noahwallet.services.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.regex.Matcher
import kotlin.coroutines.resume

/** Never pass secrets. Redaction is a backstop for accidental mnemonic-shaped text. */
class AppLog(private val tag: String, private val file: LogFile) {
    constructor(context: Context, tag: String) : this(tag, LogFile.shared(context))

    enum class Level(val symbol: String, val priority: Int) {
        verbose("V", Log.VERBOSE), debug("D", Log.DEBUG), info("I", Log.INFO),
        warn("W", Log.WARN), error("""E""", Log.ERROR),
    }
    fun v(message: String) = log(Level.verbose, message)
    fun d(message: String) = log(Level.debug, message)
    fun i(message: String) = log(Level.info, message)
    fun w(message: String) = log(Level.warn, message)
    fun e(message: String) = log(Level.error, message)

    private fun log(level: Level, message: String) {
        file.append(level, tag, message, systemLog = true)
    }
}

/** Logcat gets every record in every build, after redaction. */
@android.annotation.SuppressLint("LogConditional")
private fun systemLog(level: AppLog.Level, tag: String, message: String) {
    Log.println(level.priority, "com.noah.app", EndpointLogPrivacy.redactRecord("[$tag] $message"))
}

/** RN Android layout: cacheDir/noah_logs/noah.log and .1 … .4, 512 KiB each. */
class LogFile(
    private val directory: File,
    private val capacity: Int = maxPendingRecords,
    private val executor: Executor = writer,
    private val systemSink: (AppLog.Level, String, String) -> Unit = ::systemLog,
) {
    init { require(capacity > 0) }
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val admission = Any()
    private var pending = 0
    private var dropped = 0

    fun append(level: AppLog.Level, tag: String, message: String, date: Date = Date(), systemLog: Boolean = false) {
        // Raw text lives only in this bounded memory queue; no sink runs on the caller.
        synchronized(admission) {
            if (pending >= capacity) { dropped++; return }
            enqueueDroppedMarker()
            pending++
            executor.execute {
                try {
                    val cleaned = redact(message)
                    val safeTag = redact(tag)
                    if (systemLog) systemSink(level, safeTag, cleaned)
                    persist(level, safeTag, cleaned, date)
                } finally {
                    synchronized(admission) {
                        pending--
                        if (pending == 0) enqueueDroppedMarker()
                    }
                }
            }
        }
    }

    // Caller holds admission; the marker follows all records admitted before overflow.
    private fun enqueueDroppedMarker() {
        if (dropped == 0) return
        val count = dropped
        dropped = 0
        executor.execute { persist(AppLog.Level.warn, "logging", "[dropped $count log records]", Date()) }
    }

    private fun persist(level: AppLog.Level, tag: String, message: String, date: Date) {
        try {
            check(directory.isDirectory || directory.mkdirs())
            val active = File(directory, fileName)
            val cleaned = message.trim().replace("\n", "\n  ")
            val formatted = "${formatter.format(date)} ${level.symbol} [$tag] $cleaned"
            val record = EndpointLogPrivacy.redactRecord(formatted + "\n")
            val line = if (record.endsWith("\n")) record else "$record\n"
            if (active.length() + line.toByteArray(Charsets.UTF_8).size > maxFileSizeBytes) rotate()
            active.appendText(line)
        } catch (_: Exception) {
            // Logging must not fail a wallet operation or include a provider exception's secrets.
        }
    }

    private fun rotate() {
        for (index in maxFiles downTo 1) {
            val source = File(directory, if (index == 1) fileName else "$fileName.${index - 1}")
            if (!source.exists()) continue
            val target = File(directory, "$fileName.$index")
            check(!target.exists() || target.delete())
            check(source.renameTo(target))
        }
    }

    /** Newest 4,000 lines across the rotation, oldest first, suitable for export. */
    fun lines(): List<String> = synchronized(admission) {
        enqueueDroppedMarker()
        CompletableFuture.supplyAsync({ readLines() }, executor)
    }.join()

    private fun readLines(): List<String> {
        val lines = ArrayDeque<String>(maxLines)
        for (index in maxFiles downTo 0) {
            val file = File(directory, if (index == 0) fileName else "$fileName.$index")
            if (!file.isFile) continue
            EndpointLogPrivacy.redactLogLines(file.readLines()).forEach {
                if (lines.size == maxLines) lines.removeFirst()
                lines.addLast(it)
            }
        }
        return lines.toList()
    }

    private fun barrier(): CompletableFuture<Void> = synchronized(admission) {
        enqueueDroppedMarker()
        CompletableFuture.runAsync({}, executor)
    }

    fun flush() { barrier().join() }

    suspend fun flushAsync() = suspendCancellableCoroutine { continuation ->
        barrier().thenRun { continuation.resume(Unit) }
    }

    companion object {
        const val fileName = "noah.log"
        const val maxFileSizeBytes = 512 * 1024
        const val maxFiles = 4
        const val maxLines = 4000
        const val maxPendingRecords = 256
        private val lock = Any()
        private val writer = Executors.newSingleThreadExecutor { task ->
            Thread(task, "noah-log-writer").apply { isDaemon = true }
        }
        private const val maxMessageBytes = 16 * 1024
        private val bip39 = requireNotNull(LogFile::class.java.getResourceAsStream("/bip39-english.txt"))
            .bufferedReader().use { it.readLines().toSet() }
        private val words = Regex("[\\p{L}\\p{M}]+")
        private val boundary = Regex("""\b""")
        private val labelTokens = Regex("""[\w.-]+""")
        private val addressKeyword = Regex("""email|lightning[_-]?address""")
        private val credentialKeyword = Regex("""mnemonic|seed|priv(?:ate)?[_-]?key|secret(?:[_-]?key)?|preimage|sig(?:nature)?|token|authorization|password""")
        private val addressTail = Regex("""["']?\s*[:=]""")
        private val credentialTail = Regex("""["']?(?:\s*[:=({\[]|(?:\s+|["']\s*)["']?[A-Za-z0-9+/_=-]{16,})""")
        private val schemeTokens = Regex("""(?i)[a-z0-9+.-]+""")
        private val schemeStart = Regex("""(?i)\b[a-z]""")
        private val urlUserinfo = Regex("""(?i)(\b[a-z][a-z0-9+.-]*+://)[^\s/@<>"']++@""")
        private val emailTokens = Regex("""(?i)[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+""")
        private val emailLocal = Regex("""(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]++@""")
        private val domainPart = Regex("""(?i)[A-Z0-9-]+""")
        private val domainTLD = Regex("""(?i)[A-Z]{2,63}\b""")
        private val specialLabel = Regex("""(?i)\b(?:key$|x-amz-[\w-]++)""")
        private val jwtTokens = Regex("""[A-Za-z0-9_-]+""")
        private val jwt = Regex("""\b[A-Za-z0-9_-]{8,}+\.[A-Za-z0-9_-]{8,}+\.[A-Za-z0-9_-]{8,}+""")
        private val urlTokens = Regex("""(?i)\bhttps?://[^\s<>"']*+""")
        private val pushTokens = Regex("""(?i)\bExpo(?:nent)?PushToken\[[^\]]*+(?:\]|$)""")
        private val sensitivePatterns = listOf(
            """(?i)\bhttps?%3a%2f%2f[^\s<>"']*""",
            """(?i)\bBearer\s+[A-Za-z0-9._~+/-]++=*""",
            """\b(?:[xt]prv[1-9A-HJ-NP-Za-km-z]{20,}+|[59][1-9A-HJ-NP-Za-km-z]{50}|[KLc][1-9A-HJ-NP-Za-km-z]{51})\b""",
        ).map(::Regex)

        private val jsonEscapes = Regex("""(?:\\u[0-9a-fA-F]{4})++|\\["\\/bfnrt]""")

        private val encodedEscapes = Regex(jsonEscapes.pattern + """|(?:%[0-9a-fA-F]{2})+|&(?:#[xX][0-9a-fA-F]+;?|#[0-9]+;?|[A-Za-z][A-Za-z0-9]*;)""")

        fun redact(message: String): String {
            if (message.toByteArray(Charsets.UTF_8).size > maxMessageBytes) return "[oversized log message omitted]"
            return redactForTelemetry(message) ?: "[redacted]"
        }

        /** Uncertain decoding and oversized messages have no telemetry admission path. */
        internal fun redactForTelemetry(message: String): String? = redactForTelemetry(message, EndpointLogPrivacy::sanitize)

        internal fun redactForTelemetry(message: String, endpointPrivacy: (String) -> String?): String? {
            if (message.toByteArray(Charsets.UTF_8).size > maxMessageBytes) return null
            val safe = endpointPrivacy(message) ?: return null
            val views = decodedViews(safe) ?: return null
            if (views.drop(1).any { redactPlain(it) != it }) return "[redacted]"
            return redactPlain(safe)
        }

        internal fun decodedViews(message: String, percentEncoded: Boolean = false, compatibility: Boolean = false): List<String>? {
            val escapes = if (percentEncoded) encodedEscapes else jsonEscapes
            val views = mutableListOf(message)
            repeat(4) {
                if (!addCompatibilityView(views, compatibility)) return null
                val decoded = decodeEscapes(escapes, views.last()) ?: return null
                if (decoded == views.last()) return views
                views += decoded
            }
            val settled = addCompatibilityView(views, compatibility) && !escapes.containsMatchIn(views.last())
            return if (settled) views else null
        }

        /** Appends the NFKC view when [compatibility] asks for it; false rejects an excessive combining run. */
        private fun addCompatibilityView(views: MutableList<String>, compatibility: Boolean): Boolean {
            if (compatibility && EndpointLogPrivacy.hasExcessiveCombiningRun(views.last())) return false
            val view = if (compatibility) Normalizer.normalize(views.last(), Normalizer.Form.NFKC) else views.last()
            if (compatibility && EndpointLogPrivacy.hasExcessiveCombiningRun(view)) return false
            if (view != views.last()) views += view
            return true
        }

        /** One decoding pass; null when any escape is uncertain or the result isn't well-formed UTF-16. */
        private fun decodeEscapes(escapes: Regex, view: String): String? {
            var uncertain = false
            val decoded = escapes.replace(view) { match -> decodeEscape(match.value) ?: "".also { uncertain = true } }
            return if (uncertain || !wellFormedUTF16(decoded)) null else decoded
        }

        private fun decodeEscape(value: String): String? = try {
            when {
                // The charset-name overload: URLDecoder.decode(String, Charset) needs API 33.
                value.startsWith("%") -> java.net.URLDecoder.decode(value, "UTF-8").takeUnless { it.contains('\uFFFD') }
                value.startsWith("&") -> decodeEntity(value)
                else -> kotlinx.serialization.json.Json.decodeFromString<String>("\"$value\"")
            }
        } catch (_: Exception) {
            null
        }

        private fun decodeEntity(value: String): String {
            val entity = value.drop(1).removeSuffix(";")
            val code = when {
                entity.startsWith("#x", true) -> entity.drop(2).toIntOrNull(16)
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                else -> mapOf("amp" to 38, "quot" to 34, "apos" to 39, "lt" to 60, "gt" to 62, "colon" to 58, "sol" to 47)[entity]
            }
            require(code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF)
            return String(Character.toChars(code))
        }

        private fun wellFormedUTF16(text: String): Boolean {
            var index = 0
            while (index < text.length) {
                val char = text[index++]
                if (char.isHighSurrogate()) {
                    if (index == text.length || !text[index++].isLowSurrogate()) return false
                } else if (char.isLowSurrogate()) return false
            }
            return true
        }

        internal fun containsMnemonicIn(parts: List<String>): Boolean {
            val joined = parts.joinToString(" ")
            if (joined.toByteArray(Charsets.UTF_8).size > maxMessageBytes) return true
            val views = decodedViews(joined) ?: return true
            if (views.any { containsMnemonic(mnemonicSkeleton(it)) }) return true
            return containsMnemonicLeaves(parts)
        }

        private fun mnemonicSkeleton(text: String) =
            detectionSkeleton(Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase(Locale.ROOT).replace("ß", "ss"))

        // Individually mnemonic-only leaves can be interspersed with structural metadata.
        private fun containsMnemonicLeaves(parts: List<String>): Boolean {
            var count = 0
            for (part in parts) {
                val decoded = decodedViews(part)?.last() ?: return true
                val tokens = words.findAll(mnemonicSkeleton(decoded)).map { it.value }.toList()
                if (tokens.isNotEmpty() && tokens.all { it in bip39 }) {
                    count += tokens.size
                    if (count >= 12) return true
                }
            }
            return false
        }

        // Decoded views cannot preserve source offsets; their hits omit the whole message.
        internal fun redactPlain(message: String): String {
            var result = replaceTokens(message, schemeTokens, urlUserinfo, schemeStart) { "${it.group(1)}[redacted]@" }
            result = redactLabels(result, addresses = true)
            result = replaceEmails(result)
            result = redactLabels(result, addresses = false)
            result = urlTokens.replace(result) { if ('?' in it.value) "[redacted]" else it.value }
            for ((index, pattern) in sensitivePatterns.withIndex()) {
                result = pattern.replace(result, "[redacted]")
                if (index == 1) {
                    result = replaceTokens(result, jwtTokens, jwt)
                    result = pushTokens.replace(result) { if (it.value.endsWith(']')) "[redacted]" else it.value }
                }
            }
            return redactMnemonicRuns(result)
        }

        private fun redactMnemonicRuns(result: String): String {
            val decomposed = Normalizer.normalize(result, Normalizer.Form.NFKD)
            val normalized = decomposed.lowercase(Locale.ROOT).replace("ß", "ss")
            val skeleton = detectionSkeleton(normalized)
            if (skeleton != normalized && containsMnemonic(skeleton)) return "[redacted]"
            val matches = words.findAll(normalized).toList()
            val runs = mutableListOf<IntRange>()
            var start = 0
            var count = 0
            for ((index, word) in matches.withIndex()) {
                if (word.value.lowercase(Locale.ROOT) in bip39) {
                    if (count == 0) start = index
                    count++
                } else {
                    if (count >= 12) runs += matches[start].range.first..matches[index - 1].range.last
                    count = 0
                }
            }
            if (count >= 12) runs += matches[start].range.first..matches.last().range.last
            // Compatibility expansion/case folding may invalidate offsets into the original.
            if (runs.isNotEmpty() && (decomposed != result || normalized.length != result.length)) return "[redacted]"
            return buildString {
                var offset = 0
                for (range in runs) {
                    append(result, offset, range.first)
                    append("[redacted]")
                    offset = range.last + 1
                }
                append(result, offset, result.length)
            }
        }
        // Each maximal token is attempted once, including failed dot/hyphen candidates.
        private fun replaceTokens(
            text: String, tokens: Regex, pattern: Regex, start: Regex = boundary,
            replacement: (java.util.regex.Matcher) -> String = { "[redacted]" },
        ): String = buildString {
            val candidates = tokens.toPattern().matcher(text)
            val beginning = start.toPattern().matcher(text).useTransparentBounds(true)
            val match = pattern.toPattern().matcher(text).useTransparentBounds(true)
            var offset = 0
            while (candidates.find()) {
                if (candidates.start() < offset || !tokenMatches(text, tokens, candidates, beginning, match)) continue
                append(text, offset, match.start())
                append(replacement(match))
                offset = match.end()
            }
            append(text, offset, text.length)
        }
        private fun tokenMatches(text: String, tokens: Regex, candidates: Matcher, beginning: Matcher, match: Matcher): Boolean {
            if (tokens === schemeTokens && !text.startsWith("://", candidates.end())) return false
            beginning.region(candidates.start(), candidates.end())
            if (!beginning.find()) return false
            match.region(beginning.start(), text.length)
            return match.lookingAt()
        }

        private fun replaceEmails(text: String): String = buildString {
            val candidates = emailTokens.toPattern().matcher(text)
            val beginning = boundary.toPattern().matcher(text).useTransparentBounds(true)
            val local = emailLocal.toPattern().matcher(text).useTransparentBounds(true)
            val part = domainPart.toPattern().matcher(text)
            val tld = domainTLD.toPattern().matcher(text).useTransparentBounds(true)
            var consumed = 0
            while (candidates.find()) {
                val localEnd = if (candidates.start() < consumed) null else emailLocalEnd(text, candidates, beginning, local)
                val end = localEnd?.let { domainEnd(text, part, tld, it) } ?: continue
                append(text, consumed, beginning.start())
                append("[redacted]")
                consumed = end
            }
            append(text, consumed, text.length)
        }
        private fun emailLocalEnd(text: String, candidates: Matcher, beginning: Matcher, local: Matcher): Int? {
            beginning.region(candidates.start(), candidates.end())
            if (!beginning.find()) return null
            local.region(beginning.start(), text.length)
            return if (local.lookingAt()) local.end() else null
        }
        /** The end of a dotted domain after an email's local part, if it ends in a TLD. */
        private fun domainEnd(text: String, part: Matcher, tld: Matcher, start: Int): Int? {
            var offset = start
            var end: Int? = null
            var first = true
            var more = true
            while (more) {
                part.region(offset, text.length)
                if (!part.lookingAt()) break
                tld.region(offset, text.length)
                if (!first && tld.lookingAt()) end = tld.end()
                first = false
                offset = part.end()
                more = offset < text.length && text[offset] == '.'
                offset++
            }
            return end
        }

        private fun redactLabels(text: String, addresses: Boolean): String {
            val keyword = if (addresses) addressKeyword else credentialKeyword
            val tail = (if (addresses) addressTail else credentialTail).toPattern().matcher(text).useTransparentBounds(true)
            val beginning = boundary.toPattern().matcher(text).useTransparentBounds(true)
            val candidates = labelTokens.toPattern().matcher(text)
            val next = labelTokens.toPattern().matcher(text)
            val special = specialLabel.toPattern().matcher(text).useTransparentBounds(true)
            val pairs = if (addresses) listOf("lightning" to "address") else
                listOf("seed" to "phrase", "priv" to "key", "private" to "key", "secret" to "key")
            while (candidates.find()) {
                beginning.region(candidates.start(), candidates.end())
                if (!beginning.find()) continue
                for (end in labelEnds(text, candidates, next, pairs, keyword)) {
                    tail.region(end, text.length)
                    if (tail.lookingAt()) return text.take(beginning.start()) + "[redacted]"
                }
                if (!addresses) {
                    val suffix = candidates.start() + candidates.group().lastIndexOf('.') + 1
                    special.region(suffix, candidates.end())
                    tail.region(candidates.end(), text.length)
                    if (special.find() && tail.lookingAt()) return text.take(special.start()) + "[redacted]"
                }
            }
            return text
        }

        /** Where a label (one token, or a pair such as "seed phrase") ends, for the tail check. */
        private fun labelEnds(text: String, candidates: Matcher, next: Matcher, pairs: List<Pair<String, String>>, keyword: Regex): List<Int> {
            val label = candidates.group().lowercase(Locale.ROOT)
            val ends = mutableListOf<Int>()
            for ((left, right) in pairs) if (label.endsWith(left)) {
                val end = candidates.end()
                if (end < text.length && text[end] == ' ') {
                    next.region(end + 1, text.length)
                    if (next.lookingAt() && next.group().startsWith(right, ignoreCase = true)) ends += next.end()
                }
            }
            if (keyword.containsMatchIn(label)) ends += candidates.end()
            return ends
        }

        // Unicode Default_Ignorable_Code_Point ranges, including supplementary format controls.
        private val ignorableOrMark = Regex(
            """[\p{M}\u00AD\u061C\u115F-\u1160\u17B4-\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF""" +
                """\uFFA0\uFFF0-\uFFF8\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}]""",
        )
        private val latinConfusables = mapOf(
            'a' to "аα", 'b' to "ьβ", 'c' to "сϲςσ", 'd' to "ԁ", 'e' to "еε", 'f' to "ғ",
            'g' to "ɡ", 'h' to "һн", 'i' to "іι", 'j' to "ј", 'k' to "κк", 'l' to "ӏ",
            'm' to "мμ", 'n' to "ո", 'o' to "оο", 'p' to "рρ", 's' to "ѕ", 't' to "тτ",
            'u' to "υս", 'v' to "νѵ", 'w' to "ԝω", 'x' to "хχ", 'y' to "уγ", 'z' to "ζ",
        ).flatMap { (latin, lookalikes) -> lookalikes.map { it to latin } }.toMap()

        // For privacy detection only; wallet seed validation intentionally stays stricter.
        private fun detectionSkeleton(normalized: String): String =
            ignorableOrMark.replace(normalized, "").map { latinConfusables[it] ?: it }.joinToString("")

        private fun containsMnemonic(normalized: String): Boolean {
            var count = 0
            for (word in words.findAll(normalized)) {
                count = if (word.value in bip39) count + 1 else 0
                if (count >= 12) return true
            }
            return false
        }

        @Volatile private var instance: LogFile? = null
        fun shared(context: Context): LogFile = instance ?: synchronized(lock) {
            instance ?: LogFile(File(context.applicationContext.cacheDir, "noah_logs")).also { instance = it }
        }
    }
}
