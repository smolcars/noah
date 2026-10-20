package com.noahwallet.send

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.noahwallet.ui.QRFrameDecoder
import com.noahwallet.ui.keypadInput
import org.junit.Assert.*
import org.junit.Test

class SendScannerTest {
    @Test fun qrIsDecodedAtEveryCameraRotation() {
        val payload = "bitcoin:?amount=0.00001"
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256)
        val bytes = ByteArray(256 * 256) { i -> if (matrix[i % 256, i / 256]) 0 else 255.toByte() }
        for (rotation in listOf(0, 90, 180, 270)) assertEquals(payload, QRFrameDecoder.decode(bytes, 256, 256, rotation))
        assertNull(QRFrameDecoder.decode(ByteArray(16 * 16), 16, 16, 0))
    }
    @Test fun keypadNeverIntroducesInvalidAmountSyntax() {
        assertEquals("1", keypadInput("0", "1", false)); assertEquals("0.", keypadInput("", ".", true))
        assertEquals("1.2", keypadInput("1.2", ".", true)); assertEquals("", keypadInput("", ".", false))
        assertEquals("10", keypadInput("100", null, false)); assertEquals("100", keypadInput("100", "garbage", false))
    }
}
