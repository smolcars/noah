import Foundation

/// Runs only after the engine has committed the seed. A failure here must
/// never send the user back through the restore transaction.
@MainActor
struct RestoreCompletion {
    struct RestartRequired: Error {}

    var loadWalletState: () -> PersistedWalletState
    var saveWalletState: (PersistedWalletState) throws -> Void
    var seedRestored: (String, String?) async -> Void
    var recover: () async throws -> RestoreTransaction.Recovery
    var openWallet: () async -> Void

    func finish(_ outcome: WalletRestorer.Outcome) async throws {
        if outcome.cleanupPending {
            switch try await recover() {
            case .completed, .nothingPending: break
            case .rolledBack, .waitingForKeychain: throw RestartRequired()
            }
        }
        var wallet = loadWalletState()
        wallet.isInitialized = true
        wallet.isWalletLoaded = true
        try saveWalletState(wallet)
        if let hash = outcome.snapshotSha256 {
            await seedRestored(hash, outcome.backupId)
        }
        await openWallet()
    }
}
