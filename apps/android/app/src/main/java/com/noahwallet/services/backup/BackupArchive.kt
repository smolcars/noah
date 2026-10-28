package com.noahwallet.services.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.encoding.Base64

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
        val manifest = decodeManifest(bytes)
        if (manifest.formatVersion > formatVersion) throw Failure(Reason.newerVersion)
        if (manifest.formatVersion != formatVersion) throw Failure(Reason.invalidManifest)
        return manifest
    }
    private fun decodeManifest(bytes: ByteArray): Manifest = try {
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
    fun encode(manifest: Manifest): ByteArray = json.encodeToString(manifest).toByteArray()
    fun timestamp(date: Date = Date()): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Suppress("LongParameterList") // rounds and publish are test seams (fast PBKDF2, publish faults)
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
        return removingOnFailure(directory) {
            Opened(parseManifest(File(directory, "manifest.json").readBytes()), File(directory, "db.sqlite"))
        }
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
            removingOnFailure(directory) {
                SeekableInMemoryByteChannel(bytes).use { channel ->
                    ZipFile.builder().setSeekableByteChannel(channel).get().use { archive -> unpackEntries(archive, directory, prefix) }
                }
            }
        } finally { bytes.fill(0) }
    }
    private fun unpackEntries(archive: ZipFile, directory: File, prefix: String?) {
        var remaining = maxUnpackedBytes
        val names = EntryNames(prefix)
        for (entry in archive.entries.asSequence()) {
            val name = names.next(entry) ?: continue
            val limit = if (name == "manifest.json" && prefix == null) minOf(remaining, 65536) else remaining
            requireReadable(archive, entry, limit)
            val target = File(directory, name)
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            archive.getInputStream(entry).use { input ->
                remaining -= extract(input, target, limit, entry.crc)
            }
        }
        if (!File(directory, "db.sqlite").isFile) throw Failure(Reason.snapshotMissing)
    }
    private fun requireReadable(archive: ZipFile, entry: ZipArchiveEntry, limit: Long) {
        if (entry.method !in listOf(0, 8) || !archive.canReadEntryData(entry)) throw Failure(Reason.unexpectedEntry)
        if (entry.size > limit) throw Failure(Reason.tooLarge)
    }

    /** Names the entries a restore writes, in archive order; null skips an entry. */
    private class EntryNames(private val prefix: String?) {
        private var count = 0
        private val seen = mutableSetOf<String>()

        fun next(entry: ZipArchiveEntry): String? {
            val regular = !entry.isDirectory && !entry.isUnixSymlink &&
                (entry.unixMode and 0xf000 in listOf(0, 0x8000))
            val name = (if (prefix == null) snapshotName(entry, regular) else legacyName(entry, regular, prefix)) ?: return null
            if (!seen.add(name)) throw Failure(Reason.unexpectedEntry)
            return name
        }
        private fun snapshotName(entry: ZipArchiveEntry, regular: Boolean): String? {
            val name = entry.name.removePrefix("./")
            if (entry.isDirectory && name in listOf("", "/")) return null
            if (!regular || name !in listOf("manifest.json", "db.sqlite")) throw Failure(Reason.unexpectedEntry)
            return name
        }
        private fun legacyName(entry: ZipArchiveEntry, regular: Boolean, prefix: String): String? {
            if (!regular || !entry.name.startsWith(prefix)) return null
            val name = entry.name.removePrefix(prefix)
            if (++count > 256) throw Failure(Reason.tooLarge)
            val traversal = name.split('/').any { it in listOf("", ".", "..") }
            if (traversal || '\\' in name) throw Failure(Reason.unexpectedEntry)
            return name
        }
    }
    internal fun extract(input: InputStream, destination: File, limit: Long, expectedCrc: Long): Long {
        if (!destination.createNewFile()) throw Failure(Reason.destinationExists)
        val crc = CRC32()
        val written = destination.outputStream().use { output -> copyBounded(input, output, limit, crc) }
        if (crc.value != expectedCrc) throw Failure(Reason.checksumMismatch)
        return written
    }
    private fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long, crc: CRC32): Long {
        var written = 0L
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > limit - written) throw Failure(Reason.tooLarge)
            written += count; crc.update(buffer, 0, count); output.write(buffer, 0, count)
        }
        return written
    }

    /** Runs [block]; if it throws, removes the partly written [directory] before the failure propagates. */
    private inline fun <T> removingOnFailure(directory: File, block: () -> T): T {
        var done = false
        try {
            return block().also { done = true }
        } finally { if (!done) directory.deleteRecursively() }
    }
}
