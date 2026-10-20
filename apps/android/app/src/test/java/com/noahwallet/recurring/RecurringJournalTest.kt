package com.noahwallet.recurring

import com.noahwallet.send.SendIdentity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class RecurringJournalTest {
    private val binding = RecurringStore.Binding(SendIdentity("wallet", "regtest"), UUID.randomUUID().toString())
    @Test fun v3KeepsExactIndicesAcrossAttemptReplacementAndReopen() {
        var raw: String? = null
        fun journal() = RecurringJournal(File("unused"), { raw }, { raw = it })
        val revision = UUID.randomUUID().toString()
        val paid = RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 3, 1000), 1000, revision, resolved = true, consumed = true)
        journal().save(paid)
        journal().save(paid.copy(policyRevision = UUID.randomUUID().toString(), resolved = true, consumed = false))
        val reopened = journal()
        assertTrue(reopened.consumed(binding, "rent", 2000, revision, 3))
        assertTrue(reopened.consumed(binding.copy(instance = binding.instance.uppercase()), "rent", 2000, revision.uppercase(), 3))
        assertFalse(reopened.consumed(binding, "rent", 2000, revision, 2))
        assertFalse(reopened.consumed(binding, "rent", 2000, null, 3))
        assertFalse(reopened.consumed(binding.copy(instance = UUID.randomUUID().toString()), "rent", 1000, revision, 3))
        assertFalse(reopened.consumed(binding.copy(wallet = SendIdentity("other", "regtest")), "rent", 1000, revision, 3))
        assertFalse(reopened.consumed(binding, "other", 1000, revision, 3))
        val root = RecurringStore.json.parseToJsonElement(raw!!).jsonObject
        assertEquals(3, root.getValue("version").jsonPrimitive.int)
        assertEquals(listOf(JsonPrimitive(3)), root.getValue("consumedIndices").jsonArray.single().jsonObject.getValue("indices").jsonArray.toList())
        assertTrue(root.getValue("legacyUncertainty").jsonArray.isEmpty())
    }
    @Test fun failedLegacyDictionaryAttemptBlocksAllRevisionsAndZonesDurably() {
        val failed = RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 1, 1000), 1000, resolved = true)
        var raw = RecurringStore.json.encodeToString(mapOf("rent" to failed))
        val journal = RecurringJournal(File("unused"), { raw }, { raw = it })
        journal.load()
        assertEquals(3, RecurringStore.json.parseToJsonElement(raw).jsonObject.getValue("version").jsonPrimitive.int)
        val reopened = RecurringJournal(File("unused"), { raw }, { raw = it })
        reopened.save(failed.copy(policyRevision = UUID.randomUUID().toString()))
        assertTrue(reopened.legacyUncertain(binding, "rent"))
        assertTrue(reopened.consumed(binding, "rent", Long.MAX_VALUE, UUID.randomUUID().toString(), 99))
    }
    @Test fun androidV1RevisionEvidenceIsRetainedAlongsideUncertainty() {
        val revision = UUID.randomUUID().toString()
        var raw = """{"version":1,"attempts":{},"consumed":[],"revisions":[{"binding":${RecurringStore.json.encodeToString(binding)},"scheduleID":"rent","policyRevision":"$revision","throughIndex":7}]}"""
        val journal = RecurringJournal(File("unused"), { raw }, { raw = it })
        journal.load()
        val indices = RecurringStore.json.parseToJsonElement(raw).jsonObject.getValue("consumedIndices").jsonArray.single().jsonObject
        assertEquals(revision, indices.getValue("policyRevision").jsonPrimitive.content)
        assertEquals(7, indices.getValue("indices").jsonArray.single().jsonPrimitive.int)
        assertTrue(journal.legacyUncertain(binding, "rent"))
    }
    @Test fun unknownVersionsAndMalformedEvidencePreserveBytesAndStop() {
        for (raw in listOf(
            """{"version":3,"attempts":{}}""",
            """{"version":2,"attempts":{}}""",
            """{"version":2,"attempts":{},"consumed":[],"consumedIndices":[{"binding":${RecurringStore.json.encodeToString(binding)},"scheduleID":"rent","indices":[-1]}],"legacyUncertainty":[]}""",
            """{"version":1,"attempts":{},"consumed":[{"binding":${RecurringStore.json.encodeToString(binding)},"scheduleID":"rent"}]}"""
        )) {
            var writes = 0
            val journal = RecurringJournal(File("unused"), { raw }, { writes++ }, quarantine = {})
            assertTrue(runCatching { journal.load() }.isFailure)
            assertEquals(0, writes)
        }
    }
    @Test fun invalidUtf8BytesAndTheFirstQuarantineCopySurviveRelaunch() {
        val directory = Files.createTempDirectory("recurring-bytes").toFile()
        try {
            val file = File(directory, "journal.json")
            val bytes = """{"version":2,"attempts":{},"consumed":[],"consumedIndices":[],"legacyUncertainty":[],"unknown":"""".toByteArray() +
                byteArrayOf(0xff.toByte()) + "\"}".toByteArray()
            file.writeBytes(bytes)
            val journal = RecurringJournal(file, quarantine = { durableRecurringWrite(File("${file.path}.quarantine"), it) {} })
            assertTrue(runCatching { journal.load() }.isFailure)
            assertArrayEquals(bytes, file.readBytes()); assertArrayEquals(bytes, journal.quarantineFile.readBytes())
            file.writeText("{}")
            assertTrue(runCatching { RecurringJournal(file).load() }.isFailure)
            assertArrayEquals(bytes, journal.quarantineFile.readBytes())
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedQuarantineWritePreservesOriginalAndLatchesReadsAndSavesOff() {
        var raw: String? = "{broken"; var writes = 0; var quarantines = 0
        val journal = RecurringJournal(File("unused"), { raw }, { writes++ }, quarantine = { quarantines++; error("disk") })
        assertTrue(runCatching { journal.load() }.isFailure)
        assertEquals("{broken", raw)
        raw = null
        val entry = RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 1, 1000), 1000)
        assertTrue(runCatching { journal.load() }.isFailure)
        assertTrue(runCatching { journal.save(entry) }.isFailure)
        assertEquals(0, writes); assertEquals(1, quarantines)
    }
    @Test fun invalidNewEntryCannotMutateOrQuarantineValidJournal() {
        var raw: String? = null; var quarantines = 0
        val journal = RecurringJournal(File("unused"), { raw }, { raw = it }, quarantine = { quarantines++ })
        val entry = RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 1, 1000), 1000)
        journal.save(entry); val saved = raw
        assertTrue(runCatching { journal.save(entry.copy(plan = entry.plan.copy(occurrenceIndex = -1), consumed = true, resolved = true)) }.isFailure)
        assertEquals(saved, raw); assertEquals(0, quarantines)
        assertEquals(entry, journal.load().getValue("rent"))
    }
    @Test fun fsyncedJournalReopensWithoutLosingCrossRevisionWatermark() {
        val directory = Files.createTempDirectory("recurring-journal").toFile()
        try {
            val file = File(directory, "journal.json")
            val binding = RecurringStore.Binding(SendIdentity("wallet", "regtest"), UUID.randomUUID().toString())
            val entry = RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 0, 1000), 1000, resolved = true, consumed = true)
            val journal = RecurringJournal(file, write = { durableRecurringWrite(file, it.toByteArray()) {} })
            journal.save(entry)
            journal.save(entry.copy(plan = entry.plan.copy(scheduledFor = 2000), consumed = false, resolved = false, policyRevision = UUID.randomUUID().toString()))
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
