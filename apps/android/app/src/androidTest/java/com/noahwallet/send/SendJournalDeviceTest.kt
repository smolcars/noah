package com.noahwallet.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SendJournalDeviceTest {
    @Test fun fsyncedAtomicVersionedAttemptSurvivesNewReaderAndLegacyIsUntrusted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "send-journal-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(directory, "attempt.json"); val journal = SendJournal(file)
            val attempt = SendAttempt(walletIdentity = SendIdentity("test", "REGTEST"),
                payment = SendPayment("destination", "onchain", 1000, SendSource.onchain), broadcastingTxid = "exact")
            journal.write(attempt)
            assertEquals(SendController.journalVersion, Json.parseToJsonElement(file.readText()).jsonObject.getValue("safetyVersion").jsonPrimitive.int)
            assertEquals(attempt, SendJournal(file).read())
            assertTrue(file.renameTo(File(file.path + ".bak")))
            assertEquals(attempt, SendJournal(file).read())
            val old = Json.parseToJsonElement(file.readText()).jsonObject
            file.writeText(JsonObject(old - "safetyVersion").toString())
            assertEquals(0, SendJournal(file).read()!!.safetyVersion)
            journal.write(null); assertNull(journal.read())
        } finally { directory.deleteRecursively() }
    }
}
