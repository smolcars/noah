package com.noahwallet.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.onchain.OnchainAction
import com.noahwallet.services.AppServices
import com.noahwallet.services.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Funding only: never exports a seed and never submits a Send or recurring payment. */
@RunWith(AndroidJUnit4::class)
class InitializedWalletIOTest {
    @Test fun fundOwnedInitializedWallet() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahInitializedIO") == "disposable-emulator")
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = AppServices.shared(context)
        val settings = SettingsStorage(context)
        assertTrue(settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isInitialized == true)
        services.ensureWalletOpen()
        val session = services.session
        assertTrue(session.transactionHistory().none { it.amount < 0 })
        val marker = File(context.filesDir, "initialized-io-board-ready.txt")
        assertFalse("Fund/board exactly once on the owned emulator", marker.exists())
        File(context.filesDir, "initialized-io-address.txt").writeText(session.receiveRequest().onchainAddress)
        withTimeout(180_000) {
            while (true) {
                session.sync()
                if (session.summary()!!.balance.onchain.confirmed >= 100_000u) break
                delay(1000)
            }
        }
        val quote = session.quoteOnchain(OnchainAction.board, amount = 30_000)
        session.executeOnchain(quote)
        marker.writeText(session.arkInfo()!!.requiredBoardConfirmations.toString())
        withTimeout(180_000) {
            while (true) {
                session.sync()
                if (session.summary()!!.balance.ark.spendable >= 20_000u) break
                delay(1000)
            }
        }
        assertTrue(session.summary()!!.balance.ark.spendable >= 20_000u)
        session.closeSettled()
    }
}
