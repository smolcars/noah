import BarkBoltFFI
import Foundation

/// Only a foreground review that reaches presentation consumes its confirmed funding.
struct AutoBoardFunding {
    static let key = "native-auto-board-funding-v1"
    struct Snapshot: Equatable, Sendable {
        var walletID: String
        var outpoints: Set<String>
    }
    static func confirmed(walletID: String, utxos: [OnchainUtxo], transactions: [WalletTransaction]) -> Snapshot {
        // A board/send's change is not a new deposit. Unknown transactions are not eligible.
        let incoming = Set(transactions.filter { $0.balanceChangeSats > 0 }.map(\.txid))
        return .init(walletID: walletID, outpoints: Set(utxos.compactMap {
            guard case let .local(point, _, height) = $0, height != nil, incoming.contains(point.txid) else { return nil }
            return "\(point.txid):\(point.vout)"
        }))
    }
    var read: () -> String?
    var write: (String) throws -> Void
    init(storage: SettingsStorage = .shared) {
        read = { storage.string(forKey: Self.key) }
        write = { try storage.setDurably($0, forKey: Self.key) }
    }
    private func load() throws -> [String: Set<String>] {
        guard let raw = read() else { return [:] }
        return try JSONDecoder().decode([String: Set<String>].self, from: Data(raw.utf8))
    }
    func hasUnhandled(_ funding: Snapshot) throws -> Bool {
        try !funding.outpoints.subtracting(load()[funding.walletID] ?? []).isEmpty
    }
    @discardableResult func markHandled(_ funding: Snapshot) throws -> Bool {
        var saved = try load()
        let previous = saved[funding.walletID] ?? []
        guard !funding.outpoints.subtracting(previous).isEmpty else { return false }
        saved[funding.walletID] = previous.union(funding.outpoints)
        try write(String(decoding: JSONEncoder().encode(saved), as: UTF8.self))
        return true
    }
}

/// Async reads can outlive the scene; recheck admission before evaluating or claiming their result.
@MainActor final class AutoBoardReview {
    private let store: AutoBoardFunding
    private let now: () -> Date
    private(set) var retryAfter = Date.distantPast
    init(store: AutoBoardFunding = .init(), now: @escaping () -> Date = Date.init) {
        self.store = store; self.now = now
    }
    func prepare(allowed: () -> Bool, funding: () async throws -> AutoBoardFunding.Snapshot?,
                 quote: () async throws -> OnchainQuote?, discard: (OnchainQuote) async -> Void,
                 present: (OnchainQuote) -> Void = { _ in }) async throws -> OnchainQuote? {
        guard allowed(), now() >= retryAfter else { return nil }
        do {
            guard let current = try await funding(), allowed(), try store.hasUnhandled(current) else { return nil }
            guard let prepared = try await quote() else { return nil }
            do {
                guard allowed(), prepared.walletID == current.walletID, try store.markHandled(current) else {
                    await discard(prepared)
                    return nil
                }
                present(prepared)
                return prepared
            } catch { await discard(prepared); throw error }
        } catch is CancellationError { throw CancellationError() }
        catch { retryAfter = now().addingTimeInterval(30); throw error }
    }
}
