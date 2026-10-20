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

    func load() throws -> State {
        let data = try Data(contentsOf: file)
        guard let raw = String(data: data, encoding: .utf8) else { throw SettingsStorage.OpenError() }
        let object = try RawObject(raw)
        guard Set(object.fields.keys) == ["version", "payload", "sha256"],
              object.fields["version"]?.trimmingCharacters(in: .whitespacesAndNewlines) == "1" else { throw SettingsStorage.OpenError() }
        let envelope = try JSONDecoder().decode(Envelope.self, from: data)
        guard envelope.sha256 == Self.digest(envelope.payload) else { throw SettingsStorage.OpenError() }
        let payload = try RawObject(envelope.payload)
        guard Set(payload.fields.keys) == ["values", "settingsNoticePending", "recurringNoticePending", "cleanupPending"] else { throw SettingsStorage.OpenError() }
        let state = try JSONDecoder().decode(State.self, from: Data(envelope.payload.utf8))
        return state
    }

    func save(_ state: State) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let payload = String(decoding: try encoder.encode(state), as: UTF8.self)
        let data = try encoder.encode(Envelope(version: 1, payload: payload, sha256: Self.digest(payload)))
        let pending = file.appendingPathExtension("pending")
        try data.write(to: pending)
        try step("write")
        try sync(pending)
        try step("fileSync")
        guard rename(pending.path, file.path) == 0 else { throw POSIXError(.EIO) }
        try step("rename")
        try sync(file.deletingLastPathComponent())
        try step("directorySync")
    }

    private static func digest(_ payload: String) -> String {
        SHA256.hash(data: Data(payload.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
