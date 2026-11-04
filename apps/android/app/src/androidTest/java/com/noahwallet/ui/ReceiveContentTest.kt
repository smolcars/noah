package com.noahwallet.ui

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.noahwallet.localization.LocalizedContent
import com.noahwallet.ui.receive.*
import org.junit.Rule
import org.junit.Test

/** Receive's screen behaviour around its sheets and messages, on the fixtures (never a real wallet). */
class ReceiveContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun invoiceErrorsAreTranslated() {
        val ui = ReceiveFixtures.ui("request").copy(listening = false, invoiceError = "Could not generate a Lightning invoice. Please try again.")
        compose.setContent { LocalizedContent("es") { NoahTheme { ReceivePreviewHost(ui) } } }
        compose.onNodeWithText("No se pudo generar una factura Lightning. Inténtalo de nuevo.").assertExists()
        compose.onAllNodesWithText("Could not generate a Lightning invoice. Please try again.").assertCountEquals(0)
    }

    @Test fun aRestoredAmountSheetStaysOpenUntilASaveReturns() {
        var ui by mutableStateOf(ReceiveFixtures.ui("address").copy(listening = false))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { NoahTheme { ReceivePreviewHost(ui) } }
        compose.onNodeWithTag("receive-amount-button").performClick()
        compose.onNodeWithTag("receive-amount-submit").assertExists()
        // Rotation or a pose change restores the sheet, still open.
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("receive-amount-submit").assertExists()
        // A save that fails keeps it open...
        compose.runOnIdle { ui = ui.copy(saving = true) }
        compose.runOnIdle { ui = ui.copy(saving = false, invoiceError = "Could not generate a Lightning invoice. Please try again.") }
        compose.onNodeWithTag("receive-amount-submit").assertExists()
        // ...and one that returns the new request closes it.
        compose.runOnIdle { ui = ui.copy(saving = true, invoiceError = null) }
        compose.runOnIdle { ui = ui.copy(saving = false, request = ReceiveFixtures.withAmount, format = ReceiveFormat.request) }
        compose.onAllNodesWithTag("receive-amount-submit").assertCountEquals(0)
    }

    @Test fun aNewArrivalClosesOpenSheetsBeforeItPlays() {
        var ui by mutableStateOf(ReceiveFixtures.ui("address").copy(listening = false))
        compose.mainClock.autoAdvance = false
        compose.setContent { NoahTheme { ReceivePreviewHost(ui) } }
        compose.mainClock.advanceTimeBy(500)
        fun arrive() {
            compose.runOnUiThread { ui = ui.copy(arrival = ReceiveArrival(ReceiveArrival.Rail.ark, 2_100, SystemClock.elapsedRealtime())) }
            compose.mainClock.advanceTimeBy(16)
        }
        // Ways to pay you: a sheet on a compact screen only (wide and tabletop list the formats beside the card).
        if (compose.onAllNodesWithTag("receive-formats").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("receive-formats").performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithText("Ways to pay you").assertExists()
            arrive()
            compose.onAllNodesWithText("Ways to pay you").assertCountEquals(0)
            compose.runOnUiThread { ui = ui.copy(arrival = null) }
            compose.mainClock.advanceTimeBy(500)
        }
        compose.onNodeWithTag("receive-amount-button").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("receive-amount-submit").assertExists()
        arrive()
        compose.onAllNodesWithTag("receive-amount-submit").assertCountEquals(0)
        // A sheet opened while that arrival plays stays open: the arrival isn't new.
        compose.onNodeWithTag("receive-amount-button").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("receive-amount-submit").assertExists()
    }

    @Test fun aRestoredArrivalLeavesARestoredSheetOpen() {
        val ui = ReceiveFixtures.ui("address").copy(listening = false, arrival = ReceiveFixtures.arrival(ReceiveArrival.Rail.onchain, 20_000))
        val restoration = StateRestorationTester(compose)
        compose.mainClock.autoAdvance = false
        restoration.setContent { NoahTheme { ReceivePreviewHost(ui) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("receive-amount-button").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("receive-amount-submit").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("receive-amount-submit").assertExists()
    }
}
