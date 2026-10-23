package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
}
