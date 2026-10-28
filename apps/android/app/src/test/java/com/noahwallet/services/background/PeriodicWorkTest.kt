package com.noahwallet.services.background

import android.content.Context
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeriodicWorkTest {
    private lateinit var manager: WorkManagerImpl
    private val stages = CopyOnWriteArrayList<String>()
    private var stage: suspend (String) -> Unit = { stages += it }

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker? =
                    if (name == PeriodicWalletWorker::class.java.name) PeriodicWalletWorker(context, parameters) {
                        PeriodicJobs(PeriodicJobsTest.dependencies { stage(it) })
                    } else null
            }).build())
        manager = WorkManager.getInstance(context) as WorkManagerImpl
        PeriodicExecution.foreground(false)
    }

    @After fun cleanup() {
        manager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        PeriodicExecution.foreground(false)
    }

    @Test fun uniqueRequestsKeepIdentityTimingAndNetworkConstraintsAcrossLaunches() {
        PeriodicWork.schedule(manager)
        val first = PeriodicWork.Kind.entries.associateWith { info(it) }
        repeat(3) { PeriodicWork.schedule(manager) }
        for (kind in PeriodicWork.Kind.entries) {
            val work = info(kind)
            assertEquals(first.getValue(kind).id, work.id)
            assertEquals(TimeUnit.MINUTES.toMillis(kind.intervalMinutes), work.periodicityInfo!!.repeatIntervalMillis)
            assertEquals(work.periodicityInfo!!.repeatIntervalMillis, work.initialDelayMillis)
            assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
            assertFalse(work.constraints.requiresCharging())
            assertFalse(work.constraints.requiresDeviceIdle())
            assertEquals(first.getValue(kind).nextScheduleTimeMillis, work.nextScheduleTimeMillis)
        }
        assertTrue(stages.isEmpty())
    }

    @Test fun testDriverRunsActualWorkerAndPreservesPeriodicRequest() {
        PeriodicWork.schedule(manager)
        val id = info(PeriodicWork.Kind.refresh).id
        val driver = WorkManagerTestInitHelper.getTestDriver(RuntimeEnvironment.getApplication())!!
        driver.setAllConstraintsMet(id)
        assertTrue(stages.isEmpty())
        driver.setInitialDelayMet(id)
        await { stages.contains("exits") && info(PeriodicWork.Kind.refresh).state == WorkInfo.State.ENQUEUED }
        assertEquals(listOf("eligible", "due", "open", "sync", "recurring", "summary", "expiry", "widget", "exits"), stages.toList())
        stages.clear()
        driver.setAllConstraintsMet(id)
        driver.setPeriodDelayMet(id)
        await { stages.contains("exits") && info(PeriodicWork.Kind.refresh).state == WorkInfo.State.ENQUEUED }
        assertEquals(id, info(PeriodicWork.Kind.refresh).id)
    }

    @Test fun workManagerCancellationRevokesBeforeShieldedStageSettles() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var budget: com.noahwallet.recurring.RecurringBudget? = null
        stage = { name ->
            stages += name
            if (name == "sync") {
                budget = kotlinx.coroutines.currentCoroutineContext()[WalletExecutionBudget]!!.budget
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    entered.countDown(); release.await(); stages += "settled"
                }
            }
        }
        PeriodicWork.schedule(manager)
        val id = info(PeriodicWork.Kind.refresh).id
        val driver = WorkManagerTestInitHelper.getTestDriver(RuntimeEnvironment.getApplication())!!
        driver.setAllConstraintsMet(id); driver.setInitialDelayMet(id)
        try {
            await { entered.count == 0L }
            manager.cancelUniqueWork(PeriodicWork.Kind.refresh.workName).result.get()
            await { budget?.available == false }
            assertTrue(WalletBackgroundActivity.isRunning)
            assertFalse(stages.contains("recurring"))
        } finally { release.complete(Unit) }
        await { !WalletBackgroundActivity.isRunning }
        assertEquals(listOf("eligible", "due", "open", "sync", "settled"), stages.toList())
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(id).get().state)
    }

    @Test fun walletDeletionCancelsBothRequestsAndReonboardingCreatesNewOnes() = runBlocking {
        PeriodicWork.schedule(manager)
        val original = PeriodicWork.Kind.entries.associateWith { info(it).id }
        val root = Files.createTempDirectory("periodic-deletion").toFile()
        val location = WalletDataLocation(root, "regtest")
        location.directory.mkdirs(); location.database.writeText("test database")
        try {
            val session = WalletSession("regtest", location)
            var seed = true
            val lifecycle = WalletLifecycle(session, location, setSuspended = {}, deregister = {},
                readSeed = { if (seed) SecretResult.Found("public test identity") else SecretResult.NotFound },
                deleteSeed = { seed = false }, clearToken = {}, clearSettings = {}, clearWidget = {}, sweepScratch = {},
                syncIdentityDirectory = {}, cancelPeriodicWork = { PeriodicWork.cancel(manager) })
            lifecycle.delete()
            assertFalse(seed)
            original.values.forEach { assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(it).get().state) }
            PeriodicWork.schedule(manager) // AppServices reconciliation after a new eligible onboarding.
            PeriodicWork.Kind.entries.forEach { assertNotEquals(original[it], info(it).id) }
        } finally { root.deleteRecursively() }
    }

    private fun info(kind: PeriodicWork.Kind): WorkInfo = manager.getWorkInfosForUniqueWork(kind.workName).get()
        .single { !it.state.isFinished }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            check(System.nanoTime() < end) { "Worker did not settle: $stages" }
            Thread.sleep(10)
        }
    }
}
