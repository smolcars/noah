import Foundation
import Testing

@testable import Noah

struct WalletConfigurationTests {
    @Test func fallbackFeeRateIsConvertedToSatPerKwu() {
        for variant in AppVariant.allCases {
            let configuration = WalletConfiguration.base(for: variant)
            #expect(configuration.fallbackFeeRateSatPerKvb == 10_000)
            // 10 sat/vB: 10_000 sat/kvB == 2_500 sat/kwu
            #expect(configuration.fallbackFeeRateSatPerKwu == 2_500)
            #expect(configuration.sdkConfig(userAgent: "ua").fallbackFeeRate == 2_500)
        }
    }

    @Test func variantsMatchReactNativeConfig() {
        #expect(WalletConfiguration.base(for: .mainnet).network == .bitcoin)
        #expect(WalletConfiguration.base(for: .mainnet).roundTxRequiredConfirmations == 2)
        #expect(WalletConfiguration.base(for: .signet).vtxoRefreshExpiryThreshold == 12)
        #expect(WalletConfiguration.base(for: .regtest).bitcoind == "http://localhost:18443")
        #expect(WalletConfiguration.base(for: .regtest).esplora == nil)
        #expect(WalletConfiguration.userAgent(variant: .signet, version: "0.2.0") == "noah-signet-ios/0.2.0")
    }

    @Test func regtestHostsCanBeBakedInForPhysicalDevices() {
        let lan = WalletConfiguration.base(for: .regtest, regtestHost: "192.168.6.67")
        #expect(lan.arkServer == "http://192.168.6.67:3535")
        #expect(lan.bitcoind == "http://192.168.6.67:18443")
        #expect(RegtestHost.host(info: ["NoahRegtestHost": "192.168.6.67"]) == "192.168.6.67")
        // Unset build settings expand to "", which must keep the simulator defaults.
        #expect(RegtestHost.host(info: ["NoahRegtestHost": ""]) == "localhost")
        #expect(RegtestHost.host(info: nil) == "localhost")
        #expect(ServerEndpoint.baseURL(for: .regtest, environment: [:], info: ["NoahRegtestServerURL": ""])
            == URL(string: "http://localhost:3000"))
        #expect(ServerEndpoint.baseURL(for: .regtest, environment: [:], info: ["NoahRegtestServerURL": "http://192.168.6.67:3000"])
            == URL(string: "http://192.168.6.67:3000"))
        #expect(ServerEndpoint.baseURL(for: .regtest, environment: ["NOAH_REGTEST_SERVER_URL": "http://10.1.1.1:3000"],
                                       info: ["NoahRegtestServerURL": "http://192.168.6.67:3000"]) == URL(string: "http://10.1.1.1:3000"))
        // Only regtest reads the override.
        #expect(ServerEndpoint.baseURL(for: .mainnet, environment: [:], info: ["NoahRegtestServerURL": "http://192.168.6.67:3000"])
            == URL(string: "https://noahwallet.io"))
    }

    @Test func regtestArkAndBitcoindURLsCanTargetAnotherStack() {
        let info: [String: Any] = ["NoahRegtestArkURL": " http://127.0.0.1:13535 ", "NoahRegtestBitcoindURL": "http://127.0.0.1:28443"]
        let other = WalletConfiguration.base(for: .regtest, regtestHost: "192.168.6.67",
                                             regtestArkURL: RegtestHost.arkURL(info: info), regtestBitcoindURL: RegtestHost.bitcoindURL(info: info))
        #expect(other.arkServer == "http://127.0.0.1:13535")
        #expect(other.bitcoind == "http://127.0.0.1:28443")
        #expect(ArkInfoEndpoints.blockHeight(variant: .regtest, esploraOverride: nil, regtestBitcoindURL: "http://127.0.0.1:28443")
            == "http://127.0.0.1:28443")
        // Unset build settings expand to "", which must keep the host's default ports.
        for unset in [["NoahRegtestArkURL": "", "NoahRegtestBitcoindURL": " "], [:]] as [[String: Any]] {
            #expect(RegtestHost.arkURL(info: unset) == nil && RegtestHost.bitcoindURL(info: unset) == nil)
        }
        let defaults = WalletConfiguration.base(for: .regtest, regtestHost: "192.168.6.67", regtestArkURL: nil, regtestBitcoindURL: nil)
        #expect(defaults.arkServer == "http://192.168.6.67:3535" && defaults.bitcoind == "http://192.168.6.67:18443")
        // Mainnet and signet never read them.
        for variant in [AppVariant.mainnet, .signet] {
            #expect(WalletConfiguration.base(for: variant, regtestArkURL: "http://192.0.2.1:1", regtestBitcoindURL: "http://192.0.2.1:2")
                == WalletConfiguration.base(for: variant, regtestArkURL: nil, regtestBitcoindURL: nil))
            #expect(ArkInfoEndpoints.blockHeight(variant: variant, esploraOverride: nil, regtestBitcoindURL: "http://192.0.2.1:2")
                == ArkInfoEndpoints.blockHeight(variant: variant, esploraOverride: nil, regtestBitcoindURL: nil))
        }
    }
}
