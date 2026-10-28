import Foundation
@testable import Noah
import Testing

struct OnboardingPersistenceTests {
    @Test(arguments: ["marker", "seed", "database", "publicKey"])
    func interruptedCreationNeverAdmitsAnUnfinishedWalletToHome(stop: String) async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "onboarding-state-\(UUID())/mmkv")
        defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
        let settings = try SettingsStorage(rootPath: root.path)
        let fake = WalletCreatorTests.Fake()
        var d = fake.dependencies()
        d.beginOnboarding = {
            try settings.setPersistedState(PersistedWalletState(isInitialized: false), key: PersistedWalletState.key, version: 0)
            if stop == "marker" { throw WalletCreatorTests.Boom() }
        }
        let save = d.saveSeed
        d.saveSeed = {
            try save($0)
            if stop == "seed" { throw WalletCreatorTests.Boom() }
        }
        d.createNew = { _, _ in
            fake.withLock { fake.database = true; fake.datadir = true }
            if stop == "database" { throw WalletSession.OpenError.teardownFailed }
        }
        _ = try? await WalletCreator(dependencies: d).createOrResume()
        // Reopen only the committed settings, as a new process would, before public-key lookup.
        let reopened = try SettingsStorage(rootPath: root.path)
        let initialized = reopened.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized
        let launch = fake.withLock { LaunchState.resolve(seed: .init(fake.seed), walletDatabaseExists: fake.database) }
        #expect(!(launch == .existingWallet && initialized != false), "Home after \(stop)")
        #expect(initialized == false)
        if stop == "database" || stop == "publicKey" { #expect(launch == .existingWallet) }
    }

    @Test func failedOnboardingMarkerCreatesNothingAndCanRetry() async throws {
        let fake = WalletCreatorTests.Fake()
        let fail = RecurringExecutorTests.Locked(true)
        var d = fake.dependencies()
        d.beginOnboarding = { if fail.withLock({ $0 }) { throw SettingsStorage.OpenError() } }
        let creator = WalletCreator(dependencies: d)
        await #expect(throws: SettingsStorage.OpenError.self) { try await creator.createOrResume() }
        #expect(fake.withLock { fake.seed == .notFound && !fake.database && fake.generated.isEmpty })
        fail.withLock { $0 = false }
        try await creator.createOrResume()
        #expect(fake.withLock { fake.database })
    }
}
