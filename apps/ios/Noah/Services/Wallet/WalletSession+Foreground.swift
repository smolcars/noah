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
        /// A maintenance that failed: due again for the next pass.
        var maintenanceFailed: @Sendable () async -> Void

        static func live(_ session: WalletSession, _ handles: Handles) -> Self {
            .init(syncOnchain: { _ = try await handles.onchain.sync() },
                  sync: { try await handles.wallet.sync() },
                  maintenance: { try await handles.wallet.maintenanceDelegated() },
                  claimLightning: { _ = try await handles.wallet.tryClaimAllLightningReceives(wait: false) },
                  progressExits: { _ = try await session.progressPendingExits(access: WalletSession.exitProgressAccess(handles)) },
                  takeMaintenance: { await session.takeForegroundMaintenance() },
                  maintenanceFailed: { await session.foregroundMaintenanceFailed() })
        }

        /// RN's foreground stages, each attempted on its own. The first pass after a foreground entry runs bark's
        /// delegated maintenance in place of the wallet sync (it starts with one), so VTXOs due for refresh go into
        /// a delegated round; one that fails (offline) runs again on each later pass until it completes, since
        /// bark's sync never moves an interactive attempt an older version left, so its VTXOs stay locked until
        /// maintenance does. Every pass then moves live exits.
        var stages: [@Sendable () async throws -> Void] {
            let sync = sync, maintenance = maintenance, takeMaintenance = takeMaintenance, maintenanceFailed = maintenanceFailed
            return [
                syncOnchain,
                {
                    guard await takeMaintenance() else { return try await sync() }
                    do { try await maintenance() } catch { await maintenanceFailed(); throw error }
                },
                claimLightning,
                progressExits,
            ]
        }
    }
}
