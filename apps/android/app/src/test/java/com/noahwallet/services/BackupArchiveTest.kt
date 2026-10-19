package com.noahwallet.services

import com.noahwallet.services.backup.*
import java.io.*
import java.nio.file.Files
import java.util.Base64
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test

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
        val encrypted = BackupArchive.seal(snapshot, manifest, phrase, File(root, "sealed"), root)
        val opened = BackupArchive.open(encrypted.file, phrase, File(root, "open"))
        assertEquals(manifest, opened.manifest); assertArrayEquals(snapshot.readBytes(), opened.snapshot.readBytes())
        assertTrue(runCatching { BackupArchive.seal(snapshot, manifest, phrase, encrypted.file, root) }.isFailure)
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
            val file = File(root, "encrypted").apply { writeBytes(BackupCrypto.encrypt(data, phrase, BackupCrypto.Version.snapshot)) }
            val out = File(root, "out")
            assertTrue(runCatching { BackupArchive.open(file, phrase, out) }.isFailure)
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
    @Test fun legacyPathsAndEntryLimit() = temp { root ->
        val prefix = "backup_staging/noah-data-regtest/"
        for (entries in listOf(listOf(prefix + "db.sqlite", prefix + "../escape"),
            listOf(prefix + "db.sqlite", prefix + "a//b"), listOf(prefix + "other.sqlite"),
            listOf(prefix + "db.sqlite") + (0..255).map { prefix + "f$it" })) {
            val sealed = BackupCrypto.encrypt(zip(*entries.map { it to byteArrayOf(1) }.toTypedArray()), phrase, BackupCrypto.Version.legacyDirectory)
            val out = File(root, "legacy")
            assertTrue(runCatching { BackupArchive.openLegacy(Base64.getEncoder().encode(sealed), phrase, "regtest", out) }.isFailure)
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
                val job = launch(start = CoroutineStart.LAZY) { BoundedDownload.fetch(request, File(root, "cancel-$it"), 1000, http) }
                job.cancel(); job.join()
            }
        }
    }
}
