import Foundation

/// The foreground tick's pass. With the app open it also does what bark's daemon did and RN's stages don't
/// cover: delegated maintenance after each foreground entry, and exit progress.
extension WalletSession {
    /// What the foreground pass calls, so its rules are tested without the SDK.
    struct ForegroundPass: Sendable {
        var syncOnchain, sync, maintenance, claimLightning: @Sendable () async throws -> Void
        /// Moves exits at bark's own fee rate; calls nothing without a live one (`progressPendingExits`).
        var progressExits: @Sendable () async throws -> Void
        /// Whether a foreground entry left delegated maintenance due; taking it clears it.
        var takeMaintenance: @Sendable () async -> Bool

        static func live(_ session: WalletSession, _ handles: Handles) -> Self {
            .init(syncOnchain: { _ = try await handles.onchain.sync() },
                  sync: { try await handles.wallet.sync() },
                  maintenance: { try await handles.wallet.maintenanceDelegated() },
                  claimLightning: { _ = try await handles.wallet.tryClaimAllLightningReceives(wait: false) },
                  progressExits: { _ = try await session.progressPendingExits(access: WalletSession.exitProgressAccess(handles)) },
                  takeMaintenance: { await session.takeForegroundMaintenance() })
        }

        /// RN's foreground stages, each attempted on its own. The first pass after a foreground entry runs bark's
        /// delegated maintenance in place of the wallet sync (it starts with one), so VTXOs due for refresh go into
        /// a delegated round. Every pass then moves live exits.
        var stages: [@Sendable () async throws -> Void] {
            let sync = sync, maintenance = maintenance, takeMaintenance = takeMaintenance
            return [
                syncOnchain,
                { if await takeMaintenance() { try await maintenance() } else { try await sync() } },
                claimLightning,
                progressExits,
            ]
        }
    }
}
