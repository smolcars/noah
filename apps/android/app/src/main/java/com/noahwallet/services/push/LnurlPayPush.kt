package com.noahwallet.services.push

import android.content.Context
import android.util.Base64
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.localization.NotificationCopy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The payer data an LNURL-pay payment's push carries in `lnurl_pay_receive`, sealed with this app's [NotificationKey]
 * (docs/lnurl_pay.md, apps/fixtures/lnurl-pay-receive-notification.json). Payer-provided and unverified.
 */
internal object LnurlPayPush {
    @Serializable
    data class PayerData(val name: String? = null, val identifier: String? = null)

    @Serializable
    data class Content(
        @SerialName("amount_sat") val amountSat: Long,
        @SerialName("payer_data") val payerData: PayerData? = null,
        val comment: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The sealed content in a push's data JSON, or null when there is none or it doesn't open with [key]. */
    fun open(data: String?, key: ByteArray?): Content? = try {
        val field = (json.parseToJsonElement(data ?: return null) as? JsonObject)?.get("lnurl_pay_receive") as? JsonObject
        val id = field?.get("id")?.jsonPrimitive?.contentOrNull
        val sealed = field?.takeIf { it["v"]?.jsonPrimitive?.intOrNull == 1 }?.get("sealed")?.jsonPrimitive?.contentOrNull
        if (key == null || id == null || sealed == null) null else open(id, sealed, key)
    } catch (_: Exception) { null }

    /** AES-256-GCM: base64 of nonce (12) || ciphertext || tag (16), bound to the record [id]. */
    fun open(id: String, sealed: String, key: ByteArray): Content? = try {
        val bytes = Base64.decode(sealed, Base64.DEFAULT)
        if (bytes.size <= NONCE_BYTES + TAG_BYTES) null
        else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, bytes, 0, NONCE_BYTES))
            cipher.updateAAD("noah-lnurl-pay-receive-notification:v1:$id".toByteArray())
            val plaintext = cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES)
            json.decodeFromString<Content>(plaintext.decodeToString()).takeIf { it.amountSat >= 0 }
        }
    } catch (_: Exception) { null }

    /** `Name (⚡ identifier)` when both are given, else whichever is; null for neither. */
    fun payerLabel(payer: PayerData?): String? {
        val name = payer?.name?.takeIf { it.isNotBlank() }
        val identifier = payer?.identifier?.takeIf { it.isNotBlank() }
        return when {
            name != null && identifier != null -> "$name (⚡ $identifier)"
            else -> name ?: identifier
        }
    }

    /** The payer as the title, as a message's sender would be; their comment under the amount. */
    fun copy(context: Context, content: Content, unit: BitcoinAmountUnit): NotificationCopy =
        NotificationCopy.lnurlPayReceived(context, content.amountSat, unit, payerLabel(content.payerData),
            content.comment?.takeIf { it.isNotBlank() })

    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
}
