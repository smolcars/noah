import BarkBoltFFI
import Foundation

/// Per-variant wallet settings, ported from `client/src/lib/walletConfig.ts`.
///
/// Units differ from react-native-nitro-ark: Nitro's `fallback_fee_rate` was
/// sat/kvB, bark-bolt-ffi's `Config.fallbackFeeRate` is sat/kwu (1 vB = 4 wu).
/// Copying the old number would quadruple fees.
struct WalletConfiguration: Equatable, Sendable {
    var network: Network
    var arkServer: String
    var esplora: String?
    var bitcoind: String?
    var bitcoindUser: String?
    var bitcoindPass: String?
    var vtxoRefreshExpiryThreshold: UInt16
    var fallbackFeeRateSatPerKvb: UInt64
    var htlcRecvClaimDelta: UInt16
    var vtxoExitMargin: UInt16
    var roundTxRequiredConfirmations: UInt32

    /// Network name bark stores in `bark_properties.network`.
    var networkName: String {
        switch network {
        case .bitcoin: "bitcoin"
        case .testnet: "testnet"
        case .signet: "signet"
        case .regtest: "regtest"
        }
    }

    /// sat/kvB → sat/kwu.
    var fallbackFeeRateSatPerKwu: UInt64 { fallbackFeeRateSatPerKvb / 4 }

    static func base(for variant: AppVariant, regtestHost: String = RegtestHost.host(),
                     regtestArkURL: String? = RegtestHost.arkURL(),
                     regtestBitcoindURL: String? = RegtestHost.bitcoindURL()) -> Self {
        switch variant {
        case .mainnet:
            Self(
                network: .bitcoin,
                arkServer: "https://ark.second.tech",
                esplora: "https://mempool.second.tech/api",
                vtxoRefreshExpiryThreshold: 144,
                fallbackFeeRateSatPerKvb: 10_000,
                htlcRecvClaimDelta: 18,
                vtxoExitMargin: 12,
                roundTxRequiredConfirmations: 2)
        case .signet:
            Self(
                network: .signet,
                arkServer: "https://ark.signet.2nd.dev",
                esplora: "https://esplora.signet.2nd.dev",
                vtxoRefreshExpiryThreshold: 12,
                fallbackFeeRateSatPerKvb: 10_000,
                htlcRecvClaimDelta: 18,
                vtxoExitMargin: 12,
                roundTxRequiredConfirmations: 1)
        case .regtest:
            Self(
                network: .regtest,
                arkServer: regtestArkURL ?? "http://\(regtestHost):3535",
                bitcoind: regtestBitcoindURL ?? "http://\(regtestHost):18443",
                bitcoindUser: "second",
                bitcoindPass: "ark",
                vtxoRefreshExpiryThreshold: 24,
                fallbackFeeRateSatPerKvb: 10_000,
                htlcRecvClaimDelta: 18,
                vtxoExitMargin: 12,
                roundTxRequiredConfirmations: 1)
        }
    }

    static func effective(for variant: AppVariant, endpoint: String?) -> Self {
        var config = base(for: variant)
        if variant != .regtest, let endpoint, let normalized = try? EsploraEndpoint.normalize(endpoint) {
            config.esplora = normalized
        }
        return config
    }

    /// Same format as the RN app: `noah-<variant>-<platform>/<version>`.
    static func userAgent(variant: AppVariant, version: String) -> String {
        "noah-\(variant.rawValue)-ios/\(version)"
    }

    func sdkConfig(userAgent: String) -> Config {
        Config(
            serverAddress: arkServer,
            esploraAddress: esplora,
            bitcoindAddress: bitcoind,
            bitcoindUser: bitcoindUser,
            bitcoindPass: bitcoindPass,
            vtxoRefreshExpiryThreshold: vtxoRefreshExpiryThreshold,
            vtxoExitMargin: vtxoExitMargin,
            htlcRecvClaimDelta: htlcRecvClaimDelta,
            fallbackFeeRate: fallbackFeeRateSatPerKwu,
            roundTxRequiredConfirmations: roundTxRequiredConfirmations,
            userAgent: userAgent)
    }
}
