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
}
