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
    private struct State: Codable {
        var handled: [String: Set<String>] = [:]
        var retryAfter: [String: Double] = [:]
    }
    private func load() throws -> State {
        guard let raw = read() else { return .init() }
        let data = Data(raw.utf8)
        // Keep the original outpoint-only ledger readable.
        if let legacy = try? JSONDecoder().decode([String: Set<String>].self, from: data) {
            return .init(handled: legacy)
        }
        return try JSONDecoder().decode(State.self, from: data)
    }
    private func save(_ state: State) throws {
        try write(String(decoding: JSONEncoder().encode(state), as: UTF8.self))
    }
    static let retryBackoff: TimeInterval = 30
    /// A deadline beyond one backoff window means the clock moved back since saving; cap it durably once.
    func retryDeadline(walletID: String, now: Date = Date()) throws -> Date {
        var saved = try load()
        guard let stored = saved.retryAfter[walletID].map({ Date(timeIntervalSince1970: $0 / 1000) }) else {
            return .distantPast
        }
        let latest = now.addingTimeInterval(Self.retryBackoff)
        guard stored > latest else { return stored }
        saved.retryAfter[walletID] = latest.timeIntervalSince1970 * 1000
        try save(saved)
        return latest
    }
    func hasUnhandled(_ funding: Snapshot) throws -> Bool {
        try !funding.outpoints.subtracting(load().handled[funding.walletID] ?? []).isEmpty
    }
    @discardableResult func markHandled(_ funding: Snapshot) throws -> Bool { try claim(funding) != nil }
    func release(_ funding: Snapshot, retryAfter: Date) throws {
        var saved = try load()
        saved.handled[funding.walletID] = (saved.handled[funding.walletID] ?? []).subtracting(funding.outpoints)
        saved.retryAfter[funding.walletID] = retryAfter.timeIntervalSince1970 * 1000
        try save(saved) // One durable write: a release cannot survive without its retry deadline.
    }
    func claim(_ funding: Snapshot) throws -> Snapshot? {
        var saved = try load()
        let previous = saved.handled[funding.walletID] ?? []
        let newly = funding.outpoints.subtracting(previous)
        guard !newly.isEmpty else { return nil }
        saved.handled[funding.walletID] = previous.union(funding.outpoints)
        try save(saved)
        return .init(walletID: funding.walletID, outpoints: newly)
    }
}

/// Async reads can outlive the scene; recheck admission before evaluating or claiming their result.
@MainActor final class AutoBoardReview {
    private let store: AutoBoardFunding
    private var claimed: (UUID, AutoBoardFunding.Snapshot, AutoBoardFunding.Snapshot)?
    private let now: () -> Date
    private(set) var retryAfter = Date.distantPast
    init(store: AutoBoardFunding = .init(), now: @escaping () -> Date = Date.init) {
        self.store = store; self.now = now
    }
    func fundingFor(_ quote: OnchainQuote) throws -> AutoBoardFunding.Snapshot {
        guard let (id, _, funding) = claimed, id == quote.id else { throw OnchainFailure.changed }
        return funding
    }
    /// Proof also retires the preparation under the session's authority before releasing its deposits.
    func failedConfirm(_ quote: OnchainQuote, changed: Bool,
        proveUnsubmitted: () async throws -> Bool) async throws {
        guard let (id, funding, _) = claimed, id == quote.id else { return }
        claimed = nil
        guard try await proveUnsubmitted() else { return }
        retryAfter = changed ? now() : now().addingTimeInterval(AutoBoardFunding.retryBackoff)
        try store.release(funding, retryAfter: retryAfter)
    }
    func prepare(allowed: () -> Bool, funding: () async throws -> AutoBoardFunding.Snapshot?,
                 quote: () async throws -> OnchainQuote?, discard: (OnchainQuote) async -> Void,
                 present: (OnchainQuote) -> Void = { _ in }) async throws -> OnchainQuote? {
        guard allowed(), now() >= retryAfter else { return nil }
        do {
            guard let current = try await funding(), allowed() else { return nil }
            guard now() >= (try store.retryDeadline(walletID: current.walletID, now: now())),
                  try store.hasUnhandled(current) else { return nil }
            guard let prepared = try await quote() else { return nil }
            do {
                guard allowed(), prepared.walletID == current.walletID, let newly = try store.claim(current) else {
                    await discard(prepared)
                    return nil
                }
                claimed = (prepared.id, newly, current)
                present(prepared)
                return prepared
            } catch { await discard(prepared); throw error }
        } catch is CancellationError { throw CancellationError() }
        catch { retryAfter = now().addingTimeInterval(30); throw error }
    }
}
