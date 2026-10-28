package com.noahwallet.services.wallet

import com.noahwallet.services.storage.WalletDataLocation
import io.github.niteshbalusu11.bark.Network
import io.github.niteshbalusu11.bark.WalletSnapshotInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Caller admission, reservations, cleanup and closing. */
internal class WalletSessionTest : WalletSessionFixture() {
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
    @Test
    fun reservationWaitsForSuspensionReadOffCallerThreadAndFailsClosed() = runBlocking {
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

    @Test fun nextWalletActivityRetriesExhaustedSendCleanup() = runBlocking {
        withLocation { location ->
            val handles = Handles(); var calls = 0; var reserved = true
            val held = com.noahwallet.send.SendPreparations({
                calls++
                if (calls <= 3) error("discard")
                reserved = false
            }, {})
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
                clearSettings = {}, clearWidget = {}, sweepScratch = {}, syncIdentityDirectory = {},
                stopAutomation = { world.generation = java.util.UUID.randomUUID().toString() })
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
}
