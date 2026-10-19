package com.noahwallet.services.wallet

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
) {
    val networkName: String get() = network.name.lowercase()
    val fallbackFeeRateSatPerKwu: ULong get() = fallbackFeeRateSatPerKvb / 4u

    fun sdkConfig(userAgent: String) = Config(
        serverAddress = arkServer, esploraAddress = esplora,
        bitcoindAddress = bitcoind, bitcoindUser = bitcoindUser, bitcoindPass = bitcoindPass,
        vtxoRefreshExpiryThreshold = vtxoRefreshExpiryThreshold,
        vtxoExitMargin = vtxoExitMargin, htlcRecvClaimDelta = htlcRecvClaimDelta,
        fallbackFeeRate = fallbackFeeRateSatPerKwu,
        roundTxRequiredConfirmations = roundTxRequiredConfirmations, userAgent = userAgent,
    )

    companion object {
        fun base(variant: String): WalletConfiguration = when (variant) {
            "mainnet" -> WalletConfiguration(Network.BITCOIN, "https://ark.second.tech",
                esplora = "https://mempool.second.tech/api", vtxoRefreshExpiryThreshold = 144u,
                roundTxRequiredConfirmations = 2u)
            "signet" -> WalletConfiguration(Network.SIGNET, "https://ark.signet.2nd.dev",
                esplora = "https://esplora.signet.2nd.dev", vtxoRefreshExpiryThreshold = 12u,
                roundTxRequiredConfirmations = 1u)
            "regtest" -> WalletConfiguration(Network.REGTEST, "http://10.0.2.2:3535",
                bitcoind = "http://10.0.2.2:18443", bitcoindUser = "second", bitcoindPass = "ark",
                vtxoRefreshExpiryThreshold = 24u, roundTxRequiredConfirmations = 1u)
            else -> error("Unknown app variant")
        }

        fun userAgent(variant: String, version: String) = "noah-$variant-android/$version"
    }
}
