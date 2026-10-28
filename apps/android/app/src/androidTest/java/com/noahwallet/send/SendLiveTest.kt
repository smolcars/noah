package com.noahwallet.send

import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in bootstrap for an owned disposable emulator. Never exports the phrase or raw wallet. */
@RunWith(AndroidJUnit4::class)
class SendLiveTest {
    @Test fun sendComposerLoads() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahSendStartup") == "disposable-emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(android.content.Intent(context,
            com.noahwallet.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val session = WalletSession.shared(context)
        val seed = (SeedStorage(context, "regtest").read() as SecretResult.Found).value
        session.ensureOpen(seed, false, BuildConfig.VERSION_NAME)
        instrumentation.runOnMainSync {
            val model = androidx.lifecycle.ViewModelProvider(activity as com.noahwallet.MainActivity)[com.noahwallet.ui.AppModel::class.java]
            activity.setContent { com.noahwallet.ui.NoahTheme { com.noahwallet.ui.SendScreen(model) {} } }
        }
        delay(1000)
        lateinit var controller: SendController
        instrumentation.runOnMainSync { controller = SendRuntime.shared(context).controller }
        assertEquals("Empty journal must permit composing Send: ${controller.message}", SendController.State.ready, controller.state)
        instrumentation.runOnMainSync { activity.finish() }
    }
    @Test fun bootstrapThrowawayWallet() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahSendLive") == "disposable-emulator")
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        assertEquals("http://10.0.2.2:3100", BuildConfig.REGTEST_SERVER_URL)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Run only once in an empty disposable app", location.hasAnyData)
        val session = WalletSession.shared(context)
        WalletCreator.live(context, session).createOrResume()
        val request = session.receiveRequest()
        val settings = SettingsStorage(context)
        settings.setPersistedState(PersistedWalletState(isInitialized = true, staticVtxoPubkey = session.peekPublicKey(0u),
            isBiometricsEnabled = false, hasSeenBatteryOptimizationPrompt = true), PersistedWalletState.key, 0)
        settings.sync()
        File(context.filesDir, "send-live-addresses.json").writeText(buildJsonObject {
            put("ark", request.arkAddress); put("onchain", request.onchainAddress); put("fingerprint", session.summary()!!.fingerprint)
        }.toString())
        session.closeSettled()
    }
    @Test fun fundedPreparedLifecycle() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahSendPreparedLive") == "disposable-emulator")
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val address = InstrumentationRegistry.getArguments().getString("recipient") ?: error("External regtest recipient required")
        val session = WalletSession.shared(context)
        val seed = (SeedStorage(context, "regtest").read() as SecretResult.Found).value
        session.ensureOpen(seed, false, BuildConfig.VERSION_NAME)
        session.sync()
        val payment = SendPayment(address, "onchain", 5000, SendSource.onchain)
        val quote = session.quoteSend(payment)
        assertNotNull(quote.prepared)
        session.discardSend(quote)
        val next = session.quoteSend(payment)
        assertNotEquals(quote.prepared!!.id, next.prepared!!.id)
        session.withSendReservation { wallet ->
            assertTrue("Discarded handles cannot be claimed", runCatching { wallet.prepare(payment, quote) }.isFailure)
        }
        session.closeSettled()
        session.ensureOpen(seed, false, BuildConfig.VERSION_NAME)
        session.withSendReservation { wallet -> assertTrue("Prepared ids are process/session local", runCatching { wallet.prepare(payment, next) }.isFailure) }
        File(context.filesDir, "send-live-lifecycle.json").writeText(buildJsonObject {
            put("backoutReprepared", true); put("closeRejectsStaleID", true); put("amount", next.amount); put("fee", next.fee)
        }.toString())
        session.closeSettled()
    }
}
