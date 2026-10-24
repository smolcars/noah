package com.noahwallet.onchain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in stages for bark-bolt-ffi 0.7.0 on an owned disposable emulator, on the wallet `Ffi060UpgradeLiveTest` created
 *  with the 0.6.0 build and verified after the upgrade, against a Bark 0.8 stack selected with the regtest overrides.
 *  The host funds and mines between stages (`-e noahFfi070 <stage> -e owner ffi070-disposable`); each stage writes
 *  only public evidence to cache/ffi070-<stage>.json. Nothing here starts an exit. */
@RunWith(AndroidJUnit4::class)
class Ffi070LiveTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = WalletSession.shared(context)
    private val store = AutoBoardFunding.stored(SettingsStorage(context))

    @Test fun acceptance() = runBlocking {
        val stage = args.getString("noahFfi070")
        assumeTrue(stage != null && BuildConfig.APP_VARIANT == "regtest")
        require(args.getString("owner") == "ffi070-disposable")
        require(BuildConfig.REGTEST_ARK_URL.isNotEmpty() && BuildConfig.REGTEST_BITCOIND_URL.isNotEmpty())
        val marker = File(context.filesDir, "ffi060-upgrade-owned")
        check(marker.exists())
        session.ensureOpen((SeedStorage(context, "regtest").read() as SecretResult.Found).value, false, "ffi070-live")
        check(session.summary()!!.fingerprint == marker.readText())
        try {
            when (stage) {
                "addresses" -> evidence("addresses", buildJsonObject {
                    put("claimed", session.exitDepositAddress()); put("deposit", session.exitDepositAddress())
                })
                "auto-board" -> autoBoard(checkNotNull(args.getString("claimed")), args.getString("release"))
                "board-confirmed" -> boardConfirmed()
                "exit-all" -> exitAll()
                else -> error("Unknown stage $stage")
            }
        } finally { session.closeSettled() }
    }

    /**
     * [claimed] ("txid:vout") goes into the claimed set as a claim to this phone writes it; auto-board then boards the rest.
     * [release]: a deposit an earlier run's review claimed but never submitted (no attempt, preparation discarded), released
     * as a failed confirmation releases it.
     */
    private suspend fun autoBoard(claimed: String, release: String?) {
        session.sync()
        val walletID = session.summary()!!.fingerprint
        release?.let { check(session.onchainJournal.loadAll().isEmpty()); store.release(AutoBoardFunding.Snapshot(walletID, setOf(it)), 0) }
        store.keep(walletID, setOf(claimed))
        val review = AutoBoardReview(store)
        val quote = checkNotNull(review.prepare({ true }, { session.autoBoardFunding(store::kept) }, { session.autoBoardQuote(store::kept) },
            { session.discardBoardReview(it) })) {
            "Auto-board offered nothing: funding ${session.autoBoardFunding(store::kept)}, confirmed ${session.summary()!!.balance.onchain.confirmed}, " +
                "minimum ${session.boardingMinimum()}, kept ${store.kept(walletID)}, unhandled ${session.autoBoardFunding(store::kept)?.let(store::hasUnhandled)}"
        }
        val funding = review.fundingFor(quote)
        val board = checkNotNull(quote.preparedBoard)
        assertTrue("the claimed coin stays out of auto-board", claimed in store.kept(walletID))
        assertTrue("bark didn't spend the claimed coin", board.inputs.none { "${it.txid}:${it.vout}" == claimed })
        val message = session.executeOnchain(quote, funding, kept = store::kept)
        val attempt = session.onchainJournal.loadAll().single { it.id == quote.id }
        assertEquals(board.txid, attempt.receipt?.txid)
        evidence("auto-board", buildJsonObject {
            put("walletID", walletID); put("claimed", claimed); put("confirmedCoins", JsonArray(funding.outpoints.sorted().map(::JsonPrimitive)))
            put("confirmed", quote.confirmed); put("amount", quote.amount); put("net", quote.net); put("arkFee", quote.fee)
            put("chainFee", quote.chainFee); put("feeRate", quote.feeRate); put("reserveLeft", quote.reserveLeft)
            put("inputs", JsonArray(board.inputs.map { JsonPrimitive("${it.txid}:${it.vout}") })); put("boardTxid", board.txid)
            put("receipt", message)
        })
    }

    private suspend fun boardConfirmed() {
        session.sync()
        val summary = session.summary()!!
        evidence("board-confirmed", buildJsonObject {
            put("arkSpendable", summary.spendableSats.toLong()); put("onchainConfirmed", summary.balance.onchain.confirmed.toLong())
            put("boards", JsonArray(session.movements().filter { it.subsystemName == "bark.board" }.map { JsonPrimitive("${it.id}:${it.status}:${it.effectiveBalanceSats}") }))
        })
    }

    /** Choose VTXOs' "All": bark's whole-wallet estimate for what it leaves out, each VTXO's own, then the selection's. */
    private suspend fun exitAll() {
        session.sync()
        val vtxos = session.onchainOverview().vtxos
        val excluded = session.exitExcluded()
        val costs = session.exitCosts(vtxos.filter { it.eligible }.map { it.id })
        val all = ExitCosts.all(vtxos, costs, ExitErrors.refused, excluded)
        assertTrue("something to exit", all.isNotEmpty())
        val quote = session.quoteOnchain(OnchainAction.startExit, all.map { it.id })
        assertFalse("bark priced it", quote.feesUnknown)
        evidence("exit-all", buildJsonObject {
            put("vtxos", JsonArray(vtxos.map { JsonPrimitive("${it.id}:${it.amount}:${it.state}") }))
            put("excluded", JsonArray(excluded.sorted().map(::JsonPrimitive))); put("refused", JsonObject(ExitErrors.refused.mapValues { JsonPrimitive(it.value) }))
            put("all", JsonArray(all.map { JsonPrimitive(it.id) })); put("amount", quote.amount); put("networkFees", quote.chainFee)
            put("claimFee", quote.fee); put("feeRate", quote.feeRate); put("transactions", quote.transactions)
        })
    }

    private fun evidence(stage: String, value: JsonObject) = File(context.cacheDir, "ffi070-$stage.json").writeText(value.toString())
}
