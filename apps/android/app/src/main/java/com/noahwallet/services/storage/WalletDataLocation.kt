package com.noahwallet.services.storage

import java.io.File
import com.noahwallet.services.backup.RestoreTransaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class WalletDataLocation(filesDirectory: File, variant: String) {
    val directory = File(filesDirectory, "noah-data-$variant")
    val restoreDirectory = File(filesDirectory, ".noah-data-$variant-restore")
    val deletionMarker = File(filesDirectory, ".noah-data-$variant-deleting")
    val deletionDirectory = File(filesDirectory, ".noah-data-$variant-deleting-data")
    val instanceIdentity = File(directory, ".noah-wallet-instance")

    // Kept outside the removed datadir so ownership survives every deletion boundary.
    @Serializable private data class DeletionIdentity(val version: Int = 1, val id: String,
        val seedSalt: String, val seedTag: String?)

    fun writeDeletionIdentity(seed: SecretResult, syncDirectory: (File) -> Unit = ::syncDirectory) {
        check(seed !is SecretResult.Unavailable) { "Wallet seed unavailable" }
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val identity = DeletionIdentity(id = UUID.randomUUID().toString(), seedSalt = Base64.encode(salt),
            seedTag = (seed as? SecretResult.Found)?.let { Base64.encode(RestoreTransaction.tag(it.value, salt)) })
        if (directory.exists()) {
            java.io.FileOutputStream(instanceIdentity).use { it.write(identity.id.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            syncDirectory(directory)
        }
        java.io.FileOutputStream(deletionMarker).use { it.write(Json.encodeToString(identity).toByteArray(Charsets.UTF_8)); it.fd.sync() }
    }

    fun deletionMatchesWallet(seed: SecretResult): Boolean {
        // UUID-only and one-byte legacy markers cannot prove seed ownership, even without a datadir.
        val identity = runCatching {
            Json.decodeFromString<DeletionIdentity>(deletionMarker.readText()).also {
                require(it.version == 1 && UUID.fromString(it.id).toString() == it.id)
                require(Base64.decode(it.seedSalt).size == 32 && (it.seedTag == null || Base64.decode(it.seedTag).size == 32))
            }
        }.getOrNull() ?: return false
        when (seed) {
            is SecretResult.Found -> if (identity.seedTag == null || !MessageDigest.isEqual(
                RestoreTransaction.tag(seed.value, Base64.decode(identity.seedSalt)), Base64.decode(identity.seedTag))) return false
            is SecretResult.Unavailable -> return false
            SecretResult.NotFound -> Unit // Only non-seed cleanup remains.
        }
        if (!directory.exists()) return true
        return instanceIdentity.exists() && instanceIdentity.readText() == identity.id
    }

    val restorePending: Boolean get() = File(restoreDirectory, "journal.json").exists()
    val database = File(directory, "db.sqlite")
    // A partial datadir still belongs to the user; unreadable directories fail closed.
    val hasAnyData: Boolean get() = directory.exists() &&
        (directory.list()?.isNotEmpty() ?: error("Cannot inspect wallet directory"))

    internal fun removeAttemptFiles(datadirExisted: Boolean): Boolean = try {
        if (!datadirExisted) !directory.exists() || directory.deleteRecursively()
        else directory.listFiles()?.all { it.deleteRecursively() } == true
    } catch (_: Exception) { false }

    val databaseExists: Boolean get() = database.exists()
}

/** Directory entries must be durable before deleting the recovery secret. */
internal fun syncDirectory(directory: File) {
    val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
    try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
}
