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
        /// How many refreshes bark has recorded, failed ones included (its `bark.round` refresh movements).
        var refreshes: @Sendable () async throws -> Int

        /// With `timing`, each call's start and end go to the app log.
        static func live(_ session: WalletSession, _ handles: Handles, timing: PassTiming? = nil) -> Self {
            func timed(_ stage: String, _ work: @escaping @Sendable () async throws -> Void) -> @Sendable () async throws -> Void {
                guard let timing else { return work }
                return { try await timing.run(stage, work) }
            }
            return .init(
                syncOnchain: timed("on-chain sync") { _ = try await handles.onchain.sync() },
                sync: timed("wallet sync") { try await handles.wallet.sync() },
                maintenance: timed("delegated maintenance") { try await handles.wallet.maintenanceDelegated() },
                claimLightning: timed("Lightning claims") { _ = try await handles.wallet.tryClaimAllLightningReceives(wait: false) },
                progressExits: timed("exit progress") {
                    _ = try await session.progressPendingExits(access: WalletSession.exitProgressAccess(handles))
                },
                takeMaintenance: { await session.takeForegroundMaintenance() },
                maintenanceFailed: { await session.foregroundMaintenanceFailed() },
                refreshes: { try await handles.wallet.history().count { HistoryMapping.subsystemID($0) == "bark.round:refresh" } })
        }

        /// RN's foreground stages, each attempted on its own. The first pass after a foreground entry runs bark's
        /// delegated maintenance in place of the wallet sync (it starts with one), so VTXOs due for refresh go into
        /// a delegated round; one that fails (offline) runs again on each later pass until it completes, since
        /// bark's sync never moves an interactive attempt an older version left, so its VTXOs stay locked until
        /// maintenance does. One that failed after creating its refresh (the server down or refusing it) waits for the
        /// next entry: each try would leave another failed renewal in history. Every pass then moves live exits.
        ///
        /// The on-chain sync goes last: it holds the on-chain wallet for its whole Esplora round trip, and every read
        /// of that wallet (Home's balance and history) waits meanwhile. Nothing before it needs it fresh: bark's sync
        /// and maintenance don't touch it, and exit progress funds fee children from the confirmed coins the last
        /// pass found, as bark's daemon did, whose exit loop never waited for its on-chain sync.
        var stages: [@Sendable () async throws -> Void] {
            let sync = sync, maintenance = maintenance, takeMaintenance = takeMaintenance, maintenanceFailed = maintenanceFailed
            let refreshes = refreshes
            return [
                {
                    guard await takeMaintenance() else { return try await sync() }
                    let created = try? await refreshes()
                    do { try await maintenance() } catch {
                        if let created, let now = try? await refreshes(), now > created { throw error }
                        await maintenanceFailed()
                        throw error
                    }
                },
                claimLightning,
                progressExits,
                syncOnchain,
            ]
        }
    }
}
