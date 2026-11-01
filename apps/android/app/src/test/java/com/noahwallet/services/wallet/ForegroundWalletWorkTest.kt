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
        refreshes = { 0 },
        progressExits = { calls += "exits" },
        syncOnchain = { calls += "onchain"; if (failing == "onchain") throw Boom() },
    )

    @Test fun eachPassSyncsThenMovesExitsAndOnlyAnEntryOrANewOpenRunsMaintenanceOnce() = runBlocking {
        val calls = mutableListOf<String>()
        val work = work(calls)
        work.run(open = 1)
        assertEquals("the first pass on a wallet that has just opened", listOf("sync", "maintenance", "exits", "onchain"), calls)
        calls.clear(); work.run(1)
        assertEquals("a tick never runs maintenance", listOf("sync", "exits", "onchain"), calls)
        calls.clear(); work.entered(); work.run(1)
        assertEquals(listOf("sync", "maintenance", "exits", "onchain"), calls)
        calls.clear(); work.run(1)
        assertEquals("once per entry", listOf("sync", "exits", "onchain"), calls)
        // Two entries before a pass runs (one dropped behind a pass in flight): still once.
        calls.clear(); work.entered(); work.entered(); work.run(1); work.run(1)
        assertEquals(listOf("sync", "maintenance", "exits", "onchain", "sync", "exits", "onchain"), calls)
        // Reopened (an endpoint change, a restore) or created while in front: once more, without a new entry.
        calls.clear(); work.run(3); work.run(3)
        assertEquals(listOf("sync", "maintenance", "exits", "onchain", "sync", "exits", "onchain"), calls)
    }

    /**
     * The on-chain sync holds the on-chain wallet for its whole Esplora round trip, and Home's balance and history wait
     * for it: it runs last in every pass, so bark's sync, maintenance, Lightning claims and exit progress don't wait.
     */
    @Test fun theOnchainSyncRunsLastInEveryPass() = runBlocking {
        val calls = mutableListOf<String>()
        val work = work(calls)
        work.run(open = 1); work.run(1)
        assertEquals(listOf("sync", "maintenance", "exits", "onchain", "sync", "exits", "onchain"), calls)
    }

    @Test fun aFailedStageLetsTheRestRunAndIsRethrown() = runBlocking {
        for (failing in listOf("sync", "maintenance", "onchain")) {
            val calls = mutableListOf<String>()
            val work = work(calls, failing)
            work.entered()
            assertTrue(failing, runCatching { work.run(1) }.exceptionOrNull() is Boom)
            assertEquals(failing, listOf("sync", "maintenance", "exits", "onchain"), calls)
            calls.clear(); runCatching { work.run(1) }
            assertEquals("only a maintenance that failed runs again ($failing)",
                if (failing == "maintenance") listOf("sync", "maintenance", "exits", "onchain") else listOf("sync", "exits", "onchain"), calls)
        }
    }

    @Test fun anEntrysPassLogsEachStagesTiming() = runBlocking {
        val lines = mutableListOf<String>()
        val work = ForegroundWalletWork(sync = {}, maintenance = {}, refreshes = { 0 }, progressExits = {}, syncOnchain = {}, log = lines::add)
        work.run(open = 1)
        assertEquals(listOf("wallet sync", "delegated maintenance", "exit progress", "on-chain sync").flatMap { listOf("$it started", "$it done") },
            lines.map { it.removePrefix("Foreground pass: ").substringBefore(" at +") })
        lines.clear(); work.run(1)
        assertEquals("a tick logs nothing", emptyList<String>(), lines)
    }

    /**
     * An entry's maintenance that fails (opened offline) stays due: each later pass, one per 60 s tick, tries it again
     * until it completes. bark's sync never moves an interactive attempt an older app version left behind, so until then
     * its VTXOs stay locked.
     */
    @Test fun aMaintenanceThatDidNotCompleteRunsOnEachLaterPassUntilItDoes() = runBlocking {
        val calls = mutableListOf<String>()
        var offline = true
        val work = ForegroundWalletWork(
            sync = { calls += "sync" },
            maintenance = { calls += "maintenance"; if (offline) throw Boom() },
            refreshes = { 0 },
            progressExits = { calls += "exits" },
            syncOnchain = { calls += "onchain" },
        )
        repeat(2) { assertTrue(runCatching { work.run(open = 1) }.exceptionOrNull() is Boom) }
        assertEquals("once per pass while it fails", List(2) { listOf("sync", "maintenance", "exits", "onchain") }.flatten(), calls)
        // A pass that ends before maintenance starts (its budget gone) leaves it due as well.
        calls.clear()
        val cancelled = runCatching {
            kotlinx.coroutines.withContext(WalletExecutionBudget(com.noahwallet.recurring.RecurringBudget().apply { revoke() })) { work.run(1) }
        }
        assertTrue(cancelled.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        offline = false
        calls.clear(); work.run(1); work.run(1)
        assertEquals("done once it completes", listOf("sync", "maintenance", "exits", "onchain", "sync", "exits", "onchain"), calls)
    }

    /**
     * A maintenance that failed after creating its refresh (the server down or refusing it) left a failed renewal in
     * history, so it isn't tried again until the next entry; one that fails before any refresh (offline) still is.
     */
    @Test fun aRefreshThatFailedIsNotRetriedUntilTheNextEntry() = runBlocking {
        val calls = mutableListOf<String>()
        var refreshes = 0
        var reachable = true
        val work = ForegroundWalletWork(
            sync = { calls += "sync" },
            maintenance = {
                calls += "maintenance"
                if (reachable) refreshes++
                throw Boom()
            },
            refreshes = { refreshes },
            progressExits = { calls += "exits" },
            syncOnchain = { calls += "onchain" },
        )
        repeat(3) { runCatching { work.run(open = 1) } }
        assertEquals("refused: once this entry",
            listOf("sync", "maintenance", "exits", "onchain") + List(2) { listOf("sync", "exits", "onchain") }.flatten(), calls)
        assertEquals(1, refreshes)
        reachable = false
        calls.clear(); work.entered(); repeat(2) { runCatching { work.run(1) } }
        assertEquals("offline: again on each pass", List(2) { listOf("sync", "maintenance", "exits", "onchain") }.flatten(), calls)
    }

    @Test fun theForegroundPassMovesExitsOnlyWhileOneIsLive() = runBlocking {
        withLocation { location ->
            val exits = Exits(listOf(ExitVtxo("done", 30_000u, ExitState.Canceled(100u), false),
                ExitVtxo("gone", 30_000u, ExitState.VtxoAlreadySpent(100u), false)))
            val handles = Handles().apply { exitWallet = exits }
            val session = WalletSession("regtest", location, opener = { _, _, _, _ -> handles })
            session.createNew("unused", "test")
            val work = ForegroundWalletWork(session::syncWalletAndClaims, session::maintenanceDelegated,
                { session.movements().count(com.noahwallet.history.HistoryMapping::isRefresh) },
                progressExits = { session.progressPendingExits { fail(it) } }, syncOnchain = session::syncOnchain)
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
            val tick = listOf("wallet sync", "on-chain sync")
            val maintained = listOf("wallet sync", "maintenance", "on-chain sync")
            assertEquals("the new open's first pass and the entry", maintained + tick + tick + tick + maintained, handles.calls)
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
