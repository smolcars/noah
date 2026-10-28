import Foundation
@testable import Noah
import Testing

struct NativeSettingsFileTests {
    @Test func interruptionsOnBothSidesOfRenameKeepOneWholeGeneration() throws {
        struct Interrupted: Error {}
        for existing in [false, true] {
            for stop in ["write", "fileSync", "rename", "directorySync"] {
                let directory = FileManager.default.temporaryDirectory.appending(path: "native-atomic-\(UUID())")
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                defer { try? FileManager.default.removeItem(at: directory) }
                let file = directory.appending(path: "settings.json")
                let old = NativeSettingsFile.State(values: ["key": "old"])
                let next = NativeSettingsFile.State(values: ["key": "new"], settingsNoticePending: true)
                if existing { try NativeSettingsFile(file: file).save(old) }
                var steps: [String] = []
                let writer = NativeSettingsFile(file: file, sync: { url in
                    steps.append(url.path == directory.path ? "syncDirectory" : "syncFile")
                    try RestoreTransaction.fullSync(url)
                }, step: { step in steps.append(step); if step == stop { throw Interrupted() } })
                #expect(throws: Interrupted.self) { try writer.save(next) }
                if stop == "rename" || stop == "directorySync" { #expect(try NativeSettingsFile(file: file).load() == next) }
                else if existing { #expect(try NativeSettingsFile(file: file).load() == old) }
                else { #expect(!writer.exists) }
                #expect(steps == Array(["write", "syncFile", "fileSync", "rename", "syncDirectory", "directorySync"].prefix(steps.count)))
            }
        }
    }
    @Test func strictDecodingRejectsDamage() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "native-strict-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appending(path: "settings.json"), state = NativeSettingsFile.State(values: ["raw": "日本語"])
        let store = NativeSettingsFile(file: file)
        try store.save(state); #expect(try store.load() == state)
        let raw = try String(contentsOf: file, encoding: .utf8)
        for bad in ["{}", raw.replacingOccurrences(of: "\"version\":1", with: "\"version\":1.0"), raw.replacingOccurrences(of: "日本語", with: "changed")] {
            try Data(bad.utf8).write(to: file)
            #expect(throws: (any Error).self) { try store.load() }
        }
        try Data([0xC3, 0x28]).write(to: file)
        #expect(throws: (any Error).self) { try store.load() }
    }
}
