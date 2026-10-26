package com.noahwallet.services.wallet

import android.os.SystemClock

/**
 * Review deadlines (send, board and exit reviews). A review this process opened is timed on [SystemClock.elapsedRealtime],
 * which keeps counting while the device sleeps and which changing the device clock doesn't move, so a clock change can't
 * lengthen it. That reading is process-local and never persisted: a review read back from storage is timed by its
 * wall-clock `createdAt`, which stays the audit record either way.
 */
object ReviewClock {
    // JVM unit tests run against android.jar stubs, which throw; they get the JVM's monotonic clock instead.
    private val source: () -> Long = try { SystemClock.elapsedRealtime(); SystemClock::elapsedRealtime }
        catch (_: RuntimeException) { { System.nanoTime() / 1_000_000 } }

    fun now(): Long = source()

    /** How long a review has been open, in milliseconds. */
    fun elapsed(reviewedAt: Long?, createdAt: Long, now: Long = ReviewClock.now(), wall: Long = System.currentTimeMillis()) =
        if (reviewedAt != null) now - reviewedAt else wall - createdAt
}
