package com.noahwallet.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Who paid an LNURL-pay receive and what they wrote, as the payer sent it (unverified): the movement's `noah.lnurl_pay`
 * metadata (docs/lnurl_pay.md, apps/fixtures/lnurl-pay-receive-metadata.json). Each value is shown only when it is safe.
 */
@Serializable
data class LnurlPayReceive(val name: String? = null, val identifier: String? = null, val comment: String? = null) {
    /** `Name (⚡ identifier)`, or whichever is known; null when the payer named nobody. */
    val payerLabel: String? get() = when {
        name != null && identifier != null -> "$name (⚡ $identifier)"
        else -> name ?: identifier
    }

    /** The JSON merge patch that stores this on a movement, next to bark's and Noah's other keys. */
    fun patch(): String = buildJsonObject {
        putJsonObject("noah") {
            putJsonObject("lnurl_pay") {
                put("schema_version", SCHEMA_VERSION)
                putJsonObject("payer_data") {
                    name?.let { put("name", it) }
                    identifier?.let { put("identifier", it) }
                }
                comment?.let { put("comment", it) }
            }
        }
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1
        private const val MAX_NAME = 80
        private const val MAX_IDENTIFIER = 320
        private const val MAX_COMMENT = 280

        /** From the server's record, with each value checked again. */
        fun of(name: String?, identifier: String?, comment: String?) =
            LnurlPayReceive(safe(name, MAX_NAME), safe(identifier, MAX_IDENTIFIER), safe(comment, MAX_COMMENT))

        /** The stored entry in a movement's metadata, or null when there is none of this version. */
        fun parse(metadataJson: String?): LnurlPayReceive? = try {
            val noah = (Json.parseToJsonElement(metadataJson ?: return null) as? JsonObject)?.get("noah") as? JsonObject
            val entry = (noah?.get("lnurl_pay") as? JsonObject)
                ?.takeIf { it["schema_version"]?.jsonPrimitive?.intOrNull == SCHEMA_VERSION }
            val payer = entry?.get("payer_data") as? JsonObject
            entry?.let {
                of(payer?.string("name"), payer?.string("identifier"), it.string("comment"))
            }
        } catch (_: Exception) { null }

        /** Trimmed; null when empty, longer than [max] characters, or holding control or bidirectional controls. */
        private fun safe(value: String?, max: Int): String? = value?.trim()
            ?.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= max && it.codePoints().noneMatch(::unsafe) }

        private fun unsafe(codePoint: Int) = Character.isISOControl(codePoint) || codePoint == 0x61c ||
            codePoint in 0x200e..0x200f || codePoint in 0x2028..0x202e || codePoint in 0x2066..0x2069
    }
}
