package com.noahwallet.services.storage

import com.noahwallet.recurring.RecurringStore
import org.junit.Assert.*
import org.junit.Test

class RecurringAdmissionTest {
    @Test fun unreadableEnvelopesBlock() {
        for (raw in listOf("not JSON", """{"state":{},"version":0}""", """{"state":{"schedules":null},"version":0}""")) {
            assertTrue(runCatching { RNStoreCodec.validateStoredValue(RecurringStore.key, raw) }.exceptionOrNull() is RNStoreCodec.Unreadable)
        }
    }
    @Test fun unsupportedRowsAreAdmittedButStopped() {
        val raw = """{"state":{"schedules":{"future":{"label":"Saved rule","amountSat":1e99}},"serverSyncPending":false},"version":0}"""
        RNStoreCodec.validateStoredValue(RecurringStore.key, raw)
        val row = RecurringStore({ raw }, { error("Read only") }).load().schedules.getValue("future")
        assertTrue(row.unsupportedStorage); assertEquals("needs_attention", row.status)
    }
}
