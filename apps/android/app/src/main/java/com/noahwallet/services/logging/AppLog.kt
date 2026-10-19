package com.noahwallet.services.logging

import android.content.Context
import android.util.Log
import java.io.File
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
        private val mnemonicShape = Regex("\\b[a-z]{3,8}(?:\\s+[a-z]{3,8}){11,}\\b")
        fun redact(message: String): String = mnemonicShape.replace(message, "[redacted]")
        @Volatile private var instance: LogFile? = null
        fun shared(context: Context): LogFile = instance ?: synchronized(lock) {
            instance ?: LogFile(File(context.applicationContext.cacheDir, "noah_logs")).also { instance = it }
        }
    }
}
