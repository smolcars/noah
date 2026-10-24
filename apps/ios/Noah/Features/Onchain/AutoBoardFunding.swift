import BarkBoltFFI
import CryptoKit
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
        /// Decision 8: outputs of exit claims to this phone, per wallet. Optional so earlier ledgers stay readable.
        var claims: [String: Set<String>]?
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
    /// Decision 8: a claim to this phone's on-chain wallet isn't a deposit. Its outputs join the
    /// handled ledger, so auto-board never offers them, and the claims set, so a later deposit's board
    /// leaves their value on-chain. One durable write. On the main actor, with the ledger's other writers.
    @MainActor static func markClaimed(_ outpoints: Set<String>, walletID: String, store: AutoBoardFunding = .init()) throws {
        var saved = try store.load()
        let handled = saved.handled[walletID] ?? [], claims = saved.claims?[walletID] ?? []
        guard !outpoints.isSubset(of: handled) || !outpoints.isSubset(of: claims) else { return }
        saved.handled[walletID] = handled.union(outpoints)
        saved.claims = (saved.claims ?? [:]).merging([walletID: claims.union(outpoints)]) { $1 }
        try store.save(saved)
    }

    func claims(walletID: String) throws -> Set<String> { try load().claims?[walletID] ?? [] }

    /// Exit claims to this phone as auto-board sees them now: their outpoints, and the value of those
    /// still unspent and confirmed, which it leaves on-chain.
    struct Claims: Sendable {
        var outpoints: Set<String> = []
        var held: UInt64 = 0
    }

    /// What auto-board leaves on-chain for exit claims to this phone: those outputs still unspent and
    /// confirmed. Once one is spent it no longer counts.
    static func held(_ claims: Set<String>, utxos: [OnchainUtxo]) -> UInt64 {
        utxos.reduce(0) { total, utxo in
            guard case let .local(point, amount, height) = utxo, height != nil, claims.contains("\(point.txid):\(point.vout)") else { return total }
            return total + amount
        }
    }
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

/// A raw transaction read before it's sent: its id (double SHA-256 of its serialization without
/// witness data, byte-reversed) and its weight. nil when the hex isn't one well-formed transaction.
enum BitcoinTransaction {
    static func txid(hex: String) -> String? { parse(hex: hex)?.txid }

    static func weight(hex: String) -> UInt64? { parse(hex: hex)?.weight }

    static func parse(hex: String) -> (txid: String, weight: UInt64)? {
        guard hex.count % 2 == 0 else { return nil }
        var bytes: [UInt8] = []
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        var at = 0
        func skip(_ count: Int) -> Bool {
            guard count >= 0, at + count <= bytes.count else { return false }
            at += count
            return true
        }
        func varint() -> Int? {
            guard at < bytes.count else { return nil }
            let first = bytes[at]
            at += 1
            if first < 0xfd { return Int(first) }
            let width = first == 0xfd ? 2 : first == 0xfe ? 4 : 8
            guard at + width <= bytes.count else { return nil }
            let value = bytes[at..<at + width].reversed().reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
            at += width
            return value <= UInt64(bytes.count) ? Int(value) : nil
        }
        guard skip(4) else { return nil }
        let segwit = bytes.count > 6 && bytes[4] == 0 && bytes[5] != 0
        if segwit { at += 2 }
        let bodyStart = at
        guard let inputs = varint(), inputs > 0 else { return nil }
        for _ in 0..<inputs {
            guard skip(36), let script = varint(), skip(script), skip(4) else { return nil }
        }
        guard let outputs = varint() else { return nil }
        for _ in 0..<outputs {
            guard skip(8), let script = varint(), skip(script) else { return nil }
        }
        let bodyEnd = at
        if segwit {
            for _ in 0..<inputs {
                guard let items = varint() else { return nil }
                for _ in 0..<items {
                    guard let size = varint(), skip(size) else { return nil }
                }
            }
        }
        guard skip(4), at == bytes.count else { return nil }
        let stripped = Array(bytes[0..<4]) + Array(bytes[bodyStart..<bodyEnd]) + Array(bytes[(bytes.count - 4)...])
        let hash = SHA256.hash(data: Data(SHA256.hash(data: Data(stripped))))
        return (hash.reversed().map { String(format: "%02x", $0) }.joined(), UInt64(stripped.count * 3 + bytes.count))
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
