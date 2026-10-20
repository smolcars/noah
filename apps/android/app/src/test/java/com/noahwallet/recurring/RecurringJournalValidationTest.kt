package com.noahwallet.recurring

import com.noahwallet.send.SendIdentity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(Parameterized::class)
class RecurringJournalValidationTest(private val kind: String) {
    @Test fun malformedEvidenceIsQuarantinedBeforeAnyReadOrMutation() {
        val directory = Files.createTempDirectory("recurring-invalid").toFile()
        try {
            val file = File(directory, "journal.json")
            val json = RecurringStore.json
            val binding = RecurringStore.Binding(SendIdentity("wallet", "regtest"), UUID.randomUUID().toString())
            val entry = RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 1, 1000), 1000,
                UUID.randomUUID().toString(), resolved = true, consumed = true)
            var writes = 0; var quarantines = 0
            fun journal() = RecurringJournal(file,
                write = { writes++; durableRecurringWrite(file, it.toByteArray()) {} },
                quarantine = { quarantines++; durableRecurringWrite(File("${file.path}.quarantine"), it) {} })
            journal().save(entry)
            val valid = file.readBytes()
            val root = json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
            val attempt = root.getValue("attempts").jsonObject.getValue("rent").jsonObject.toMutableMap()
            val plan = attempt.getValue("plan").jsonObject.toMutableMap()
            val indices = root.getValue("consumedIndices").jsonArray.single().jsonObject.toMutableMap()
            val watermark = root.getValue("consumed").jsonArray.single().jsonObject.toMutableMap()
            fun changeBinding(record: MutableMap<String, JsonElement>, field: String, value: String) {
                val owner = record.getValue("binding").jsonObject.toMutableMap()
                if (field == "instance") owner[field] = JsonPrimitive(value)
                else owner["wallet"] = JsonObject(owner.getValue("wallet").jsonObject + (field to JsonPrimitive(value)))
                record["binding"] = JsonObject(owner)
            }
            when (kind) {
                "negative" -> indices["indices"] = JsonArray(listOf(JsonPrimitive(-1)))
                "duplicate" -> indices["indices"] = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1)))
                "unsorted" -> indices["indices"] = JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1)))
                "empty" -> indices["indices"] = JsonArray(emptyList())
                "wrongIndex" -> indices["indices"] = JsonArray(listOf(JsonPrimitive(2)))
                "emptyScope" -> indices["scheduleID"] = JsonPrimitive(" ")
                "wrongScope" -> indices["scheduleID"] = JsonPrimitive("other")
                "emptyWallet" -> changeBinding(indices, "fingerprint", " ")
                "wrongWallet" -> changeBinding(indices, "fingerprint", "other")
                "wrongNetwork" -> changeBinding(indices, "network", "other")
                "badInstance" -> changeBinding(indices, "instance", "broken")
                "shortInstance" -> changeBinding(indices, "instance", "1-1-1-1-1")
                "wrongInstance" -> changeBinding(indices, "instance", UUID.randomUUID().toString())
                "badRevision" -> indices["policyRevision"] = JsonPrimitive("broken")
                "wrongRevision" -> indices["policyRevision"] = JsonPrimitive(UUID.randomUUID().toString())
                "nullRevision" -> indices["policyRevision"] = JsonNull
                "attemptRevision" -> attempt["policyRevision"] = JsonPrimitive("broken")
                "attemptID" -> attempt["scheduleID"] = JsonPrimitive("other")
                "attemptKind" -> plan["kind"] = JsonPrimitive("idle")
                "attemptIndex", "legacy", "v1" -> plan["occurrenceIndex"] = JsonPrimitive(-1)
                "attemptDate" -> plan["scheduledFor"] = JsonNull
                "markedAt" -> attempt.remove("markedAt")
                "unresolvedConsumed" -> attempt["resolved"] = JsonPrimitive(false)
                "watermarkBehind" -> watermark["through"] = JsonPrimitive(999)
                "watermarkScope" -> watermark["scheduleID"] = JsonPrimitive("other")
                "watermarkWallet" -> changeBinding(watermark, "fingerprint", "other")
                "watermarkNetwork" -> changeBinding(watermark, "network", "other")
                "watermarkInstance" -> changeBinding(watermark, "instance", UUID.randomUUID().toString())
                "watermarkDate" -> watermark["through"] = JsonNull
            }
            attempt["plan"] = JsonObject(plan)
            root["attempts"] = JsonObject(mapOf("rent" to JsonObject(attempt)))
            root["consumedIndices"] = JsonArray(listOf(JsonObject(indices)))
            root["consumed"] = JsonArray(listOf(JsonObject(watermark)))
            when (kind) {
                "missingIndices" -> root["consumedIndices"] = JsonArray(emptyList())
                "missingWatermark" -> root["consumed"] = JsonArray(emptyList())
                "missingField" -> root.remove("consumedIndices")
                "stringVersion" -> root["version"] = JsonPrimitive("2")
                "futureVersion" -> root["version"] = JsonPrimitive(4)
                "v1", "v1Revision" -> root["version"] = JsonPrimitive(1)
            }
            if (kind == "v1Revision") root["revisions"] = JsonArray(listOf(JsonObject(indices + ("throughIndex" to JsonPrimitive(-1)))))
            val malformed = (if (kind == "legacy") root.getValue("attempts") else JsonObject(root)).toString().toByteArray()
            file.writeBytes(malformed)
            val reopened = journal()
            assertTrue(runCatching { reopened.load() }.isFailure)
            assertArrayEquals(malformed, file.readBytes())
            assertArrayEquals(malformed, reopened.quarantineFile.readBytes())
            assertTrue(runCatching { reopened.consumed(binding, "rent", 2000, entry.policyRevision, 1) }.isFailure)
            assertTrue(runCatching { reopened.legacyUncertain(binding, "rent") }.isFailure)
            assertTrue(runCatching { reopened.save(entry.copy(consumed = false)) }.isFailure)
            assertEquals(1, writes); assertEquals(1, quarantines)
            // A relaunch cannot bypass the first quarantine by replacing or removing the journal.
            file.writeBytes(valid)
            assertTrue(runCatching { journal().load() }.isFailure)
            assertArrayEquals(valid, file.readBytes())
            assertArrayEquals(malformed, reopened.quarantineFile.readBytes())
            assertTrue(file.delete())
            assertTrue(runCatching { journal().save(entry) }.isFailure)
            assertFalse(file.exists())
            assertEquals(1, writes); assertEquals(1, quarantines)
        } finally { directory.deleteRecursively() }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = listOf(
            "negative", "duplicate", "unsorted", "empty", "wrongIndex", "emptyScope", "wrongScope", "emptyWallet", "wrongWallet", "wrongNetwork",
            "badInstance", "shortInstance", "wrongInstance", "badRevision", "wrongRevision", "nullRevision", "attemptRevision", "attemptID",
            "attemptKind", "attemptIndex", "attemptDate", "markedAt", "unresolvedConsumed", "watermarkBehind", "watermarkScope", "watermarkWallet",
            "watermarkNetwork", "watermarkInstance", "watermarkDate", "missingIndices", "missingWatermark", "missingField", "stringVersion", "futureVersion",
            "legacy", "v1", "v1Revision",
        ).map { arrayOf(it) }
    }
}
