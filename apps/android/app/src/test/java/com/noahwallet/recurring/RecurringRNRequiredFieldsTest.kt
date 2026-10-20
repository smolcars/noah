package com.noahwallet.recurring

import com.noahwallet.fixtures.SharedFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RecurringRNRequiredFieldsTest(private val name: String, private val row: String, private val classification: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = listOf("recurring-rn-required-fields", "recurring-rn-numeric-types", "recurring-rn-numeric-ranges", "recurring-rn-duplicate-keys").flatMap { fixture ->
            SharedFixtures.document(fixture).jsonObject.getValue("cases").jsonArray.map { it.jsonObject.let { c -> arrayOf(c.getValue("name").jsonPrimitive.content,
                c.getValue("rowJSON").jsonPrimitive.content, c.getValue("classification").jsonPrimitive.content) } } }
    }
    @Test fun firstImportClassifiesBeforeBootstrapAndPreservesUnsupportedBytes() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.raw = """{"version":0,"state":{"schedules":{"broken":$row},"serverSyncPending":false}}"""
            val original = w.raw
            val visible = w.store.load().schedules.getValue("broken")
            assertEquals(classification, if (visible.unsupportedStorage) "stopped-unsupported" else visible.status)
            assertEquals(original, w.raw)
            w.executor.execute(); w.executor.execute(manual = true)
            val unsupported = classification == "stopped-unsupported"
            assertEquals(if (unsupported) 0 else 1, w.wallet.calls)
            if (!unsupported) return@runBlocking
            assertTrue(w.journal.load().isEmpty())
            assertTrue(runCatching { w.executor.resume("broken") }.isFailure)
            assertTrue(runCatching { w.executor.save(visible, editing = true) }.isFailure)
            w.executor.pause("broken"); w.store.setPending(false)
            // An untrusted display copy must not repair missing approval/execution fields.
            w.store.upsert(visible.copy(unsupportedStorage = false, status = "active"))
            assertTrue("$name row must survive byte-for-byte", w.raw!!.contains(row))
            assertTrue(RecurringStore({ w.raw }, { w.raw = it }).load().schedules.getValue("broken").unsupportedStorage)
            w.executor.cancel("broken")
            assertTrue(w.store.load().schedules.isEmpty())
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
}
