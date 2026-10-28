package com.noahwallet.services.wallet

import com.noahwallet.recurring.RecurringBudget
import io.github.niteshbalusu11.bark.Network
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ForegroundWidgetRefreshTest {
    private val summary = WalletSession.Summary(Network.REGTEST, "fixture",
        WalletBalance(WalletBalance.Ark(1234u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u)), 0)

    @Test fun foregroundRefreshPublishesBalanceAndExtras() = runBlocking {
        var written: Pair<WalletBalance, Long?>? = null
        val latest = ForegroundWidgetRefresh.read({ summary }, { 123L }, {}, { balance, expiry -> written = balance to expiry })
        assertEquals(summary, latest); assertEquals(summary.balance to 123L, written)
    }

    @Test fun revokedSummaryOrExpiryCannotPublish() = runBlocking {
        for (afterSummary in listOf(true, false)) {
            val budget = RecurringBudget()
            var writes = 0; var expiryReads = 0
            withContext(WalletExecutionBudget(budget)) {
                val caller = currentCoroutineContext()
                try {
                    ForegroundWidgetRefresh.read({
                        if (afterSummary) budget.revoke()
                        summary
                    },
                        { expiryReads++; budget.revoke(); 123 }, { caller.checkWalletAdmission() }, { _, _ -> writes++ })
                    fail("Revoked refresh returned successfully")
                } catch (_: CancellationException) { }
            }
            assertEquals(0, writes); assertEquals(if (afterSummary) 0 else 1, expiryReads)
        }
    }

    @Test fun cancelledReadCannotPublishAndMissingTipKeepsSummary() = runBlocking {
        var writes = 0
        val work = launch {
            val caller = currentCoroutineContext()
            ForegroundWidgetRefresh.read({ summary }, { currentCoroutineContext().cancel(); 123 },
                { caller.checkWalletAdmission() }, { _, _ -> writes++ })
        }
        work.join(); assertTrue(work.isCancelled); assertEquals(0, writes)
        val latest = ForegroundWidgetRefresh.read({ summary }, { error("Tip unavailable") }, {}, { _, _ -> writes++ })
        assertEquals(summary, latest); assertEquals(0, writes)
    }
}
