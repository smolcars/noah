package com.noahwallet.services.storage

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.util.UUID

/** expo-notifications 58 InstallationId.java precedence, UUID parsing, migration and session cache. */
class ExpoInstallationID internal constructor(
    private val directory: File,
    private val legacy: SharedPreferences,
) {
    constructor(context: Context) : this(context.noBackupFilesDir,
        context.getSharedPreferences("host.exp.exponent.SharedPreferences", Context.MODE_PRIVATE))

    private var cached: String? = null

    @Synchronized
    fun read(): String? {
        cached?.let { return it }
        readFile(File(directory, fileName))?.let { cached = it; return it }
        legacy.getString("uuid", null)?.let { value ->
            cached = value
            try {
                save(value)
                legacy.edit().remove("uuid").apply()
            } catch (_: IOException) { /* Expo keeps the ID in memory and legacy storage on failure. */ }
            return value
        }
        readFile(File(directory, "expo_installation_uuid.txt"))?.let { value ->
            cached = value
            try { save(value) } catch (_: IOException) { /* Keep the legacy file. */ }
            return value
        }
        return null
    }

    @Synchronized
    fun getOrCreate(): String {
        read()?.let { return it }
        val value = UUID.randomUUID().toString()
        cached = value
        try { save(value) } catch (_: IOException) { /* Same session ID if persistence fails. */ }
        return value
    }

    private fun save(value: String) = File(directory, fileName).writeText(value)

    private fun readFile(file: File): String? = try {
        file.bufferedReader().use { it.readLine()?.let { line -> UUID.fromString(line).toString() } }
    } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }

    companion object { const val fileName = "expo_notifications_installation_uuid.txt" }
}
