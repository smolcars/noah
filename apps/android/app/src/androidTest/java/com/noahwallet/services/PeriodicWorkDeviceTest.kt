package com.noahwallet.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.noahwallet.services.background.PeriodicWalletWorker
import com.noahwallet.services.background.PeriodicWork
import com.noahwallet.services.storage.WalletDataLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Enqueues the production worker through WorkManager on an empty, disposable emulator. */
@RunWith(AndroidJUnit4::class)
class PeriodicWorkDeviceTest {
    /** Runs [kind]'s periodic work once; it must re-enqueue itself, create no wallet, and keep its id when scheduled again. */
    private fun runOnce(manager: WorkManagerImpl, driver: TestDriver, kind: PeriodicWork.Kind, location: WalletDataLocation) {
        val id = manager.getWorkInfosForUniqueWork(kind.workName).get().single().id
        val scheduled = manager.getWorkInfoById(id).get().nextScheduleTimeMillis
        driver.setAllConstraintsMet(id)
        driver.setInitialDelayMet(id)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (manager.getWorkInfoById(id).get().let { it.state != WorkInfo.State.ENQUEUED || it.nextScheduleTimeMillis == scheduled }) {
            check(System.nanoTime() < end) { "Periodic worker did not finish: ${manager.getWorkInfoById(id).get().state}" }
            Thread.sleep(20)
        }
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(id).get().state)
        assertFalse(location.hasAnyData)
        PeriodicWork.schedule(manager)
        assertEquals(id, manager.getWorkInfosForUniqueWork(kind.workName).get().single().id)
    }

    @Test fun periodicWorkerRunsHeadlesslyWithoutCreatingWalletAndSurvivesDedupThenCancellation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Use a fresh disposable emulator", location.hasAnyData)
        for (kind in PeriodicWork.Kind.entries) {
            val worker = TestListenableWorkerBuilder<PeriodicWalletWorker>(context)
                .setInputData(workDataOf(PeriodicWork.KIND to kind.name)).build()
            assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        }
        assertFalse(java.io.File(context.noBackupFilesDir, "noah-native-seed-regtest.bin").exists())
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context) as WorkManagerImpl
        try {
            PeriodicWork.schedule(manager)
            val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
            for (kind in PeriodicWork.Kind.entries) runOnce(manager, driver, kind, location)
            PeriodicWork.cancel(manager)
            PeriodicWork.Kind.entries.forEach { kind ->
                assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfosForUniqueWork(kind.workName).get().single().state)
            }
        } finally { manager.cancelAllWork().result.get(); WorkManagerTestInitHelper.closeWorkDatabase() }
    }
}
