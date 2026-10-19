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
        val subscribed = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var holderClosed = false
        var beforeNativeWait: CompletableDeferred<Unit>? = null
        var waitRegistered = false
        override fun notifications(): WalletSession.Notifications {
            subscribed.complete(Unit)
            return object : WalletSession.Notifications {
                override suspend fun next(): io.github.niteshbalusu11.bark.WalletNotification? {
                    waiting.complete(Unit)
                    beforeNativeWait?.await()
                    waitRegistered = true
                    released.await(); return null
                }
                override fun cancelWait() {
                    check(!holderClosed)
                    // Real SDK cancellation has no effect before nextNotification registers its wait.
                    if (beforeNativeWait == null || waitRegistered) released.complete(Unit)
                }
                override fun close() { holderClosed = true }
            }
        }
        override suspend fun history() = emptyList<io.github.niteshbalusu11.bark.Movement>()
        override suspend fun receiveRequest() = ReceiveRequest("ark-test", "chain-test")
        override suspend fun receiveInvoice(request: ReceiveRequest, amountSat: Long, description: String) =
            request.copy(amountSat = amountSat, description = description, lightningInvoice = "invoice", paymentHash = "hash")
        var initialized = false
        var initializationFails = false
        override suspend fun initializeAddress() { if (initializationFails) throw Boom(); initialized = true }
        override suspend fun peekPublicKey(index: UInt): String { check(initialized); return "public-key-0" }
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
    @Test fun closeReleasesWaitingReceiveObserverBeforeClosingHandles() = runBlocking {
        withLocation { location ->
            val handles = Handles()
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val observer = launch { session.receiveCompletion(ReceiveRequest("ark-test", "chain-test")) }
            handles.waiting.await()
            withTimeout(2000) { session.close(); observer.join() }
            assertTrue(handles.holderClosed); assertTrue(handles.closed)
        }
    }

    @Test fun closeAlsoCancelsBeforeNativeWaitHasRegistered() = runBlocking {
        withLocation { location ->
            val handles = Handles().apply { beforeNativeWait = CompletableDeferred() }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val observer = launch { session.receiveCompletion(ReceiveRequest("ark-test", "chain-test")) }
            handles.waiting.await()
            val closing = async { session.close() }
            try { withTimeout(1000) { closing.await(); observer.join() } }
            finally {
                // Release the fake even on a regression so the test itself cannot hang.
                handles.beforeNativeWait!!.complete(Unit); handles.released.complete(Unit)
                closing.await(); observer.join()
            }
            assertTrue(handles.holderClosed); assertTrue(handles.closed)
        }
    }

    @Test fun immediateCloseCannotAbandonAnObserverBeforeItStarts() = runBlocking {
        repeat(20) {
            withLocation { location ->
                val handles = Handles()
                val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
                session.createNew("unused", false, "test")
                val observer = launch(Dispatchers.Default) {
                    runCatching { session.receiveCompletion(ReceiveRequest("ark-test", "chain-test")) }
                }
                handles.subscribed.await()
                withTimeout(2000) { session.close(); observer.join() }
                assertTrue(handles.holderClosed); assertTrue(handles.closed)
            }
        }
    }

    @Test fun cancelReceiveObserverReleasesHolderButLeavesWalletUsable() = runBlocking {
        withLocation { location ->
            val handles = Handles()
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val observer = launch { session.receiveCompletion(ReceiveRequest("ark-test", "chain-test")) }
            handles.waiting.await()
            withTimeout(2000) { observer.cancelAndJoin() }
            assertTrue(handles.holderClosed); assertNotNull(session.summary())
            session.close()
        }
    }

    @Test fun canceledOperationKeepsMutexUntilNativeWorkFinishes() = runBlocking {
        withLocation { location ->
            val handles = Handles().apply { summaryEntered = CompletableDeferred(); summaryGate = CompletableDeferred() }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val reading = launch { session.summary() }
            handles.summaryEntered!!.await(); reading.cancel()
            val closing = launch { session.close() }
            yield(); assertFalse(handles.closed)
            handles.summaryGate!!.complete(Unit)
            reading.join(); closing.join(); assertTrue(handles.closed)
        }
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
            assertTrue(handles.initialized)
            assertEquals("public-key-0", session.peekPublicKey(0u))
            session.close(); assertTrue(handles.closed)
        }
    }
    @Test fun failedKeyDerivationStopsAndClosesBeforeCreationFails() = runBlocking {
        withLocation { location ->
            val handles = Handles().apply { initializationFails = true }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            assertTrue(runCatching { session.createNew("unused", false, "test") }.exceptionOrNull() is Boom)
            assertTrue(handles.closed)
            assertFalse(session.isOpen)
        }
    }

    @Test fun failedDerivationAndStopRetainHandlesUntilSuccessfulClose() = runBlocking {
        withLocation { location ->
            val handles = Handles().apply { initializationFails = true; stopFails = true }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            assertFailure(WalletSession.OpenFailure.teardownFailed, runCatching { session.createNew("unused", false, "test") })
            assertFalse(handles.closed)
            assertTrue(session.isOpen)
            handles.stopFails = false
            session.close()
            assertTrue(handles.closed)
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
            val first = launch { session.createNew("unused", false, "test") }; entered.await(); first.cancel()
            assertFailure(WalletSession.OpenFailure.busy, runCatching { session.ensureOpen("unused", false, "test") })
            assertFalse(first.isCompleted)
            release.complete(Unit); first.join()
            assertTrue(session.isOpen); assertEquals(1, calls)
            session.ensureOpen("unused", false, "test"); assertEquals(1, calls)
            handles.stopFails = true
            assertTrue(runCatching { session.close() }.exceptionOrNull() is Boom)
            assertTrue(session.isOpen); assertFalse(handles.closed)
            handles.stopFails = false; session.close(); assertTrue(handles.closed); assertFalse(session.isOpen)
        }
    }
    @Test fun reservationAndPendingJournalGuardAllEntryPoints() = runBlocking {
        withLocation { location ->
            bark(location)
            val session = WalletSession("regtest", location, inspect = { WalletDatabase.Properties("regtest", "abcd") },
                opener = { _, _, _, _, _ -> throw Boom() })
            val hold = session.reserve()
            assertFalse(session.unlessReserved { fail("Reserved") })
            assertFailure(WalletSession.OpenFailure.busy, runCatching { session.close() })
            assertFailure(WalletSession.OpenFailure.busy, runCatching { session.ensureOpen("unused", false, "test") })
            assertTrue(runCatching { session.verify(hold, "unused", "test") }.exceptionOrNull() is Boom)
            session.release(hold)
            assertTrue(session.unlessReserved { })
            location.restoreDirectory.mkdirs(); File(location.restoreDirectory, "journal.json").writeText("pending")
            assertFailure(WalletSession.OpenFailure.restorePending, runCatching { session.openExisting("unused", false, "test") })
            assertFailure(WalletSession.OpenFailure.restorePending, runCatching { session.createNew("unused", false, "test") })
        }
    }
    @Test fun failedReservedCreationKeepsHoldUntilCleanup() = runBlocking {
        withLocation { location ->
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> throw Boom() })
            val hold = session.reserve()
            assertTrue(runCatching { session.createNew(hold, "unused", false, "test") }.exceptionOrNull() is Boom)
            assertFailure(WalletSession.OpenFailure.busy, runCatching { session.reserve(); Unit })
            session.release(hold)
            session.release(session.reserve())
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
