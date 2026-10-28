import Foundation
@testable import Noah
import Testing

@MainActor struct MMKVIntegrityTests {
    @Test func realRN240FixturePinsThe243Layout() throws {
        let root = MMKVRecoveryTests.fixture, id = SettingsStorage.instanceID
        let data = try Data(contentsOf: root.appending(path: id))
        let meta = try Data(contentsOf: root.appending(path: id + ".crc"))
        func u32(_ bytes: Data, _ offset: Int) -> UInt32 {
            (0..<4).reduce(0) { $0 | UInt32(bytes[offset + $1]) << (8 * $1) }
        }
        #expect(u32(data, 0) == 0 && u32(meta, 4) == 4 && u32(meta, 8) == 1)
        #expect(u32(meta, 0) == 0xA896_E632 && u32(meta, 28) == 4157)
        #expect(u32(meta, 32) == 4 && u32(meta, 36) == 0x606E_62B4)
        #expect(MMKVIntegrity.verifies(data: data, meta: meta))
        for offset in [0, 28] {
            var damaged = meta; damaged[offset] ^= 0xFF
            #expect(!MMKVIntegrity.verifies(data: data, meta: damaged))
        }
        #expect(!MMKVIntegrity.verifies(data: data.prefix(4157), meta: meta))
        #expect(!MMKVIntegrity.verifies(data: data, meta: meta.prefix(111)))
        var future = meta; future[4] = 5
        #expect(!MMKVIntegrity.verifies(data: data, meta: future))
    }
    @Test func freshInstallAndInvalidPairPreserveFiles() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "integrity-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let id = SettingsStorage.instanceID
        #expect(MMKVIntegrity.verifies(root: root, store: id))
        let data = try Data(contentsOf: MMKVRecoveryTests.fixture.appending(path: id))
        try data.write(to: root.appending(path: id))
        #expect(!MMKVIntegrity.verifies(root: root, store: id))
        #expect(try Data(contentsOf: root.appending(path: id)) == data)
        #expect(!FileManager.default.fileExists(atPath: root.appending(path: id + ".crc").path))
    }
}
