package com.noahwallet.services.backup

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlin.io.encoding.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel

object BackupArchive {
    const val formatVersion = 2
    const val maxUnpackedBytes = 1L shl 30
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }
    @Serializable data class Manifest(
        val formatVersion: Int, val createdAt: String, val snapshotSha256: String,
        val network: String, val walletFingerprint: String, val serverPubkey: String?,
        val mailboxPubkey: String?, val barkSchemaVersion: Int,
    )
    data class EncryptedFile(val file: File, val sizeBytes: Long, val sha256: String)
    data class Opened(val manifest: Manifest, val snapshot: File)
    enum class Reason { destinationExists, snapshotMissing, unexpectedEntry, newerVersion, invalidManifest, tooLarge, checksumMismatch, notLegacyBackup }
    class Failure(val reason: Reason) : Exception(reason.name)

    fun parseManifest(bytes: ByteArray): Manifest {
        val manifest = try {
            val objectValue = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            for (key in listOf("createdAt", "snapshotSha256", "network", "walletFingerprint")) {
                require((objectValue[key] as? JsonPrimitive)?.isString == true)
            }
            for (key in listOf("serverPubkey", "mailboxPubkey")) {
                require(objectValue.containsKey(key))
                require(objectValue[key] == JsonNull || (objectValue[key] as? JsonPrimitive)?.isString == true)
            }
            for (key in listOf("formatVersion", "barkSchemaVersion")) {
                val value = objectValue[key] as? JsonPrimitive
                require(value != null && !value.isString && value.intOrNull != null)
            }
            json.decodeFromJsonElement<Manifest>(objectValue)
        } catch (_: Exception) { throw Failure(Reason.invalidManifest) }
        if (manifest.formatVersion > formatVersion) throw Failure(Reason.newerVersion)
        if (manifest.formatVersion != formatVersion) throw Failure(Reason.invalidManifest)
        return manifest
    }
    fun encode(manifest: Manifest): ByteArray = json.encodeToString(manifest).toByteArray()
    fun timestamp(date: Date = Date()): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun seal(snapshot: File, manifest: Manifest, mnemonic: String, destination: File, workDirectory: File,
        rounds: Int = BackupCrypto.pbkdf2Rounds,
        publish: (File, File) -> Unit = { complete, target -> AtomicBackupMove.publish(complete, target) },
    ): EncryptedFile {
        if (!snapshot.isFile) throw Failure(Reason.snapshotMissing)
        if (destination.exists()) throw Failure(Reason.destinationExists)
        val zip = File(workDirectory, ".backup-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("manifest.json")); output.write(encode(manifest)); output.closeEntry()
                output.putNextEntry(ZipEntry("db.sqlite")); snapshot.inputStream().use { it.copyTo(output) }; output.closeEntry()
            }
            val bytes = BackupCrypto.encrypt(zip.readBytes(), mnemonic, BackupCrypto.Version.snapshot, rounds)
            // Publish the complete file atomically, refusing an existing target.
            val partial = File(destination.parentFile, ".${UUID.randomUUID()}.partial")
            try {
                partial.writeBytes(bytes)
                publish(partial, destination)
            } finally { partial.delete() }
            return EncryptedFile(destination, bytes.size.toLong(), sha256(bytes))
        } finally { zip.delete() }
    }

    fun open(encrypted: File, mnemonic: String, directory: File, rounds: Int = BackupCrypto.pbkdf2Rounds): Opened {
        val bytes = BackupCrypto.decrypt(encrypted.readBytes(), mnemonic, BackupCrypto.Version.snapshot, rounds)
        unpack(bytes, directory, null)
        try {
            return Opened(parseManifest(File(directory, "manifest.json").readBytes()), File(directory, "db.sqlite"))
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }
    fun openLegacy(base64: ByteArray, mnemonic: String, variant: String, directory: File, rounds: Int = BackupCrypto.pbkdf2Rounds) {
        val envelope = try { Base64.decode(base64.toString(Charsets.UTF_8).trim()) }
        catch (_: Exception) { throw Failure(Reason.notLegacyBackup) }
        unpack(BackupCrypto.decrypt(envelope, mnemonic, BackupCrypto.Version.legacyDirectory, rounds), directory,
            "backup_staging/noah-data-$variant/")
    }
    private fun unpack(bytes: ByteArray, directory: File, prefix: String?) {
        if (directory.exists()) throw Failure(Reason.destinationExists)
        check(directory.mkdir()) { "Cannot create restore folder" }
        try {
            SeekableInMemoryByteChannel(bytes).use { channel ->
                ZipFile.builder().setSeekableByteChannel(channel).get().use { archive ->
                    var remaining = maxUnpackedBytes
                    var count = 0
                    val seen = mutableSetOf<String>()
                    for (entry in archive.entries.asSequence()) {
                        val regular = !entry.isDirectory && !entry.isUnixSymlink &&
                            (entry.unixMode and 0xf000 in listOf(0, 0x8000))
                        val name: String
                        if (prefix == null) {
                            name = entry.name.removePrefix("./")
                            if (entry.isDirectory && name in listOf("", "/")) continue
                            if (!regular || name !in listOf("manifest.json", "db.sqlite")) throw Failure(Reason.unexpectedEntry)
                        } else {
                            if (!regular || !entry.name.startsWith(prefix)) continue
                            name = entry.name.removePrefix(prefix)
                            if (++count > 256) throw Failure(Reason.tooLarge)
                            if (name.split('/').any { it.isEmpty() || it == "." || it == ".." } || '\\' in name) {
                                throw Failure(Reason.unexpectedEntry)
                            }
                        }
                        if (!seen.add(name)) throw Failure(Reason.unexpectedEntry)
                        if (entry.method !in listOf(0, 8) || !archive.canReadEntryData(entry)) throw Failure(Reason.unexpectedEntry)
                        val limit = if (name == "manifest.json" && prefix == null) minOf(remaining, 65536) else remaining
                        if (entry.size > limit) throw Failure(Reason.tooLarge)
                        val target = File(directory, name)
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        archive.getInputStream(entry).use { input ->
                            remaining -= extract(input, target, limit, entry.crc)
                        }
                    }
                    if (!File(directory, "db.sqlite").isFile) throw Failure(Reason.snapshotMissing)
                }
            }
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
        finally { bytes.fill(0) }
    }
    internal fun extract(input: InputStream, destination: File, limit: Long, expectedCrc: Long): Long {
        if (!destination.createNewFile()) throw Failure(Reason.destinationExists)
        var written = 0L
        val crc = CRC32()
        destination.outputStream().use { output ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > limit - written) throw Failure(Reason.tooLarge)
                written += count; crc.update(buffer, 0, count); output.write(buffer, 0, count)
            }
        }
        if (crc.value != expectedCrc) throw Failure(Reason.checksumMismatch)
        return written
    }
}
