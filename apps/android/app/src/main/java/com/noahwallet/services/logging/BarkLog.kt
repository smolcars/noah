package com.noahwallet.services.logging

import android.content.Context
import io.github.niteshbalusu11.bark.BarkLogger
import io.github.niteshbalusu11.bark.LogLevel
import io.github.niteshbalusu11.bark.setLogger

/** Rust/JNI calls synchronously on worker threads. Never call Bark from a callback. */
class BarkLog(private val file: LogFile) : BarkLogger {
    override fun log(level: LogLevel, target: String, message: String) {
        log(level, target, message, java.util.Date())
    }

    internal fun log(level: LogLevel, target: String, message: String, date: java.util.Date) {
        file.append(appLevel(level), "bark:$target", message, date, systemLog = true)
    }

    companion object {
        // nitro-ark and native iOS use Debug in every variant.
        val maxLevel = LogLevel.DEBUG

        fun install(context: Context) {
            // Initializes the bundled dictionary before registering the retained SDK callback.
            val file = LogFile.shared(context)
            try { setLogger(BarkLog(file), maxLevel) }
            catch (_: Exception) { AppLog("bark", file).e("Could not install Bark logger") }
            catch (_: LinkageError) { AppLog("bark", file).e("Could not install Bark logger") }
        }

        internal fun appLevel(level: LogLevel): AppLog.Level = when (level) {
            LogLevel.TRACE -> AppLog.Level.verbose
            LogLevel.DEBUG -> AppLog.Level.debug
            LogLevel.INFO -> AppLog.Level.info
            LogLevel.WARN -> AppLog.Level.warn
            LogLevel.ERROR -> AppLog.Level.error
        }
    }
}
