package com.noahwallet.send

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** In-place 0.5.0 → 0.6.0 upgrade on an owned disposable emulator. Uses only APIs both SDKs share, so the same
 *  file runs in the previous build (create, fund, send, baseline) and in this one (`upgrade-verify`). */
@RunWith(AndroidJUnit4::class)
class Ffi060UpgradeLiveTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = WalletSession.shared(context)
    private val location = WalletDataLocation(context.filesDir, "regtest")
    private val baseline = File(context.filesDir, "ffi060-upgrade-baseline.json")

    @Test fun upgrade() = runBlocking {
        val stage = args.getString("noahFfi060Upgrade")
        assumeTrue(stage != null && BuildConfig.APP_VARIANT == "regtest")
        require(args.getString("owner") == "ffi060-disposable")
        require(BuildConfig.REGTEST_ARK_URL.isNotEmpty() && BuildConfig.REGTEST_BITCOIND_URL.isNotEmpty())
        val seed = SeedStorage(context, "regtest")
        val marker = File(context.filesDir, "ffi060-upgrade-owned")
        if (stage == "create") return@runBlocking create(seed, marker)
        check(marker.exists())
        session.ensureOpen((seed.read() as SecretResult.Found).value, false, "ffi060-upgrade")
        check(session.summary()!!.fingerprint == marker.readText())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            when (stage) {
                "fund" -> fund()
                "send" -> sendBaseline(scope)
                "verify" -> verify()
                else -> error("Unknown stage $stage")
            }
        } finally { session.closeSettled(); scope.cancel() }
    }

    private suspend fun create(seed: SeedStorage, marker: File) {
        check(!location.hasAnyData && seed.read() == SecretResult.NotFound && !marker.exists())
        val phrase = generateMnemonic()
        check(seed.save(phrase) is SecretResult.Found)
        session.createNew(phrase, false, "ffi060-upgrade")
        marker.writeText(session.summary()!!.fingerprint)
        SettingsStorage(context).apply {
            setPersistedState(PersistedWalletState(isInitialized = true, isWalletLoaded = true,
                staticVtxoPubkey = session.peekPublicKey(0u), isBiometricsEnabled = false,
                hasSeenBatteryOptimizationPrompt = true), PersistedWalletState.key, 0)
            set(SettingsPreferences.autoBoard(string("transaction-storage"), false), "transaction-storage")
            sync()
        }
        session.closeSettled()
    }

    private suspend fun fund() {
        suspend fun received() = session.movements().any { it.subsystemName == "bark.lightning_receive" && it.status == "successful" }
        if (!received()) {
            val request = session.receiveInvoice(session.receiveRequest(), 10_000, "")
            File(context.cacheDir, "ffi060-upgrade-invoice.txt").writeText(checkNotNull(request.lightningInvoice))
            poll(240) { session.sync(includingLightningClaims = true); received() }
        }
    }

    /** Sends over Ark and Lightning, then records the wallet and its database schema before the upgrade. */
    private suspend fun sendBaseline(scope: CoroutineScope) {
        session.sync()
        val peer = File(context.cacheDir, "ffi060-upgrade-peer-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val config = WalletConfiguration.base("regtest").sdkConfig("noah-ffi060-peer/1")
            val mnemonic = generateMnemonic()
            val onchain = OnchainWallet.default(Network.REGTEST, mnemonic, config, peer.path)
            val wallet = Wallet.openWithOnchain(Network.REGTEST, mnemonic, config,
                WalletOpenArgs(runDaemon = false, datadir = peer.path, createIfNotExists = true), onchain)
            try { send(scope, SendPayment(wallet.newAddress(), "ark", 3_000)) } finally { wallet.close(); onchain.close() }
        } finally { peer.deleteRecursively() }
        send(scope, SendPayment(checkNotNull(args.getString("invoice")), "lightning", 2_000))
        val summary = session.summary()!!
        val saved = buildJsonObject {
            put("fingerprint", summary.fingerprint); put("spendable", summary.spendableSats.toLong()); put("movements", rows())
        }
        session.closeSettled()
        val result = JsonObject(saved + schema())
        baseline.writeText(result.toString())
        evidence("baseline", result)
    }

    /** After the upgrade: the same wallet, balance, movements and schema as the baseline. */
    private suspend fun verify() {
        val expected = Json.parseToJsonElement(baseline.readText()).jsonObject
        val summary = session.summary()!!
        assertEquals(expected.getValue("fingerprint").jsonPrimitive.content, summary.fingerprint)
        assertEquals(expected.getValue("spendable").jsonPrimitive.long, summary.spendableSats.toLong())
        assertEquals(expected.getValue("movements"), rows())
        session.sync()
        assertEquals(expected.getValue("movements"), rows())
        assertEquals(expected.getValue("spendable").jsonPrimitive.long, session.summary()!!.spendableSats.toLong())
        session.closeSettled()
        val after = schema()
        assertEquals(expected.getValue("schemaSha256"), after.getValue("schemaSha256"))
        assertEquals(expected.getValue("migration"), after.getValue("migration"))
        evidence("verify", buildJsonObject {
            put("fingerprint", summary.fingerprint); put("movements", expected.getValue("movements").jsonArray.size)
            put("spendable", summary.spendableSats.toLong()); after.forEach { (key, value) -> put(key, value) }
        })
    }

    private suspend fun send(scope: CoroutineScope, payment: SendPayment) {
        val journal = SendJournal(File(location.directory, "noah-send-attempt.json"))
        val controller = SendController(SendController.Dependencies(journal::read, journal::write) { body -> session.withSendReservation(body) }, scope)
        controller.reloadForWalletChange()
        check(controller.state == SendController.State.ready) { "Send journal must be empty: ${controller.state}" }
        withTimeout(240_000) { checkNotNull(controller.submit(payment, session.quoteSend(payment))).await() }
        check(controller.state == SendController.State.succeeded) { "${payment.method} send did not succeed: ${controller.state}" }
        controller.acknowledge()
    }

    private suspend fun rows() = JsonArray(session.movements().sortedBy { it.id }.map {
        buildJsonObject {
            put("id", it.id.toLong()); put("status", it.status); put("subsystem", it.subsystemName); put("kind", it.subsystemKind)
            put("intended", it.intendedBalanceSats); put("effective", it.effectiveBalanceSats); put("fee", it.offchainFeeSats.toLong())
        }
    })

    /** Bark's schema and newest migration, read from a private copy of the closed wallet database. */
    private fun schema(): JsonObject {
        check(!File(location.database.path + "-wal").exists() || File(location.database.path + "-shm").exists())
        val copy = File(context.cacheDir, "ffi060-schema-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            for (suffix in listOf("", "-wal", "-shm")) File(location.database.path + suffix).takeIf { it.exists() }?.copyTo(File(copy, "db.sqlite$suffix"))
            return SQLiteDatabase.openDatabase(File(copy, "db.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val sql = db.rawQuery("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name", null).use { row ->
                    buildString { while (row.moveToNext()) append((0..3).joinToString("\u0000") { row.getString(it) ?: "" }).append('\n') }
                }
                val migration = db.rawQuery("SELECT value FROM migrations ORDER BY value DESC LIMIT 1", null).use { it.moveToFirst(); it.getLong(0) }
                buildJsonObject {
                    put("schemaSha256", MessageDigest.getInstance("SHA-256").digest(sql.toByteArray()).joinToString("") { "%02x".format(it) })
                    put("migration", migration)
                }
            }
        } finally { copy.deleteRecursively() }
    }

    private suspend fun poll(seconds: Int, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (done()) return
            delay(2000)
        }
        check(done()) { "Timed out after $seconds s" }
    }

    private fun evidence(stage: String, value: JsonObject) = File(context.cacheDir, "ffi060-upgrade-$stage.json").writeText(value.toString())
}
