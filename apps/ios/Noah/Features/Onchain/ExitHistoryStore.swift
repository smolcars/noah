import Foundation

/// Bark's active list drops canceled exits and reopens only live ones; retain discovery per wallet.
actor ExitHistoryStore {
    static let shared = ExitHistoryStore(file: WalletDataLocation(variant: .current).directory.appending(
        path: "noah-exit-history.json"))
    private let file: URL
    init(file: URL) { self.file = file }

    private func load() throws -> [String: [String]] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [:] }
        return try JSONDecoder().decode([String: [String]].self, from: Data(contentsOf: file))
    }
    func ids(walletID: String) throws -> [String] { try load()[walletID] ?? [] }
    func remember(_ ids: [String], walletID: String) throws {
        var values = try load()
        let merged = Set((values[walletID] ?? []) + ids).sorted()
        guard values[walletID] != merged else { return }
        values[walletID] = merged
        try JSONEncoder().encode(values).write(
            to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var url = file
        var attributes = URLResourceValues()
        attributes.isExcludedFromBackup = true
        try url.setResourceValues(attributes)
    }
}
