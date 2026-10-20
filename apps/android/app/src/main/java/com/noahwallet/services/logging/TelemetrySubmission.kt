package com.noahwallet.services.logging

import io.sentry.Hint
import io.sentry.hints.Cached
import io.sentry.hints.DiskFlushNotification
import io.sentry.hints.Enqueable
import io.sentry.hints.Flushable
import io.sentry.hints.Resettable
import io.sentry.hints.Retryable
import io.sentry.hints.SubmissionResult
import io.sentry.protocol.SentryId
import io.sentry.util.HintUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object TelemetrySubmission {
    fun prepare(hint: Hint, timeoutMillis: Long) {
        val current = HintUtils.getSentrySdkHint(hint)
        // DirectoryProcessor reuses one non-resettable hint; OutboxSender resets after each item.
        if (current is Cached && current is Enqueable && current is Flushable &&
            current is SubmissionResult && current is Retryable && current !is Resettable) {
            HintUtils.setTypeCheckHint(hint, ReplayHint(current, current, timeoutMillis))
        }
        (HintUtils.getSentrySdkHint(hint) as? ReplayHint)?.setRetry(true)
    }

    fun drop(hint: Hint, eventId: SentryId?, timeoutMillis: Long) {
        prepare(hint, timeoutMillis)
        HintUtils.runIfHasType(hint, Retryable::class.java) { it.setRetry(false) }
        HintUtils.runIfHasType(hint, DiskFlushNotification::class.java) {
            if (it.isFlushable(eventId)) it.markFlushed()
        }
        HintUtils.runIfHasType(hint, SubmissionResult::class.java) { it.setResult(true) }
    }

    private class ReplayHint(private val retryable: Retryable, enqueable: Enqueable,
                             private val timeoutMillis: Long) :
        Cached, Retryable by retryable, Enqueable by enqueable, SubmissionResult, Flushable, Resettable {
        private var latch = CountDownLatch(1)
        @Volatile private var succeeded = false

        override fun setResult(success: Boolean) {
            succeeded = success
            if (success) setRetry(false)
            latch.countDown()
        }
        override fun isSuccess() = succeeded
        override fun waitFlush(): Boolean = try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        override fun reset() {
            succeeded = false
            latch = CountDownLatch(1)
        }
    }
}
