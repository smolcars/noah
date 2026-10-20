import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringJournalValidationTests {
    @Test func malformedConsumedIndicesMustStopExecution() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        var schedule = RecurringScheduleTests.schedule(start: 1_772_337_600_000)
        schedule.destinationType = "ark"; schedule.destination = "ark-test-destination"
        h.zone.withLock { $0 = TimeZone(identifier: "America/New_York")! }
        h.clock.withLock { $0 = 1_774_753_200_000 }
        try h.store.upsert(schedule); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        var raw = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: h.journal.file)) as? [String: Any])
        var scopes = try #require(raw["consumedIndices"] as? [[String: Any]])
        scopes[0]["indices"] = [-1]; raw["consumedIndices"] = scopes
        let bytes = try JSONSerialization.data(withJSONObject: raw)
        try bytes.write(to: h.journal.file)
        try h.store.upsert(schedule)
        h.zone.withLock { $0 = .gmt }; h.clock.withLock { $0 = 1_775_016_000_000 }
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try Data(contentsOf: h.journal.file) == bytes)
        #expect(try Data(contentsOf: h.journal.quarantineFile) == bytes)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
    }

    @Test func revisionAFailureInBThenZoneRollbackMustStayConsumed() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        var a = RecurringScheduleTests.schedule(start: 1_772_337_600_000)
        a.destinationType = "ark"; a.destination = "ark-test-destination"; a.nativeRevision = UUID()
        h.zone.withLock { $0 = TimeZone(identifier: "America/New_York")! }
        h.clock.withLock { $0 = 1_774_753_200_000 }
        try h.store.upsert(a); await h.executor.execute()
        var b = a; b.startAt = h.clock.withLock { $0 } + 1; b.nextRunAt = b.startAt
        try await h.executor.save(b, editing: true)
        h.clock.withLock { $0 += 1 }
        h.beforePay = { throw SendNotSubmitted(reason: .recurringStopped) }
        await h.executor.execute()
        #expect(try h.journal.load()[a.id]?.resolved == true)
        #expect(try h.journal.load()[a.id]?.consumed != true)
        try h.store.upsert(a)
        h.zone.withLock { $0 = .gmt }; h.clock.withLock { $0 = 1_775_016_000_000 }
        h.beforePay = nil; h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[a.id]?.status == "needs_attention")
    }
    @Test(arguments: ["negative", "duplicate", "unsorted", "empty", "scope", "wallet", "instance", "revision", "attemptID", "attemptKind", "attemptIndex", "attemptDate", "unresolvedConsumed", "missingIndices", "missingWatermark", "missingField", "legacy"])
    func malformedEvidenceIsQuarantinedBeforeReadsOrWrites(kind: String) async throws {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        await h.executor.execute()
        var raw = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: h.journal.file)) as? [String: Any])
        var indices = try #require(raw["consumedIndices"] as? [[String: Any]])
        var attempts = try #require(raw["attempts"] as? [String: [String: Any]])
        var entry = try #require(attempts[schedule.id])
        switch kind {
        case "negative": indices[0]["indices"] = [-1]
        case "duplicate": indices[0]["indices"] = [4, 4]
        case "unsorted": indices[0]["indices"] = [4, 3]
        case "empty": indices[0]["indices"] = []
        case "scope": indices[0]["scheduleID"] = " "
        case "wallet":
            var binding = indices[0]["binding"] as! [String: Any]
            var wallet = binding["wallet"] as! [String: Any]
            wallet["fingerprint"] = ""; binding["wallet"] = wallet; indices[0]["binding"] = binding
        case "instance":
            var binding = indices[0]["binding"] as! [String: Any]
            binding["instance"] = "broken"; indices[0]["binding"] = binding
        case "revision": indices[0]["policyRevision"] = "broken"
        case "attemptID": entry["scheduleID"] = "different"
        case "attemptKind":
            var plan = entry["plan"] as! [String: Any]; plan["kind"] = "idle"; entry["plan"] = plan
        case "attemptIndex", "legacy":
            var plan = entry["plan"] as! [String: Any]; plan["occurrenceIndex"] = -1; entry["plan"] = plan
        case "attemptDate":
            var plan = entry["plan"] as! [String: Any]; plan.removeValue(forKey: "scheduledFor"); entry["plan"] = plan
        case "unresolvedConsumed": entry["resolved"] = false
        case "missingIndices": indices = []
        case "missingWatermark": raw["consumed"] = []
        default: break
        }
        attempts[schedule.id] = entry; raw["attempts"] = attempts; raw["consumedIndices"] = indices
        if kind == "missingField" { raw.removeValue(forKey: "consumedIndices") }
        let bytes = try JSONSerialization.data(withJSONObject: kind == "legacy" ? attempts : raw)
        try bytes.write(to: h.journal.file)
        try h.store.upsert(schedule)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
        #expect(try Data(contentsOf: h.journal.file) == bytes)
        #expect(try Data(contentsOf: h.journal.quarantineFile) == bytes)
        #expect(throws: (any Error).self) { try h.journal.load() }
        #expect(throws: (any Error).self) {
            try h.journal.save(.init(binding: h.owner.withLock { $0 }, scheduleID: schedule.id,
                                     plan: schedule.plan(now: h.now), markedAt: h.now))
        }
        #expect(try Data(contentsOf: h.journal.file) == bytes)
        #expect(try Data(contentsOf: h.journal.quarantineFile) == bytes)
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
    }
}
