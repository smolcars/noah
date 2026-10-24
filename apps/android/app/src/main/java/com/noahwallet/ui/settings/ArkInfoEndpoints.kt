package com.noahwallet.ui.settings

import com.noahwallet.BuildConfig

internal object ArkInfoEndpoints {
    fun blockHeight(variant: String, esploraOverride: String?, regtestHost: String = BuildConfig.REGTEST_HOST,
        regtestBitcoindURL: String? = BuildConfig.REGTEST_BITCOIND_URL.ifEmpty { null }): String {
        if (variant != "regtest" && esploraOverride != null) return esploraOverride.trimEnd('/') + "/blocks/tip/height"
        return when (variant) {
            "mainnet" -> "https://mempool.second.tech/api/blocks/tip/height"
            "signet" -> "https://mempool.space/signet/api/blocks/tip/height"
            "regtest" -> regtestBitcoindURL ?: "http://$regtestHost:18443"
            else -> error("""Unknown app variant""")
        }
    }
}
