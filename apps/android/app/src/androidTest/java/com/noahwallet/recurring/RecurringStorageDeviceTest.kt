package com.noahwallet.recurring

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecurringStorageDeviceTest {
    @Test fun nativeEnvelopeAndInFlightMarkerSurviveFreshReader() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahRecurringStorage") == "disposable-emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStorage(context)
        val previous = settings.string(RecurringStore.key)
        assertNull("Run on empty disposable app before funded test", previous)
        try {
            val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("recurring-storage.json").bufferedReader().readText()
            settings.set(raw, RecurringStore.key); settings.sync()
            val store = RecurringStore(settings)
            val rent = store.load().schedules.getValue("rent")
            val marked = rent.copy(inFlight = RecurringPayment.InFlight(0, 1000))
            store.upsert(marked)
            assertEquals(marked, RecurringStore(SettingsStorage(context)).load().schedules.getValue("rent"))
            val corrupt = "{invalid"
            val generation = settings.nativeFile.load()
            settings.nativeFile.save(generation.copy(values = generation.values + (RecurringStore.key to corrupt)))
            assertTrue(runCatching { RecurringStore(settings).load() }.isFailure)
            assertEquals(corrupt, settings.string(RecurringStore.key))
        } finally {
            settings.removeAll()
        }
    }
    @Test fun realBindingChangesAfterSameSeedDatadirReplacementAndRefusesPendingMutation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahRecurringStorage") == "disposable-emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "recurring-binding-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val location = WalletDataLocation(root, "regtest")
        val session = WalletSession("regtest", location)
        try {
            session.createNew(io.github.niteshbalusu11.bark.generateMnemonic(), false, "recurring-storage-test")
            session.closeSettled()
            val first = RecurringStore.binding(location)
            assertEquals(first, RecurringStore.binding(location))
            check(File(location.directory, ".noah-recurring-instance").delete())
            val replaced = RecurringStore.binding(location)
            assertEquals(first.wallet, replaced.wallet); assertNotEquals(first.instance, replaced.instance)
            check(location.restoreDirectory.mkdir()); File(location.restoreDirectory, "journal.json").writeText("pending")
            assertTrue(runCatching { RecurringStore.binding(location) }.isFailure)
            check(location.restoreDirectory.deleteRecursively()); location.deletionMarker.writeText("pending")
            assertTrue(runCatching { RecurringStore.binding(location) }.isFailure)
        } finally { session.closeSettled(); check(root.deleteRecursively()) }
    }
}
