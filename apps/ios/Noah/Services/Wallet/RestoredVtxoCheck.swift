import Foundation

/// A restored backup can be older than the wallet it came from: VTXOs it still lists as spendable may have been spent
/// since, and bark's sync never asks the server about VTXOs it holds as spendable. A restore leaves a marker in the
/// datadir, and the first foreground pass syncs, then asks the server about each spendable VTXO and adopts "spent"
/// (bark's `trust_and_adopt_server_vtxo_status`), before delegated maintenance can try to refresh one. A pass that
/// leaves a VTXO unanswered keeps the marker, with its pass count, for the next pass, up to `maxPasses`.
enum RestoredVtxoCheck {
    static let maxPasses = 5

    /// One check at a time per datadir: the open before the first balance read and the foreground pass can both start
    /// one, and the second joins the first.
    actor Flight {
        static let shared = Flight()
        private var running: [URL: Task<Int, Error>] = [:]

        func run(_ marker: URL, _ check: @escaping @Sendable () async throws -> Int) async throws -> Int {
            if let running = running[marker] { return try await running.value }
            let task = Task { try await check() }
            running[marker] = task
            defer { running[marker] = nil }
            return try await task.value
        }
    }

    struct Steps: Sendable {
        var sync: @Sendable () async throws -> Void
        var spendableIDs: @Sendable () async throws -> [String]
        /// Asks the server about one VTXO and adopts its answer; throws when there is no usable answer.
        var adopt: @Sendable (String) async throws -> Void
    }

    /// Returns how many VTXOs went unanswered: 0 when nothing was due or every one was answered.
    @discardableResult
    static func run(marker: URL, _ steps: Steps, log: AppLog = AppLog("restore")) async throws -> Int {
        guard FileManager.default.fileExists(atPath: marker.path) else { return 0 }
        try await steps.sync()
        var unanswered = 0
        for id in try await steps.spendableIDs() {
            do { try await steps.adopt(id) } catch let cancelled as CancellationError { throw cancelled } catch { unanswered += 1 }
        }
        let passes = ((try? String(contentsOf: marker, encoding: .utf8)).flatMap { Int($0) } ?? 0) + 1
        if unanswered == 0 || passes >= maxPasses {
            if unanswered > 0 { log.w("Stopped checking \(unanswered) restored VTXOs with the server after \(passes) passes") }
            try FileManager.default.removeItem(at: marker)
        } else {
            try Data(String(passes).utf8).write(to: marker, options: .atomic)
        }
        return unanswered
    }
}

extension WalletSession {
    /// The foreground pass's first stage, and the open's before the first balance read (`AppModel.openExistingWallet`);
    /// does nothing unless a restore left `RestoreTransaction.vtxoCheckName`.
    nonisolated func checkRestoredVtxos(_ handles: Handles) async throws {
        let marker = location.directory.appending(path: RestoreTransaction.vtxoCheckName)
        _ = try await RestoredVtxoCheck.Flight.shared.run(marker) {
            try await RestoredVtxoCheck.run(marker: marker, .init(
                sync: { try await handles.wallet.sync() },
                spendableIDs: { try await handles.wallet.spendableVtxos().map(\.id) },
                adopt: { _ = try await handles.wallet.trustAndAdoptServerVtxoStatus(vtxoId: $0) }))
        }
    }
}
