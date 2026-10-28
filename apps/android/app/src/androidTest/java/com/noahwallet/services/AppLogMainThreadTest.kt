package com.noahwallet.services

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.logging.LogFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class AppLogMainThreadTest {
    @Test fun mainLooperOnlyEnqueuesRawMessages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "log-latency-${UUID.randomUUID()}")
        val tasks = ArrayDeque<Runnable>()
        val sinks = mutableListOf<String>()
        val file = LogFile(directory, capacity = 100, executor = Executor { tasks.addLast(it) },
            systemSink = { _, tag, message -> sinks += "[$tag] $message" })
        try {
            instrumentation.runOnMainSync {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                AppLog("warmup", file).i("warmup")
                for (unit in listOf("!@#$%^&*()[]{}:;,?", "x-", "x.", "1234567890",
                    "wallet sync completed xyzzy ", "\\u0078\\u002e")) {
                    val input = unit.repeat((16384 + unit.length - 1) / unit.length).take(16384)
                    val started = System.nanoTime()
                    AppLog(input, file).e(input)
                    assertTrue("main-looper admission exceeds 5 ms", System.nanoTime() - started < 5_000_000)
                }
                assertTrue(sinks.isEmpty())
                assertFalse(directory.exists())
            }
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
            assertEquals(7, sinks.size)
        } finally { directory.deleteRecursively() }
    }
}
