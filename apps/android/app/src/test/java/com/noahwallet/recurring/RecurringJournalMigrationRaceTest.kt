package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Cold-start Send handoff and executor migration share a wallet journal. */
class RecurringJournalMigrationRaceTest {
    @Test fun startupMigrationMustNotEraseDefaultHandoffProof() = runBlocking {
        val w = RecurringWorld(this)
        val migrationReady = CountDownLatch(1)
        val resumeMigration = CountDownLatch(1)
        val worker = Executors.newFixedThreadPool(2)
        try {
            val s = w.add()
            val entry = RecurringJournal.Entry(w.binding, s.id, s.plan(w.now, w.zone, true), w.now, s.nativeRevision)
            w.journal.save(entry)
            w.controller.submit(arkPayment, FakeSendWallet.quote.copy(walletIdentity = w.wallet.owner), entry.key)!!.await()
            assertEquals(SendController.State.succeeded, w.controller.state)
            val paid = checkNotNull(w.sendJournal.attempt)
            assertNotNull(paid.receipt)

            // A native v2 pending marker and its restored, proven Send receipt.
            w.journal.save(entry)
            val root = RecurringStore.json.parseToJsonElement(checkNotNull(w.journalRaw)).jsonObject
            val durable = AtomicReference<String?>(JsonObject(root + ("version" to JsonPrimitive(2))).toString())
            val executorJournal = RecurringJournal.shared(w.journal.file, w.binding) { file ->
                RecurringJournal(file, { durable.get() }, { raw ->
                    if (migrationReady.count > 0) {
                        migrationReady.countDown()
                        check(resumeMigration.await(10, TimeUnit.SECONDS)) { "Migration release timed out" }
                    }
                    durable.set(raw)
                })
            }
            // Send and executor resolve the same canonical path, even through an alias.
            val defaultHandoff = RecurringJournal.shared(java.io.File(w.journal.file.parentFile, "./journal.json"), w.binding)
            assertSame(executorJournal, defaultHandoff)
            val migration = worker.submit<Map<String, RecurringJournal.Entry>> { executorJournal.load() }
            check(migrationReady.await(10, TimeUnit.SECONDS)) { "Migration was not reached" }
            val handoffStarted = CountDownLatch(1)
            val handoff = worker.submit {
                handoffStarted.countDown()
                defaultHandoff.record(paid, 1, 1, null, w.binding, w.now)
            }
            check(handoffStarted.await(10, TimeUnit.SECONDS))
            resumeMigration.countDown()
            migration.get(10, TimeUnit.SECONDS)
            handoff.get(10, TimeUnit.SECONDS)
            assertEquals(RecurringJournal.Outcome.succeeded, executorJournal.load().getValue(s.id).terminal?.outcome)
            assertNotNull("Startup migration must retain the proven recurring receipt", defaultHandoff.load().getValue(s.id).terminal)
            assertEquals(1, w.wallet.calls)
        } finally {
            resumeMigration.countDown()
            worker.shutdownNow()
            check(worker.awaitTermination(10, TimeUnit.SECONDS)) { "Review worker did not stop" }
            w.policyDirectory.parentFile!!.deleteRecursively()
        }
    }
    @Test fun migrationFailureIsSharedUntilTheWalletInstanceChanges() = runBlocking {
        val w = RecurringWorld(this)
        try {
            val s = w.add()
            w.journal.save(RecurringJournal.Entry(w.binding, s.id, s.plan(w.now, w.zone, true), w.now, s.nativeRevision))
            val root = RecurringStore.json.parseToJsonElement(checkNotNull(w.journalRaw)).jsonObject
            var raw: String? = JsonObject(root + ("version" to JsonPrimitive(2))).toString()
            var writes = 0
            val executor = RecurringJournal.shared(w.journal.file, w.binding) { file ->
                RecurringJournal(file, { raw }, { writes++; error("migration fsync failed") }, quarantine = {})
            }
            assertTrue(runCatching { executor.load() }.isFailure)
            val send = RecurringJournal.shared(w.journal.file.canonicalFile, w.binding)
            assertSame(executor, send)
            assertTrue(runCatching { send.load() }.isFailure)
            assertEquals(1, writes)
            // A same-path replacement is a different wallet instance, admitted by lifecycle.
            raw = null
            val replacement = w.binding.copy(instance = java.util.UUID.randomUUID().toString())
            assertSame(send, RecurringJournal.shared(w.journal.file, replacement))
            assertTrue(send.load().isEmpty())
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }

}
