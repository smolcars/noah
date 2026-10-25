package com.noahwallet.services.wallet

import com.noahwallet.services.storage.WalletDataLocation
import io.github.niteshbalusu11.bark.Network
import io.github.niteshbalusu11.bark.WalletSnapshotInfo
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class WalletSessionTest {
    @Test fun cancelledOrExpiredCallerCannotCreateOrVerify() = runBlocking {
        for (operation in listOf("create", "reserved-create", "verify")) for (cancel in listOf(true, false)) withLocation { location ->
            if (operation == "verify") bark(location)
            var opened = false
            val session = WalletSession("regtest", location,
                inspect = { WalletDatabase.Properties("regtest", "abcd") },
                opener = { _, _, _, _, _ -> opened = true; Handles() })
            val reservation = if (operation == "create") null else session.reserve()
            val budget = com.noahwallet.recurring.RecurringBudget()
            launch(Dispatchers.IO + WalletExecutionBudget(budget)) {
                if (cancel) currentCoroutineContext().cancel() else budget.revoke()
                runCatching {
                    when (operation) {
                        "create" -> session.createNew("unused", false, "test")
                        "reserved-create" -> session.createNew(reservation!!, "unused", false, "test")
                        else -> session.verify(reservation!!, "unused", "test")
                    }
                }
            }.join()
            reservation?.let { session.release(it) }
            session.closeSettled()
            assertFalse("$operation admitted an opener (cancel=$cancel)", opened)
        }
    }

    @Test fun recurringReservationRechecksCallerAfterCleanup() = runBlocking {
        for (cancel in listOf(true, false)) withLocation { location ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val handles = Handles().apply { beforeRetry = { entered.complete(Unit); release.await() } }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val budget = com.noahwallet.recurring.RecurringBudget()
            var started = false
            val caller = launch(Dispatchers.IO + WalletExecutionBudget(budget)) {
                runCatching { session.withRecurringReservation { started = true } }
            }
            entered.await()
            if (cancel) caller.cancel() else budget.revoke()
            release.complete(Unit); caller.join()
            session.close()
            val reservation = session.reserve(); session.release(reservation)
            assertFalse("Reserved body started after cleanup (cancel=$cancel)", started)
        }
    }

    @Test fun sendReservationRechecksCallerAfterWaitingForAnotherSend() = runBlocking {
        for (cancel in listOf(true, false)) withLocation { location ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> Handles() })
            session.createNew("unused", false, "test")
            val first = launch { session.withSendReservation { entered.complete(Unit); release.await() } }
            entered.await()
            val budget = com.noahwallet.recurring.RecurringBudget()
            var started = false
            withContext(Dispatchers.IO) {
                // Same dispatcher + UNDISPATCHED runs through admission up to the held mutex.
                val second = launch(WalletExecutionBudget(budget), start = CoroutineStart.UNDISPATCHED) {
                    runCatching { session.withSendReservation { started = true } }
                }
                if (cancel) second.cancel() else budget.revoke()
                release.complete(Unit); first.join(); second.join()
            }
            session.close()
            assertFalse("Queued send started after cancellation/revocation", started)
        }
    }

    @Test fun cancelledCallerCannotAdmitNewNativeRead() = runBlocking {
        withLocation { location ->
            val handles = Handles()
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("public-test-input", false, "test")
            var nativeStarted = false
            val work = launch(Dispatchers.IO) {
                currentCoroutineContext().cancel()
                runCatching { session.withOpen { nativeStarted = true } }
            }
            work.join()
            session.close()
            assertFalse("New native body ran after caller cancellation", nativeStarted)
        }
    }
    @Test fun cancelledAdmissionCannotStartDelayedOpener() = runBlocking {
        for (cancelCaller in listOf(true, false)) withLocation { location ->
            bark(location)
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val opened = CompletableDeferred<Unit>()
            val budget = com.noahwallet.recurring.RecurringBudget()
            val session = WalletSession("regtest", location,
                isSuspended = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); false },
                inspect = { WalletDatabase.Properties("regtest", "abcd") },
                opener = { _, _, _, _, _ -> opened.complete(Unit); throw Boom() })
            val work = launch(Dispatchers.IO + WalletExecutionBudget(budget)) {
                runCatching { session.ensureOpen("unused", false, "test") }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            if (cancelCaller) work.cancel() else budget.revoke()
            release.countDown(); work.join()
            session.closeSettled()
            assertFalse("Opener ran after cancellation/revocation during admission", opened.isCompleted)
            val reservation = session.reserve(); session.release(reservation)
        }
    }

    @Test fun revokedAdmissionCannotStartNativeBodyAfterCleanup() = runBlocking {
        for (cancelCaller in listOf(true, false)) withLocation { location ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val handles = Handles().apply { beforeRetry = { entered.complete(Unit); release.await() } }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("public-test-input", false, "test")
            val budget = com.noahwallet.recurring.RecurringBudget()
            var nativeStarted = false
            val work = launch(Dispatchers.IO + WalletExecutionBudget(budget)) {
                runCatching { session.withOpen { nativeStarted = true } }
            }
            entered.await()
            if (cancelCaller) work.cancel() else budget.revoke()
            release.complete(Unit); work.join()
            session.close()
            val reservation = session.reserve(); session.release(reservation)
            assertFalse("New body ran after cancellation/revocation during cleanup", nativeStarted)
        }
    }

    @Test fun aReusedSessionCannotAuthorizeMigrationOfAnotherCredential() = runBlocking {
        withLocation { location ->
            val handles = Handles()
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("first-public-test-input", false, "test")
            session.ensureOpen("first-public-test-input", false, "test")
            assertFailure(WalletSession.OpenFailure.seedConflict,
                runCatching { session.ensureOpen("different-public-test-input", false, "test") })
            session.close()
        }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test fun reservationWaitsForSuspensionReadOffCallerThreadAndFailsClosed() = runBlocking {
        withLocation { location ->
            val handles = Handles()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            var gate = false
            var readThread: Thread? = null
            val session = WalletSession("regtest", location, isSuspended = {
                if (gate) {
                    readThread = Thread.currentThread(); entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    true
                } else false
            }, opener = { _, _, _, _, _ -> handles })
            session.createNew("public-test-input", false, "test")
            newSingleThreadContext("launch-main").use { main ->
                val caller = withContext(main) { Thread.currentThread() }
                gate = true
                var submitted = false
                val work = async(main) { runCatching { session.withSendReservation { submitted = true } } }
                try {
                    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    assertNotSame("Suspension disk reads must leave the caller thread", caller, readThread)
                    assertFalse(submitted)
                    assertFalse(work.isCompleted)
                } finally { release.countDown() }
                assertFailure(WalletSession.OpenFailure.suspended, work.await())
                assertFalse(submitted)
            }
            gate = false; session.close()
        }
    }

    @Test fun backgroundExitProgressFailureIsLoggedNeverThrown() = runBlocking {
        withLocation { location ->
            val handles = Handles()
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val warnings = mutableListOf<String>()
            // The background job holds the wallet: the step still runs, where the UI's onchain calls are refused.
            WalletBackgroundActivity.exclusive { session.progressPendingExits { warnings += it } }
            assertEquals(listOf("offline"), warnings)
            assertTrue(handles.exitsRead)
            session.close()
        }
    }

    private class Boom : Exception()
    private class Handles : WalletSession.Handles {
        var exitsRead = false
        override val m6 = object : com.noahwallet.onchain.OnchainWalletAccess {
            override val walletID = "wallet"
            override suspend fun exits(): List<io.github.niteshbalusu11.bark.ExitVtxo> {
                exitsRead = true; throw io.github.niteshbalusu11.bark.BarkError("offline", io.github.niteshbalusu11.bark.BarkErrorCode.OTHER)
            }
        }
        override val send = com.noahwallet.send.FakeSendWallet()
        var preparations: com.noahwallet.send.SendPreparations? = null
        var beforeRetry: suspend () -> Unit = {}
        override suspend fun retrySendDiscards() { beforeRetry(); preparations?.retryDiscards() }
        var sendReviewsDiscarded = false
        override suspend fun discardSendReviews() { preparations?.discardAll(); sendReviewsDiscarded = true }
        val subscribed = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var holderClosed = false
        var beforeNativeWait: CompletableDeferred<Unit>? = null
        var waitRegistered = false
        val changeWaiting = CompletableDeferred<Unit>()
        val changeReleased = CompletableDeferred<Unit>()
        var changeClosed = false
        override fun stateChanges(): WalletSession.Changes = object : WalletSession.Changes {
            override suspend fun next(): io.github.niteshbalusu11.bark.StateChangeEvent? {
                changeWaiting.complete(Unit); changeReleased.await(); return null
            }
            override fun stop() { changeReleased.complete(Unit) }
            override fun close() { changeClosed = true }
        }
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
        var historyEntered: CompletableDeferred<Unit>? = null
        var historyGate: CompletableDeferred<Unit>? = null
        var chainRead = false
        override suspend fun history(): List<io.github.niteshbalusu11.bark.Movement> {
            historyEntered?.complete(Unit); historyGate?.await(); assertFalse(closed)
            return emptyList()
        }
        override suspend fun transactions(): List<io.github.niteshbalusu11.bark.WalletTransaction> {
            assertFalse(closed); chainRead = true
            return emptyList()
        }
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
    @Test fun nextWalletActivityRetriesExhaustedSendCleanup() = runBlocking {
        withLocation { location ->
            val handles = Handles(); var calls = 0; var reserved = true
            val held = com.noahwallet.send.SendPreparations({ calls++; if (calls <= 3) error("discard"); reserved = false }, {})
            handles.preparations = held
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            held.retain("unvalidated")
            held.discard("unvalidated")
            assertTrue(reserved); assertEquals(3, calls)
            session.peekPublicKey(0u)
            assertFalse(reserved); assertEquals(4, calls)
            session.close(); assertTrue(handles.closed)
        }
    }
    @Test fun failedSendCleanupKeepsSessionOpenAndNativeHandlesUntilNextClose() = runBlocking {
        withLocation { location ->
            val handles = Handles(); var calls = 0
            val held = com.noahwallet.send.SendPreparations({ calls++; if (calls <= 3) error("discard") }, {})
            handles.preparations = held
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test"); held.retain("unvalidated")
            assertTrue(runCatching { session.close() }.exceptionOrNull() is com.noahwallet.send.SendFailure)
            assertTrue(session.isOpen); assertFalse(handles.closed); assertFalse(handles.sendReviewsDiscarded)
            assertEquals(3, calls)
            session.close(); assertFalse(session.isOpen); assertTrue(handles.closed); assertEquals(4, calls)
        }
    }
    @Test fun canceledQuoteDiscardsTheSuccessfulNativeResult() = runBlocking {
        withLocation { location ->
            val handles = Handles(); val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            handles.send.quoteBlock = { entered.complete(Unit); gate.await(); com.noahwallet.send.chainQuote }
            val quoting = launch { session.quoteSend(com.noahwallet.send.chainPayment) }
            entered.await(); quoting.cancel(); gate.complete(Unit); quoting.join()
            assertEquals(1, handles.send.discards)
            session.close()
        }
    }
    @Test fun recurringReservationDrainsCallsExcludesCloseAndSurvivesCancellation() = runBlocking {
        withLocation { location ->
            val handles = Handles().apply { summaryEntered = CompletableDeferred(); summaryGate = CompletableDeferred() }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val reading = launch { session.summary() }; handles.summaryEntered!!.await()
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            val sending = launch { session.withRecurringReservation { entered.complete(Unit); gate.await(); assertFalse(handles.closed) } }
            withTimeout(2000) { while (runCatching { session.peekAddress(0u) }.isSuccess) yield() }
            assertFalse(entered.isCompleted)
            assertTrue(runCatching { session.close() }.isFailure)
            handles.summaryGate!!.complete(Unit); reading.join(); entered.await(); sending.cancel()
            assertTrue(runCatching { session.reserve() }.isFailure)
            assertTrue(runCatching { session.summary() }.isFailure)
            gate.complete(Unit); sending.join(); session.close(); assertTrue(handles.closed)
        }
    }
    @Test fun failedSuspendOrDeletePermanentlyRevokesPendingRecurringSubmission() = runBlocking {
        for (delete in listOf(false, true)) withLocation { location ->
            val handles = Handles()
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val world = com.noahwallet.recurring.RecurringWorld(this, handles.send) { body -> session.withRecurringReservation(body) }
            world.add()
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            handles.send.prepareBlock = { entered.complete(Unit); gate.await(); handles.send.snapshot }
            val execution = async { world.executor.execute() }; entered.await()
            val lifecycle = WalletLifecycle(session, location, setSuspended = { world.allowed = !it }, deregister = {},
                readSeed = { com.noahwallet.services.storage.SecretResult.NotFound }, deleteSeed = {}, clearToken = {},
                clearSettings = {}, clearWidget = {}, sweepScratch = {}, syncIdentityDirectory = {}, stopAutomation = { world.generation = java.util.UUID.randomUUID().toString() })
            assertTrue(runCatching { if (delete) lifecycle.delete() else lifecycle.suspend() }.isFailure)
            assertTrue(world.allowed); assertNotNull(world.generation)
            gate.complete(Unit); execution.await()
            assertEquals(0, handles.send.calls); assertFalse(world.journal.consumed(world.binding, "rent", 1000))
            session.close()
        }
    }
    @Test fun sendReservationIsCountedShieldedAndExcludesReplacement() = runBlocking {
        withLocation { location ->
            val handles = Handles()
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            val sending = launch { session.withSendReservation { entered.complete(Unit); gate.await(); assertFalse(handles.closed) } }
            entered.await(); sending.cancel()
            assertTrue(runCatching { session.reserve() }.isFailure)
            val closing = launch { session.close() }; yield(); assertFalse(handles.closed)
            gate.complete(Unit); sending.join(); closing.join()
            assertTrue(handles.sendReviewsDiscarded); assertTrue(handles.closed)
        }
    }
    @Test fun canceledHistoryReadCompletesBothReadsBeforeClose() = runBlocking {
        withLocation { location ->
            val handles = Handles().apply { historyEntered = CompletableDeferred(); historyGate = CompletableDeferred() }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            assertTrue(runCatching { session.transactionHistory() }.exceptionOrNull() is WalletSession.SessionError)
            session.createNew("unused", false, "test")
            val reading = launch { session.transactionHistory() }
            handles.historyEntered!!.await(); reading.cancel()
            val closing = launch { session.close() }
            yield(); assertFalse(handles.closed)
            handles.historyGate!!.complete(Unit); reading.join(); closing.join()
            assertTrue(handles.chainRead); assertTrue(handles.closed)
        }
    }
    @Test fun closeStopsStateObserverBeforeDrainingCalls() = runBlocking {
        withLocation { location ->
            val handles = Handles().apply { summaryEntered = CompletableDeferred(); summaryGate = CompletableDeferred() }
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            val observer = launch { runCatching { session.stateChanges().collect() } }
            handles.changeWaiting.await()
            val call = launch { session.summary() }; handles.summaryEntered!!.await()
            val closing = launch { session.close() }
            withTimeout(2000) { observer.join() }
            assertTrue(handles.changeClosed); assertFalse(handles.closed)
            assertTrue(runCatching { session.reserve() }.isFailure)
            handles.summaryGate!!.complete(Unit); call.join(); closing.join(); assertTrue(handles.closed)
        }
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
    @Test fun silentEsploraReleasesUIGateButKeepsReservationUntilRollback() = runBlocking {
        for (lateSuccess in listOf(false, true)) withLocation { location ->
            bark(location)
            java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                val connected = CompletableDeferred<java.net.Socket>()
                val accepting = launch(Dispatchers.IO) { connected.complete(server.accept()) }
                val previous = EsploraSettings.Selection(variant = "signet")
                val candidate = EsploraSettings.Selection("http://127.0.0.1:${server.localPort}", "signet")
                var opens = 0
                val allHandles = mutableListOf<Handles>()
                val session = WalletSession("regtest", location, inspect = { WalletDatabase.Properties("regtest", "abcd") },
                    opener = { _, _, _, _, _ ->
                        if (++opens == 2) withContext(NonCancellable + Dispatchers.IO) {
                            java.net.Socket("127.0.0.1", server.localPort).use { it.getInputStream().read() }
                            if (!lateSuccess) throw Boom()
                        }
                        Handles().also { allHandles += it }
                    })
                session.openExisting("unused", false, "test")
                val values = mutableMapOf<String, String>()
                val store = EsploraSettings(values::get) { key, value -> values[key] = value }
                store.save(EsploraSettings.Record(previous))
                val prepared = store.prepare(candidate)
                val reservation = session.reserveForConfigurationChange()
                val gate = kotlinx.coroutines.sync.Mutex()
                val wait = EsploraOpenWait(); val finished = CompletableDeferred<Boolean>()
                assertTrue(runCatching {
                    gate.lock()
                    try {
                        wait.run(timeoutMillis = 100, lateCompletion = { finished.complete(it) }) { attempt ->
                            try {
                                store.change(candidate, prepared, attempt::commit) { _, commit -> session.reopen(reservation, "unused", "test", commit) }
                            } finally { session.release(reservation) }
                        }
                    } finally { gate.unlock() }
                }.exceptionOrNull() is EsploraOpenWait.Timeout)
                assertTrue(gate.tryLock()); gate.unlock()
                assertFalse(session.unlessReserved { fail("native open lost its reservation") })
                assertFailure(WalletSession.OpenFailure.busy, runCatching { session.ensureOpen("unused", false, "test") })
                withTimeout(5_000) { connected.await() }.close(); accepting.join()
                assertFalse(withTimeout(5_000) { finished.await() })
                assertTrue(session.isOpen)
                assertEquals(previous, store.record()!!.active)
                assertEquals(3, opens)
                assertTrue(allHandles.dropLast(1).all { it.closed })
                session.close()
            }
        }
    }

    @Test fun launchDefaultWaitsForNativeAndReopensDespiteStorageFailure() = runBlocking {
        withLocation { location ->
            bark(location)
            val native = CompletableDeferred<Unit>()
            var opens = 0
            val session = WalletSession("regtest", location, inspect = { WalletDatabase.Properties("regtest", "abcd") },
                opener = { _, _, _, _, _ ->
                    if (++opens == 2) { native.await(); throw Boom() }
                    Handles()
                })
            session.openExisting("unused", false, "test"); session.close()
            val reservation = session.reserve()
            val wait = EsploraOpenWait(); val finished = CompletableDeferred<Boolean>()
            val storage = EsploraSettings({ null }) { _, _ -> throw java.io.IOException("disk full") }
            assertTrue(runCatching {
                wait.run(timeoutMillis = 100, lateCompletion = { finished.complete(it) }) { attempt ->
                    try {
                        try { session.reopen(reservation, "unused", "test") { attempt.commit {} } }
                        catch (failure: Exception) {
                            if (!attempt.timedOut) throw failure
                            attempt.waitForDefault()
                            runCatching { storage.save(EsploraSettings.Record(EsploraSettings.Selection(variant = "signet"))) }
                            session.reopen(reservation, "unused", "test") {}
                        }
                    } finally { session.release(reservation) }
                }
            }.exceptionOrNull() is EsploraOpenWait.Timeout)
            wait.chooseDefault()
            assertFalse(session.isOpen)
            assertFalse(session.unlessReserved { fail("default opened over pending native work") })
            assertEquals(2, opens)
            native.complete(Unit)
            assertTrue(withTimeout(5_000) { finished.await() })
            assertTrue(session.isOpen)
            assertTrue(location.database.exists())
            assertEquals(3, opens)
            session.close()
        }
    }

    @Test fun esploraReloadRefusesInFlightMoneyOperations() = runBlocking {
        withLocation { location ->
            val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> Handles() })
            session.createNew("unused", false, "test")
            for (operation in listOf("send", "board", "exit", "recurring")) {
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val work = launch {
                    when (operation) {
                        "send" -> session.withSendReservation { entered.complete(Unit); release.await() }
                        "recurring" -> session.withRecurringReservation { entered.complete(Unit); release.await() }
                        else -> session.withOpen { entered.complete(Unit); release.await() }
                    }
                }
                entered.await()
                assertTrue(runCatching { session.reserveForConfigurationChange() }.exceptionOrNull() is EsploraEndpoint.Failure)
                assertTrue(session.isOpen)
                release.complete(Unit); work.join()
            }
            session.release(session.reserveForConfigurationChange())
        }
    }

    @Test fun esploraReopenKeepsReservationThroughDurableCommit() = runBlocking {
        withLocation { location ->
            bark(location)
            val session = WalletSession("regtest", location, inspect = { WalletDatabase.Properties("regtest", "abcd") },
                opener = { _, _, _, _, _ -> Handles() })
            session.openExisting("unused", false, "test")
            val reservation = session.reserveForConfigurationChange()
            session.reopen(reservation, "unused", "test") {
                assertFalse(session.isOpen)
                assertFalse(session.unlessReserved { fail("commit released reservation") })
            }
            assertTrue(session.isOpen)
            session.release(reservation)
            assertTrue(session.isOpen)
            session.close()
        }
    }

    @Test fun esploraCommitFailureKeepsReservationOrQuarantinesFailedTeardown() = runBlocking {
        for (teardownFails in listOf(false, true)) withLocation { location ->
            bark(location)
            var opened = 0
            val session = WalletSession("regtest", location, inspect = { WalletDatabase.Properties("regtest", "abcd") },
                opener = { _, _, _, _, _ -> Handles().also { it.stopFails = ++opened == 2 && teardownFails } })
            session.openExisting("unused", false, "test")
            val reservation = session.reserveForConfigurationChange()
            val failure = runCatching { session.reopen(reservation, "unused", "test") { throw Boom() } }.exceptionOrNull()
            assertFalse(session.isOpen)
            if (teardownFails) {
                assertEquals(WalletSession.OpenFailure.teardownFailed, (failure as WalletSession.OpenError).reason)
                assertTrue(runCatching { session.reopen(reservation, "unused", "test") {} }.isFailure)
                assertTrue(runCatching { session.close() }.isFailure)
                assertFalse(session.isOpen)
            } else {
                assertTrue(failure is Boom)
                assertFalse(session.unlessReserved { fail("rollback lost reservation") })
                session.reopen(reservation, "unused", "test") {}
                assertTrue(session.isOpen); session.close()
            }
        }
    }

    @Test fun suspendedWalletPerformsNoM6Operations() = runBlocking {
        withLocation { location ->
            var suspended = false
            val handles = Handles()
            val session = WalletSession("regtest", location, isSuspended = { suspended }, opener = { _, _, _, _, _ -> handles })
            session.createNew("unused", false, "test")
            suspended = true
            val quote = com.noahwallet.onchain.OnchainQuote(com.noahwallet.onchain.OnchainAction.board, "wallet")
            val calls: List<suspend () -> Unit> = listOf(
                { session.onchainOverview() }, { session.exitDetail("a") }, { session.exitDepositAddress() },
                { session.boardingMinimum() }, { session.autoBoardQuote() },
                { session.quoteOnchain(com.noahwallet.onchain.OnchainAction.board) }, { session.executeOnchain(quote) },
                { session.checkOnchainAttempt("a") }, { session.refreshOnchainReceiptFees() },
            )
            for (call in calls) assertEquals(WalletSession.OpenFailure.suspended, (runCatching { call() }.exceptionOrNull() as WalletSession.OpenError).reason)
            assertFalse(location.directory.resolve("noah-onchain-attempt.json").exists())
            suspended = false; session.close()
        }
    }

}
