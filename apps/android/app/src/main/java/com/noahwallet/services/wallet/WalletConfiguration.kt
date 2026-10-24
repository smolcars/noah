package com.noahwallet.services.wallet

import com.noahwallet.BuildConfig
import io.github.niteshbalusu11.bark.Config
import io.github.niteshbalusu11.bark.Network

/** RN walletConfig.ts settings; the SDK takes sat/kwu, not RN's sat/kvB. */
data class WalletConfiguration(
    val network: Network,
    val arkServer: String,
    val esplora: String? = null,
    val bitcoind: String? = null,
    val bitcoindUser: String? = null,
    val bitcoindPass: String? = null,
    val vtxoRefreshExpiryThreshold: UShort,
    val fallbackFeeRateSatPerKvb: ULong = 10_000u,
    val htlcRecvClaimDelta: UShort = 18u,
    val vtxoExitMargin: UShort = 12u,
    val roundTxRequiredConfirmations: UInt,
    /** How often bark's daemon syncs and progresses exits; bark's own default, set here so the exit pages can rely on it. */
    val daemonSyncIntervalSecs: ULong = 60u,
) {
    val networkName: String get() = network.name.lowercase()
    val fallbackFeeRateSatPerKwu: ULong get() = fallbackFeeRateSatPerKvb / 4u

    fun sdkConfig(userAgent: String) = Config(
        serverAddress = arkServer, esploraAddress = esplora,
        bitcoindAddress = bitcoind, bitcoindUser = bitcoindUser, bitcoindPass = bitcoindPass,
        vtxoRefreshExpiryThreshold = vtxoRefreshExpiryThreshold,
        vtxoExitMargin = vtxoExitMargin, htlcRecvClaimDelta = htlcRecvClaimDelta,
        fallbackFeeRate = fallbackFeeRateSatPerKwu,
        roundTxRequiredConfirmations = roundTxRequiredConfirmations, daemonSyncIntervalSecs = daemonSyncIntervalSecs, userAgent = userAgent,
    )

    companion object {
        /** Only regtest reads the Ark server / bitcoind overrides (-PnoahRegtestArkURL / -PnoahRegtestBitcoindURL). */
        fun base(variant: String, regtestHost: String = BuildConfig.REGTEST_HOST,
            regtestArkURL: String? = BuildConfig.REGTEST_ARK_URL.ifEmpty { null },
            regtestBitcoindURL: String? = BuildConfig.REGTEST_BITCOIND_URL.ifEmpty { null }): WalletConfiguration = when (variant) {
            "mainnet" -> WalletConfiguration(Network.BITCOIN, "https://ark.second.tech",
                esplora = "https://mempool.second.tech/api", vtxoRefreshExpiryThreshold = 144u,
                roundTxRequiredConfirmations = 2u)
            "signet" -> WalletConfiguration(Network.SIGNET, "https://ark.signet.2nd.dev",
                esplora = "https://esplora.signet.2nd.dev", vtxoRefreshExpiryThreshold = 12u,
                roundTxRequiredConfirmations = 1u)
            "regtest" -> WalletConfiguration(Network.REGTEST, regtestArkURL ?: "http://$regtestHost:3535",
                bitcoind = regtestBitcoindURL ?: "http://$regtestHost:18443", bitcoindUser = "second", bitcoindPass = "ark",
                vtxoRefreshExpiryThreshold = 24u, roundTxRequiredConfirmations = 1u)
            else -> error("""Unknown app variant""")
        }

        fun effective(variant: String, endpoint: String?): WalletConfiguration {
            val config = base(variant)
            val normalized = endpoint?.let { runCatching { EsploraEndpoint.normalize(it) }.getOrNull() }
            return if (variant != "regtest" && normalized != null) config.copy(esplora = normalized) else config
        }

        fun userAgent(variant: String, version: String) = "noah-$variant-android/$version"
    }
}
