package com.noahwallet.services

import com.noahwallet.services.backup.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.Base64
import java.util.zip.*

class BackupArchiveTest {
    private val phrase = "test phrase"
    private val manifest = BackupArchive.Manifest(2, "x", "hash", "regtest", "abcd", null, null, 42)
    private inline fun <T> temp(body: (File) -> T): T {
        val root = Files.createTempDirectory("archive").toFile()
        try { return body(root) } finally { root.deleteRecursively() }
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
        } }
    }.toByteArray()
    private fun publish(from: File, to: File) { Files.createLink(to.toPath(), from.toPath()) }
    /** The archive's own reason: a key derived with other rounds would fail authentication instead. */
    private fun failure(open: () -> Unit) = (runCatching(open).exceptionOrNull() as? BackupArchive.Failure)?.reason
    @Test fun publicationRefusesATargetCreatedWhileSealing() = temp { root ->
        val snapshot = File(root, "snapshot").apply { writeText("wallet") }
        val destination = File(root, "backup")
        assertTrue(runCatching {
            BackupArchive.seal(snapshot, manifest, phrase, destination, root, testRounds) { complete, target ->
                target.writeText("someone else's export")
                publish(complete, target)
            }
        }.isFailure)
        assertEquals("someone else's export", destination.readText())
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".partial") || it.name.endsWith(".zip") })
    }
    /** RN's real rounds: Node and Info-ZIP built these files, as an RN wallet's backups were. */
    @Test fun independentlyBuiltNodeAndInfoZipFixtures() = temp { root ->
        val cases = Json.parseToJsonElement(javaClass.classLoader!!.getResource("backup-archives.json")!!.readText())
            .jsonObject["cases"]!!.jsonArray
        cases.forEachIndexed { index, case ->
            val input = case.jsonObject["input"]!!.jsonObject
            val expected = case.jsonObject["expected"]!!.jsonObject
            val destination = File(root, "out-$index")
            val mnemonic = input["mnemonic"]!!.jsonPrimitive.content
            if (input["kind"]!!.jsonPrimitive.content == "v2") {
                val file = File(root, "encrypted-$index").apply {
                    writeBytes(Base64.getDecoder().decode(input["fileBase64"]!!.jsonPrimitive.content))
                }
                val opened = BackupArchive.open(file, mnemonic, destination)
                assertEquals(BackupArchive.parseManifest(expected["manifest"].toString().toByteArray()), opened.manifest)
            } else BackupArchive.openLegacy(input["fileText"]!!.jsonPrimitive.content.toByteArray(), mnemonic,
                input["variant"]!!.jsonPrimitive.content, destination)
            val files = expected["files"]!!.jsonObject
            files.forEach { (path, hash) -> assertEquals(hash.jsonPrimitive.content, BackupArchive.sha256(File(destination, path))) }
            assertEquals(files.size + if (input["kind"]!!.jsonPrimitive.content == "v2") 1 else 0,
                destination.walk().count { it.isFile })
        }
    }
    @Test fun roundTripAndNoOverwrite() = temp { root ->
        val snapshot = File(root, "snapshot").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        val encrypted = BackupArchive.seal(snapshot, manifest, phrase, File(root, "sealed"), root, testRounds, ::publish)
        val opened = BackupArchive.open(encrypted.file, phrase, File(root, "open"), testRounds)
        assertEquals(manifest, opened.manifest); assertArrayEquals(snapshot.readBytes(), opened.snapshot.readBytes())
        assertTrue(runCatching { BackupArchive.seal(snapshot, manifest, phrase, encrypted.file, root, testRounds, ::publish) }.isFailure)
    }
    @Test fun strictManifest() {
        val valid = BackupArchive.encode(manifest).toString(Charsets.UTF_8)
        for (bad in listOf(valid.replace("\"formatVersion\":2", "\"formatVersion\":1"),
            valid.replace("\"serverPubkey\":null,", ""), valid.replace("\"barkSchemaVersion\":42", "\"barkSchemaVersion\":\"42\""),
            valid.replace("\"createdAt\":\"x\"", "\"createdAt\":42"), "{")) {
            assertEquals(BackupArchive.Reason.invalidManifest,
                (runCatching { BackupArchive.parseManifest(bad.toByteArray()) }.exceptionOrNull() as BackupArchive.Failure).reason)
        }
    }
    @Test fun rejectsUnexpectedPathsAndCleansPartialExtraction() = temp { root ->
        for (name in listOf("notes.txt", "../escape", "./db.sqlite")) {
            val data = zip("manifest.json" to BackupArchive.encode(manifest), "db.sqlite" to byteArrayOf(1), name to byteArrayOf(2))
            val file = File(root, "encrypted").apply { writeBytes(BackupCrypto.encrypt(data, phrase, BackupCrypto.Version.snapshot, testRounds)) }
            val out = File(root, "out")
            assertEquals(name, BackupArchive.Reason.unexpectedEntry, failure { BackupArchive.open(file, phrase, out, testRounds) })
            assertFalse(out.exists())
        }
    }
    @Test fun streamingCapAndCrcAreEnforced() = temp { root ->
        val bytes = ByteArray(256 * 1024)
        val failure = runCatching { BackupArchive.extract(bytes.inputStream(), File(root, "bomb"), 65536, 0) }.exceptionOrNull()
        assertEquals(BackupArchive.Reason.tooLarge, (failure as BackupArchive.Failure).reason)
        assertTrue(File(root, "bomb").length() <= 65536)
        assertEquals(BackupArchive.Reason.checksumMismatch, (runCatching {
            BackupArchive.extract(byteArrayOf(1).inputStream(), File(root, "crc"), 10, 123)
        }.exceptionOrNull() as BackupArchive.Failure).reason)
    }
    /** [original] with every zip header's CRC (or, without [crc], its sizes) forged. */
    private fun forged(original: ByteArray, crc: Boolean): ByteArray {
        val bytes = original.copyOf()
        fun u32(at: Int): Long = (0..3).fold(0L) { value, i -> value or ((bytes[at + i].toLong() and 255) shl (8 * i)) }
        fun write(at: Int, value: Int) { repeat(4) { bytes[at + it] = (value ushr (8 * it)).toByte() } }
        for (offset in 0 until bytes.size - 46) {
            when (u32(offset)) {
                0x04034b50L -> if (crc) write(offset + 14, 123) else write(offset + 22, 16)
                0x02014b50L -> if (crc) write(offset + 16, 123) else write(offset + 24, 16)
                0x08074b50L -> if (crc) write(offset + 4, 123) else write(offset + 12, 16)
            }
        }
        return bytes
    }
    @Test fun forgedSizesAndCrcAreRejectedWhileInflatingArchive() = temp { root ->
        val manifestBytes = BackupArchive.encode(manifest)
        val original = zip("manifest.json" to manifestBytes, "db.sqlite" to ByteArray(256 * 1024))
        fun patch(crc: Boolean) = forged(original, crc)
        val corrupt = File(root, "corrupt").apply { writeBytes(BackupCrypto.encrypt(patch(true), phrase, BackupCrypto.Version.snapshot, testRounds)) }
        assertEquals(BackupArchive.Reason.checksumMismatch, failure { BackupArchive.open(corrupt, phrase, File(root, "bad"), testRounds) })
        assertFalse(File(root, "bad").exists())
        org.apache.commons.compress.utils.SeekableInMemoryByteChannel(patch(false)).use { channel ->
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setSeekableByteChannel(channel).get().use { archive ->
                val entry = archive.getEntry("db.sqlite")
                assertEquals(16L, entry.size)
                val target = File(root, "bomb")
                val failure = runCatching { archive.getInputStream(entry).use { BackupArchive.extract(it, target, 65536, entry.crc) } }.exceptionOrNull()
                assertEquals(BackupArchive.Reason.tooLarge, (failure as BackupArchive.Failure).reason)
                assertTrue(target.length() <= 65536)
            }
        }
    }
    @Test fun unixSymlinkIsRejectedInV2() = temp { root ->
        val bytes = ByteArrayOutputStream()
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(bytes).use { zip ->
            val entry = org.apache.commons.compress.archivers.zip.ZipArchiveEntry("db.sqlite").apply { unixMode = 0xa1ff }
            zip.putArchiveEntry(entry); zip.write("/other/wallet".toByteArray()); zip.closeArchiveEntry()
        }
        val encrypted = File(root, "encrypted").apply {
            writeBytes(BackupCrypto.encrypt(bytes.toByteArray(), phrase, BackupCrypto.Version.snapshot, testRounds))
        }
        assertEquals(BackupArchive.Reason.unexpectedEntry, failure { BackupArchive.open(encrypted, phrase, File(root, "out"), testRounds) })
        assertFalse(File(root, "out").exists())
    }
    @Test fun legacyPathsAndEntryLimit() = temp { root ->
        val prefix = "backup_staging/noah-data-regtest/"
        for ((entries, reason) in listOf(listOf(prefix + "db.sqlite", prefix + "../escape") to BackupArchive.Reason.unexpectedEntry,
            listOf(prefix + "db.sqlite", prefix + "a//b") to BackupArchive.Reason.unexpectedEntry,
            listOf(prefix + "other.sqlite") to BackupArchive.Reason.snapshotMissing,
            listOf(prefix + "db.sqlite") + (0..255).map { prefix + "f$it" } to BackupArchive.Reason.tooLarge)) {
            val sealed = BackupCrypto.encrypt(zip(*entries.map { it to byteArrayOf(1) }.toTypedArray()), phrase,
                BackupCrypto.Version.legacyDirectory, testRounds)
            val out = File(root, "legacy")
            assertEquals(reason, failure { BackupArchive.openLegacy(Base64.getEncoder().encode(sealed), phrase, "regtest", out, testRounds) })
            assertFalse(out.exists())
        }
    }
    @Test fun scratchSweepsOnlyAbandonedAndCleansOnFailure() = runBlocking {
        temp { root ->
            val scratch = ScratchSpace(root)
            val abandoned = File(root, "abandoned").apply { mkdir() }
            scratch.withFolder("test") { folder ->
                scratch.sweepAbandoned(); assertFalse(abandoned.exists()); assertTrue(folder.exists())
            }
            runCatching { scratch.withFolder("test") { throw IOException() } }
            assertTrue(root.listFiles()!!.isEmpty())
            // A shared export outlives its operation until the caller discards it.
            val shared = scratch.makeFolder("share-backup")
            scratch.sweepAbandoned(); assertTrue(shared.exists())
            scratch.discard(shared); assertTrue(root.listFiles()!!.isEmpty())
        }
    }
    @Test fun boundedDownloadChecksActualBytesWithoutTrustingHeaders() = runBlocking {
        temp { root ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(object : ResponseBody() {
                        override fun contentType(): MediaType? = null
                        override fun contentLength() = -1L
                        override fun source() = okio.Buffer().write(ByteArray(300_000))
                    }).build()
            }.build()
            val request = Request.Builder().url("http://unused").build()
            val file = File(root, "download")
            BoundedDownload.fetch(request, file, 300_000, http)
            assertEquals(300_000L, file.length())
            file.delete()
            assertTrue(runCatching { BoundedDownload.fetch(request, file, 1000, http) }.exceptionOrNull() is BoundedDownload.TooLarge)
            assertTrue(file.length() <= 1000)
            repeat(20) {
                val job = launch {
                    currentCoroutineContext().cancel()
                    BoundedDownload.fetch(request, File(root, "cancel-$it"), 1000, http)
                }
                withTimeout(2000) { job.join() }
            }
        }
    }
}
