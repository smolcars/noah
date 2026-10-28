package com.noahwallet.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.noahwallet.ui.receive.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The Receive card's QR decodes to exactly the shown format's payload, for every format, in both themes. */
class ReceiveQRTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
            mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text
    }

    @Test fun everyFormatDecodesToItsPayloadInLightAndDark() {
        var dark by mutableStateOf(false)
        var ui by mutableStateOf(ReceiveFixtures.ui("address"))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val base = LocalConfiguration.current
            val configuration = Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                NoahTheme { key(dark, ui) { ReceivePreviewHost(ui) } }
            }
        }
        for (theme in listOf(false, true)) for (state in listOf("address", "request", "amount", "invoice", "ark", "onchain")) {
            val shown = ReceiveFixtures.ui(state).copy(listening = false)
            val payload = checkNotNull(ReceiveFormats.payload(shown.format, shown.request, shown.address))
            compose.runOnUiThread { dark = theme; ui = shown }
            // The QR draws in on the wall clock (about 1.3 s).
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(2_000)
            compose.mainClock.advanceTimeBy(500)
            val card = compose.onNodeWithTag("receive-card", useUnmergedTree = true).captureToImage().asAndroidBitmap()
            try { assertEquals("$state dark=$theme", payload, decode(card)) } finally { card.recycle() }
        }
    }
}
