package com.noahwallet.recurring

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecurringVersionTest {
    @Test fun invalidRawVersionsStopPopulatedStoreWithoutRewritingOrExecuting() = runBlocking {
        val fixture = javaClass.getResource("/recurring-rn-range.json")!!.readText()
        for (version in listOf("1e-400", "0.0", "-0", "0e5", "\"0\"", "1", "1e400", "true", "false", "null", "{}", "[]")) {
            val w = RecurringWorld(this)
            val raw = fixture.replace("\"version\": 0", "\"version\": $version")
            w.raw = raw
            assertTrue(version, runCatching { w.store.load() }.isFailure)
            assertTrue(version, runCatching { w.store.bind(w.binding) }.isFailure)
            assertTrue(version, runCatching { w.store.upsert(RecurringPayment("new", "New", "recipient", "ark", 1000,
                interval = RecurringInterval("day", 1), startAt = 1000, createdAt = 1000, updatedAt = 1000)) }.isFailure)
            assertTrue(version, runCatching { w.store.remove("count-0") }.isFailure)
            assertTrue(version, runCatching { w.store.setPending(false) }.isFailure)
            w.executor.execute(); w.executor.execute(manual = true)
            assertEquals(version, 0, w.wallet.calls)
            assertNotNull(version, w.executor.error)
            assertNull(version, w.journalRaw)
            assertEquals(version, raw, w.raw)
            assertTrue(version, runCatching { RecurringStore({ w.raw }, { w.raw = it }).load() }.isFailure)
        }
    }

    @Test fun writerAndLegacyVersionsRemainSupported() {
        val fixture = javaClass.getResource("/recurring-rn-range.json")!!.readText()
        for (version in listOf("0", " 0 \n", "absent")) {
            var raw = if (version == "absent") fixture.replace(",\n  \"version\": 0", "")
            else fixture.replace("\"version\": 0", "\"version\": $version")
            val original = raw
            val store = RecurringStore({ raw }, { raw = it })
            assertEquals(11, store.load().schedules.size)
            assertEquals(original, raw)
            store.setPending(false)
            assertFalse(RecurringStore({ raw }, { raw = it }).load().pending)
        }
    }
}
