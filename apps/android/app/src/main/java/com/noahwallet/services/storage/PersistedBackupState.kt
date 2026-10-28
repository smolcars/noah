package com.noahwallet.services.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** RN backup-storage version 0, including explicit nulls and tolerant field-by-field reads. */
@Serializable
data class PersistedBackupState(
    var backupPending: Boolean = false,
    var lastBackupAt: Double? = null,
    var lastBackupAttemptAt: Double? = null,
    var lastBackupStatus: String = "idle",
    var lastBackupError: String? = null,
    var lastBackupId: String? = null,
    var lastUploadedSnapshotSha256: String? = null,
) {
    fun markPending() {
        backupPending = true
        if (lastBackupStatus != "in_progress") lastBackupStatus = "idle"
        lastBackupError = null
    }
    fun markInProgress(now: Double) { backupPending = true; lastBackupStatus = "in_progress"; lastBackupAttemptAt = now; lastBackupError = null }
    fun markSuccess(sha: String, id: String?, uploaded: Boolean, pending: Boolean, now: Double) {
        backupPending = pending; lastBackupStatus = if (uploaded) "success" else "idle"
        if (uploaded) lastBackupAt = now
        lastBackupError = null; lastBackupId = id ?: lastBackupId; lastUploadedSnapshotSha256 = sha
    }
    fun markFailed(message: String) { backupPending = true; lastBackupStatus = "failed"; lastBackupError = message }
    fun seedRestored(sha: String, id: String?, now: Double) {
        markSuccess(sha, id, true, false, now); lastBackupId = id
    }
    fun forgetUploadedBackup(id: String): Boolean {
        if (id != lastBackupId) return false
        backupPending = true; lastBackupId = null; lastUploadedSnapshotSha256 = null; lastBackupStatus = "idle"; lastBackupError = null
        return true
    }
    fun encode(): String = json.encodeToString(PersistedEnvelope(this, 0))
    companion object {
        const val key = "backup-storage"
        private val json = Json { encodeDefaults = true; explicitNulls = true }
        fun decode(value: String?): PersistedBackupState {
            val fields = runCatching { json.parseToJsonElement(value ?: "").jsonObject["state"]!!.jsonObject }.getOrNull()
                ?: return PersistedBackupState()
            fun string(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun number(key: String) = (fields[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            return PersistedBackupState(
                (fields["backupPending"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false,
                number("lastBackupAt"), number("lastBackupAttemptAt"),
                string("lastBackupStatus")?.takeIf { it in listOf("idle", "in_progress", "success", "failed") } ?: "idle",
                string("lastBackupError"), string("lastBackupId"), string("lastUploadedSnapshotSha256"))
        }
    }
}
