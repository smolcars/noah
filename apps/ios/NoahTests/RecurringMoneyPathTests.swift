import Foundation
import MMKV
@testable import Noah
import Testing

@MainActor struct RecurringMoneyPathTests {
    @Test func suspendedWalletRollbackMustNotAuthorizeRecurring() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        var wallet = PersistedWalletState(); wallet.isInitialized = true; wallet.isWalletSuspended = false
        try h.settings.setPersistedState(wallet, key: PersistedWalletState.key, version: 0)
        let original = try h.add()
        await h.executor.timeChanged()
        let activeWallet = try #require(h.settings.string(forKey: PersistedWalletState.key))
        let owner = h.owner, settings = h.settings
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: h.directory.deletingLastPathComponent())
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw CocoaError(.fileReadUnknown) }
        let item = KeychainPassword(service: "review-unused-\(UUID())", account: "noah")
        let lifecycle = WalletLifecycle(dependencies: .init(session: session, location: location, seed: item, authToken: item,
                                                            settings: settings, scratch: ScratchSpace(root: h.directory.appending(path: "scratch")),
                                                            appGroup: nil, deregister: {}))
        try await lifecycle.suspend()
        #expect(settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended == true)
        // An accepted old MMKV snapshot loses the wallet flag and generation, but not policy evidence.
        try settings.setDurably(activeWallet, forKey: PersistedWalletState.key)
        settings.remove(forKey: WalletLifecycle.automationStopKey)
        let executor = RecurringExecutor(dependencies: .init(store: h.store, journal: h.journal, controller: h.controller,
                                                             ensureOpen: {}, binding: { owner.withLock { $0 } }, allowed: {
                                                                 guard let saved = settings.persistedState(
                                                                     PersistedWalletState.self,
                                                                     key: PersistedWalletState.key),
                                                                     saved.isInitialized == true,
                                                                     saved.isWalletSuspended != true else { throw AppServices.WalletSuspended() }
                                                             }, quote: { schedule in await h.quoted(schedule, fees: RecurringFeePolicy.fallback) },
                                                             automationGeneration: { settings.string(forKey: WalletLifecycle.automationStopKey) },
                                                             now: { 1_800_000_000_000 }))
        await executor.execute()
        #expect(h.calls.withLock { $0 } == 0, "suspension must survive MMKV rollback")
        #expect(try h.store.load().schedules[original.id]?.status == "needs_attention")
    }

    @Test(arguments: [false, true]) func durableGenerationRejectsAdmittedWorkAfterRollback(resume: Bool) async throws {
        let h = try RecurringExecutorTests.Harness(); _ = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        h.beforePay = {
            let authority = RecurringAutomationAuthority(directory: h.directory)
            try authority.stop()
            if resume { try authority.resume() }
            h.settings.remove(forKey: WalletLifecycle.automationStopKey)
        }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
    }
}
