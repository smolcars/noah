import Foundation

/// Durable attempts are scoped to the operation/items they could duplicate; never auto-retry.
actor OnchainOperationJournal {
    struct Attempt: Codable, Sendable, Identifiable {
        var quote: OnchainQuote
        var receipt: OnchainReceipt?
        var txid: String?
        var id: UUID { quote.id }

        func blocks(_ action: OnchainAction, ids: [String]) -> Bool {
            guard quote.action == action else { return false }
            // Pinned Bark start skips active IDs; cancel of an already-canceled ID is a no-op.
            // Claims skip non-claimable IDs, but until chain observation a second PSBT can differ
            // (destination/locktime/fee). Progress can spend more on CPFP; board can spend new coins.
            // Refresh locks round inputs, but an ambiguous registration must not be repeated.
            // https://gitlab.com/ark-bitcoin/bark/-/blob/bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c/bark/src/exit/mod.rs
            // https://gitlab.com/ark-bitcoin/bark/-/blob/bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c/bark/src/round/mod.rs
            if receipt == nil && [.startExit, .cancel].contains(action) { return false }
            return action == .board || !Set(quote.ids).isDisjoint(with: ids)
        }
    }
    struct Evidence: Sendable {
        var walletID: String
        var vtxoStates: [String: String] = [:]
        var exitStates: [String: String] = [:]
        var transactionIDs: Set<String> = []

        func receipt(for attempt: Attempt) -> OnchainReceipt? {
            guard walletID == attempt.quote.walletID else { return nil }
            if let txid = attempt.txid, transactionIDs.contains(txid) {
                return .init(message: "The recorded transaction is in this wallet's transaction history.", txid: txid)
            }
            let ids = attempt.quote.ids
            guard !ids.isEmpty else { return nil }
            let resolved: Bool
            switch attempt.quote.action {
            case .startExit:
                resolved = ids.allSatisfy { exitStates[$0] != nil }
            case .cancel:
                resolved = ids.allSatisfy { exitStates[$0] == "Canceled" }
            case .claim:
                resolved = ids.allSatisfy { ["ClaimInProgress", "Claimed"].contains(exitStates[$0]) }
            case .refresh:
                // This proves the exact inputs cannot be refreshed again, not which operation spent them.
                resolved = ids.allSatisfy { ["Spent", "Exited"].contains(vtxoStates[$0]) }
            case .progress:
                resolved = ids.allSatisfy {
                    ["Claimable", "ClaimInProgress", "Claimed", "Canceled", "VtxoAlreadySpent", "VtxoSwept"].contains(exitStates[$0])
                }
            case .board: resolved = false // No ID means no safe correlation; never match by amount.
            }
            return resolved ? .init(message: "The selected items have reached a state that resolves this attempt. Review their current status and history before continuing.") : nil
        }
    }
    static let shared = OnchainOperationJournal(
        file: WalletDataLocation(variant: .current).directory.appending(path: "noah-onchain-attempt.json"))
    private let file: URL
    private var running = false
    init(file: URL) { self.file = file }

    func loadAll() throws -> [Attempt] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [] }
        let data = try Data(contentsOf: file)
        if let legacy = try? JSONDecoder().decode(Attempt.self, from: data) { return [legacy] }
        return try JSONDecoder().decode([Attempt].self, from: data)
    }
    func load() throws -> Attempt? { try loadAll().first }
    private func save(_ values: [Attempt]) throws {
        try JSONEncoder().encode(values).write(
            to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var url = file
        var attributes = URLResourceValues()
        attributes.isExcludedFromBackup = true
        try url.setResourceValues(attributes)
    }
    private func update(_ id: UUID, receipt: OnchainReceipt? = nil, txid: String? = nil) throws {
        var attempts = try loadAll()
        guard let index = attempts.firstIndex(where: { $0.id == id }) else { throw OnchainFailure.uncertain }
        if let receipt { attempts[index].receipt = receipt }
        if let txid { attempts[index].txid = txid }
        try save(attempts)
    }
    func recordTransaction(_ txid: String, for id: UUID) throws { try update(id, txid: txid) }

    func perform(_ quote: OnchainQuote, action: @Sendable () async throws -> OnchainReceipt) async throws -> String {
        let previous = try loadAll()
        guard !running, !previous.contains(where: { $0.blocks(quote.action, ids: quote.ids) }) else {
            throw OnchainFailure.busy
        }
        running = true
        defer { running = false }
        try save(previous + [.init(quote: quote)])
        do {
            let receipt = try await action()
            try update(quote.id, receipt: receipt)
            return receipt.message
        } catch {
            if let failure = OnchainSubmission.preBroadcast(error, action: quote.action) {
                try save(try loadAll().filter { $0.id != quote.id })
                throw failure
            }
            throw OnchainFailure.uncertain
        }
    }
    func reconcile(_ id: UUID, evidence: Evidence) throws -> Bool {
        guard !running else { throw OnchainFailure.busy }
        guard let attempt = try loadAll().first(where: { $0.id == id }), attempt.receipt == nil,
            let receipt = evidence.receipt(for: attempt)
        else { return false }
        try update(id, receipt: receipt)
        return true
    }
    func refreshBoardFees(walletID: String, fees: [String: UInt64]) throws {
        guard !running else { return }
        var attempts = try loadAll()
        var changed = false
        for index in attempts.indices {
            guard attempts[index].quote.walletID == walletID, attempts[index].quote.action == .board,
                var receipt = attempts[index].receipt, receipt.onchainFee == nil,
                let txid = receipt.txid, let fee = fees[txid] else { continue }
            receipt.onchainFee = fee
            receipt.message = OnchainReceipt.boardingMessage(fee: fee)
            attempts[index].receipt = receipt
            changed = true
        }
        if changed { try save(attempts) }
    }
    func acknowledge(_ id: UUID? = nil) throws {
        guard !running, let attempt = try loadAll().first(where: { id == nil || $0.id == id }),
            attempt.receipt != nil else { throw OnchainFailure.uncertain }
        try save(try loadAll().filter { $0.id != attempt.id })
    }
    /// Only called after the user explicitly accepts the risk of repeating this operation.
    func discardUncertain(_ id: UUID) throws {
        guard !running, let attempt = try loadAll().first(where: { $0.id == id }), attempt.receipt == nil else {
            throw OnchainFailure.busy
        }
        try save(try loadAll().filter { $0.id != id })
    }
}
