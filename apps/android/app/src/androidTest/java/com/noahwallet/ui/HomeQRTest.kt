package com.noahwallet.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.noahwallet.ui.home.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The styled QR (dot modules, rounded eyes, the longship over a cleared centre) still decodes, in both themes. */
class HomeQRTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
            mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text
    }

    @Test fun getPaidCardDecodesInLightAndDark() {
        var dark by androidx.compose.runtime.mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val base = LocalConfiguration.current
            val configuration = Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                NoahTheme { androidx.compose.runtime.key(dark) { HomePreviewHost(HomeFixtures.ui("fresh"), HomeMemory().apply { enteredAt = 0L }) } }
            }
        }
        for (theme in listOf(false, true)) {
            compose.runOnUiThread { dark = theme }
            // Compose the card first; its QR then draws in on the wall clock (about 1.3 s).
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(2_000)
            compose.mainClock.advanceTimeBy(500)
            val card = compose.onNodeWithTag("home-get-paid").captureToImage().asAndroidBitmap()
            try {
                assertEquals("theme dark=$theme", HomeFixtures.address, decode(card))
                // The QR's card stays white with near-black ink in both themes.
                val corner = card.getPixel(card.width / 2, 6)
                assertTrue("white card dark=$theme", android.graphics.Color.red(corner) > 240 && android.graphics.Color.blue(corner) > 240)
            } finally { card.recycle() }
        }
    }

    @Test fun styledCodeDecodesAtSmallAndLargeSizes() {
        val payload = "satoshi.nakamoto_21@noahwallet.io"
        for (side in listOf(160, 228, 320)) {
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { NoahTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // The card's white padding is the quiet zone.
                    Box(Modifier.background(androidx.compose.ui.graphics.Color.White).padding(20.dp).testTag("qr")) {
                        StyledQR(payload, Modifier.size(side.dp), reveal = false)
                    }
                } } }
            }
            compose.mainClock.advanceTimeBy(500)
            val image = compose.onNodeWithTag("qr").captureToImage().asAndroidBitmap()
            try { assertEquals("side $side", payload, decode(image)) } finally { image.recycle() }
        }
    }
}
