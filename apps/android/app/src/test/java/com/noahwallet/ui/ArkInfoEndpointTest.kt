package com.noahwallet.ui

import com.noahwallet.services.server.ServerEndpoint
import org.junit.Assert.*
import org.junit.Test

class ArkInfoEndpointTest {
    @Test fun pricesUseConfiguredServerForEveryVariant() {
        for ((variant, origin) in listOf("mainnet" to "https://noahwallet.io", "signet" to "https://signet.noahwallet.io", "regtest" to "http://10.0.2.2:3000")) {
            val urls = ServerEndpoint.priceURLs(ServerEndpoint.baseURL(variant))
            assertEquals("$origin/v0/prices", urls.first)
            assertEquals("$origin/v0/historical-price", urls.second)
        }
        val base = ServerEndpoint.baseURL("regtest", "http://192.0.2.1:3000/proxy/")
        assertEquals("http://192.0.2.1:3000/proxy/v0/prices", ServerEndpoint.priceURLs(base).first)
    }
}
