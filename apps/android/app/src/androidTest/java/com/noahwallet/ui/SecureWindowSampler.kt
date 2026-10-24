package com.noahwallet.ui

import android.app.Instrumentation
import android.view.WindowManager
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.atomic.AtomicInteger

/**
 * Samples FLAG_SECURE on the resumed activity's window, on the main thread, until closed. Each sample lands between
 * main-thread messages, so a recreation (one message) shows only the replacement window.
 */
internal class SecureWindowSampler(private val instrumentation: Instrumentation) : AutoCloseable {
    @Volatile private var running = true
    val samples = AtomicInteger()
    val gaps = AtomicInteger()
    private val thread = kotlin.concurrent.thread {
        while (running) {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.let {
                    samples.incrementAndGet()
                    if (it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) gaps.incrementAndGet()
                }
            }
            Thread.sleep(8)
        }
    }
    override fun close() { running = false; thread.join() }
}
