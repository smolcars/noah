import Foundation
import Testing

@testable import Noah

@MainActor
struct SettingsStorageTests {
    init() {
        SettingsStorage.bootstrap()
    }

    private let rootPath = FileManager.default.temporaryDirectory
        .appending(path: "mmkv-tests-\(UUID().uuidString)").path

    @Test func decodesZustandEnvelopeWrittenByReactNative() throws {
        let storage = try SettingsStorage(rootPath: rootPath)
        // Exactly what zustand persist + createJSONStorage writes.
        storage.set(
            #"{"state":{"isInitialized":true,"isWalletLoaded":true,"staticVtxoPubkey":null,"isBiometricsEnabled":true,"isDebugModeEnabled":false,"isWalletSuspended":false,"hasSeenBatteryOptimizationPrompt":true},"version":0}"#,
            forKey: PersistedWalletState.key)

        let state = try #require(storage.persistedState(PersistedWalletState.self, key: PersistedWalletState.key))
        #expect(state.isInitialized == true)
        #expect(state.isBiometricsEnabled == true)
        #expect(state.staticVtxoPubkey == nil)
        #expect(state.hasSeenBatteryOptimizationPrompt == true)
    }

    @Test func toleratesMissingFieldsAndBadJSON() throws {
        let storage = try SettingsStorage(rootPath: rootPath)
        storage.set(#"{"state":{"isInitialized":true}}"#, forKey: "partial")
        #expect(storage.persistedState(PersistedWalletState.self, key: "partial")?.isInitialized == true)
        storage.set("not json", forKey: "broken")
        #expect(storage.persistedState(PersistedWalletState.self, key: "broken") == nil)
        #expect(storage.persistedState(PersistedWalletState.self, key: "absent") == nil)
    }

    @Test func roundTripsAndPersistsAcrossInstances() throws {
        let state = PersistedWalletState(isInitialized: true, isBiometricsEnabled: false)
        try SettingsStorage(rootPath: rootPath)
            .setPersistedState(state, key: PersistedWalletState.key, version: PersistedWalletState.version)

        let reopened = try SettingsStorage(rootPath: rootPath)
        #expect(reopened.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) == state)
        let raw = try #require(reopened.string(forKey: PersistedWalletState.key))
        #expect(raw.contains(#""version":0"#))
    }

    @Test func repeatedReadsComeFromMemoryAndMatchTheFile() throws {
        let storage = try SettingsStorage(rootPath: rootPath)
        try storage.setDurably("1", forKey: "a")
        try storage.setDurably("2", forKey: "b")
        let disk = try storage.nativeFile.load().values
        #expect(storage.string(forKey: "a") == disk["a"] && storage.string(forKey: "b") == disk["b"])
        // chmod moves neither size nor modification time: an unreadable file still reads, from memory.
        let file = storage.nativeFile.file
        #expect(chmod(file.path, 0) == 0)
        defer { chmod(file.path, 0o600) }
        #expect(storage.string(forKey: "a") == "1")
        #expect(try SettingsStorage(rootPath: rootPath).string(forKey: "b") == "2")
    }

    @Test func aWriteIsSeenAtOnceByEveryInstance() throws {
        let storage = try SettingsStorage(rootPath: rootPath)
        let other = try SettingsStorage(rootPath: rootPath)
        #expect(other.string(forKey: "k") == nil)
        try storage.setDurably("v1", forKey: "k")
        #expect(storage.string(forKey: "k") == "v1")
        #expect(other.string(forKey: "k") == "v1")
        other.remove(forKey: "k")
        #expect(storage.string(forKey: "k") == nil)
        #expect(try storage.nativeFile.load().values["k"] == nil)
    }

    @Test func aWriteOutsideTheStorageOrDamageIsNoticed() throws {
        let storage = try SettingsStorage(rootPath: rootPath)
        try storage.setDurably("cached", forKey: "k")
        #expect(storage.string(forKey: "k") == "cached")
        try storage.nativeFile.save(.init(values: ["k": "outside"]))
        #expect(storage.string(forKey: "k") == "outside")
        // A damaged file still resets to defaults with the notice, as when every read went to disk.
        try Data("damaged generation".utf8).write(to: storage.nativeFile.file)
        #expect(storage.string(forKey: "k") == nil)
        #expect(try storage.takeUpgradeNotices() == [SettingsStorage.settingsNotice])
    }

    /// Another writer racing a save: writing into our pending file before its rename (it shares the path), or
    /// replacing the committed file after it. Reads and the next write follow the disk; our values are never
    /// cached under the other writer's stamp.
    @Test(arguments: ["write", "rename", "directorySync"])
    func aConcurrentReplacementDuringASaveIsNeverCachedAsOurs(window: String) throws {
        final class Race { var armed = false }
        let directory = FileManager.default.temporaryDirectory.appending(path: "settings-race-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let root = directory.appending(path: "mmkv")
        let file = URL(fileURLWithPath: root.path + ".native-settings.json")
        let theirs = NativeSettingsFile.State(values: ["k": "theirs"])
        let race = Race()
        let storage = try SettingsStorage(rootPath: root.path, step: { step in
            guard race.armed, step == window else { return }
            race.armed = false
            if step == "write" {
                let scratch = NativeSettingsFile(file: directory.appending(path: "scratch.json"))
                try scratch.save(theirs)
                try Data(contentsOf: scratch.file).write(to: file.appendingPathExtension("pending"))
            } else {
                try NativeSettingsFile(file: file).save(theirs)
            }
        })
        race.armed = true
        try storage.setDurably("ours", forKey: "k")
        #expect(try NativeSettingsFile(file: file).load() == theirs)
        #expect(storage.string(forKey: "k") == "theirs")
        try storage.setDurably("1", forKey: "next")
        #expect(try NativeSettingsFile(file: file).load().values == ["k": "theirs", "next": "1"])
    }

    @Test func aFailedWriteKeepsTheCommittedValue() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "settings-cache-\(UUID())")
        defer { chmod(directory.path, 0o700); try? FileManager.default.removeItem(at: directory) }
        let storage = try SettingsStorage(rootPath: directory.appending(path: "mmkv").path)
        try storage.setDurably("kept", forKey: "k")
        #expect(chmod(directory.path, 0o500) == 0)
        #expect(throws: (any Error).self) { try storage.setDurably("lost", forKey: "k") }
        #expect(storage.string(forKey: "k") == "kept")
        #expect(chmod(directory.path, 0o700) == 0)
        #expect(storage.string(forKey: "k") == "kept")
        #expect(try storage.nativeFile.load().values["k"] == "kept")
    }
}
