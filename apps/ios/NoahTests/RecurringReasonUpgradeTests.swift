import CryptoKit
import Foundation
import Testing
@testable import Noah

/// Pre-reason-code native rows retain their original digest until replay completes.
@MainActor struct RecurringReasonUpgradeTests {
    private final class Faults: @unchecked Sendable {
        private let lock = NSLock()
        var settingsWrites = 0, ledgerWrites = 0
        var crashSettings = 0, crashLedger = 0
        func settingsCommitted() throws {
            try lock.withLock {
                settingsWrites += 1
                if settingsWrites == crashSettings { throw CocoaError(.fileWriteUnknown) }
            }
        }
        func ledgerCommitted() throws {
            try lock.withLock {
                ledgerWrites += 1
                if ledgerWrites == crashLedger { throw CocoaError(.fileWriteUnknown) }
            }
        }
    }
    @MainActor private final class World {
        let directory: URL
        let settings: SettingsStorage
        let faults = Faults()
        let binding = RecurringStore.Binding(wallet: .init(fingerprint: "wallet", network: "regtest"), instance: UUID())
        var file: URL { directory.appending(path: "journal.json.policies") }
        init() throws {
            SettingsStorage.bootstrap()
            directory = FileManager.default.temporaryDirectory.appending(path: "review10-reason-upgrade-\(UUID())")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            settings = try SettingsStorage(rootPath: directory.appending(path: "settings").path, instanceID: UUID().uuidString)
        }
        func object<T: Encodable>(_ value: T) throws -> [String: Any] {
            try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) as? [String: Any])
        }
        func oldRow(_ row: RecurringPayment) throws -> [String: Any] {
            var result = try object(row); result.removeValue(forKey: "issue"); return result
        }
        func hash(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
        func oldDigest(_ row: RecurringPayment) throws -> String {
            // Match pre-A JSONEncoder(.sortedKeys) byte formatting, not JSONSerialization escaping.
            var old = row; old.issue = nil
            let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
            return hash(try encoder.encode(old))
        }
        func install(_ before: RecurringPayment, paid: RecurringPayment? = nil) throws {
            var savedRow = try oldRow(before)
            // The pre-A settings writer fills these RN nullable fields even though
            // the typed encoder used for ledger hashes omits nil properties.
            for key in ["endAt", "maxOccurrences", "nextRunAt", "lastError", "retryNotBefore", "inFlight", "nativeRevision"] {
                if savedRow[key] == nil { savedRow[key] = NSNull() }
            }
            let root: [String: Any] = ["version": 0, "state": ["nativeWalletBinding": try object(binding), "serverSyncPending": true,
                "schedules": [before.id: savedRow]]]
            try settings.setDurably(String(decoding: JSONSerialization.data(withJSONObject: root), as: UTF8.self), forKey: RecurringStore.key)
            let decoded = try #require(RecurringStore(settings: settings).load().schedules[before.id])
            #expect(!decoded.unsupportedStorage)
            #expect(try oldDigest(decoded) == oldDigest(before))
            let target = paid ?? before
            var entry: [String: Any] = ["binding": try object(binding), "scheduleID": target.id, "generation": 1,
                "status": target.status, "digest": try oldDigest(target)]
            if let revision = target.nativeRevision { entry["revision"] = revision.uuidString }
            if let paid {
                entry["settlementKey"] = UUID().uuidString; entry["pending"] = try oldRow(paid)
                entry["predecessor"] = try oldDigest(before)
            }
            let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
            let key = hash(try encoder.encode(binding)) + ":" + target.id
            try JSONSerialization.data(withJSONObject: ["version": 1, "binding": try object(binding)]).write(to: file.appendingPathExtension("initialized"))
            try JSONSerialization.data(withJSONObject: ["version": 1, "binding": try object(binding), "generation": 1,
                "entries": [key: entry]]).write(to: file)
        }
        func store() -> RecurringStore {
            let settings = settings, faults = faults, directory = directory, owner = binding
            let store = RecurringStore(settings: settings, write: { raw in
                try settings.setDurably(raw, forKey: RecurringStore.key)
                try faults.settingsCommitted()
            })
            store.protectPolicies(file: file, sync: { url in
                if url.path == directory.path { try faults.ledgerCommitted() }
            }, binding: { owner })
            return store
        }
        func cleanup() { try? FileManager.default.removeItem(at: directory) }
    }
    @Test func oldRetryableNativePolicyMigratesOnlyWithMatchingAuthority() throws {
        let w = try World(); defer { w.cleanup() }
        var row = RecurringScheduleTests.schedule(start: 1000)
        row.nativeRevision = UUID()
        row.lastError = "No payment was submitted."; row.retryNotBefore = 901_000
        try w.install(row)
        let loaded = try #require(w.store().load().schedules[row.id])
        #expect(loaded.status == "active" && !loaded.requiresPolicyApproval)
        #expect(loaded.issue?.code == .retryableFailure && loaded.retryNotBefore == 901_000)
    }
    @Test func oldPendingPaidSettlementCanRecoverAndThenBeCancelled() throws {
        let w = try World(); defer { w.cleanup() }
        var row = RecurringScheduleTests.schedule(start: 1000)
        row.nativeRevision = UUID()
        row.lastError = "No payment was submitted."; row.retryNotBefore = 901_000
        let plan = row.plan(now: 1000, ignoreBackoff: true)
        row.inFlight = .init(occurrenceIndex: plan.occurrenceIndex!, startedAt: 1000)
        var paid = row; paid.success(plan, now: 1000)
        try w.install(row, paid: paid)
        let restarted = w.store()
        #expect(try restarted.load().schedules[row.id]?.occurrencesPaid == 1)
        try restarted.remove(row.id)
        #expect(try restarted.load().schedules.isEmpty)
    }
    @Test(arguments: ["settings1", "ledger1", "ledger2", "settings2", "ledger3"])
    func processDeathAtEveryReplayAndFormatCommitIsIdempotent(boundary: String) throws {
        let w = try World(); defer { w.cleanup() }
        var row = RecurringScheduleTests.schedule(start: 1000)
        row.nativeRevision = UUID(); row.lastError = "No payment was submitted."; row.retryNotBefore = 901_000
        let plan = row.plan(now: 1000, ignoreBackoff: true)
        row.inFlight = .init(occurrenceIndex: plan.occurrenceIndex!, startedAt: 1000)
        var paid = row; paid.success(plan, now: 1000)
        try w.install(row, paid: paid)
        let oldSettings = try #require(w.settings.string(forKey: RecurringStore.key))
        if boundary.hasPrefix("settings") { w.faults.crashSettings = Int(boundary.suffix(1))! }
        else { w.faults.crashLedger = Int(boundary.suffix(1))! }
        #expect(throws: (any Error).self) { _ = try w.store().load() }
        w.faults.crashSettings = 0; w.faults.crashLedger = 0
        for _ in 0..<3 {
            let loaded = try #require(w.store().load().schedules[row.id])
            #expect(loaded.occurrencesPaid == 1 && loaded.runs.filter { $0.status == "success" }.count == 1)
            #expect(loaded.inFlight == nil && !loaded.requiresPolicyApproval)
        }
        let complete = try #require(w.settings.string(forKey: RecurringStore.key))
        try w.settings.setDurably(oldSettings, forKey: RecurringStore.key)
        let stopped = try #require(w.store().load().schedules[row.id])
        #expect(stopped.requiresPolicyApproval && stopped.occurrencesPaid == 0)
        try w.settings.setDurably(complete, forKey: RecurringStore.key)
        try w.store().remove(row.id)
        #expect(try w.store().load().schedules.isEmpty)
    }
    @Test(arguments: ["predecessor", "pending", "version", "wallet"])
    func mismatchedPendingEvidenceFailsClosed(damage: String) throws {
        let w = try World(); defer { w.cleanup() }
        var row = RecurringScheduleTests.schedule(start: 1000); row.nativeRevision = UUID()
        let plan = row.plan(now: 1000)
        row.inFlight = .init(occurrenceIndex: plan.occurrenceIndex!, startedAt: 1000)
        var paid = row; paid.success(plan, now: 1000)
        try w.install(row, paid: paid)
        var root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: w.file)) as? [String: Any])
        var entries = try #require(root["entries"] as? [String: [String: Any]])
        let key = try #require(entries.keys.first)
        var entry = try #require(entries[key])
        switch damage {
        case "predecessor": entry["predecessor"] = String(repeating: "0", count: 64)
        case "pending":
            var target = try #require(entry["pending"] as? [String: Any]); target["amountSat"] = 999; entry["pending"] = target
        case "wallet":
            var owner = w.binding; owner.instance = UUID(); entry["binding"] = try w.object(owner)
        default: entry["digestVersion"] = 99
        }
        entries[key] = entry; root["entries"] = entries
        try JSONSerialization.data(withJSONObject: root).write(to: w.file)
        let original = w.settings.string(forKey: RecurringStore.key)
        #expect(throws: (any Error).self) { _ = try w.store().load() }
        #expect(w.settings.string(forKey: RecurringStore.key) == original)
    }
    @Test func everyReasonRendersInEveryLanguage() {
        let codes: [RecurringIssue.Code] = [.policyEvidence, .automationStopped, .legacyHistory, .invalidJournal, .interrupted,
            .tooManyMissed, .quoteExceedsCap, .scheduleExceedsCap, .arkFee, .uncertain, .retryableFailure, .alreadyPaid,
            .unsupported, .amountLimit, .unknown]
        for language in ["en", "es", "ja", "ko", "sv"] {
            for code in codes {
                let args = code == .quoteExceedsCap || code == .scheduleExceedsCap ? ["500", "200"] : []
                let issue = RecurringIssue(code: code, arguments: args), rendered = issue.message(locale: language)
                #expect(issue.valid && !rendered.isEmpty)
                #expect(!rendered.contains("{") && !rendered.contains("%1$") && !rendered.contains("%2$"))
            }
        }
    }
}
