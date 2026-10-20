package com.noahwallet.services.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Never pass secrets. Redaction is a backstop for accidental mnemonic-shaped text. */
class AppLog(private val tag: String, private val file: LogFile) {
    constructor(context: Context, tag: String) : this(tag, LogFile.shared(context))

    enum class Level(val symbol: String, val priority: Int) {
        verbose("V", Log.VERBOSE), debug("D", Log.DEBUG), info("I", Log.INFO),
        warn("W", Log.WARN), error("E", Log.ERROR),
    }
    fun v(message: String) = log(Level.verbose, message)
    fun d(message: String) = log(Level.debug, message)
    fun i(message: String) = log(Level.info, message)
    fun w(message: String) = log(Level.warn, message)
    fun e(message: String) = log(Level.error, message)

    private fun log(level: Level, message: String) {
        val cleaned = LogFile.redact(message)
        Log.println(level.priority, "com.noah.app", "[${LogFile.redact(tag)}] $cleaned")
        file.append(level, tag, cleaned)
    }
}

/** RN Android layout: cacheDir/noah_logs/noah.log and .1 … .4, 512 KiB each. */
class LogFile(private val directory: File) {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun append(level: AppLog.Level, tag: String, message: String, date: Date = Date()) = synchronized(lock) {
        try {
            check(directory.isDirectory || directory.mkdirs())
            val active = File(directory, fileName)
            if (active.length() >= maxFileSizeBytes) {
                for (index in maxFiles downTo 1) {
                    val source = File(directory, if (index == 1) fileName else "$fileName.${index - 1}")
                    if (!source.exists()) continue
                    val target = File(directory, "$fileName.$index")
                    check(!target.exists() || target.delete())
                    check(source.renameTo(target))
                }
            }
            val cleaned = redact(message).trim().replace("\n", "\n  ")
            active.appendText("${formatter.format(date)} ${level.symbol} [${redact(tag)}] $cleaned\n")
        } catch (_: Exception) {
            // Logging must not fail a wallet operation or include a provider exception's secrets.
        }
    }

    /** Newest 4,000 lines across the rotation, oldest first, suitable for export. */
    fun lines(): List<String> = synchronized(lock) {
        val lines = ArrayDeque<String>(maxLines)
        for (index in maxFiles downTo 0) {
            val file = File(directory, if (index == 0) fileName else "$fileName.$index")
            if (!file.isFile) continue
            file.useLines { sequence -> sequence.forEach {
                if (lines.size == maxLines) lines.removeFirst()
                lines.addLast(it)
            } }
        }
        lines.toList()
    }

    fun flush() { synchronized(lock) {} }

    companion object {
        const val fileName = "noah.log"
        const val maxFileSizeBytes = 512 * 1024
        const val maxFiles = 4
        const val maxLines = 4000
        private val lock = Any()
        private const val maxMessageBytes = 16 * 1024
        private val bip39 = requireNotNull(LogFile::class.java.getResourceAsStream("/bip39-english.txt"))
            .bufferedReader().use { it.readLines().toSet() }
        private val words = Regex("[\\p{L}\\p{M}]+")
        private val urlUserinfo = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^\s/@<>"']+@""")
        private val sensitivePatterns = listOf(
            """(?is)\b[\w.-]*(?:email|lightning[_ -]?address)[\w.-]*["']?\s*[:=].*""",
            """(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9-]+(?:\.[A-Z0-9-]+)*\.[A-Z]{2,63}\b""",
            """(?is)\b(?:[\w.-]*(?:mnemonic|seed(?:[_ -]?phrase)?|priv(?:ate)?[_ -]?key|secret(?:[_ -]?key)?|preimage|sig(?:nature)?|token|authorization|password)[\w.-]*|key|x-amz-[\w-]+)["']?(?:\s*[:=({\[].*|(?:\s+|["']\s*)["']?[A-Za-z0-9+/_=-]{16,}.*)""",
            """(?i)\bhttps?://[^\s<>"']*\?[^\s<>"']*""",
            """(?i)\bhttps?%3a%2f%2f[^\s<>"']*""",
            """(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*|\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""",
            """(?i)\bExpo(?:nent)?PushToken\[[^\]]*\]""",
            """\b(?:[xt]prv[1-9A-HJ-NP-Za-km-z]{20,}|[59][1-9A-HJ-NP-Za-km-z]{50}|[KLc][1-9A-HJ-NP-Za-km-z]{51})\b""",
        ).map(::Regex)

        private val jsonEscapes = Regex("""(?:\\u[0-9a-fA-F]{4})+|\\["\\/bfnrt]""")

        fun redact(message: String): String {
            if (message.toByteArray(Charsets.UTF_8).size > maxMessageBytes) return "[oversized log message omitted]"
            var view = message
            repeat(4) {
                var uncertain = false
                val decoded = jsonEscapes.replace(view) { match ->
                    try {
                        kotlinx.serialization.json.Json.decodeFromString<String>("\"${match.value}\"")
                    } catch (_: Exception) {
                        uncertain = true
                        ""
                    }
                }
                if (uncertain) return "[redacted]"
                if (decoded == view) return redactPlain(message)
                if (redactPlain(decoded) != decoded) return "[redacted]"
                view = decoded
            }
            if (jsonEscapes.containsMatchIn(view)) return "[redacted]"
            return redactPlain(message)
        }

        // Decoded views cannot preserve source offsets; their hits omit the whole message.
        private fun redactPlain(message: String): String {
            var result = urlUserinfo.replace(message) { "${it.groupValues[1]}[redacted]@" }
            for (pattern in sensitivePatterns) result = pattern.replace(result, "[redacted]")
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
            for (range in runs.asReversed()) result = result.replaceRange(range, "[redacted]")
            return result
        }
        // Unicode Default_Ignorable_Code_Point ranges, including supplementary format controls.
        private val ignorableOrMark = Regex("""[\p{M}\u00AD\u061C\u115F-\u1160\u17B4-\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFF8\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}]""")
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
