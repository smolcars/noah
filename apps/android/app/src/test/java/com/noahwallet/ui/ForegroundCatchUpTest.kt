package com.noahwallet.ui

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.noahwallet.recurring.RecurringRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A cold launch and each return to the front read Home before the foreground pass: its on-chain sync holds the on-chain
 * wallet for its whole Esplora round trip, and Home's balance and history would wait for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ForegroundCatchUpTest {
    private class Activity : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun homesSummaryAndHistoryAreReadBeforeThePassAndTheSummaryAgainAfterIt() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        RecurringRuntime.shared(application).syncForegroundAdmission.onResume(Activity())
        val app = AppModel(application)
        val before = app.summaryRevision
        var duringPass: Pair<Long, Boolean>? = null
        app.catchUpInForeground { duringPass = app.summaryRevision - before to app.history.isLoading }
        assertEquals("the summary read once and the history loaded before the pass", 1L to false, duringPass)
        assertEquals("and the summary read again after it", before + 2, app.summaryRevision)
    }
}
