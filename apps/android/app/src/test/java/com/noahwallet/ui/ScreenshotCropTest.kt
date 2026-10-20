package com.noahwallet.ui

import com.noahwallet.ui.settings.ScreenshotCrop
import org.junit.Assert.*
import org.junit.Test

class ScreenshotCropTest {
    @Test fun cropBoundsIncludeOnlyTheSelectedRegion() {
        assertEquals(ScreenshotCrop.Pixels(100, 400, 600, 1000), ScreenshotCrop(0.1f, 0.2f, 0.7f, 0.7f).pixels(1000, 2000))
        assertEquals(ScreenshotCrop.Pixels(0, 0, 100, 200), ScreenshotCrop().pixels(100, 200))
    }
    @Test fun tinyImagesAndRoundedEdgesRemainInsideTheImage() {
        assertEquals(ScreenshotCrop.Pixels(0, 0, 1, 1), ScreenshotCrop(0.99f, 0.99f).pixels(1, 1))
        assertEquals(ScreenshotCrop.Pixels(98, 98, 1, 1), ScreenshotCrop(0.99f, 0.99f).pixels(99, 99))
    }
}
