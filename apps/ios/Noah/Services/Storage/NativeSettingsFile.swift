import CryptoKit
import Darwin
import Foundation

/// One committed generation. Pending files are never read or used as a fallback.
struct NativeSettingsFile {
    struct State: Codable, Equatable {
        var values: [String: String] = [:]
        var settingsNoticePending = false
        var recurringNoticePending = false
        var cleanupPending = false
    }
    private struct Envelope: Codable { let version: Int; let payload: String; let sha256: String }
    let file: URL
    var sync: (URL) throws -> Void = RestoreTransaction.fullSync
    // Fault injection after each filesystem step, including both sides of rename.
    var step: (String) throws -> Void = { _ in }
    var exists: Bool { FileManager.default.fileExists(atPath: file.path) }

    /// Which bytes are on disk, from one `stat`: every save renames a new file in, and any
    /// other write changes the size or modification time.
    struct Stamp: Equatable {
        let inode: UInt64, size: Int64, seconds: Int, nanoseconds: Int
        init(_ info: stat) {
            inode = info.st_ino; size = info.st_size; seconds = info.st_mtimespec.tv_sec; nanoseconds = info.st_mtimespec.tv_nsec
        }
        init?(descriptor: Int32) {
            var info = stat()
            guard fstat(descriptor, &info) == 0 else { return nil }
            self.init(info)
        }
    }
    var stamp: Stamp? {
        var info = stat()
        guard stat(file.path, &info) == 0 else { return nil }
        return Stamp(info)
    }

    /// The stamp of `url` if it holds exactly `data`, read through one descriptor and unchanged meanwhile.
    /// Nil when another writer shares the pending path, so its bytes are never cached as ours.
    private static func stamp(of url: URL, holding data: Data) -> Stamp? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard let before = Stamp(descriptor: handle.fileDescriptor), (try? handle.readToEnd()) ?? Data() == data,
              Stamp(descriptor: handle.fileDescriptor) == before else { return nil }
        return before
    }

    func load() throws -> State {
        let data = try Data(contentsOf: file)
        guard let raw = String(data: data, encoding: .utf8) else { throw SettingsStorage.OpenError() }
        let object = try RawObject(raw)
        guard Set(object.fields.keys) == ["version", "payload", "sha256"],
              object.fields["version"]?.trimmingCharacters(in: .whitespacesAndNewlines) == "1" else { throw SettingsStorage.OpenError() }
        let envelope = try JSONDecoder().decode(Envelope.self, from: data)
        guard envelope.sha256 == Self.digest(envelope.payload) else { throw SettingsStorage.OpenError() }
        let payload = try RawObject(envelope.payload)
        guard Set(payload.fields.keys) == ["values", "settingsNoticePending", "recurringNoticePending", "cleanupPending"]
        else { throw SettingsStorage.OpenError() }
        return try JSONDecoder().decode(State.self, from: Data(envelope.payload.utf8))
    }

    /// Commits one generation and returns its stamp, taken from the pending file before the rename (which
    /// keeps its inode and modification time): never a later `stat`, which could see another writer's file.
    @discardableResult
    func save(_ state: State) throws -> Stamp? {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let payload = try String(json: encoder.encode(state))
        let data = try encoder.encode(Envelope(version: 1, payload: payload, sha256: Self.digest(payload)))
        let pending = file.appendingPathExtension("pending")
        try data.write(to: pending)
        try step("write")
        try sync(pending)
        try step("fileSync")
        let stamp = Self.stamp(of: pending, holding: data)
        guard rename(pending.path, file.path) == 0 else { throw POSIXError(.EIO) }
        try step("rename")
        try sync(file.deletingLastPathComponent())
        try step("directorySync")
        return stamp
    }

    private static func digest(_ payload: String) -> String {
        SHA256.hash(data: Data(payload.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
