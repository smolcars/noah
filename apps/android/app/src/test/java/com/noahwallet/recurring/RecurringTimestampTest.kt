package com.noahwallet.recurring

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RecurringTimestampTest(private val field: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun fields() = listOf(
            "startAt", "endAt", "nextRunAt", "createdAt", "updatedAt", "retryNotBefore",
            "inFlight.startedAt", "runs.scheduledFor", "runs.attemptedAt")
    }

    @Test fun fractionalTimeStaysVisibleStoppedLosslessAndCancellable() = runBlocking {
        val w = RecurringWorld(this)
        val original = w.add()
        w.store.upsert(original.copy(id = "healthy"))
        val row = RecurringStore.json.encodeToJsonElement(original).jsonObject.toMutableMap()
        when (field) {
            "inFlight.startedAt" -> row["inFlight"] = buildJsonObject { put("occurrenceIndex", 0); put("startedAt", 1000.5) }
            "runs.scheduledFor", "runs.attemptedAt" -> row["runs"] = buildJsonArray { add(buildJsonObject {
                put("occurrenceIndex", 0); put("scheduledFor", 1000); put("attemptedAt", 1000)
                put("status", "failed"); put("amountSat", 1000); put(field.substringAfter('.'), 1000.5)
            }) }
            else -> row[field] = JsonPrimitive(1000.5)
        }
        val unsupported = JsonObject(row)
        val root = Json.parseToJsonElement(w.raw!!).jsonObject
        val state = root.getValue("state").jsonObject
        val schedules = JsonObject(mapOf(original.id to unsupported,
            "healthy" to RecurringStore.json.encodeToJsonElement(original.copy(id = "healthy"))))
        w.raw = JsonObject(root + ("state" to JsonObject(state + ("schedules" to schedules)))).toString()
        val raw = w.raw
        val visible = w.store.load().schedules.getValue(original.id)
        assertEquals(raw, w.raw)
        assertTrue(visible.unsupportedStorage)
        assertEquals("needs_attention", visible.status)
        assertEquals(RecurringPayment.unsupportedMessage, visible.lastError)
        assertEquals("idle", visible.plan(1001).kind)
        w.executor.execute(); w.executor.execute(manual = true)
        assertEquals(1, w.wallet.calls)
        assertNull(w.journal.load()[original.id])
        assertTrue(runCatching { w.executor.resume(original.id) }.isFailure)
        assertTrue(runCatching { w.executor.overrideAndResume(original.id, true) }.isFailure)
        assertTrue(runCatching { w.executor.save(visible, editing = true) }.isFailure)
        w.executor.pause(original.id)
        w.store.setPending(false)
        assertEquals(unsupported, Json.parseToJsonElement(w.raw!!).jsonObject.getValue("state").jsonObject.getValue("schedules").jsonObject[original.id])
        assertTrue(RecurringStore({ w.raw }, { w.raw = it }).load().schedules.getValue(original.id).unsupportedStorage)
        w.executor.cancel(original.id)
        assertEquals(setOf("healthy"), w.store.load().schedules.keys)
        assertEquals(1, w.wallet.calls)
    }
}
