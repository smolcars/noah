package com.noahwallet.btcmap

import java.net.URI

/** Public merchant data never supplies an Intent, component, or arbitrary URI scheme. */
object BtcMapLinks {
    fun web(value: String): String? = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null)
        require(value.none { it.isISOControl() })
        uri.toASCIIString()
    }.getOrNull()

    fun phone(value: String): String? {
        val number = value.trim()
        if (!number.matches(Regex("\\+?[0-9() .-]{3,40}"))) return null
        return "tel:" + number.filter { it.isDigit() || it == '+' }
    }

    fun email(value: String): String? {
        val address = value.trim()
        if (!address.matches(Regex("[A-Za-z0-9.!#$%&'*+/=^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))) return null
        return URI("mailto", address, null).toASCIIString()
    }

    fun directions(place: BtcMapPlace) = "https://www.google.com/maps/dir/?api=1&destination=${place.lat},${place.lon}"
}
