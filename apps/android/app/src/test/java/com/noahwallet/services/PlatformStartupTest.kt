package com.noahwallet.services

import com.noahwallet.BuildConfig
import com.noahwallet.NoahApplication
import io.sentry.Sentry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformStartupTest {
    @Test fun actualDebugApplicationStartsWithoutTelemetryOrSentryProviders() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(app is NoahApplication)
        assertTrue(BuildConfig.DEBUG)
        assertFalse(Sentry.isEnabled())
        for (name in listOf("SentryInitProvider", "SentryPerformanceProvider", "SentryNdkPreloadProvider")) {
            assertNull(app.packageManager.resolveContentProvider("${BuildConfig.APPLICATION_ID}.$name", 0))
        }
    }
}
