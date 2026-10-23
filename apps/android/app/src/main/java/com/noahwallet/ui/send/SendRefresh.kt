package com.noahwallet.ui.send

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Paces Send's automatic refresh (today's status check and the block row's tip) to at most once every [every] ms.
 * Entering Send and the visible-screen loop share it, and it lives with the screen's presentation, so leaving the
 * tab or recreating the activity can't refresh sooner. An explicit Check status doesn't go through it.
 */
internal class SendRefresh(private val every: Long = 30_000, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var refreshedAt: Long? = null

    /** How long until the next automatic refresh is due. */
    fun wait(): Long = refreshedAt?.let { (it + every - clock()).coerceAtLeast(0) } ?: 0

    /** Waits until a refresh is due, then runs [refresh] unless another got there first: a paced check is late, never lost. */
    suspend fun whenDue(refresh: suspend () -> Unit) {
        val last = refreshedAt
        while (wait() > 0) {
            delay(wait())
            if (refreshedAt != last) return
        }
        ifDue(refresh)
    }

    /** Runs [refresh] if one is due, and starts the next interval. */
    suspend fun ifDue(refresh: suspend () -> Unit) {
        val now = clock()
        if (refreshedAt?.let { now - it < every } == true) return
        refreshedAt = now
        refresh()
    }
}
