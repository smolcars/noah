import Foundation
import Testing

@testable import Noah

/// Only runs over a real RN sandbox prepared by UpgradeTests/run.sh.
/// Checkpoints contain public identity/counters, never phrases or tokens.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_UPGRADE_CHECK"] == "1"))
@MainActor
struct UpgradeCompatibilityTests {
    struct Expected: Decodable {
        struct Movement: Decodable {
            let id: UInt32
            let status: String
            let subsystem_name: String
            let movement_kind: String
            let intended_balance: Int64
            let effective_balance: Int64
            let offchain_fee: UInt64
        }
        let funded: Bool
        let fingerprint: String
        let spendable: UInt64
        let onchain: UInt64
        let locale: String?
        let movements: [Movement]
    }
    private func expected() throws -> Expected {
        try JSONDecoder().decode(Expected.self, from: Data(contentsOf: URL.documentsDirectory.appending(path: "noah-upgrade-check.json")))
    }
    private func rnSettings() throws -> SettingsStorage {
        SettingsStorage.bootstrap()
        return try SettingsStorage(rootPath: URL.documentsDirectory.appending(path: "noah-upgrade-rn-mmkv").path)
    }

    @Test func reactNativeStateIsReadable() throws {
        guard case .found(let mnemonic) = KeychainPassword.mnemonic(for: .current).read() else {
            Issue.record("RN seed not readable")
            return
        }
        let valid = (try? StoredSeed.normalized(mnemonic)) != nil
        #expect(valid)
        #expect(WalletDataLocation(variant: .current).databaseExists)
        #expect(LaunchState.current() == .existingWallet)
        #expect(SettingsStorage.shared.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == true)
    }

    @Test func existingWalletOpensWithNewBark() async throws {
        let baseline = try expected()
        let services = AppServices.shared
        try await services.ensureWalletOpen()
        try await services.session.sync()
        let summary = try #require(try await services.session.summary())
        #expect(summary.network == .regtest)
        #expect(summary.fingerprint == baseline.fingerprint)
        #expect(summary.movementCount == baseline.movements.count)
        #expect(summary.spendableSats == baseline.spendable)
        #expect(summary.balance.offchainSats == baseline.spendable)
        #expect(summary.balance.pendingSats == 0)
        #expect(summary.balance.totalSats == baseline.spendable + baseline.onchain)
        #expect(summary.balance.onchain.confirmed == baseline.onchain)
        #expect(summary.balance.onchain.total == baseline.onchain)
        let movements = try await services.session.withOpenWallet { try await $0.wallet.history() }
        for row in baseline.movements {
            let actual = try #require(movements.first { $0.id == row.id })
            #expect(actual.status == row.status)
            #expect(actual.subsystemName == row.subsystem_name)
            #expect(actual.subsystemKind == row.movement_kind)
            #expect(actual.intendedBalanceSats == row.intended_balance)
            #expect(actual.effectiveBalanceSats == row.effective_balance)
            #expect(actual.offchainFeeSats == row.offchain_fee)
        }
        let report: [String: Any] = ["fingerprint": summary.fingerprint, "movements": summary.movementCount,
            "spendable": summary.spendableSats, "onchainConfirmed": summary.balance.onchain.confirmed,
            "expoContinuityVerified": false]
        try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]).write(to: URL.documentsDirectory.appending(path: "noah-upgrade-result.json"))
    }

    @Test func nonDefaultSettingsMatchTheRNBytes() throws {
        guard try expected().funded else { return }
        let before = try rnSettings()
        let storage = SettingsStorage.shared
        let profile = try #require(storage.persistedState(PersistedProfileState.self, key: PersistedProfileState.key))
        #expect(profile == before.persistedState(PersistedProfileState.self, key: PersistedProfileState.key))
        #expect(profile.amountUnit == .sats)
        #expect(profile.preferredCurrency == "EUR")
        #expect(profile.displayName == "Upgrade safety")
        #expect(storage.persistedState(PersistedPrivacyState.self, key: PersistedPrivacyState.key) == before.persistedState(PersistedPrivacyState.self, key: PersistedPrivacyState.key))
        #expect(storage.persistedState(PersistedPrivacyState.self, key: PersistedPrivacyState.key)?.isHomeBalanceHidden == true)
        #expect(PersistedTransactionState.load(from: storage) == PersistedTransactionState.load(from: before))
        #expect(!PersistedTransactionState.load(from: storage).isAutoBoardingEnabled)
        let oldWallet = try #require(before.persistedState(PersistedWalletState.self, key: PersistedWalletState.key))
        let wallet = try #require(storage.persistedState(PersistedWalletState.self, key: PersistedWalletState.key))
        #expect(wallet.isBiometricsEnabled == oldWallet.isBiometricsEnabled)
        #expect(wallet.isDebugModeEnabled == oldWallet.isDebugModeEnabled)
        #expect(wallet.isDebugModeEnabled == true)
        #expect(LegacyLocale.read() == (try expected().locale))
        #expect(LegacyLocale.read() == "sv")
        #expect(wallet.isWalletSuspended == oldWallet.isWalletSuspended)
        let oldServer = before.persistedState(PersistedServerState.self, key: PersistedServerState.key)
        let server = storage.persistedState(PersistedServerState.self, key: PersistedServerState.key)
        #expect(server?.isMailboxAuthorizationEnabled == oldServer?.isMailboxAuthorizationEnabled)
        #expect(server?.isMailboxAuthorizationEnabled == false)
        #expect(server?.isBackupEnabled == oldServer?.isBackupEnabled)
    }

    @Test func recurringPolicyIsIdenticalAndHasNotRun() throws {
        guard try expected().funded else { return }
        let old = try RecurringStore(settings: rnSettings()).load().schedules
        let current = try RecurringStore().load().schedules
        #expect(old.count == 1)
        let identical = old == current
        #expect(identical, "All RN recurring policy fields must survive unchanged")
        let rule = try #require(current.values.first)
        #expect(rule.label == "Upgrade safety rule")
        #expect(rule.amountSat == 1000)
        #expect(rule.status == "active")
        #expect(rule.startAt > Date().timeIntervalSince1970 * 1000)
        #expect(rule.occurrencesPaid == 0 && rule.runs.isEmpty && rule.inFlight == nil)
    }

    /// Simulators cannot acquire RN APNs/Expo registration. Missing expectations
    /// are a visible skip, never a generated UUID masquerading as continuity.
    /// The installation ID lives only in RN's keychain item, so a physical-device
    /// run may supply just the RN-registered token: Expo derives the token from
    /// that ID, so an unchanged token proves the ID was reused.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["NOAH_UPGRADE_EXPECT_EXPO_TOKEN"] != nil))
    func registeredExpoIdentityAndTokenAreUnchanged() async throws {
        let env = ProcessInfo.processInfo.environment
        let token = try #require(env["NOAH_UPGRADE_EXPECT_EXPO_TOKEN"])
        if let id = env["NOAH_UPGRADE_EXPECT_EXPO_ID"] {
            let sameID = try ExpoInstallationID.existing() == id
            #expect(sameID)
        } else {
            #expect(try ExpoInstallationID.existing() != nil)
        }
        guard case .registered(let actual) = try await PushRegistration.shared.register() else {
            Issue.record("Physical RN-to-native Expo registration is required")
            return
        }
        let sameToken = actual == token
        #expect(sameToken)
    }
}
