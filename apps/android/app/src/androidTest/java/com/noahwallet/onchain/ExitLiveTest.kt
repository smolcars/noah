package com.noahwallet.onchain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
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

/** Opt-in stages require a wallet created here; never opens an unowned wallet for mutation. */
@RunWith(AndroidJUnit4::class)
class ExitLiveTest {
    @Test fun regtestAcceptance() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val stage = args.getString("noahM6Live")
        assumeTrue(BuildConfig.APP_VARIANT == "regtest" && stage != null)
        require(args.getString("noahM6Owner") == "2f7e-throwaway")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val location = WalletDataLocation(context.filesDir, "regtest")
        val seed = SeedStorage(context, "regtest")
        val settings = SettingsStorage(context)
        val owned = File(context.filesDir, "noah-m6-owned-2f7e")
        val session = WalletSession.shared(context)
        val run = Stage(stage, context, session, settings, File(context.cacheDir, "m6-$stage.json"))
        try {
            if (stage == "create") return@runBlocking run.create(location, seed, owned)
            check(owned.exists()) { "Refusing to use an unowned wallet" }
            val mnemonic = (seed.read() as SecretResult.Found).value
            // Cancellation must win before final broadcast: only this explicit stage disables the daemon.
            session.ensureOpen(mnemonic, stage != "cancel-cycle", "m6-owned-live")
            check(session.summary()!!.fingerprint == owned.readText())
            run.perform()
        } finally { session.closeSettled() }
    }

    /** One stage's wallet, and where its public evidence goes. */
    private class Stage(val name: String?, val context: android.content.Context, val session: WalletSession, val settings: SettingsStorage,
        private val evidence: File) {
        fun save(vararg values: Pair<String, JsonElement>) = evidence.writeText(JsonObject(mapOf(*values)).toString())
        suspend fun done() {
            session.onchainJournal.loadAll().filter { it.receipt != null && it.overrideAudit == null }.forEach { session.onchainJournal.acknowledge(it.id) }
        }
        suspend fun reviewExecute(action: OnchainAction, ids: List<String> = emptyList(), amount: Long = 0, destination: String? = null): OnchainQuote {
            val quote = session.quoteOnchain(action, ids, amount, destination)
            check(quote.current())
            check(reviewExplanation(quote).isNotBlank())
            session.executeOnchain(quote)
            return quote
        }
    }

    private suspend fun Stage.perform() {
        when (name) {
            "funded" -> funded()
            "board" -> board()
            "board-all" -> boardAll()
            "inspect" -> inspect()
            "ledger" -> ledger()
            "refresh" -> refresh()
            "estimate" -> estimate()
            "start" -> start()
            "status", "claimed" -> status()
            "progress" -> progress()
            "claim" -> claim()
            "cancel-cycle" -> cancelCycle()
            "cancel-reopen" -> cancelReopen()
            else -> error("Unknown stage")
        }
    }

    private suspend fun Stage.create(location: WalletDataLocation, seed: SeedStorage, owned: File) {
        check(!location.hasAnyData && seed.read() == SecretResult.NotFound && !owned.exists())
        val mnemonic = generateMnemonic()
        check(seed.save(mnemonic) is SecretResult.Found)
        session.createNew(mnemonic, true, "m6-owned-live")
        val summary = checkNotNull(session.summary())
        owned.writeText(summary.fingerprint)
        settings.setPersistedState(PersistedWalletState(isInitialized = true, isWalletLoaded = true), PersistedWalletState.key, 0)
        settings.set(SettingsPreferences.autoBoard(settings.string("transaction-storage"), false), "transaction-storage")
        settings.set(SettingsPreferences.update(settings.string("profile-storage"),
            mapOf("bitcoinAmountUnit" to JsonPrimitive("sats"))), "profile-storage")
        settings.sync()
        save("fingerprint" to JsonPrimitive(summary.fingerprint), "address" to JsonPrimitive(session.exitDepositAddress()))
    }

    private suspend fun Stage.funded() {
        session.sync()
        save("confirmed" to JsonPrimitive(session.onchainOverview().confirmed))
        assertTrue(session.onchainOverview().confirmed >= 100_000)
    }

    private suspend fun Stage.board() {
        session.sync(); done()
        val quote = reviewExecute(OnchainAction.board, amount = 30_000)
        val receipt = session.onchainJournal.loadAll().last().receipt!!
        save("gross" to JsonPrimitive(quote.amount), "arkFee" to JsonPrimitive(quote.fee), "estimatedChainFee" to JsonPrimitive(quote.chainFee),
            "txid" to JsonPrimitive(receipt.txid), "actualChainFee" to JsonPrimitive(receipt.onchainFee))
    }

    /** Boards every confirmed coin (MAX), leaving no confirmed on-chain funds: an exit then needs them. */
    private suspend fun Stage.boardAll() {
        session.sync(); done()
        val quote = reviewExecute(OnchainAction.board)
        val receipt = session.onchainJournal.loadAll().last().receipt!!
        save("gross" to JsonPrimitive(quote.amount), "boardAll" to JsonPrimitive(quote.boardAll), "txid" to JsonPrimitive(receipt.txid),
            "confirmedAfter" to JsonPrimitive(session.onchainOverview().confirmed))
    }

    /** What the exit pages read: every exit's state, its transactions' weights and fee children, and the waiting package's fee. */
    private suspend fun Stage.inspect() {
        session.sync()
        val overview = session.onchainOverview()
        val wallet = session.withOpen { it.transactions() }.map { tx -> JsonObject(mapOf("txid" to JsonPrimitive(tx.txid),
            "fee" to (tx.onchainFeeSats?.let { JsonPrimitive(it.toLong()) } ?: JsonNull), "change" to JsonPrimitive(tx.balanceChangeSats))) }
        save("tip" to JsonPrimitive(overview.tip), "confirmed" to JsonPrimitive(overview.confirmed), "wallet" to JsonArray(wallet),
            "exits" to JsonArray(overview.exits.map { exit ->
                val detail = session.exitDetail(exit.id)
                JsonObject(mapOf("id" to JsonPrimitive(exit.id), "state" to JsonPrimitive(exit.state), "amount" to JsonPrimitive(exit.amount),
                    "nextPackageFee" to (exit.nextPackageFee?.let(::JsonPrimitive) ?: JsonNull),
                    "detailNextPackageFee" to (detail?.nextPackageFee?.let(::JsonPrimitive) ?: JsonNull),
                    "childFees" to JsonObject(exit.childFees.mapValues { JsonPrimitive(it.value) }),
                    "packages" to JsonArray(exit.packages.map { Json.encodeToJsonElement(mapOf("txid" to it.txid, "weight" to it.weight?.toString(),
                        "outputs" to it.outputs.toString(), "spends" to it.spends?.toString(), "child" to it.childTxid,
                        "origin" to it.childOrigin)) }),
                    "details" to Json.encodeToJsonElement(exit.details),
                    "history" to JsonArray(exit.historyDetails.map { Json.encodeToJsonElement(it) })))
            }))
    }

    /** Auto-board's handled ledger and what it would offer now: a claim to this phone must be handled, never offered. */
    private suspend fun Stage.ledger() {
        session.sync()
        val funding = session.autoBoardFunding()
        save("ledger" to JsonPrimitive(settings.string(AutoBoardFunding.key) ?: ""),
            "confirmedIncoming" to JsonArray(funding?.outpoints.orEmpty().map(::JsonPrimitive)),
            "unhandled" to JsonPrimitive(funding?.let(AutoBoardFunding.stored(settings)::hasUnhandled) ?: false))
    }

    private suspend fun Stage.refresh() {
        session.sync(); done()
        val item = session.onchainOverview().vtxos.first { it.refreshable }
        val quote = reviewExecute(OnchainAction.refresh, listOf(item.id))
        save("id" to JsonPrimitive(item.id), "amount" to JsonPrimitive(item.amount), "fee" to JsonPrimitive(quote.fee),
            "receipt" to JsonPrimitive(session.onchainJournal.loadAll().last().receipt!!.message))
    }

    private suspend fun Stage.estimate() {
        session.sync(); done()
        val before = session.withOpen { it.transactions().map { tx -> tx.txid } }
        val overview = session.onchainOverview()
        val ids = overview.vtxos.filter { it.eligible }.map { it.id }
        assertTrue(ids.isNotEmpty())
        val quote = session.quoteOnchain(OnchainAction.startExit, ids)
        assertEquals(overview.exits, session.onchainOverview().exits)
        assertEquals(before, session.withOpen { it.transactions().map { tx -> tx.txid } })
        save("ids" to JsonArray(ids.map(::JsonPrimitive)), "broadcastFee" to JsonPrimitive(quote.chainFee),
            "claimFee" to JsonPrimitive(quote.fee), "net" to JsonPrimitive(quote.net), "readOnly" to JsonPrimitive(true))
    }

    private suspend fun Stage.start() {
        done()
        val ids = session.onchainOverview().vtxos.filter { it.eligible }.map { it.id }
        assertTrue(ids.isNotEmpty())
        val quote = reviewExecute(OnchainAction.startExit, ids)
        File(context.filesDir, "m6-started-ids.json").writeText(Json.encodeToString(ids))
        save("ids" to JsonArray(ids.map(::JsonPrimitive)), "broadcastFee" to JsonPrimitive(quote.chainFee),
            "claimFee" to JsonPrimitive(quote.fee), "receipt" to JsonPrimitive(session.onchainJournal.loadAll().last().receipt!!.message))
    }

    private suspend fun Stage.status() {
        val overview = session.onchainOverview()
        val ids = Json.decodeFromString<List<String>>(File(context.filesDir, "m6-started-ids.json").readText())
        val exits = ids.map { checkNotNull(session.exitDetail(it)) }
        if (name == "claimed") assertTrue(exits.all { it.state == "Claimed" })
        save("tip" to JsonPrimitive(overview.tip), "states" to JsonArray(exits.map { JsonPrimitive(it.state) }),
            "claimableHeight" to JsonArray(exits.map { it.details.claimable_height?.let(::JsonPrimitive) ?: JsonNull }),
            "timeline" to JsonArray(exits.flatMap { it.timeline.map { item -> JsonPrimitive(item.state) } }),
            "details" to JsonArray(exits.map { Json.encodeToJsonElement(it.details) }))
    }

    private suspend fun Stage.progress() {
        done()
        val ids = session.onchainOverview().exits.filter { it.state !in ExitTimeline.terminal }.map { it.id }
        reviewExecute(OnchainAction.progress, ids)
        save("receipt" to JsonPrimitive(session.onchainJournal.loadAll().last().receipt!!.message))
    }

    private suspend fun Stage.claim() {
        done()
        val ids = session.onchainOverview().exits.filter { it.claimable }.map { it.id }
        assertTrue(ids.isNotEmpty())
        val destination = session.exitDepositAddress()
        val quote = reviewExecute(OnchainAction.claim, ids, destination = destination)
        val receipt = session.onchainJournal.loadAll().last().receipt!!
        save("ids" to JsonArray(ids.map(::JsonPrimitive)), "fee" to JsonPrimitive(quote.fee), "destination" to JsonPrimitive(destination),
            "txid" to JsonPrimitive(receipt.txid))
    }

    private suspend fun Stage.cancelCycle() {
        session.sync(); done()
        // An additional reviewed board supplies a fresh, spendable input for cancellation.
        val ids = session.onchainOverview().vtxos.filter { it.eligible }.map { it.id }
        assertTrue(ids.isNotEmpty())
        reviewExecute(OnchainAction.startExit, ids)
        done()
        for (id in ids) reviewExecute(OnchainAction.cancel, listOf(id))
        File(context.filesDir, "m6-canceled-ids.json").writeText(Json.encodeToString(ids))
        assertTrue(ids.all { id -> session.onchainOverview().exits.any { it.id == id && it.state == "Canceled" } })
        save("ids" to JsonArray(ids.map(::JsonPrimitive)), "states" to JsonArray(ids.map { JsonPrimitive(session.exitDetail(it)!!.state) }))
    }

    private suspend fun Stage.cancelReopen() {
        val ids = Json.decodeFromString<List<String>>(File(context.filesDir, "m6-canceled-ids.json").readText())
        val exits = ids.map { checkNotNull(session.exitDetail(it)) }
        val activeCanceled = session.withOpen { handles -> handles.m6.exits().any { it.vtxoId in ids } }
        assertFalse(activeCanceled)
        assertTrue(exits.all { it.state == "Canceled" && it.timeline.last().state == "Canceled" })
        assertTrue(ids.all { id -> session.onchainOverview().exits.any { it.id == id } })
        save("activeCanceled" to JsonPrimitive(activeCanceled), "ids" to JsonArray(ids.map(::JsonPrimitive)),
            "states" to JsonArray(exits.map { JsonPrimitive(it.state) }),
            "timeline" to JsonArray(exits.flatMap { it.timeline.map { item -> JsonPrimitive(item.state) } }),
            "details" to JsonArray(exits.map { Json.encodeToJsonElement(it.details) }))
    }
}
