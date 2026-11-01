package com.noahwallet.services.wallet

import com.noahwallet.onchain.OnchainWalletAccess
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** What the foreground pass does in place of bark's daemon, which never runs. */
internal class ForegroundWalletWorkTest : WalletSessionFixture() {
    private class Exits(var exits: List<ExitVtxo>) : OnchainWalletAccess {
        override val walletID = "wallet"
        var progressed = 0
        var error: Exception? = null
        override suspend fun exits() = exits
        override suspend fun progress(feeRate: Long?): List<ExitProgressStatus> {
            assertNull(feeRate); progressed++
            error?.let { throw it }
            return emptyList()
        }
        override suspend fun allExits() = emptyList<ExitTransactionStatus>()
    }

    private fun work(calls: MutableList<String>, failing: String? = null) = ForegroundWalletWork(
        sync = { calls += "sync"; if (failing == "sync") throw Boom() },
        maintenance = { calls += "maintenance"; if (failing == "maintenance") throw Boom() },
        progressExits = { calls += "exits" },
    )

    @Test fun eachPassSyncsThenMovesExitsAndOnlyAnEntryOrANewOpenRunsMaintenanceOnce() = runBlocking {
        val calls = mutableListOf<String>()
        val work = work(calls)
        work.run(open = 1)
        assertEquals("the first pass on a wallet that has just opened", listOf("sync", "maintenance", "exits"), calls)
        calls.clear(); work.run(1)
        assertEquals("a tick never runs maintenance", listOf("sync", "exits"), calls)
        calls.clear(); work.entered(); work.run(1)
        assertEquals(listOf("sync", "maintenance", "exits"), calls)
        calls.clear(); work.run(1)
        assertEquals("once per entry", listOf("sync", "exits"), calls)
        // Two entries before a pass runs (one dropped behind a pass in flight): still once.
        calls.clear(); work.entered(); work.entered(); work.run(1); work.run(1)
        assertEquals(listOf("sync", "maintenance", "exits", "sync", "exits"), calls)
        // Reopened (an endpoint change, a restore) or created while in front: once more, without a new entry.
        calls.clear(); work.run(3); work.run(3)
        assertEquals(listOf("sync", "maintenance", "exits", "sync", "exits"), calls)
    }

    @Test fun aFailedStageLetsTheRestRunAndIsRethrown() = runBlocking {
        for (failing in listOf("sync", "maintenance")) {
            val calls = mutableListOf<String>()
            val work = work(calls, failing)
            work.entered()
            assertTrue(failing, runCatching { work.run(1) }.exceptionOrNull() is Boom)
            assertEquals(failing, listOf("sync", "maintenance", "exits"), calls)
            calls.clear(); runCatching { work.run(1) }
            assertEquals("a failed entry isn't retried on every tick ($failing)", listOf("sync", "exits"), calls)
        }
    }

    @Test fun theForegroundPassMovesExitsOnlyWhileOneIsLive() = runBlocking {
        withLocation { location ->
            val exits = Exits(listOf(ExitVtxo("done", 30_000u, ExitState.Canceled(100u), false),
                ExitVtxo("gone", 30_000u, ExitState.VtxoAlreadySpent(100u), false)))
            val handles = Handles().apply { exitWallet = exits }
            val session = WalletSession("regtest", location, opener = { _, _, _, _ -> handles })
            session.createNew("unused", "test")
            val work = ForegroundWalletWork({ session.sync(includingLightningClaims = true) }, session::maintenanceDelegated) {
                session.progressPendingExits { fail(it) }
            }
            val open = session.walletChanges.value
            work.run(open)
            assertEquals("only finished exits: bark isn't asked", 0, exits.progressed)
            exits.exits += ExitVtxo("a", 30_000u, ExitState.Start(100u), false)
            work.run(open); work.run(open)
            assertEquals("each pass while one is live, at bark's own rate", 2, exits.progressed)
            exits.exits = exits.exits.filter { it.vtxoId != "a" }
            work.run(open)
            assertEquals(2, exits.progressed)
            work.entered(); work.run(open)
            assertEquals("the new open's first pass and the entry", listOf("sync", "maintenance", "sync", "sync", "sync", "sync", "maintenance"),
                handles.calls)
            session.close()
        }
    }

    /** The exit pages read [ExitFunding.checkFailed] as their evidence that exits aren't moving ([com.noahwallet.onchain.ExitStages.of]). */
    @Test fun exitsReadAsStalledOnlyWhenNoahsOwnProgressFailsOrCannotRun() = runBlocking {
        withLocation { location ->
            val funding = com.noahwallet.onchain.ExitFunding.shared
            val exits = Exits(listOf(ExitVtxo("a", 30_000u, ExitState.Start(100u), false)))
            val handles = Handles().apply { exitWallet = exits }
            val session = WalletSession("regtest", location, opener = { _, _, _, _ -> handles })
            var reasons = 0
            session.progressPendingExits { reasons++ }
            assertTrue("couldn't run: the wallet isn't open", funding.checkFailed)
            session.createNew("unused", "test")
            session.progressPendingExits { fail(it) }
            assertFalse("a completed progress: exits are moving", funding.checkFailed)
            exits.error = BarkError("offline", BarkErrorCode.OTHER)
            session.progressPendingExits { reasons++ }
            assertTrue("bark's call failed", funding.checkFailed)
            exits.error = null; session.progressPendingExits { fail(it) }
            assertFalse(funding.checkFailed)
            assertEquals("each failure's reason goes to the log", 2, reasons)
            session.close()
        }
    }
}
