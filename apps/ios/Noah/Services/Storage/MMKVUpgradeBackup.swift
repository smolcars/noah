import Foundation

/// Immutable pre-native bytes; the separate retirement record governs automatic restoration.
struct MMKVUpgradeBackup {
    let root: URL
    let instanceID: String
    var directory: URL { URL(fileURLWithPath: root.path + ".native-backup", isDirectory: true) }
    var retirementFile: URL { URL(fileURLWithPath: root.path + ".native-retired") }
    var retiredKeys: [String]? {
        get throws {
            guard FileManager.default.fileExists(atPath: retirementFile.path) else { return nil }
            return try JSONDecoder().decode([String].self, from: Data(contentsOf: retirementFile))
        }
    }

    /// This admission record outlives the support snapshot and is durable before any native write.
    func retire(keys: [String]) throws {
        let keys = keys.sorted()
        if try retiredKeys != keys {
            try JSONEncoder().encode(keys).write(to: retirementFile, options: .atomic)
        }
        try RestoreTransaction.fullSync(retirementFile)
        try RestoreTransaction.fullSync(retirementFile.deletingLastPathComponent())
    }

    private var names: [String] { [instanceID, instanceID + ".crc"] }
    private struct Manifest: Codable { let present: Bool; let actualSize: UInt32 }

    func prepare() throws {
        if try retiredKeys != nil {
            try RestoreTransaction.fullSync(retirementFile)
            try RestoreTransaction.fullSync(retirementFile.deletingLastPathComponent())
            return
        }
        let fm = FileManager.default
        if !fm.fileExists(atPath: directory.path) {
            let staging = URL(fileURLWithPath: directory.path + ".pending", isDirectory: true)
            if fm.fileExists(atPath: staging.path) { try fm.removeItem(at: staging) }
            try fm.createDirectory(at: staging, withIntermediateDirectories: true)
            try excludeFromBackup(staging)
            let present = names.map { fm.fileExists(atPath: root.appending(path: $0).path) }
            guard present[0] == present[1] else { throw SettingsStorage.OpenError() }
            var size: UInt32 = 0
            if present[0] {
                for name in names {
                    let source = root.appending(path: name), target = staging.appending(path: name)
                    try fm.copyItem(at: source, to: target)
                    try RestoreTransaction.fullSync(target)
                }
                let data = try Data(contentsOf: staging.appending(path: instanceID))
                let meta = try Data(contentsOf: staging.appending(path: instanceID + ".crc"))
                // MMKV 2.4.x: header actualSize, meta actualSize and last-confirmed actualSize.
                guard data.count >= 4, meta.count >= 36 else { throw SettingsStorage.OpenError() }
                size = max(Self.uint32(data, 0), Self.uint32(meta, 28), Self.uint32(meta, 32))
            }
            try JSONEncoder().encode(Manifest(present: present[0], actualSize: size))
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
        // Resume an interrupted restore before MMKV maps either file.
        if exists("restore-pending") { try restore() }
    }

    var hasContent: Bool {
        get throws { try retiredKeys == nil && manifest().actualSize > 4 && !exists("reset") }
    }

    var restored: Bool { exists("restored") }

    func restore() throws {
        guard try retiredKeys == nil, try manifest().present else { throw SettingsStorage.OpenError() }
        try mark("restore-pending")
        for name in names {
            let target = root.appending(path: name)
            try Data(contentsOf: directory.appending(path: name)).write(to: target, options: .atomic)
            try RestoreTransaction.fullSync(target)
        }
        try RestoreTransaction.fullSync(root)
        try mark("restored")
        try FileManager.default.removeItem(at: directory.appending(path: "restore-pending"))
        try RestoreTransaction.fullSync(directory)
    }

    /// A deliberate wallet deletion must never resurrect the old settings.
    func markReset() throws { try retire(keys: []) }

    /// Called only after local wallet deletion has cleared and synced live settings.
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
        if value.present {
            for name in names where !FileManager.default.fileExists(atPath: directory.appending(path: name).path) {
                throw SettingsStorage.OpenError()
            }
        }
        return value
    }

    private func mark(_ name: String) throws {
        let file = directory.appending(path: name)
        try Data([1]).write(to: file, options: .atomic)
        try RestoreTransaction.fullSync(file)
        try RestoreTransaction.fullSync(directory)
    }

    private func exists(_ name: String) -> Bool {
        FileManager.default.fileExists(atPath: directory.appending(path: name).path)
    }

    private static func uint32(_ data: Data, _ offset: Int) -> UInt32 {
        (0..<4).reduce(0) { $0 | UInt32(data[offset + $1]) << ($1 * 8) }
    }
}
