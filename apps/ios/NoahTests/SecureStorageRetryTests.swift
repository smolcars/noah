import Foundation
import Security
import Testing

@testable import Noah

@MainActor
struct SecureStorageRetryTests {
    @Test func transientUnavailabilityRetriesInProcessWithoutBecomingAbsent() async {
        SettingsStorage.bootstrap()
        var seed = KeychainPassword.ReadResult.unavailable(errSecInteractionNotAllowed)
        var reads = 0
        var recoveries = 0
        let model = AppModel(readLaunchState: {
            reads += 1
            return LaunchState.resolve(seed: .init(seed), walletDatabaseExists: false)
        }, settleRestore: { recoveries += 1; return .nothingPending })
        await model.start()
        #expect(model.phase == .blocked(.secureStorageUnavailable))
        await model.retrySecureStorage() // Explicit retry while still locked.
        #expect(model.phase == .blocked(.secureStorageUnavailable))
        seed = .notFound // Only an actual successful missing-item read permits onboarding.
        await model.appBecameActive()
        #expect(model.phase == .onboarding)
        let completedReads = reads
        await model.start()
        await model.retrySecureStorage()
        #expect(reads == completedReads)
        #expect(recoveries == 3)
    }

    @Test func retryCannotBypassInvalidOrConflictingSeeds() async {
        SettingsStorage.bootstrap()
        for state in [LaunchState.invalidSeed, .seedConflict] {
            var reads = 0
            let model = AppModel(readLaunchState: { reads += 1; return state }, settleRestore: { .nothingPending })
            await model.start()
            let before = reads
            await model.retrySecureStorage()
            #expect(model.phase == .blocked(state))
            #expect(reads == before)
        }
    }

    @Test func concurrentRetriesJoinOneLaunchAttempt() async {
        SettingsStorage.bootstrap()
        var locked = true
        var recoveries = 0
        let model = AppModel(readLaunchState: { locked ? .secureStorageUnavailable : .onboarding }, settleRestore: {
            recoveries += 1
            await Task.yield()
            return .nothingPending
        })
        await model.start()
        locked = false
        async let explicit: Void = model.retrySecureStorage()
        async let foreground: Void = model.appBecameActive()
        _ = await (explicit, foreground)
        #expect(model.phase == .onboarding)
        #expect(recoveries == 2)
    }
}
