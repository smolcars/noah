package com.noahwallet.ui.settings

import com.noahwallet.api.generated.models.*
import java.io.InputStream

class FeedbackDraft(var name: String = "", var email: String = "", var subject: String = "", var body: String = "", var screenshot: ByteArray? = null) {
    fun payload(device: DeviceInfo, base64: (ByteArray) -> String): SubmitSupportTicketPayload {
        val name = name.trim(); val subject = subject.trim(); val body = body.trim()
        require(name.isNotEmpty() && subject.isNotEmpty() && body.isNotEmpty()) { "Name, subject, and message are required." }
        require(name.toByteArray().size <= 150 && subject.toByteArray().size <= 150 && body.toByteArray().size <= 60_000) {
            "Name and subject must be at most 150 bytes, and message at most 60,000 bytes."
        }
        val attachment = screenshot?.let {
            val mime = imageType(it)
            SubmitSupportTicketPayloadAttachment(base64(it), mime, if (mime == "image/png") "screenshot.png" else "screenshot.jpg")
        }
        return SubmitSupportTicketPayload(attachment, body, SubmitSupportTicketPayloadDeviceInfo(device.appBuild,
            device.appVersion, device.deviceManufacturer, device.deviceModel, device.osName, device.osVersion),
            email.trim().takeIf { it.isNotEmpty() }, name, subject)
    }
    companion object {
        const val maximumScreenshotBytes = 5 * 1024 * 1024
        fun imageType(bytes: ByteArray): String {
            require(bytes.size <= maximumScreenshotBytes) { "Screenshot must be 5 MB or smaller." }
            fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it].toInt() and 255 == prefix[it] }
            return when {
                starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
                starts(0xff, 0xd8, 0xff) -> "image/jpeg"
                else -> throw IllegalArgumentException("Screenshot must be a JPEG or PNG image.")
            }
        }
        fun readScreenshot(input: InputStream): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maximumScreenshotBytes) { "Screenshot must be 5 MB or smaller." }
                output.write(buffer, 0, count)
            }
            return output.toByteArray().also { imageType(it) }
        }
    }
}
