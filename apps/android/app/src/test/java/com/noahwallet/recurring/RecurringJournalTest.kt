package com.noahwallet.recurring

import com.noahwallet.send.SendIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class RecurringJournalTest {
    @Test fun fsyncedJournalReopensWithoutLosingCrossRevisionWatermark() {
        val directory = Files.createTempDirectory("recurring-journal").toFile()
        try {
            val file = File(directory, "journal.json")
            val binding = RecurringStore.Binding(SendIdentity("wallet", "regtest"), UUID.randomUUID().toString())
            val entry = RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 0, 1000), 1000, resolved = true, consumed = true)
            val journal = RecurringJournal(file, write = { durableRecurringWrite(file, it.toByteArray()) {} })
            journal.save(entry)
            journal.save(entry.copy(plan = entry.plan.copy(scheduledFor = 2000), consumed = false, resolved = false, policyRevision = "new"))
            val reopened = RecurringJournal(file)
            assertTrue(reopened.consumed(binding, "rent", 1000)); assertFalse(reopened.consumed(binding.copy(instance = UUID.randomUUID().toString()), "rent", 1000))
            assertFalse(reopened.load().getValue("rent").resolved)
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedDirectorySyncBlocksAllLaterReadsAndSubmissions() {
        val directory = Files.createTempDirectory("recurring-sync").toFile()
        try {
            val file = File(directory, "journal.json")
            val binding = RecurringStore.Binding(SendIdentity("wallet", "regtest"), UUID.randomUUID().toString())
            val entry = RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 0, 1000), 1000)
            val journal = RecurringJournal(file, write = { durableRecurringWrite(file, it.toByteArray()) { error("fsync") } })
            assertTrue(runCatching { journal.save(entry) }.isFailure); assertTrue(runCatching { journal.load() }.isFailure)
            assertTrue(file.exists()); assertFalse(RecurringJournal(file).load().getValue("rent").resolved)
        } finally { directory.deleteRecursively() }
    }
}
