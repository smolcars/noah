package com.noahwallet.btcmap

import java.net.URI

/** Public merchant data never supplies an Intent, component, or arbitrary URI scheme. */
object BtcMapLinks {
    fun web(value: String): String? = runCatching {
        val uri = URI(value)
        require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null)
        require(value.none { it.isISOControl() || it.isWhitespace() })
        uri.toASCIIString()
    }.getOrNull()

    fun phone(value: String): String? {
        if (value.any { it !in "+0123456789 ()-." }) return null
        val number = value.filter { it in '0'..'9' || it == '+' }
        val digits = number.removePrefix("+")
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        return "tel:$number"
    }

    fun email(value: String): String? {
        val address = value.trim()
        if (!address.matches(Regex("[A-Za-z0-9.!#$%&'*+/=^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))) return null
        return URI("mailto", address, null).toASCIIString()
    }

    fun directions(place: BtcMapPlace) = "https://www.google.com/maps/dir/?api=1&destination=${place.lat},${place.lon}"
}
