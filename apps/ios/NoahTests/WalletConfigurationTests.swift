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

    @Test func refusesToOpenWithoutDatabase() async {
        let documents = FileManager.default.temporaryDirectory.appending(path: "nowallet-\(UUID().uuidString)")
        let session = WalletSession(variant: .regtest, location: WalletDataLocation(variant: .regtest, documentsDirectory: documents))
        await #expect(throws: WalletSession.OpenError.walletDatabaseMissing) {
            try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        #expect(!FileManager.default.fileExists(atPath: documents.path), "must not create the datadir")
    }
}
