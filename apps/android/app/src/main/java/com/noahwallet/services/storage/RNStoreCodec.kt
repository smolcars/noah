package com.noahwallet.services.storage

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Validate the persisted RN contract before any mutation; retain the complete JSON envelope. */
object RNStoreCodec {
    class Unreadable : Exception("Saved settings could not be read. Keep Noah installed and contact support.")
    val versions = mapOf("wallet-storage" to 0, "backup-storage" to 0, "esplora-storage" to 0,
        "privacy-storage" to 0, "profile-storage" to 0, "recurring-payment-storage" to 0,
        "server-storage" to 0, "transaction-storage" to 1)
    private val fields = mapOf(
        "wallet-storage" to setOf("isInitialized", "isWalletLoaded", "staticVtxoPubkey", "isBiometricsEnabled", "isDebugModeEnabled", "isWalletSuspended",
            "hasSeenBatteryOptimizationPrompt"),
        "backup-storage" to setOf("backupPending", "lastBackupAt", "lastBackupAttemptAt", "lastBackupStatus", "lastBackupError", "lastBackupId",
            "lastUploadedSnapshotSha256"),
        "esplora-storage" to setOf("endpointOverride"),
        "privacy-storage" to setOf("isHomeBalanceHidden"),
        "profile-storage" to setOf("displayName", "preferredCurrency", "bitcoinAmountUnit"),
        "recurring-payment-storage" to setOf("schedules", "serverSyncPending"),
        "server-storage" to setOf("isRegisteredWithServer", "lightningAddress", "nostrPubkey", "isBackupEnabled", "emailAddress", "isEmailVerified",
            "isEmailPromptDismissed", "mailboxAuthorizationExpiry", "isMailboxAuthorizationEnabled", "lastReportedDeviceFingerprint", "lastDeviceReportAt"),
        "transaction-storage" to setOf("isAutoBoardingEnabled", "hasAttemptedAutoBoarding"),
    )
    private val booleans = setOf("isInitialized", "isWalletLoaded", "isBiometricsEnabled", "isDebugModeEnabled",
        "isWalletSuspended", "hasSeenBatteryOptimizationPrompt", "backupPending", "isHomeBalanceHidden",
        "serverSyncPending", "isRegisteredWithServer", "isBackupEnabled", "isEmailVerified", "isEmailPromptDismissed",
        "isMailboxAuthorizationEnabled", "isAutoBoardingEnabled", "hasAttemptedAutoBoarding")
    private val nullableStrings = setOf("displayName", "preferredCurrency", "bitcoinAmountUnit", "staticVtxoPubkey", "endpointOverride", "lastBackupError",
        "lastBackupId", "lastUploadedSnapshotSha256", "lightningAddress", "nostrPubkey", "emailAddress", "lastReportedDeviceFingerprint")
    private val strings = setOf("lastBackupStatus")
    private val numbers = setOf("lastBackupAt", "lastBackupAttemptAt", "mailboxAuthorizationExpiry", "lastDeviceReportAt")

    fun decode(key: String, value: String): JsonObject = try {
        val root = Json.parseToJsonElement(value).jsonObject
        val state = root.getValue("state").jsonObject
        val version = root["version"]
        require(version == null || (version is JsonPrimitive && !version.isString &&
            (version.content == "0" || (versions.getValue(key) == 1 && version.content == "1"))))
        state.forEach { (field, value) -> require(field !in fields.getValue(key) || validField(field, value)) }
        if (key == "backup-storage") {
            state["lastBackupStatus"]?.let { require(it.jsonPrimitive.content in setOf("idle", "in_progress", "success", "failed")) }
        }
        root
    } catch (_: Exception) { throw Unreadable() }

    private fun validField(field: String, value: JsonElement): Boolean {
        val primitive = value as? JsonPrimitive
        return when (field) {
            in booleans -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
            in nullableStrings -> value == JsonNull || primitive?.isString == true
            in strings -> primitive?.isString == true
            in numbers -> value == JsonNull || (primitive != null && !primitive.isString && primitive.doubleOrNull?.isFinite() == true)
            "schedules" -> value is JsonObject
            else -> true
        }
    }

    /** Admission uses the recurring reader too; unsupported rows remain visible and stopped. */
    internal fun validateStoredValue(key: String, raw: String) {
        decode(key, raw)
        if (key == com.noahwallet.recurring.RecurringStore.key) {
            try { com.noahwallet.recurring.RecurringStore({ raw }, { error("""Read only""") }).load() }
            catch (_: Exception) { throw Unreadable() }
        }
    }

    /** JNI string getters may replace invalid UTF-8; never turn a present value into defaults. */
    fun storedString(present: Boolean, bytes: ByteArray?): String? {
        if (!present) return null
        if (bytes == null) throw Unreadable()
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw Unreadable() }
    }

    /** Merge complete envelopes and nested state so typed writers never drop future fields. */
    fun merge(key: String, previous: String?, next: String): String {
        val new = decode(key, next)
        val old = previous?.let { decode(key, it) }.orEmpty()
        val state = (old["state"] as? JsonObject).orEmpty() + new.getValue("state").jsonObject
        return JsonObject(old + new + ("state" to JsonObject(state))).toString()
    }
}
