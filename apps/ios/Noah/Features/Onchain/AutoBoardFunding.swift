import BarkBoltFFI
import Foundation

/// Persist before presentation so every end to a review, including process death, consumes its funding.
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
