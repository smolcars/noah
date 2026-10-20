package com.noahwallet.ui.home

import com.noahwallet.services.wallet.WalletBackgroundActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeBackgroundStripTest {
    @Test fun stripSurvivesNestedJobsAndClearsAfterFailure() = runBlocking {
        assertNull(WalletBackgroundActivity.changes.value.message)
        runCatching {
            WalletBackgroundActivity.during {
                assertEquals("Background task in progress...", WalletBackgroundActivity.changes.value.message)
                WalletBackgroundActivity.during { assertNotNull(WalletBackgroundActivity.changes.value.message) }
                assertNotNull(WalletBackgroundActivity.changes.value.message)
                error("job failed")
            }
        }
        assertNull(WalletBackgroundActivity.changes.value.message)
    }
}
