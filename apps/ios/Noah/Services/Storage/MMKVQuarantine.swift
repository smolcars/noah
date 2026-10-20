import Foundation

/// Written before MMKV rewrites recovered bytes; contains no settings or secrets.
final class MMKVQuarantine: @unchecked Sendable {
    let file: URL
    private let store: String
    private let sync: (URL) throws -> Void
    private(set) var writeFailed = false
    init(root: URL, store: String, sync: @escaping (URL) throws -> Void = RestoreTransaction.fullSync) {
        file = URL(fileURLWithPath: root.path + ".native-quarantine")
        self.store = store; self.sync = sync
    }
    var exists: Bool { FileManager.default.fileExists(atPath: file.path) }
    private var record: Data { Data("1\n\(store)\n".utf8) }
    @discardableResult
    func recordRecovery() -> Bool {
        do {
            try record.write(to: file, options: .atomic)
            try sync(file); try sync(file.deletingLastPathComponent())
        } catch { writeFailed = true }
        return !writeFailed
    }
    func requireDurable() throws {
        guard !writeFailed else { throw SettingsStorage.OpenError() }
        if exists {
            guard try Data(contentsOf: file) == record else { throw SettingsStorage.OpenError() }
            try sync(file); try sync(file.deletingLastPathComponent())
        }
    }
    /// Only validated first-open restoration or a foreground acknowledgement calls this.
    func clear() throws {
        try requireDurable()
        guard exists else { return }
        do {
            try FileManager.default.removeItem(at: file)
            try sync(file.deletingLastPathComponent())
        } catch { recordRecovery(); throw error }
    }
}
