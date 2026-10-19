import Foundation
import Testing

@testable import Noah

/// Runs inside the installed app's sandbox after the native build was
/// installed over a React Native install (see `UpgradeTests/run.sh`).
/// Skipped unless `NOAH_UPGRADE_CHECK=1` (pass `TEST_RUNNER_NOAH_UPGRADE_CHECK=1`
/// to xcodebuild). Never prints secrets.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_UPGRADE_CHECK"] == "1"))
struct UpgradeCompatibilityTests {
    @Test func reactNativeStateIsReadable() throws {
        let variant = AppVariant.current

        guard case .found(let mnemonic) = KeychainPassword.mnemonic(for: variant).read() else {
            Issue.record("seed written by the RN app was not readable")
            return
        }
        #expect([12, 24].contains(mnemonic.split(separator: " ").count), "seed has an unexpected word count")

        #expect(WalletDataLocation(variant: variant).databaseExists, "RN wallet database not found")
        #expect(LaunchState.current(variant: variant) == .existingWallet)

        let wallet = SettingsStorage.shared.persistedState(
            PersistedWalletState.self, key: PersistedWalletState.key)
        #expect(wallet?.isInitialized == true, "wallet-storage not readable from the RN MMKV file")
    }
}

extension UpgradeCompatibilityTests {
    /// Opens the RN-created database with bark-bolt-ffi (new bark) without
    /// creating anything, and checks it belongs to this variant's network.
    @Test func existingWalletOpensWithNewBark() async throws {
        guard case .found(let mnemonic) = KeychainPassword.mnemonic(for: .current).read() else {
            Issue.record("seed not readable")
            return
        }
        let session = WalletSession()
        try await session.openExisting(mnemonic: mnemonic, runDaemon: false, appVersion: "upgrade-test")
        let summary = try #require(try await session.summary())
        try await session.close()

        #expect(summary.network == .regtest)
        #expect(!summary.fingerprint.isEmpty)
        let expected = ProcessInfo.processInfo.environment["NOAH_UPGRADE_EXPECT_FINGERPRINT"]
        if let expected, !expected.isEmpty {
            #expect(summary.fingerprint == expected, "opened a different wallet than the RN app created")
        }
    }
}
