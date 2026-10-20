package com.noahwallet.recurring

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.models.NotificationDataRecurringPaymentDue
import com.noahwallet.services.AppServices
import com.noahwallet.services.push.PushRuntime
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt in on one empty disposable emulator; a failed/ambiguous payment must never be rerun. */
@RunWith(AndroidJUnit4::class)
class RecurringLiveTest {
    @Test fun cleanupOwnedLiveWallet() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahRecurringCleanup") == "disposable-emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(File(context.filesDir, "recurring-live-evidence.json").exists())
        val services = AppServices.shared(context)
        services.ensureWalletOpen()
        withContext(Dispatchers.Main) {
            val executor = RecurringRuntime.shared(context).executor
            withTimeout(10_000) { while (executor.controller.locked) delay(50) }
            for (s in RecurringStore(SettingsStorage(context)).load().schedules.values) {
                assertTrue(s.startAt > System.currentTimeMillis())
                executor.cancel(s.id)
            }
        }
        services.lifecycle.delete()
        assertFalse(WalletDataLocation(context.filesDir, "regtest").directory.exists())
        assertTrue(SeedStorage(context, "regtest").read() is SecretResult.NotFound)
    }

    @Test fun pushForegroundArkAndAutomaticLightningPayExactlyOnce() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("noahRecurringLive") == "disposable-emulator")
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val location = WalletDataLocation(context.filesDir, "regtest")
        val resumeSetup = args.getString("resumeSetupOnly") == "owned-wallet-with-no-send-journals"
        if (resumeSetup) {
            assertTrue(File(context.filesDir, "recurring-live-board-ready.txt").exists())
            assertTrue(RecurringStore(SettingsStorage(context)).load().schedules.isEmpty())
            assertFalse(File(location.directory, "noah-recurring-journal.json").exists())
            assertFalse(File(location.directory, "noah-send-attempt.json").exists())
            assertFalse(File(location.directory, "noah-send-attempt.json.bak").exists())
        } else {
            assertFalse("A live run requires a fresh wallet", location.hasAnyData)
            assertTrue(SeedStorage(context, "regtest").read() is SecretResult.NotFound)
        }
        val peerRoot = File(context.filesDir, "recurring-peer-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val peer = WalletSession("regtest", WalletDataLocation(peerRoot, "regtest"))
        peer.createNew(io.github.niteshbalusu11.bark.generateMnemonic(), false, BuildConfig.VERSION_NAME)
        val ark = peer.receiveRequest().arkAddress
        val offer = args.getString("offerRecipient") ?: error("Fresh regtest BOLT12 offer required")
        val services = AppServices.shared(context)
        val session = services.session
        if (resumeSetup) {
            val seed = (SeedStorage(context, "regtest").read() as SecretResult.Found).value
            services.lifecycle.withWalletMutation { session.ensureOpen(seed, true, BuildConfig.VERSION_NAME) }
            assertTrue(session.withOpen { it.history().none { m -> m.subsystemKind == "send" } })
        } else services.lifecycle.withWalletMutation { WalletCreator.live(context, session).createOrResume() }
        val settings = SettingsStorage(context)
        settings.setPersistedState(PersistedWalletState(isInitialized = true, staticVtxoPubkey = session.peekPublicKey(0u),
            isBiometricsEnabled = false, hasSeenBatteryOptimizationPrompt = true), PersistedWalletState.key, 0); settings.sync()
        if (!resumeSetup) {
        val request = session.receiveRequest()
        File(context.filesDir, "recurring-live-funding-address.txt").writeText(request.onchainAddress)
        settings.set(SettingsPreferences.autoBoard(settings.string("transaction-storage"), false), "transaction-storage"); settings.sync()
        withTimeout(180_000) {
            while (true) { session.sync(); if (session.summary()!!.balance.onchain.confirmed >= 100_000u) break; delay(1000) }
        }
        val board = session.quoteOnchain(com.noahwallet.onchain.OnchainAction.board, amount = 30_000)
        session.executeOnchain(board)
        File(context.filesDir, "recurring-live-board-ready.txt").writeText("boarded 30000 sats; required confirmations ${session.arkInfo()!!.requiredBoardConfirmations}")
        }
        withTimeout(180_000) {
            while (true) {
                session.sync()
                if (session.summary()!!.balance.ark.spendable >= 20_000u) break
                delay(1000)
            }
        }
        services.registration.ensureRegistered()
        val runtime = withContext(Dispatchers.Main) { RecurringRuntime.shared(context) }
        withTimeout(10_000) { while (withContext(Dispatchers.Main) { runtime.executor.controller.locked }) delay(50) }
        val before = session.transactionHistory().map { it.id }.toSet()
        fun schedule(label: String, destination: String, type: String) = RecurringPayment(UUID.randomUUID().toString(), label,
            destination, type, 1000, interval = RecurringInterval("day", 1), startAt = System.currentTimeMillis() + 1500,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        val a = schedule("Ark recurring live", ark, "ark")
        withContext(Dispatchers.Main) { runtime.executor.save(a, false) }
        delay(maxOf(0, a.startAt - System.currentTimeMillis()) + 50)
        coroutineScope {
            launch { PushRuntime(context, services).jobs.handle(NotificationDataRecurringPaymentDue(999)) }
            launch { runtime.execute() }
        }
        withContext(Dispatchers.Main) {
            assertEquals(1, runtime.executor.schedules.single { it.id == a.id }.occurrencesPaid)
            assertFalse(runtime.executor.controller.locked)
        }
        runtime.execute(); withContext(Dispatchers.Main) { runtime.executor.pause(a.id) }; runtime.execute()
        withContext(Dispatchers.Main) { assertEquals("paused", runtime.executor.schedules.single { it.id == a.id }.status); runtime.executor.cancel(a.id) }; runtime.execute()
        val afterArk = session.transactionHistory().filter { it.id !in before }
        assertEquals(1, afterArk.size)
        peer.sync()
        val peerReceive = peer.transactionHistory().single()
        assertEquals(1000L, peerReceive.amount)
        val l = schedule("Lightning recurring live", offer, "offer")
        val (_, quoted) = RecurringRecipient.quote(l, session)
        val feePolicy = RecurringFeePolicy.evaluate(1000, "offer", quoted.fee, quoted.lightningFeeSchedule)
        assertTrue("The captured regtest quote and worst-case schedule must be under cap", feePolicy.autoPay)
        assertEquals(166L, feePolicy.capSat)
        withContext(Dispatchers.Main) { runtime.executor.save(l, false) }
        delay(maxOf(0, l.startAt - System.currentTimeMillis()) + 50)
        coroutineScope {
            launch { PushRuntime(context, services).jobs.handle(NotificationDataRecurringPaymentDue(999)) }
            launch { runtime.execute() }
        }
        runtime.execute()
        withContext(Dispatchers.Main) {
            val paid = runtime.executor.schedules.single { it.id == l.id }
            assertEquals("Lightning must auto-pay without attended approval: ${paid.lastError}", 1, paid.occurrencesPaid)
            assertFalse(runtime.executor.controller.locked)
            runtime.executor.pause(l.id)
        }
        runtime.execute(); withContext(Dispatchers.Main) { runtime.executor.cancel(l.id) }; runtime.execute()
        val afterAll = session.transactionHistory().filter { it.id !in before }
        assertEquals(2, afterAll.size)
        val lightningMovement = session.withOpen { it.history().single { m -> m.subsystemName == "bark.lightning_send" && m.subsystemKind == "send" } }
        assertEquals("successful", lightningMovement.status)
        val journal = RecurringJournal(File(location.directory, "noah-recurring-journal.json"))
        assertTrue(journal.load().getValue(a.id).consumed); assertTrue(journal.load().getValue(l.id).consumed)
        assertFalse(RecurringStore(settings).load().pending)
        File(context.filesDir, "recurring-live-evidence.json").writeText(buildJsonObject {
            put("arkAmountSat", 1000); put("arkFeeSat", afterArk.single().offchainFeeSat); put("arkNewMovements", afterArk.size); put("totalNewMovements", afterAll.size)
            put("lightningAmountSat", 1000); put("lightningQuoteFeeSat", quoted.fee); put("worstCaseFeeSat", feePolicy.worstCaseFeeSat)
            put("feeCapSat", feePolicy.capSat); put("lightningPaymentHash", lightningMovement.paymentHash); put("lightningActualFeeSat", lightningMovement.offchainFeeSats.toLong()); put("lightningAutomatic", true); put("consumedBoth", true); put("syncPending", false)
            put("arkMovement", afterArk.single().id); put("peerReceiveMovement", peerReceive.id); put("peerReceivedSat", peerReceive.amount); putJsonArray("movements") { afterAll.forEach { add(it.id) } }
        }.toString())
        // Keep a future policy for the real Settings/list/editor inspection. It cannot be due.
        withContext(Dispatchers.Main) { runtime.executor.save(a.copy(id = UUID.randomUUID().toString(), label = "Monthly donation", startAt = System.currentTimeMillis() + 86_400_000,
            nextRunAt = System.currentTimeMillis() + 86_400_000, interval = RecurringInterval("month", 1)), false) }
        peer.closeSettled(); check(peerRoot.deleteRecursively())
        session.closeSettled()
    }
}
