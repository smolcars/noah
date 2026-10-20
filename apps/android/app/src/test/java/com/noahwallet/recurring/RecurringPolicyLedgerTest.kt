package com.noahwallet.recurring

import com.noahwallet.send.SendIdentity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class RecurringPolicyLedgerTest {
    private class World : AutoCloseable {
        val directory = Files.createTempDirectory("policy-ledger-").toFile()
        val file = File(directory, "journal.json.policies")
        val marker = File("${file.path}.initialized")
        val binding = RecurringStore.Binding(SendIdentity("wallet", "regtest"), UUID.randomUUID().toString())
        val policy = RecurringPayment("rent", "Rent", "recipient", "ark", 1000, interval = RecurringInterval("day", 1), startAt = 1000, createdAt = 0, updatedAt = 0)
        var raw: String? = null
        var writes = 0
        var beforeWrite: () -> Unit = {}
        fun store(protected: Boolean = true, owner: RecurringStore.Binding = binding) = RecurringStore({ raw }, { beforeWrite(); raw = it; writes++ }).also {
            if (protected) it.protectPolicies(file, sync = {}) { owner }
        }
        init { store(false).apply { bind(binding); upsert(policy) }; writes = 0 }
        override fun close() { directory.deleteRecursively() }
    }
    @Test fun bootstrapIsDurableOnceAndRetainsRNEnvelope() = World().use { w ->
        val original = w.raw
        val store = w.store()
        assertEquals("active", store.load().schedules.getValue("rent").status)
        val first = w.file.readBytes(); val marker = w.marker.readBytes()
        assertEquals(original, w.raw); assertEquals(0, w.writes)
        w.store().load()
        assertArrayEquals(first, w.file.readBytes()); assertArrayEquals(marker, w.marker.readBytes())
        // An unknown row injected after bootstrap must never be imported a second time.
        w.store(false).upsert(w.policy.copy(id = "resurrected"))
        val stopped = w.store().load().schedules.getValue("resurrected")
        assertEquals("needs_attention", stopped.status)
        assertTrue(stopped.requiresPolicyApproval)
        assertTrue(stopped.copy(lastError = "Translated or revised display copy").requiresPolicyApproval)
        assertTrue(stopped.copy(stopReason = null).requiresPolicyApproval)
        assertFalse(stopped.copy(stopReason = null, issue = null).requiresPolicyApproval)
        assertArrayEquals(first, w.file.readBytes())
    }
    @Test fun missingCorruptLedgerOrMarkerCannotReseedOrMutate() {
        for (damage in listOf("missing", "corrupt", "marker-missing", "marker-corrupt")) World().use { w ->
            w.store().load()
            when (damage) {
                "missing" -> assertTrue(w.file.delete())
                "corrupt" -> w.file.writeText("{")
                "marker-missing" -> assertTrue(w.marker.delete())
                else -> w.marker.writeText("{")
            }
            val store = w.store(); val before = w.raw
            assertEquals(damage, "needs_attention", store.load().schedules.getValue("rent").status)
            assertTrue(damage, runCatching { store.upsert(w.policy.copy(status = "paused")) }.isFailure)
            assertEquals(before, w.raw)
        }
    }
    @Test fun bootstrapInterruptedAfterMarkerCannotReseed() = World().use { w ->
        durableRecurringWrite(w.marker, RecurringStore.json.encodeToString(buildJsonObject {
            put("version", 1); put("binding", RecurringStore.json.encodeToJsonElement(w.binding))
        }).toByteArray()) {}
        assertEquals("needs_attention", w.store().load().schedules.getValue("rent").status)
        assertFalse(w.file.exists())
    }
    @Test fun ledgerPrecedesMMKVAndCrashBetweenWritesStopsOldPolicy() = World().use { w ->
        val store = w.store(); store.load(); val before = w.raw
        w.beforeWrite = {
            val entry = RecurringStore.json.parseToJsonElement(w.file.readText()).jsonObject.getValue("entries").jsonObject.values.single().jsonObject
            assertEquals("paused", entry.getValue("status").jsonPrimitive.content)
            error("crash before MMKV commit")
        }
        assertTrue(runCatching { store.upsert(w.policy.copy(status = "paused")) }.isFailure)
        assertEquals(before, w.raw)
        w.beforeWrite = {}
        assertEquals("needs_attention", w.store().load().schedules.getValue("rent").status)
        // A stale policy cannot be blessed by an ordinary update or destructive resume.
        assertTrue(runCatching { w.store().upsert(w.policy) }.isFailure)
    }
    @Test fun MMKVAheadOfLedgerAlsoStopsAndLedgerFailureNeverWritesMMKV() = World().use { w ->
        val store = w.store(); store.load()
        w.store(false).upsert(w.policy.copy(amountSat = 500, nativeRevision = UUID.randomUUID().toString()))
        assertEquals("needs_attention", w.store().load().schedules.getValue("rent").status)
        w.file.writeText("{"); val writes = w.writes
        assertTrue(runCatching { store.upsert(w.policy.copy(status = "paused")) }.isFailure)
        assertEquals(writes, w.writes)
    }
    @Test fun cancellationWithMissingMMKVRowStillWritesTombstone() = World().use { w ->
        val store = w.store(); store.load(); val active = w.raw
        w.store(false).remove("rent") // A recovered MMKV file has lost the policy row.
        store.remove("rent")
        w.raw = active
        assertEquals("needs_attention", w.store().load().schedules.getValue("rent").status)
    }
    @Test fun directorySyncFailureBlocksCommitBeforeMMKV() = World().use { w ->
        w.store().load(); val before = w.raw
        val store = RecurringStore({ w.raw }, { w.raw = it; fail("MMKV must not be written") })
        store.protectPolicies(w.file, sync = { error("directory fsync") }) { w.binding }
        assertTrue(runCatching { store.upsert(w.policy.copy(status = "paused")) }.isFailure)
        assertEquals(before, w.raw)
        assertEquals("needs_attention", w.store().load().schedules.getValue("rent").status)
    }

    @Test fun deletionTombstoneSurvivesReopenAndNewBindingCannotInherit() = World().use { w ->
        val store = w.store(); store.load(); val original = w.raw
        store.remove("rent"); w.raw = original
        assertEquals("needs_attention", w.store().load().schedules.getValue("rent").status)
        assertTrue(runCatching { w.store().upsert(w.policy) }.isFailure)
        store.remove("rent") // Cancellation remains possible for a resurrected row.
        assertTrue(w.store().load().schedules.isEmpty())
        w.raw = original
        val other = w.binding.copy(instance = UUID.randomUUID().toString())
        w.store(false).bind(other)
        assertEquals("needs_attention", w.store(owner = other).load().schedules.getValue("rent").status)
    }
}
