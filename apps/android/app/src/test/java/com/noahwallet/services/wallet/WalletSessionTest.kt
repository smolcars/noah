package com.noahwallet.services.wallet

import com.noahwallet.services.storage.WalletDataLocation
import io.github.niteshbalusu11.bark.Network
import io.github.niteshbalusu11.bark.WalletSnapshotInfo
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WalletSessionTest {
    private class Boom : Exception()
    private class Handles : WalletSession.Handles {
        override suspend fun peekAddress(index: UInt) = "ark-test"
        var closed = false
        var stopFails = false
        var stopEntered: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        var summaryGate: CompletableDeferred<Unit>? = null
        var summaryEntered: CompletableDeferred<Unit>? = null
        override suspend fun summary(): WalletSession.Summary {
            summaryEntered?.complete(Unit); summaryGate?.await()
            assertFalse(closed)
            return WalletSession.Summary(Network.REGTEST, "abcd", WalletBalance(
                WalletBalance.Ark(1u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u)), 2)
        }
        override suspend fun sync() { assertFalse(closed) }
        override suspend fun maintenanceDelegated() { assertFalse(closed) }
        override suspend fun bolt11Invoice(amountSats: ULong): String { check(!closed); return "invoice-$amountSats" }
        override suspend fun claimLightningReceives() { assertFalse(closed) }
        override suspend fun closestExpiryBlocks(): Long? { check(!closed); return -1L }
        override suspend fun createSnapshot(destination: File): WalletSnapshotInfo = throw UnsupportedOperationException()
        override suspend fun stopDaemonWait() {
            stopEntered?.complete(Unit); stopGate?.await()
            if (stopFails) throw Boom()
        }
        override fun close() { closed = true }
    }
    private suspend fun withLocation(block: suspend (WalletDataLocation) -> Unit) {
        val root = Files.createTempDirectory("session").toFile()
        try { withTimeout(10_000) { block(WalletDataLocation(root, "regtest")) } }
        finally { root.deleteRecursively() }
    }
    private fun bark(location: WalletDataLocation) { location.directory.mkdirs(); location.database.writeText("fake") }
    private fun assertFailure(expected: WalletSession.OpenFailure, result: Result<Unit>) {
        assertEquals(expected, (result.exceptionOrNull() as? WalletSession.OpenError)?.reason)
    }
    @Test fun preflightNeverInvokesOpenerForMissingForeignOrWrongNetwork() = runBlocking {
        withLocation { location ->
            var calls = 0
            var network = "regtest"
            var foreign = false
            val session = WalletSession("regtest", location, inspect = {
                if (foreign) throw Boom()
                WalletDatabase.Properties(network, "abcd")
            }, opener = { _, _, _, _, _ -> calls++; throw Boom() })
            assertFailure(WalletSession.OpenFailure.walletDatabaseMissing, runCatching { session.openExisting("unused", false, "test") })
            assertFalse(location.directory.exists())
            bark(location); foreign = true
            assertFailure(WalletSession.OpenFailure.notABarkWallet, runCatching { session.openExisting("unused", false, "test") })
            foreign = false; network = "signet"
            assertFailure(WalletSession.OpenFailure.networkMismatch, runCatching { session.openExisting("unused", false, "test") })
            assertEquals(0, calls); assertEquals("fake", location.database.readText())
        }
    }
    @Test fun createNeverOverwritesDatabaseAndSetsCreateFlag() = runBlocking {
        withLocation { location ->
            var calls = 0; val handles = Handles()
            val session = WalletSession("regtest", location, opener = { _, _, _, _, create ->
                assertTrue(create); calls++; handles
            })
            bark(location)
            assertFailure(WalletSession.OpenFailure.walletDatabaseExists, runCatching { session.createNew("unused", false, "test") })
            assertEquals(0, calls); assertEquals("fake", location.database.readText())
            location.database.delete()
            session.createNew("unused", false, "test"); assertEquals(1, calls)
            session.close(); assertTrue(handles.closed)
        }
    }
    @Test fun joinsOpeningRejectsConflictsAndRecoversAfterFailure() = runBlocking {
        withLocation { location ->
            bark(location)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var calls = 0
            val session = WalletSession("regtest", location, inspect = { WalletDatabase.Properties("regtest", "abcd") },
                opener = { _, _, _, _, create ->
                    assertFalse(create); calls++; entered.complete(Unit); release.await(); throw Boom()
                })
            val first = async { runCatching { session.openExisting("unused", false, "test") } }; entered.await()
            assertFailure(WalletSession.OpenFailure.busy, runCatching { session.openExisting("unused", false, "test") })
            assertFailure(WalletSession.OpenFailure.busy, runCatching { session.close() })
            val joined = async { runCatching { session.ensureOpen("unused", false, "test") } }
            // Let the IO dispatcher reach the join before releasing the opener.
            delay(100); release.complete(Unit)
            assertTrue(first.await().exceptionOrNull() is Boom)
            assertTrue(joined.await().exceptionOrNull() is Boom); assertEquals(1, calls)
            assertNull(session.summary())
            assertTrue(runCatching { session.openExisting("unused", false, "test") }.exceptionOrNull() is Boom)
            assertEquals(2, calls)
        }
    }
    @Test fun cancelledWaiterStillLeavesOneOwnedOpenWallet() = runBlocking {
        withLocation { location ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val handles = Handles(); var calls = 0
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ ->
                calls++; entered.complete(Unit); release.await(); handles
            })
            val first = launch { session.createNew("unused", false, "test") }; entered.await(); first.cancelAndJoin()
            val joined = async { session.ensureOpen("unused", false, "test") }
            release.complete(Unit); joined.await()
            assertTrue(session.isOpen); assertEquals(1, calls)
            session.ensureOpen("unused", false, "test"); assertEquals(1, calls)
            handles.stopFails = true
            assertTrue(runCatching { session.close() }.exceptionOrNull() is Boom)
            assertTrue(session.isOpen); assertFalse(handles.closed)
            handles.stopFails = false; session.close(); assertTrue(handles.closed); assertFalse(session.isOpen)
        }
    }
    @Test fun closeWaitsForActiveOperationAndOpeningDuringCloseIsBusy() = runBlocking {
        withLocation { location ->
            val handles = Handles().apply {
                summaryEntered = CompletableDeferred(); summaryGate = CompletableDeferred()
                stopEntered = CompletableDeferred(); stopGate = CompletableDeferred()
            }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val reading = async { session.summary() }; handles.summaryEntered!!.await()
            val closing = async { session.close() }
            yield(); assertFalse(handles.closed); assertFalse(handles.stopEntered!!.isCompleted)
            handles.summaryGate!!.complete(Unit); reading.await(); handles.stopEntered!!.await()
            assertFailure(WalletSession.OpenFailure.busy, runCatching { session.ensureOpen("unused", false, "test") })
            assertFailure(WalletSession.OpenFailure.busy, runCatching { session.close() })
            handles.stopGate!!.complete(Unit); closing.await(); assertTrue(handles.closed)
        }
    }
}
