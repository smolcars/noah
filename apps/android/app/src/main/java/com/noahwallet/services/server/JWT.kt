package com.noahwallet.services.server

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

/** Expiry inspection only; signature verification belongs to the server. */
object JWT {
    const val refreshWindowSeconds = 3 * 60 * 60 + 60

    fun expiry(token: String): Double {
        try {
            val segments = token.split('.')
            require(segments.size == 3)
            val bytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(segments[1])
            val exp = Json.parseToJsonElement(bytes.decodeToString()).jsonObject["exp"]?.jsonPrimitive
            require(exp != null && !exp.isString)
            return requireNotNull(exp.doubleOrNull).also { require(it.isFinite()) }
        } catch (_: Exception) {
            // Parsing exceptions can contain the JWT payload. Never retain their message/cause.
            throw IllegalArgumentException("""Invalid server token expiry""")
        }
    }

    /** `sub`: the login public key the server issued the token to, or null if unreadable. */
    fun subject(token: String): String? = try {
        val segments = token.split('.')
        require(segments.size == 3)
        val bytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(segments[1])
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject["sub"]?.jsonPrimitive?.takeIf { it.isString }?.content
    } catch (_: Exception) {
        null
    }

    fun needsRefresh(token: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiry(token) - nowMillis / 1000 <= refreshWindowSeconds
}
