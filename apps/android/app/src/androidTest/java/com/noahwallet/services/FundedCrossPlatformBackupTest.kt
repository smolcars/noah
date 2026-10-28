package com.noahwallet.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.send.SendPayment
import com.noahwallet.services.backup.*
import com.noahwallet.services.server.ServerClient
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import io.github.niteshbalusu11.bark.generateMnemonic
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.encodedPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID

/** Real regtest services/JNI, opt-in on a runner-owned disposable emulator. */
@RunWith(AndroidJUnit4::class)
class FundedCrossPlatformBackupTest {
    private val base get() = InstrumentationRegistry.getArguments().getString("noahFundedBackupURL")!!
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun request(path: String, body: JsonObject? = null): String {
        val connection = URI("$base/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 180_000
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(connection.responseCode == 200) { "Host operation failed: $path" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private suspend fun state(session: WalletSession) = buildJsonObject {
        val summary = session.summary()!!
        put("fingerprint", summary.fingerprint); put("movements", summary.movementCount)
        put("balance", Json.encodeToJsonElement(ListSerializer(WalletBalance.Detail.serializer()), summary.balance.details))
        put("history", buildJsonArray {
            session.transactionHistory().sortedBy { it.id }.forEach { row -> add(buildJsonObject {
                put("id", row.id); put("amount", row.amount); put("type", row.type); put("direction", row.direction)
                row.movementId?.let { put("movementId", it.toLong()) }
                row.movementStatus?.let { put("status", it) }; row.movementKind?.let { put("kind", it) }
                row.offchainFeeSat?.let { put("fee", it) }
            }) }
        })
    }

    @Test fun fundedTransfer() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahFundedBackupURL") != null)
        check(BuildConfig.APP_VARIANT == "regtest")
        val root = File(context.noBackupFilesDir, "funded-backup-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            restoreFromIOS(root)
            exportForNode(root)
        } finally { root.deleteRecursively() }
    }

    /** Consumes the closed iOS source before creating a different Android wallet: restores it, then spends from it. */
    private suspend fun restoreFromIOS(root: File) {
        val location = WalletDataLocation(File(root, "restored").apply { mkdirs() }, "regtest")
        val session = WalletSession("regtest", location)
        val alias = "noah.tests.funded.${UUID.randomUUID()}"
        val storage = SeedStorage(NativeSecretStore(alias, AndroidSecretKeys(),
            AtomicSecretFile(File(root, "seed.bin")))) { SecretResult.NotFound }
        val seed = SeedItem(storage)
        try {
            val input = Json.parseToJsonElement(request("input/ios")).jsonObject
            val phrase = input["phrase"]!!.jsonPrimitive.content
            val outcome = WalletRestorer("regtest", session, location, seed,
                makeClient = { ServerClient.make(base).apply {
                    // Generated Ktor clients use absolute paths; route fixture metadata to this producer.
                    plugin(HttpSend).intercept { request ->
                        if (request.url.encodedPath.startsWith("/v0/")) {
                            request.url.encodedPath = "/ios${request.url.encodedPath}"
                        }
                        execute(request)
                    }
                } }).restore(phrase)
            assertFalse(outcome.cleanupPending); assertFalse(location.restorePending)
            val saved = seed.read()
            assertTrue(saved is SecretResult.Found && saved.value == phrase)
            session.ensureOpen(phrase, true, "funded-backup-test")
            request("check/ios/before-sync", buildJsonObject { put("state", state(session)) })
            session.sync()
            request("check/ios/after-sync", buildJsonObject { put("state", state(session)) })
            val payment = SendPayment(request("recipient"), "ark", 1000)
            val quote = session.quoteSend(payment)
            val receipt = session.withSendReservation { wallet ->
                wallet.pay(payment, quote, wallet.prepare(payment, quote))
            }
            assertTrue(receipt.isProven(payment))
            session.sync()
            request("spent/ios", buildJsonObject { put("state", state(session)); put("fee", quote.fee) })
        } finally {
            session.close()
            storage.deleteWalletSeed()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    /** The Node side pays two Lightning invoices into [source]; each must settle. */
    private suspend fun fund(source: WalletSession) {
        var previous = 0uL
        for (amount in listOf(31_000uL, 7_000uL)) {
            request("pay", buildJsonObject { put("invoice", source.bolt11Invoice(amount)) })
            val deadline = System.nanoTime() + 120_000_000_000L
            while (System.nanoTime() < deadline) {
                source.claimLightningReceives(); source.sync()
                if (source.summary()!!.spendableSats > previous) break
                delay(1000)
            }
            val funded = source.summary()!!
            check(funded.spendableSats > previous) { "Lightning receive did not settle" }
            previous = funded.spendableSats
            request("paid")
        }
    }

    /** A funded Android wallet, exported for the Node side to restore. */
    private suspend fun exportForNode(root: File) {
        val source = WalletSession("regtest", WalletDataLocation(File(root, "source").apply { mkdirs() }, "regtest"))
        try {
            val phrase = generateMnemonic()
            source.createNew(phrase, true, "funded-backup-test")
            request("onchain", buildJsonObject { put("address", source.receiveRequest().onchainAddress) })
            fund(source)
            val funded = source.summary()!!
            check(funded.movementCount >= 2 && funded.balance.onchain.confirmed == 10_000uL)
            val before = state(source)
            ServerClient.make(base).use { client ->
                val export = BackupService(source, client, { phrase }, ScratchSpace(File(root, "scratch")))
                    .exportEncryptedBackup("android.noahbackup")
                try {
                    source.close()
                    request("export/android", buildJsonObject {
                        put("phrase", phrase); put("state", before)
                        put("fileBase64", Base64.getEncoder().encodeToString(export.archive.file.readBytes()))
                    })
                } finally { export.discard() }
            }
        } finally { source.close() }
    }
}
