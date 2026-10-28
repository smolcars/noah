import Foundation
@testable import Noah
import Testing

@MainActor struct RecurringPolicyLedgerTests {
    @MainActor private final class World {
        let directory: URL
        let settings: SettingsStorage
        let binding = RecurringStore.Binding(wallet: .init(fingerprint: "wallet", network: "regtest"), instance: UUID())
        var file: URL { directory.appending(path: "journal.json.policies") }
        var marker: URL { file.appendingPathExtension("initialized") }
        var policy: RecurringPayment { RecurringScheduleTests.schedule(start: 1000) }
        init() throws {
            SettingsStorage.bootstrap()
            directory = FileManager.default.temporaryDirectory.appending(path: "policy-ledger-\(UUID())")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            settings = try SettingsStorage(rootPath: directory.appending(path: "mmkv").path, instanceID: UUID().uuidString)
            let store = RecurringStore(settings: settings)
            try store.bind(binding); try store.upsert(policy)
        }
        func store(protected: Bool = true, owner: RecurringStore.Binding? = nil, write: (@Sendable (String) throws -> Void)? = nil) -> RecurringStore {
            let store = RecurringStore(settings: settings, write: write)
            let owner = owner ?? binding
            if protected { store.protectPolicies(file: file, binding: { owner }) }
            return store
        }
        func cleanup() { try? FileManager.default.removeItem(at: directory) }
    }
    @Test func bootstrapIsDurableOnceAndRetainsRNEnvelope() throws {
        let w = try World(); defer { w.cleanup() }
        let original = w.settings.string(forKey: RecurringStore.key)
        #expect(try w.store().load().schedules[w.policy.id]?.status == "active")
        let bytes = try Data(contentsOf: w.file), marker = try Data(contentsOf: w.marker)
        #expect(w.settings.string(forKey: RecurringStore.key) == original)
        _ = try w.store().load()
        #expect(try Data(contentsOf: w.file) == bytes)
        #expect(try Data(contentsOf: w.marker) == marker)
        var unknown = w.policy; unknown.id = "resurrected"
        try w.store(protected: false).upsert(unknown)
        var stopped = try #require(w.store().load().schedules[unknown.id])
        #expect(stopped.status == "needs_attention")
        #expect(stopped.requiresPolicyApproval)
        stopped.lastError = "Translated or revised display copy"
        #expect(stopped.requiresPolicyApproval)
        stopped.stopReason = nil
        #expect(stopped.requiresPolicyApproval)
        stopped.issue = nil
        #expect(!stopped.requiresPolicyApproval)
        #expect(try Data(contentsOf: w.file) == bytes)
    }
    @Test(arguments: ["missing", "corrupt", "marker-missing", "marker-corrupt"])
    func missingCorruptEvidenceCannotReseedOrMutate(damage: String) throws {
        let w = try World(); defer { w.cleanup() }
        _ = try w.store().load()
        switch damage {
        case "missing": try FileManager.default.removeItem(at: w.file)
        case "corrupt": try Data("{".utf8).write(to: w.file)
        case "marker-missing": try FileManager.default.removeItem(at: w.marker)
        default: try Data("{".utf8).write(to: w.marker)
        }
        let store = w.store(), before = w.settings.string(forKey: RecurringStore.key)
        #expect(try store.load().schedules[w.policy.id]?.status == "needs_attention")
        var paused = w.policy; paused.status = "paused"
        #expect(throws: (any Error).self) { try store.upsert(paused) }
        #expect(w.settings.string(forKey: RecurringStore.key) == before)
    }
    @Test func interruptedBootstrapCannotImportTwice() throws {
        let w = try World(); defer { w.cleanup() }
        try RecurringStore.durableWrite(JSONEncoder().encode(RecurringPolicyLedger.Marker(version: 1, binding: w.binding)), to: w.marker)
        #expect(try w.store().load().schedules[w.policy.id]?.status == "needs_attention")
        #expect(!FileManager.default.fileExists(atPath: w.file.path))
    }
    @Test func ledgerPrecedesMMKVAndInterruptedCommitStopsOldPolicy() throws {
        let w = try World(); defer { w.cleanup() }
        _ = try w.store().load()
        let before = w.settings.string(forKey: RecurringStore.key), file = w.file, id = w.policy.id
        let store = w.store(write: { _ in
            let root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
            let entries = try #require(root["entries"] as? [String: [String: Any]])
            #expect(entries.values.first?["status"] as? String == "paused")
            throw CocoaError(.fileWriteUnknown) // Process stops before MMKV commits.
        })
        var paused = w.policy; paused.status = "paused"
        #expect(throws: (any Error).self) { try store.upsert(paused) }
        #expect(w.settings.string(forKey: RecurringStore.key) == before)
        #expect(try w.store().load().schedules[id]?.status == "needs_attention")
        #expect(throws: (any Error).self) { try w.store().upsert(w.policy) }
    }
    @Test func MMKVAheadOfLedgerAlsoStopsAndLedgerFailureNeverWritesMMKV() throws {
        let w = try World(); defer { w.cleanup() }
        let store = w.store(); _ = try store.load()
        var changed = w.policy; changed.amountSat = 500; changed.nativeRevision = UUID()
        try w.store(protected: false).upsert(changed)
        #expect(try w.store().load().schedules[changed.id]?.status == "needs_attention")
        try Data("{".utf8).write(to: w.file)
        let before = w.settings.string(forKey: RecurringStore.key)
        #expect(throws: (any Error).self) { try store.upsert(changed) }
        #expect(w.settings.string(forKey: RecurringStore.key) == before)
    }
    @Test func cancellationWithMissingMMKVRowStillWritesTombstone() throws {
        let w = try World(); defer { w.cleanup() }
        let store = w.store(); _ = try store.load()
        let active = try #require(w.settings.string(forKey: RecurringStore.key))
        try w.store(protected: false).remove(w.policy.id)
        try store.remove(w.policy.id)
        try w.settings.setDurably(active, forKey: RecurringStore.key)
        #expect(try w.store().load().schedules[w.policy.id]?.status == "needs_attention")
    }

    @Test func tombstoneSurvivesReopenAndNewBindingCannotInherit() throws {
        let w = try World(); defer { w.cleanup() }
        let store = w.store(); _ = try store.load()
        let original = try #require(w.settings.string(forKey: RecurringStore.key))
        try store.remove(w.policy.id)
        try w.settings.setDurably(original, forKey: RecurringStore.key)
        #expect(try w.store().load().schedules[w.policy.id]?.status == "needs_attention")
        #expect(throws: (any Error).self) { try w.store().upsert(w.policy) }
        try store.remove(w.policy.id)
        #expect(try w.store().load().schedules.isEmpty)
        try w.settings.setDurably(original, forKey: RecurringStore.key)
        var other = w.binding; other.instance = UUID()
        try w.store(protected: false).bind(other)
        #expect(try w.store(owner: other).load().schedules[w.policy.id]?.status == "needs_attention")
    }
}
