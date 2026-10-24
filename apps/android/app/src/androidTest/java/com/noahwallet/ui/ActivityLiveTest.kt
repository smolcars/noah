package com.noahwallet.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.send.*
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import io.github.niteshbalusu11.bark.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in stages that give a disposable regtest wallet the payments Activity must show, against a Bark 0.8 stack
 * selected with -PnoahRegtestArkURL / -PnoahRegtestBitcoindURL / -PnoahRegtestServerURL (`-e noahActivityLive <stage>
 * -e owner activity-disposable`). The host pays, fails and mines between stages, then opens the app to check
 * Activity. Each stage writes only public evidence to cache/activity-<stage>.json.
 */
@RunWith(AndroidJUnit4::class)
class ActivityLiveTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = WalletSession.shared(context)
    private val location = WalletDataLocation(context.filesDir, "regtest")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun stage() = runBlocking {
        val stage = args.getString("noahActivityLive")
        assumeTrue(stage != null && BuildConfig.APP_VARIANT == "regtest")
        require(args.getString("owner") == "activity-disposable")
        // Only a second stack selected with the overrides, never the default regtest endpoints.
        require(BuildConfig.REGTEST_ARK_URL.isNotEmpty() && BuildConfig.REGTEST_BITCOIND_URL.isNotEmpty())
        val seed = SeedStorage(context, "regtest")
        val marker = File(context.filesDir, "activity-owned")
        if (stage == "create") {
            check(!location.hasAnyData && seed.read() == SecretResult.NotFound && !marker.exists())
            val phrase = generateMnemonic()
            check(seed.save(phrase) is SecretResult.Found)
            session.createNew(phrase, false, "activity-live")
            marker.writeText(session.summary()!!.fingerprint)
            SettingsStorage(context).apply {
                setPersistedState(PersistedWalletState(isInitialized = true, isWalletLoaded = true,
                    staticVtxoPubkey = session.peekPublicKey(0u), isBiometricsEnabled = false,
                    hasSeenBatteryOptimizationPrompt = true), PersistedWalletState.key, 0)
                set(SettingsPreferences.autoBoard(string("transaction-storage"), false), "transaction-storage")
                sync()
            }
            evidence("create", buildJsonObject { put("arkServer", WalletConfiguration.base("regtest").arkServer) })
            session.closeSettled()
            return@runBlocking
        }
        // `-e adopt 1`: take over a disposable wallet made through the app's own onboarding (Back checks need one).
        check(marker.exists() || args.getString("adopt") == "1")
        val mnemonic = (seed.read() as SecretResult.Found).value
        session.ensureOpen(mnemonic, false, "activity-live")
        if (!marker.exists()) marker.writeText(session.summary()!!.fingerprint)
        check(session.summary()!!.fingerprint == marker.readText())
        try {
            when (stage) {
                "fund" -> fund()
                "pay" -> pay(expectFailure = false)
                "fail" -> pay(expectFailure = true)
                "onchain" -> onchain()
                "nothing-left" -> nothingLeft()
                "address" -> evidence("address", buildJsonObject { put("onchain", session.receiveRequest().onchainAddress) })
                // From the on-chain wallet: pending until its block, with the block row.
                "chain-send" -> {
                    val (controller, _) = send(SendPayment(checkNotNull(args.getString("recipient")), "onchain",
                        checkNotNull(args.getString("amount")).toLong(), SendSource.onchain))
                    val txid = checkNotNull(controller.attempt?.receipt?.txid) { "On-chain wallet send did not succeed: ${controller.state}" }
                    controller.acknowledge()
                    evidence("chain-send", buildJsonObject { put("txid", txid) })
                }
                // A stage that stopped before acknowledging leaves its finished attempt in the journal.
                "ack" -> controller().run { reloadForWalletChange(); acknowledge(); check(state == SendController.State.ready) }
                else -> error("Unknown stage $stage")
            }
        } finally { session.closeSettled(); scope.cancel() }
    }

    /** The host pays this invoice from LND through the server. */
    private suspend fun fund() {
        suspend fun received() = session.movements().filter { it.subsystemName == "bark.lightning_receive" && it.status == "successful" }
        val request = session.receiveInvoice(session.receiveRequest(), args.getString("amount")?.toLong() ?: 9_000, "Activity live funding")
        File(context.cacheDir, "activity-invoice.txt").writeText(checkNotNull(request.lightningInvoice))
        poll(240) { session.sync(includingLightningClaims = true); received().isNotEmpty() }
        evidence("fund", buildJsonObject { put("spendable", session.summary()!!.spendableSats.toLong()); put("movementID", received().first().id.toLong()) })
    }

    /** Pays the host's invoice (`-e invoice`): it either succeeds, or fails with nothing taken from the balance. */
    private suspend fun pay(expectFailure: Boolean) {
        val invoice = checkNotNull(args.getString("invoice"))
        val amount = checkNotNull(PaymentEncoding.invoice(invoice)?.sats).toLong()
        val hash = checkNotNull(PaymentEncoding.invoice(invoice)?.hash)
        session.sync()
        val before = session.summary()!!
        val (controller, _) = send(SendPayment(invoice, "lightning", amount))
        val movement = session.movements().first { it.paymentHash == hash && it.subsystemName == "bark.lightning_send" }
        if (expectFailure) {
            assertEquals(SendController.State.failed, controller.state)
            assertEquals("failed", movement.status)
            poll(90) {
                session.sync()
                val after = session.summary()!!.balance.ark
                after.spendable == before.balance.ark.spendable && after.pendingLightningSend == 0uL
            }
        } else assertEquals(SendController.State.succeeded, controller.state)
        controller.acknowledge()
        evidence(if (expectFailure) "fail" else "pay", buildJsonObject {
            put("movementID", movement.id.toLong()); put("status", movement.status); put("effective", movement.effectiveBalanceSats)
            put("fee", movement.offchainFeeSats.toLong()); put("outputs", movement.outputVtxoIds.size); put("exited", movement.exitedVtxoIds.size)
        })
    }

    /**
     * A send the server refuses before any HTLC: a throwaway peer (funded from this wallet over Ark, and paying back
     * what it has left) pays the host's invoice first, so the server knows it as paid. bark records this wallet's
     * attempt as failed with nothing taken: "Failed · nothing left your wallet".
     */
    private suspend fun nothingLeft() {
        val invoice = checkNotNull(args.getString("invoice"))
        val peer = Peer.create(context)
        try {
            send(SendPayment(peer.wallet.newAddress(), "ark", 2_500)).first.acknowledge()
            poll(120) { peer.wallet.sync(); peer.wallet.balance().spendableSats >= 2_500uL }
            check(peer.wallet.payLightningInvoice(invoice, null, LightningSendOptions(wait = true, retryForSecs = 60u)) is LightningSendStatus.Paid)
            peer.wallet.sync()
            peer.wallet.balance().spendableSats.takeIf { it > 0uL }?.let { peer.wallet.sendArkoorPayment(session.receiveRequest().arkAddress, it) }
        } finally { peer.close() }
        pay(expectFailure = true)
    }

    /** Ark → on-chain (send-onchain) to the host's address (`-e recipient`); it waits for two confirmations. */
    private suspend fun onchain() {
        val (controller, _) = send(SendPayment(checkNotNull(args.getString("recipient")), "onchain", args.getString("amount")?.toLong() ?: 4_000, SendSource.offchain))
        val receipt = checkNotNull(controller.attempt?.receipt) { "On-chain send did not succeed: ${controller.state}" }
        controller.acknowledge()
        evidence("onchain", buildJsonObject { put("txid", checkNotNull(receipt.txid)); put("movementID", receipt.movementID?.toLong()) })
    }

    private fun controller(): SendController {
        val journal = SendJournal(File(location.directory, "noah-send-attempt.json"))
        return SendController(SendController.Dependencies(journal::read, journal::write) { body -> session.withSendReservation(body) }, scope)
    }

    private suspend fun send(payment: SendPayment): Pair<SendController, SendAttempt?> {
        val journal = SendJournal(File(location.directory, "noah-send-attempt.json"))
        val controller = controller()
        controller.reloadForWalletChange()
        check(controller.state == SendController.State.ready) { "Send journal must be empty: ${controller.state}" }
        withTimeout(240_000) { checkNotNull(controller.submit(payment, session.quoteSend(payment))).await() }
        return controller to journal.read()
    }

    private suspend fun poll(seconds: Int, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) { if (done()) return; delay(2000) }
        check(done()) { "Timed out after $seconds s" }
    }

    private fun evidence(stage: String, value: JsonObject) = File(context.cacheDir, "activity-$stage.json").writeText(value.toString())

    /** A throwaway second wallet in the app's cache; its phrase never leaves memory and its folder is deleted. */
    private class Peer(val wallet: Wallet, val onchain: OnchainWallet, val directory: File) {
        fun close() { wallet.close(); onchain.close(); directory.deleteRecursively() }
        companion object {
            suspend fun create(context: android.content.Context): Peer {
                val directory = File(context.cacheDir, "activity-peer-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
                val config = WalletConfiguration.base("regtest").sdkConfig("noah-activity-peer/1")
                val mnemonic = generateMnemonic()
                val onchain = OnchainWallet.default(Network.REGTEST, mnemonic, config, directory.path)
                val wallet = Wallet.openWithOnchain(Network.REGTEST, mnemonic, config,
                    WalletOpenArgs(runDaemon = false, datadir = directory.path, createIfNotExists = true), onchain)
                return Peer(wallet, onchain, directory)
            }
        }
    }
}
