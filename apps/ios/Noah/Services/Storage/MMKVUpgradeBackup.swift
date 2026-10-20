import Foundation

/// Immutable pre-native bytes, used only for the one-time best-effort import.
struct MMKVUpgradeBackup {
    let root: URL
    let instanceID: String
    var directory: URL { URL(fileURLWithPath: root.path + ".native-backup", isDirectory: true) }
    private var names: [String] { [instanceID, instanceID + ".crc"] }
    private struct Manifest: Codable { let present: Bool }

    func prepare() throws {
        let fm = FileManager.default
        if !fm.fileExists(atPath: directory.path) {
            let staging = URL(fileURLWithPath: directory.path + ".pending", isDirectory: true)
            if fm.fileExists(atPath: staging.path) { try fm.removeItem(at: staging) }
            try fm.createDirectory(at: staging, withIntermediateDirectories: true)
            try excludeFromBackup(staging)
            let present = names.map { fm.fileExists(atPath: root.appending(path: $0).path) }
            if present.contains(true) {
                for name in names {
                    let source = root.appending(path: name), target = staging.appending(path: name)
                    guard fm.fileExists(atPath: source.path) else { continue }
                    try fm.copyItem(at: source, to: target)
                    try RestoreTransaction.fullSync(target)
                }
            }
            try JSONEncoder().encode(Manifest(present: present.contains(true)))
                .write(to: staging.appending(path: "manifest.json"))
            try RestoreTransaction.fullSync(staging.appending(path: "manifest.json"))
            try RestoreTransaction.fullSync(staging)
            try fm.moveItem(at: staging, to: directory)
            try RestoreTransaction.fullSync(directory.deletingLastPathComponent())
        }
        try excludeFromBackup(directory)
        _ = try manifest()
        try RestoreTransaction.fullSync(directory)
        try RestoreTransaction.fullSync(directory.deletingLastPathComponent())
    }

    var hasContent: Bool { get throws { try manifest().present } }

    /// MMKV only maps this disposable copy of the immutable pair.
    func inspectSnapshot<T>(_ read: (URL) throws -> T) throws -> T {
        let fm = FileManager.default
        let inspection = directory.appending(path: "inspection", directoryHint: .isDirectory)
        if fm.fileExists(atPath: inspection.path) { try fm.removeItem(at: inspection) }
        try fm.createDirectory(at: inspection, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: inspection) }
        for name in names where fm.fileExists(atPath: directory.appending(path: name).path) {
            try fm.copyItem(at: directory.appending(path: name), to: inspection.appending(path: name))
        }
        return try read(inspection)
    }

    /// Called after local wallet deletion. The native store remains the import marker.
    func deleteSnapshot() throws {
        let fm = FileManager.default
        for url in [directory, URL(fileURLWithPath: directory.path + ".pending")] {
            if fm.fileExists(atPath: url.path) { try fm.removeItem(at: url) }
        }
        try RestoreTransaction.fullSync(directory.deletingLastPathComponent())
    }

    private func excludeFromBackup(_ url: URL) throws {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }

    private func manifest() throws -> Manifest {
        let value = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: directory.appending(path: "manifest.json")))
        return value
    }

}
