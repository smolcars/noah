import Foundation
import Testing
@testable import Noah

@MainActor struct MMKVQuarantineTests {
    private func fixture(_ body: (URL) throws -> Void) throws {
        let parent = FileManager.default.temporaryDirectory.appending(path: "quarantine-\(UUID())")
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: parent) }
        try body(parent.appending(path: "mmkv"))
    }
    @Test func markerSurvivesRetriesAndAcknowledgementSyncsDirectory() throws {
        try fixture { root in
            var synced: [URL] = []
            let marker = MMKVQuarantine(root: root, store: SettingsStorage.instanceID, sync: { synced.append($0) })
            marker.recordRecovery(); try marker.requireDurable()
            #expect(try String(contentsOf: marker.file, encoding: .utf8) == "1\nnoah-wallet-storage\n")
            #expect(synced == [marker.file, root.deletingLastPathComponent(), marker.file, root.deletingLastPathComponent()])
            for _ in 0..<2 { #expect(MMKVQuarantine(root: root, store: SettingsStorage.instanceID).exists) }
            try marker.clear()
            #expect(!marker.exists && synced.last == root.deletingLastPathComponent())
        }
    }
    @Test func failedSyncFailsClosed() throws {
        struct SyncFailure: Error {}
        try fixture { root in
            let marker = MMKVQuarantine(root: root, store: SettingsStorage.instanceID, sync: { _ in throw SyncFailure() })
            marker.recordRecovery()
            #expect(marker.writeFailed)
            #expect(throws: (any Error).self) { try marker.requireDurable() }
            #expect(throws: (any Error).self) { try marker.clear() }
            #expect(marker.exists)
        }
    }
    @Test func failedAcknowledgementRetainsMarker() throws {
        struct SyncFailure: Error {}
        try fixture { root in
            var fail = false
            let marker = MMKVQuarantine(root: root, store: SettingsStorage.instanceID, sync: { _ in
                if fail && !FileManager.default.fileExists(atPath: root.path + ".native-quarantine") { throw SyncFailure() }
            })
            marker.recordRecovery(); fail = true
            #expect(throws: (any Error).self) { try marker.clear() }
            #expect(marker.exists)
        }
    }
    @Test func unknownMarkerBlocks() throws {
        try fixture { root in
            let marker = MMKVQuarantine(root: root, store: SettingsStorage.instanceID)
            try Data("future version".utf8).write(to: marker.file)
            #expect(throws: (any Error).self) { try marker.requireDurable() }
        }
    }
}
