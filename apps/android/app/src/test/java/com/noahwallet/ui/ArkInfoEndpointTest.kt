package com.noahwallet.ui

import com.noahwallet.services.server.ServerEndpoint
import com.noahwallet.ui.settings.ArkInfoEndpoints
import org.junit.Assert.*
import org.junit.Test

class ArkInfoEndpointTest {
    @Test fun blockHeightMatchesRNDefaultsAndOverride() {
        assertEquals("https://mempool.second.tech/api/blocks/tip/height", ArkInfoEndpoints.blockHeight("mainnet", null))
        assertEquals("https://mempool.space/signet/api/blocks/tip/height", ArkInfoEndpoints.blockHeight("signet", null))
        for (variant in listOf("mainnet", "signet")) {
            assertEquals("https://custom.example/api/blocks/tip/height", ArkInfoEndpoints.blockHeight(variant, "https://custom.example/api/"))
        }
        assertEquals("http://192.0.2.1:18443", ArkInfoEndpoints.blockHeight("regtest", "https://ignored.example", "192.0.2.1"))
        assertEquals("http://10.0.2.2:28443", ArkInfoEndpoints.blockHeight("regtest", null, "192.0.2.1", "http://10.0.2.2:28443"))
        for (variant in listOf("mainnet", "signet")) for (esplora in listOf(null, "https://custom.example/api"))
            assertEquals(ArkInfoEndpoints.blockHeight(variant, esplora, regtestBitcoindURL = null),
                ArkInfoEndpoints.blockHeight(variant, esplora, regtestBitcoindURL = "http://192.0.2.1:2"))
    }

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
