package com.noahwallet.services.logging

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.util.EnumSet

/**
 * How long a launch takes to show the wallet, in the app log: each milestone once per process, in milliseconds since the
 * process started. Never logs wallet data.
 */
internal class LaunchTiming(private val log: (String) -> Unit, private val sinceStart: () -> Long) {
    enum class Milestone(val text: String) {
        walletOpened("Wallet open done"), summaryShown("First summary shown"), historyShown("First history rows shown"),
    }
    private val logged = EnumSet.noneOf(Milestone::class.java)

    fun mark(milestone: Milestone, detail: String = "") {
        if (synchronized(logged) { logged.add(milestone) }) log("${milestone.text} at +${sinceStart()} ms$detail")
    }

    companion object {
        @Volatile private var shared: LaunchTiming? = null

        fun shared(context: Context): LaunchTiming = shared ?: synchronized(this) {
            shared ?: LaunchTiming(AppLog(context.applicationContext, "launch")::i) {
                SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
            }.also { shared = it }
        }
    }
}

/**
 * The stages of a foreground pass after a launch or a return to the front, in the app log as each starts and ends, in
 * milliseconds since the pass started.
 */
internal class PassTiming(private val log: (String) -> Unit, private val nanos: () -> Long = System::nanoTime) {
    private val started = nanos()

    suspend fun run(stage: String, work: suspend () -> Unit) {
        log("Foreground pass: $stage started at +${elapsed()} ms")
        var done = false
        try { work(); done = true }
        finally { log("Foreground pass: $stage ${if (done) "done" else "failed"} at +${elapsed()} ms") }
    }

    private fun elapsed() = (nanos() - started) / 1_000_000
}
