package com.noahwallet.ui.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.noahwallet.ui.NoahTheme
import org.junit.Rule
import org.junit.Test

class HomeToastTest {
    @get:Rule val compose = createComposeRule()

    /** Hiding amounts mid-toast masks it at once, and it still ends when it would have. */
    @Test fun retextKeepsTheToastsRemainingTime() {
        val toasts = HomeToasts()
        compose.mainClock.autoAdvance = false
        compose.setContent { NoahTheme { HomeToast(toasts.current) } }
        compose.runOnUiThread { toasts.show("₿ 20,000 available in Ark", 3_000, tag = "boarding") }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("₿ 20,000 available in Ark").assertExists()
        compose.runOnUiThread { toasts.retext("boarding", "•••• available in Ark") }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("•••• available in Ark").assertExists()
        compose.onNodeWithText("₿ 20,000 available in Ark").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_300)
        compose.onNodeWithText("•••• available in Ark").assertExists()
        // Restarting at the retext would keep it until about 4,600 ms.
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("•••• available in Ark").assertDoesNotExist()
    }
}
