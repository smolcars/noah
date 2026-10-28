package com.noahwallet.onchain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.send.*
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import io.github.niteshbalusu11.bark.generateMnemonic
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in, fingerprint-scoped disposable-wallet stages. Only public evidence leaves the app. */
@RunWith(AndroidJUnit4::class)
class Ffi050LiveTest {
    @Test fun acceptance() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val stage = args.getString("noahFfi050")
        assumeTrue(stage != null && BuildConfig.APP_VARIANT == "regtest")
        require(args.getString("owner") == "4978-disposable")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = WalletSession.shared(context)
        val location = WalletDataLocation(context.filesDir, "regtest")
        val seed = SeedStorage(context, "regtest")
        val marker = File(context.filesDir, "ffi050-owned-4978")
        val run = Stage(stage, context, session, location)
        try {
            if (stage == "create") return@runBlocking run.create(seed, marker)
            check(marker.exists())
            session.ensureOpen((seed.read() as SecretResult.Found).value, false, "ffi050-4978")
            check(session.summary()!!.fingerprint == marker.readText())
            when (stage) {
                "sync" -> run.syncOverview()
                "board-amount", "board-max", "auto-review" -> run.board()
                "cancel-cycle" -> run.cancelCycle()
                "cancel-reopen" -> run.cancelReopen()
                "offboard", "offboard-status" -> run.offboard(args)
                else -> error("Unknown stage")
            }
        } finally { session.closeSettled() }
    }

    /** One stage's wallet, and where its public evidence goes. */
    private class Stage(val name: String?, val context: android.content.Context, val session: WalletSession, val location: WalletDataLocation) {
        val json = Json { encodeDefaults = true }
        fun save(value: JsonElement) = File(context.cacheDir, "ffi050-$name.json").writeText(value.toString())
        suspend fun done() { session.onchainJournal.loadAll().filter { it.receipt != null && !it.receipt.broadcastRetryPending }.forEach {
            session.onchainJournal.acknowledge(it.id)
        } }
    }

    private suspend fun Stage.create(seed: SeedStorage, marker: File) {
        check(!location.hasAnyData && seed.read() == SecretResult.NotFound && !marker.exists())
        val phrase = generateMnemonic()
        check(seed.save(phrase) is SecretResult.Found)
        session.createNew(phrase, false, "ffi050-4978")
        session.sync()
        val fingerprint = session.summary()!!.fingerprint
        marker.writeText(fingerprint)
        SettingsStorage(context).apply {
            setPersistedState(PersistedWalletState(isInitialized = true, isWalletLoaded = true,
                staticVtxoPubkey = session.peekPublicKey(0u), isBiometricsEnabled = false,
                hasSeenBatteryOptimizationPrompt = true), PersistedWalletState.key, 0)
            set(SettingsPreferences.autoBoard(string("transaction-storage"), false), "transaction-storage")
            set(SettingsPreferences.update(string("profile-storage"), mapOf("bitcoinAmountUnit" to JsonPrimitive("sats"))), "profile-storage")
            sync()
        }
        save(buildJsonObject { put("fingerprint", fingerprint); put("address", session.exitDepositAddress()) })
    }

    private suspend fun Stage.syncOverview() {
        session.sync()
        val overview = session.onchainOverview()
        save(buildJsonObject {
            put("confirmed", overview.confirmed)
            put("tip", overview.tip)
            put("address", session.exitDepositAddress())
            put("arkSpendable", session.summary()!!.balance.ark.spendable.toLong())
        })
    }

    private suspend fun Stage.board() {
        session.sync(); done()
        val quote = if (name == "auto-review") checkNotNull(session.autoBoardQuote())
        else session.quoteOnchain(OnchainAction.board, amount = if (name == "board-max") 0 else 30_000)
        val prepared = checkNotNull(quote.preparedBoard)
        check(quote.current() && !reviewExplanation(quote).contains("estimates"))
        check(quote.net == quote.amount - checkNotNull(quote.fee))
        val before = session.withOpen { it.transactions().map { tx -> tx.txid } }
        check(prepared.txid !in before) { "Preparation must not record a transaction" }
        if (name == "auto-review") {
            assertTrue(quote.reserveLeft >= 5000)
            session.discardBoardReview(quote)
            save(buildJsonObject { put("review", json.encodeToJsonElement(quote)); put("discardedWithoutSubmission", true) })
        } else {
            session.executeOnchain(quote)
            session.checkOnchainAttempt(quote.id)
            val receipt = session.onchainJournal.loadAll().last().receipt!!
            assertEquals(prepared.txid, receipt.txid)
            assertEquals(quote.chainFee, receipt.onchainFee)
            save(buildJsonObject { put("review", json.encodeToJsonElement(quote)); put("receipt", json.encodeToJsonElement(receipt)) })
        }
    }

    private suspend fun Stage.cancelCycle() {
        session.sync(); done()
        val ids = session.onchainOverview().vtxos.filter { it.eligible }.map { it.id }
        check(ids.isNotEmpty())
        session.executeOnchain(session.quoteOnchain(OnchainAction.startExit, ids))
        done()
        for (id in ids) session.executeOnchain(session.quoteOnchain(OnchainAction.cancel, listOf(id)))
        File(context.filesDir, "ffi050-canceled.json").writeText(json.encodeToString(ids))
        assertFalse(session.withOpen { it.m6.exits().any { exit -> exit.vtxoId in ids } })
        save(buildJsonObject { put("canceledIDs", json.encodeToJsonElement(ids)); put("activeListEmptyForCanceled", true) })
    }

    private suspend fun Stage.cancelReopen() {
        val ids = json.decodeFromString<List<String>>(File(context.filesDir, "ffi050-canceled.json").readText())
        // Prove the SDK, not Noah's discovery file or movement bridge, supplies the rows.
        File(location.directory, "noah-exit-history.json").delete()
        val all = session.onchainOverview().exits
        assertTrue(ids.all { id -> all.any { it.id == id && it.state == "Canceled" &&
            it.timeline.map { item -> item.state } == listOf("Start", "Canceled") } })
        assertFalse(session.withOpen { it.m6.exits().any { exit -> exit.vtxoId in ids } })
        save(buildJsonObject {
            put("rows", JsonArray(all.map { row -> buildJsonObject {
                put("id", row.id)
                put("amount", row.amount)
                put("state", row.state)
                put("history", JsonArray(row.timeline.map { JsonPrimitive(it.state) }))
            } }))
            put("withoutNoahDiscoveryIndex", true)
        })
    }

    private suspend fun Stage.offboard(args: android.os.Bundle) {
        val journal = SendJournal(File(location.directory, "noah-send-attempt.json"))
        val controller = SendController(SendController.Dependencies(journal::read, journal::write) { body -> session.withSendReservation(body) },
            CoroutineScope(SupervisorJob() + Dispatchers.Default))
        if (name == "offboard") {
            check(journal.read() == null)
            session.sync()
            val payment = SendPayment(checkNotNull(args.getString("recipient")), "onchain", 10_000, SendSource.offchain)
            val quote = session.quoteSend(payment)
            controller.submit(payment, quote)
            withTimeout(90_000) { while (controller.state == SendController.State.submitting) delay(100) }
            check(controller.attempt?.receipt?.txid != null) { "No exact offboard receipt returned" }
        } else controller.reloadForWalletChange()
        controller.checkStatus()
        val receipt = checkNotNull(controller.attempt?.receipt)
        checkNotNull(receipt.chainStatus)
        save(json.encodeToJsonElement(receipt))
    }
}
