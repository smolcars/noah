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
        override suspend fun exits() = exits
        override suspend fun progress(feeRate: Long?): List<ExitProgressStatus> { assertNull(feeRate); progressed++; return emptyList() }
        override suspend fun allExits() = emptyList<ExitTransactionStatus>()
    }

    private fun work(calls: MutableList<String>, failing: String? = null) = ForegroundWalletWork(
        sync = { calls += "sync"; if (failing == "sync") throw Boom() },
        maintenance = { calls += "maintenance"; if (failing == "maintenance") throw Boom() },
        progressExits = { calls += "exits" },
    )

    @Test fun eachPassSyncsThenMovesExitsAndOnlyAnEntryRunsMaintenanceOnce() = runBlocking {
        val calls = mutableListOf<String>()
        val work = work(calls)
        work.run()
        assertEquals("a tick never runs maintenance", listOf("sync", "exits"), calls)
        calls.clear(); work.entered(); work.run()
        assertEquals(listOf("sync", "maintenance", "exits"), calls)
        calls.clear(); work.run()
        assertEquals("once per entry", listOf("sync", "exits"), calls)
        // Two entries before a pass runs (one dropped behind a pass in flight): still once.
        calls.clear(); work.entered(); work.entered(); work.run(); work.run()
        assertEquals(listOf("sync", "maintenance", "exits", "sync", "exits"), calls)
    }

    @Test fun aFailedStageLetsTheRestRunAndIsRethrown() = runBlocking {
        for (failing in listOf("sync", "maintenance")) {
            val calls = mutableListOf<String>()
            val work = work(calls, failing)
            work.entered()
            assertTrue(failing, runCatching { work.run() }.exceptionOrNull() is Boom)
            assertEquals(failing, listOf("sync", "maintenance", "exits"), calls)
            calls.clear(); runCatching { work.run() }
            assertEquals("a failed entry isn't retried on every tick ($failing)", listOf("sync", "exits"), calls)
        }
    }

    @Test fun theForegroundPassMovesExitsOnlyWhileOneIsLive() = runBlocking {
        withLocation { location ->
            val exits = Exits(listOf(ExitVtxo("done", 30_000u, ExitState.Canceled(100u), false),
                ExitVtxo("gone", 30_000u, ExitState.VtxoAlreadySpent(100u), false)))
            val handles = Handles().apply { exitWallet = exits }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val work = ForegroundWalletWork({ session.sync(includingLightningClaims = true) }, session::maintenanceDelegated) {
                session.progressPendingExits { fail(it) }
            }
            work.run()
            assertEquals("only finished exits: bark isn't asked", 0, exits.progressed)
            exits.exits += ExitVtxo("a", 30_000u, ExitState.Start(100u), false)
            work.run(); work.run()
            assertEquals("each pass while one is live, at bark's own rate", 2, exits.progressed)
            exits.exits = exits.exits.filter { it.vtxoId != "a" }
            work.run()
            assertEquals(2, exits.progressed)
            work.entered(); work.run()
            assertEquals(listOf("sync", "sync", "sync", "sync", "sync", "maintenance"), handles.calls)
            session.close()
        }
    }
}
