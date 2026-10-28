package com.noahwallet.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.backup.*
import com.noahwallet.services.server.ServerClient
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CrossPlatformBackupTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private class MemorySeed : SecretItem {
        var value: String? = null
        override suspend fun read() = value?.let { SecretResult.Found(it) } ?: SecretResult.NotFound
        override suspend fun save(value: String) { this.value = value }
    }
    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test fun generateAndroidServiceExport() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahGenerateCrossBackup") == "1")
        val root = File(context.cacheDir, "cross-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val location = WalletDataLocation(root, "regtest")
        val session = WalletSession("regtest", location)
        try {
            // Offline factory initializes only a synthetic SDK database; BackupService takes its own snapshot.
            location.directory.mkdirs()
            val config = WalletConfiguration.base("regtest").sdkConfig("cross-backup-fixture")
            OnchainWallet.default(Network.REGTEST, phrase, config, location.directory.path).use { chain ->
                Wallet.openWithOnchain(Network.REGTEST, phrase, config,
                    WalletOpenArgs(runDaemon = false, datadir = location.directory.path,
                        createWithoutServer = true, skipRecovery = true), chain).use { it.stopDaemonWait() }
            }
            session.ensureOpen(phrase, false, "cross-backup-fixture")
            val summary = session.summary()!!
            assertEquals(0, summary.movementCount); assertTrue(summary.balance.details.all { it.sats == 0uL })
            ServerClient.make("http://unused").use { client ->
                val service = BackupService(session, client, { phrase }, ScratchSpace(File(root, "scratch")))
                val export = service.exportEncryptedBackup("android.noahbackup")
                try {
                    val fixture = buildJsonObject {
                        put("name", "Android BackupService export")
                        putJsonObject("input") {
                            put("producer", "android"); put("mnemonic", phrase)
                            put("fileBase64", java.util.Base64.getEncoder().encodeToString(export.archive.file.readBytes()))
                        }
                        putJsonObject("expected") {
                            put("fingerprint", summary.fingerprint); put("movements", summary.movementCount)
                            put("balance", Json.encodeToJsonElement(ListSerializer(WalletBalance.Detail.serializer()), summary.balance.details))
                        }
                    }
                    File(context.cacheDir, "native-backup-android.json").writeText(fixture.toString())
                } finally { export.discard() }
            }
        } finally { session.close(); root.deleteRecursively() }
    }

    @Test fun restoresIOSServiceExport() = runBlocking<Unit> {
        val cases = Json.parseToJsonElement(instrumentation.context.assets.open("native-backups.json")
            .bufferedReader().use { it.readText() }).jsonObject["cases"]!!.jsonArray
        val fixture = cases.first { it.jsonObject["input"]!!.jsonObject["producer"]!!.jsonPrimitive.content == "ios" }.jsonObject
        val input = fixture["input"]!!.jsonObject
        val expected = fixture["expected"]!!.jsonObject
        val root = File(context.noBackupFilesDir, "cross-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val encrypted = File(root, "ios.noahbackup").apply { writeBytes(java.util.Base64.getDecoder().decode(input["fileBase64"]!!.jsonPrimitive.content)) }
        val location = WalletDataLocation(File(root, "restored").apply { mkdirs() }, "regtest")
        val session = WalletSession("regtest", location)
        val seed = MemorySeed()
        try {
            // Loopback serves the exact committed file; production download/validation/transaction/opener run.
            FixtureServer(encrypted).use { server ->
                val restorer = WalletRestorer("regtest", session, location, seed,
                    makeClient = { ServerClient.make(server.url) })
                val outcome = restorer.restore(input["mnemonic"]!!.jsonPrimitive.content)
                assertEquals("ios", outcome.backupId); assertFalse(outcome.cleanupPending)
                assertTrue(seed.value == input["mnemonic"]!!.jsonPrimitive.content); assertFalse(location.restorePending)
                session.ensureOpen(seed.value!!, false, "cross-backup")
                val summary = session.summary()!!
                assertEquals(expected["fingerprint"]!!.jsonPrimitive.content, summary.fingerprint)
                assertEquals(expected["movements"]!!.jsonPrimitive.int, summary.movementCount)
                assertTrue(session.withOpen { it.history() }.isEmpty())
                assertEquals(expected["balance"], Json.encodeToJsonElement(ListSerializer(WalletBalance.Detail.serializer()), summary.balance.details))
            }
        } finally { session.close(); root.deleteRecursively() }
    }

    /** Reseals [encrypted] as a newer backup: a future manifest format, or a future bark schema in its snapshot. */
    private fun reseal(encrypted: File, root: File, kind: String) {
        val opened = BackupArchive.open(encrypted, phrase, File(root, "unpacked"))
        val manifest = if (kind == "manifest") opened.manifest.copy(formatVersion = 3) else {
            android.database.sqlite.SQLiteDatabase.openDatabase(opened.snapshot.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("INSERT INTO migrations(value) VALUES(9999)")
            }
            opened.manifest.copy(barkSchemaVersion = 9999, snapshotSha256 = BackupArchive.sha256(opened.snapshot))
        }
        check(encrypted.delete())
        BackupArchive.seal(opened.snapshot, manifest, phrase, encrypted, root)
    }

    private suspend fun refuseNewer(kind: String): Exception {
        val fixture = Json.parseToJsonElement(instrumentation.context.assets.open("native-backups.json")
            .bufferedReader().use { it.readText() }).jsonObject["cases"]!!.jsonArray
            .first { it.jsonObject["input"]!!.jsonObject["producer"]!!.jsonPrimitive.content == "ios" }.jsonObject["input"]!!.jsonObject
        val root = File(context.noBackupFilesDir, "future-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val location = WalletDataLocation(File(root, "existing"), "regtest")
        val session = WalletSession("regtest", location)
        val seed = MemorySeed().apply { value = phrase }
        try {
            val encrypted = File(root, "future.noahbackup").apply {
                writeBytes(java.util.Base64.getDecoder().decode(fixture["fileBase64"]!!.jsonPrimitive.content))
            }
            if (kind == "envelope") {
                val bytes = encrypted.readBytes(); bytes[0] = 3; encrypted.writeBytes(bytes)
            } else if (kind == "manifest" || kind == "schema") reseal(encrypted, root, kind)
            location.directory.mkdirs(); location.database.writeText("previous wallet bytes")
            val before = location.database.readBytes()
            val failure = FixtureServer(encrypted, if (kind == "list") 3 else 2).use { server ->
                runCatching { WalletRestorer("regtest", session, location, seed,
                    makeClient = { ServerClient.make(server.url) },
                    beginOnboarding = { fail("A newer backup reached the installation boundary") }).restore(phrase) }.exceptionOrNull()
            }
            assertArrayEquals(before, location.database.readBytes())
            assertTrue(seed.value == phrase); assertFalse(location.restorePending); assertFalse(session.isOpen)
            assertTrue(location.restoreDirectory.listFiles().orEmpty().isEmpty())
            return failure as? Exception ?: error("Newer backup was accepted")
        } finally { session.close(); root.deleteRecursively() }
    }

    @Test fun refusesNewerBackupsWithoutPartialRestore() = runBlocking<Unit> {
        for (kind in listOf("list", "manifest", "envelope", "schema")) {
            val failure = refuseNewer(kind)
            when (kind) {
                "list" -> assertEquals(WalletRestorer.Reason.unsupportedFormat, (failure as WalletRestorer.Failure).reason)
                "manifest" -> assertEquals(BackupArchive.Reason.newerVersion, (failure as BackupArchive.Failure).reason)
                "envelope" -> assertEquals(BackupCrypto.Reason.newerVersion, (failure as BackupCrypto.Failure).reason)
                else -> assertTrue(failure.message.orEmpty().contains("newer than supported version"))
            }
        }
    }

    @Test fun newerBackupsExplainUpdateRequired() = runBlocking<Unit> {
        for (kind in listOf("manifest", "envelope", "schema")) {
            val failure = refuseNewer(kind)
            assertTrue("$kind must explain that an app update is required",
                com.noahwallet.ui.RestoreIssue.from(failure).message ==
                    "This backup was created by a newer version of Noah. Update the app, then try restoring again.")
        }
    }

    /** Minimal local HTTP fixture: production Ktor/OkHttp download code, no external server. */
    private class FixtureServer(file: File, format: Int = 2) : java.io.Closeable {
        private val listener = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        private val bytes = file.readBytes()
        private val info =
            """{"backup_id":"ios","created_at":"2026-10-20T00:00:00Z","encrypted_sha256":"${BackupArchive.sha256(bytes)}",""" +
                """"encrypted_size":${bytes.size},"format_version":$format}"""
        private val thread = Thread {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    it.soTimeout = 5000
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    do { val header = reader.readLine() } while (!header.isNullOrEmpty())
                    val response = when (path) {
                        "/v0/backup/v2/list" -> "[$info]".toByteArray()
                        "/v0/backup/v2/download" -> """{"backup":$info,"download_url":"$url/file"}""".toByteArray()
                        "/file" -> bytes
                        else -> error("Unexpected fixture route")
                    }
                    it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: " +
                        "close\r\n\r\n").toByteArray())
                    it.getOutputStream().write(response)
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { listener.close(); thread.join(6000); check(!thread.isAlive) }
    }
}
