package com.noahwallet.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.services.logging.LogFile
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import io.github.niteshbalusu11.bark.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in stages for bark-bolt-ffi 0.6.0 on an owned disposable emulator, against a Bark 0.8 stack selected with
 *  -PnoahRegtestArkURL / -PnoahRegtestBitcoindURL / -PnoahRegtestServerURL. The host funds, pays, cancels and
 *  mines between stages (`-e noahFfi060 <stage> -e owner ffi060-disposable`); each stage writes only public
 *  evidence to cache/ffi060-<stage>.json. */
@RunWith(AndroidJUnit4::class)
class Ffi060LiveTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = WalletSession.shared(context)
    private val location = WalletDataLocation(context.filesDir, "regtest")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun acceptance() = runBlocking {
        val stage = args.getString("noahFfi060")
        assumeTrue(stage != null && BuildConfig.APP_VARIANT == "regtest")
        require(args.getString("owner") == "ffi060-disposable")
        // Only a second stack selected with the overrides, never the default regtest endpoints.
        require(BuildConfig.REGTEST_ARK_URL.isNotEmpty() && BuildConfig.REGTEST_BITCOIND_URL.isNotEmpty())
        val seed = SeedStorage(context, "regtest")
        val marker = File(context.filesDir, "ffi060-owned")
        if (stage == "create") {
            check(!location.hasAnyData && seed.read() == SecretResult.NotFound && !marker.exists())
            val phrase = generateMnemonic()
            check(seed.save(phrase) is SecretResult.Found)
            session.createNew(phrase, false, "ffi060-live")
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
            evidence("create", buildJsonObject {
                put("fingerprint", fingerprint); put("onchainAddress", session.receiveRequest().onchainAddress)
                put("arkServer", WalletConfiguration.base("regtest").arkServer); put("serverPubkey", session.arkInfo()?.serverPubkey)
            })
            session.closeSettled()
            return@runBlocking
        }
        check(marker.exists())
        val mnemonic = (seed.read() as SecretResult.Found).value
        session.ensureOpen(mnemonic, false, "ffi060-live")
        check(session.summary()!!.fingerprint == marker.readText())
        try {
            when (stage) {
                "receive" -> receive()
                "ark" -> arkRoundTrip()
                "ln-send" -> lightningSend()
                "ln-fail" -> lightningFailure()
                "offboard" -> offboard()
                "offboard-confirmed" -> offboardConfirmed()
                "reopen" -> reopen(mnemonic)
                else -> error("Unknown stage $stage")
            }
        } finally { session.closeSettled(); scope.cancel() }
    }

    /** Core paid the onchain address (one block mined); LND pays the invoice written here through the server. */
    private suspend fun receive() {
        suspend fun received() = session.movements().filter { it.subsystemName == "bark.lightning_receive" && it.status == "successful" }
        if (received().isEmpty()) {
            val request = session.receiveInvoice(session.receiveRequest(), 30_000, "")
            File(context.cacheDir, "ffi060-invoice.txt").writeText(checkNotNull(request.lightningInvoice))
            poll(240) { session.sync(includingLightningClaims = true); received().isNotEmpty() }
        }
        session.sync()
        val summary = session.summary()!!
        val movement = received().first()
        // The server keeps its Lightning receive fee.
        assertTrue(summary.spendableSats > 0u && summary.spendableSats.toLong() == movement.effectiveBalanceSats)
        assertTrue(summary.balance.onchain.confirmed >= 200_000u)
        evidence("receive", buildJsonObject {
            put("arkSpendable", summary.spendableSats.toLong()); put("onchainConfirmed", summary.balance.onchain.confirmed.toLong())
            put("lightningReceiveMovementID", movement.id.toLong()); put("lightningReceiveFee", movement.offchainFeeSats.toLong())
        })
    }

    /** Ark send to a second in-process wallet, which pays part of it back (and optionally probes retryForSecs). */
    private suspend fun arkRoundTrip() {
        val peer = Peer.create(context)
        try {
            val peerAddress = peer.wallet.newAddress()
            val payment = SendPayment(peerAddress, "ark", 8_000,
                repeatMetadata = SendPayment.metadata("ffi060@localhost.com", "exact movement", "8000", null))
            val (controller, journal) = send(payment)
            val receipt = checkNotNull(controller.attempt?.receipt) { "Ark send did not succeed: ${controller.state}" }
            assertEquals(SendController.State.succeeded, controller.state)
            val id = checkNotNull(receipt.movementID)
            assertEquals(SendReceipt.Proof.submitReturnedArkSuccess, receipt.proof); assertEquals(id, journal?.receipt?.movementID)
            val movements = session.movements()
            val movement = movements.first { it.id == id }
            assertTrue(movement.subsystemName == "bark.arkoor" && movement.subsystemKind == "send")
            assertTrue(movement.sentToAddresses.any { it.paymentMethod.value == peerAddress && it.amountSats == 8_000uL })
            // History metadata lands on exactly the returned movement.
            assertEquals(listOf(id), movements.filter { "repeat_payment" in it.metadataJson }.map { it.id })
            controller.acknowledge()

            poll(120) { peer.wallet.sync(); peer.wallet.balance().spendableSats >= 8_000uL }
            val back = session.receiveRequest().arkAddress
            val peerMovement = peer.wallet.sendArkoorPayment(back, 3_000uL)
            assertTrue(peer.wallet.history().any { it.id == peerMovement && it.subsystemKind == "send" })
            poll(120) { session.sync(); session.movements().any { it.subsystemName == "bark.arkoor" && it.subsystemKind == "receive" } }
            // 60 s is also captaind's default, so a distinct value shows the SDK forwards retryForSecs (host greps its span).
            val probe = args.getString("probe")
            val probed = probe != null && peer.wallet.payLightningInvoice(probe, null, LightningSendOptions(wait = true, retryForSecs = 45u)) is LightningSendStatus.Paid
            evidence("ark", buildJsonObject {
                put("movementID", id.toLong()); put("journalMovementID", journal?.receipt?.movementID?.toLong())
                put("peerSendMovementID", peerMovement.toLong()); put("spendable", session.summary()!!.spendableSats.toLong())
                put("retryForSecs45ProbePaid", probed)
            })
        } finally { peer.close() }
    }

    private suspend fun lightningSend() {
        val invoice = checkNotNull(args.getString("invoice"))
        val hash = checkNotNull(PaymentEncoding.invoice(invoice)?.hash)
        val (controller, journal) = send(SendPayment(invoice, "lightning", 5_000))
        val receipt = checkNotNull(controller.attempt?.receipt) { "Lightning send did not succeed: ${controller.state}" }
        assertEquals(SendController.State.succeeded, controller.state)
        val id = checkNotNull(receipt.movementID)
        assertEquals(SendReceipt.Proof.actualMovement, receipt.proof); assertEquals(hash, receipt.paymentHash)
        assertEquals(id, journal?.receipt?.movementID)
        val movement = session.movements().first { it.id == id }
        assertTrue(movement.subsystemName == "bark.lightning_send" && movement.status == "successful" && movement.paymentHash == hash)
        controller.acknowledge()
        evidence("ln-send", buildJsonObject { put("movementID", id.toLong()); put("fee", receipt.fee) })
    }

    /** The host holds the HTLC on LND, then cancels it: the payment fails and Ark refunds the coins. */
    private suspend fun lightningFailure() {
        val invoice = checkNotNull(args.getString("invoice"))
        val hash = checkNotNull(PaymentEncoding.invoice(invoice)?.hash)
        session.sync()
        val before = session.summary()!!
        val started = System.currentTimeMillis()
        val (controller, journal) = send(SendPayment(invoice, "lightning", 2_000))
        val seconds = (System.currentTimeMillis() - started) / 1000
        assertEquals(SendController.State.failed, controller.state); assertTrue(journal?.failed == true)
        val movement = session.movements().first { it.paymentHash == hash && it.subsystemName == "bark.lightning_send" }
        assertEquals("failed", movement.status)
        poll(60) {
            session.sync()
            val after = session.summary()!!.balance.ark
            after.spendable == before.balance.ark.spendable && after.pendingLightningSend == 0uL
        }
        controller.acknowledge()
        evidence("ln-fail", buildJsonObject {
            put("movementID", movement.id.toLong()); put("spendableBefore", before.spendableSats.toLong())
            put("spendableAfter", session.summary()!!.spendableSats.toLong()); put("seconds", seconds)
        })
    }

    /** Ark → onchain (sendOnchain): the receipt keeps the returned txid and movement id. */
    private suspend fun offboard() {
        val (controller, journal) = send(SendPayment(checkNotNull(args.getString("recipient")), "onchain", 10_000, SendSource.offchain))
        val receipt = checkNotNull(controller.attempt?.receipt) { "Offboard did not succeed: ${controller.state}" }
        val txid = checkNotNull(receipt.txid); val id = checkNotNull(receipt.movementID)
        assertEquals(SendReceipt.Proof.submitReturnedTxid, receipt.proof)
        assertEquals(id, journal?.receipt?.movementID); assertEquals(txid, journal?.receipt?.txid)
        val movement = session.movements().first { it.id == id }
        assertTrue(movement.subsystemName == "bark.offboard" && movement.subsystemKind == "send_onchain" && txid in movement.metadataJson)
        controller.checkStatus()
        val checked = checkNotNull(controller.attempt?.receipt)
        assertEquals(ChainStatus.Kind.inMempool, checked.chainStatus?.kind)
        assertEquals(10_000L, checked.amount); assertEquals(id, checked.movementID)
        evidence("offboard", buildJsonObject {
            put("txid", txid); put("movementID", id.toLong()); put("amount", checked.amount); put("fee", checked.fee)
        })
    }

    /** After the host mines a block, the restarted journal resolves by id and txid without resubmitting. */
    private suspend fun offboardConfirmed() {
        val controller = controller()
        controller.reloadForWalletChange()
        val saved = checkNotNull(controller.attempt?.receipt)
        controller.checkStatus()
        val receipt = checkNotNull(controller.attempt?.receipt)
        assertEquals(ChainStatus.Kind.confirmed, receipt.chainStatus?.kind)
        assertEquals(SendReceipt.Status.confirmed, receipt.status)
        assertEquals(saved.movementID, receipt.movementID); assertEquals(saved.txid, receipt.txid)
        controller.acknowledge()
        assertFalse(controller.locked)
        evidence("offboard-confirmed", buildJsonObject {
            put("movementID", receipt.movementID?.toLong()); put("height", receipt.chainStatus?.height)
        })
    }

    /** Close and reopen: history, balances and identity survive; debug info is display-only. */
    private suspend fun reopen(mnemonic: String) {
        session.sync()
        val before = session.summary()!!
        val ids = session.movements().map { it.id }.toSet()
        session.closeSettled()
        session.ensureOpen(mnemonic, false, "ffi060-live")
        val after = session.summary()!!
        assertEquals(before.fingerprint, after.fingerprint); assertEquals(before.movementCount, after.movementCount)
        assertEquals(before.balance.ark.spendable, after.balance.ark.spendable)
        assertEquals(ids, session.movements().map { it.id }.toSet())
        val info = Json.parseToJsonElement(session.debug(WalletSession.DebugAction.debugInfo, "")).jsonObject
        val xpub = info.getValue("vtxoXpub").jsonPrimitive.content
        assertEquals("regtest", info.getValue("network").jsonPrimitive.content)
        assertTrue(info.getValue("mailboxId").jsonPrimitive.content.isNotEmpty() && xpub.isNotEmpty())
        // The xpub never reaches the app log that Settings exports.
        val logs = withContext(Dispatchers.IO) { LogFile.shared(context).lines() }
        assertTrue(logs.isNotEmpty() && logs.none { xpub in it })
        evidence("reopen", buildJsonObject {
            put("movements", after.movementCount); put("spendable", after.spendableSats.toLong())
            put("onchainConfirmed", after.balance.onchain.confirmed.toLong()); put("debugInfoFields", JsonArray(info.keys.sorted().map(::JsonPrimitive)))
        })
    }

    private fun controller(): SendController {
        val journal = SendJournal(File(location.directory, "noah-send-attempt.json"))
        return SendController(SendController.Dependencies(journal::read, journal::write) { body -> session.withSendReservation(body) }, scope)
    }

    private suspend fun send(payment: SendPayment): Pair<SendController, SendAttempt?> {
        val controller = controller()
        controller.reloadForWalletChange()
        check(controller.state == SendController.State.ready) { "Send journal must be empty: ${controller.state}" }
        withTimeout(240_000) { checkNotNull(controller.submit(payment, session.quoteSend(payment))).await() }
        return controller to SendJournal(File(location.directory, "noah-send-attempt.json")).read()
    }

    private suspend fun poll(seconds: Int, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) { if (done()) return; delay(2000) }
        check(done()) { "Timed out after $seconds s" }
    }

    private fun evidence(stage: String, value: JsonObject) = File(context.cacheDir, "ffi060-$stage.json").writeText(value.toString())

    /** A throwaway second wallet in the app's cache; its phrase never leaves memory and its folder is deleted. */
    private class Peer(val wallet: Wallet, val onchain: OnchainWallet, val directory: File) {
        fun close() { wallet.close(); onchain.close(); directory.deleteRecursively() }
        companion object {
            suspend fun create(context: android.content.Context): Peer {
                val directory = File(context.cacheDir, "ffi060-peer-${UUID.randomUUID()}").apply { check(mkdirs()) }
                val config = WalletConfiguration.base("regtest").sdkConfig("noah-ffi060-peer/1")
                val mnemonic = generateMnemonic()
                val onchain = OnchainWallet.default(Network.REGTEST, mnemonic, config, directory.path)
                val wallet = Wallet.openWithOnchain(Network.REGTEST, mnemonic, config,
                    WalletOpenArgs(runDaemon = false, datadir = directory.path, createIfNotExists = true), onchain)
                return Peer(wallet, onchain, directory)
            }
        }
    }
}
