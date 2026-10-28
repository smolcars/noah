import BarkBoltFFI
import Foundation
import MMKV
@testable import Noah
import Testing

@MainActor extension RecurringExecutorTests {
    @Test func consumedJournalBlocksAnOlderMMKVSnapshot() async throws {
        let h = try Harness(); let original = try h.add()
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.journal.load()[original.id]?.consumed == true)
        try h.store.upsert(original) // Simulated stale persistence after a crash.
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[original.id]?.status == "needs_attention")
    }
    @Test func consumedMonthlyIndexSurvivesRollbackAndZoneChange() async throws {
        let h = try Harness()
        var schedule = RecurringScheduleTests.schedule(start: 1_772_337_600_000)
        schedule.destinationType = "ark"; schedule.destination = "ark-test-destination"
        let newYork = try #require(TimeZone(identifier: "America/New_York"))
        h.zone.withLock { $0 = newYork }
        h.clock.withLock { $0 = 1_774_753_200_000 }
        #expect(schedule.plan(now: h.clock.withLock { $0 }, zone: h.zone.withLock { $0 }).occurrenceIndex == 1)
        try h.store.upsert(schedule)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        try h.store.upsert(schedule)
        h.zone.withLock { $0 = .gmt }
        h.clock.withLock { $0 = 1_775_016_000_000 }
        #expect(schedule.plan(now: h.clock.withLock { $0 }, zone: .gmt).scheduledFor == 1_775_016_000_000)
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
    }

    @Test(arguments: [false, true]) func consumedIndexSurvivesDSTAndZoneRollback(fall: Bool) async throws {
        let h = try Harness()
        let start = try #require(ISO8601DateFormatter().date(from: fall ? "2026-10-31T16:00:00Z" : "2026-03-07T17:00:00Z")).timeIntervalSince1970 * 1000
        var schedule = RecurringScheduleTests.schedule(start: start)
        schedule.interval = .init(unit: "day", every: 1)
        schedule.destinationType = "ark"; schedule.destination = "ark-test-destination"
        schedule.nextOccurrenceIndex = 1
        let ny = try #require(TimeZone(identifier: "America/New_York"))
        let from = fall ? TimeZone.gmt : ny, to = fall ? ny : TimeZone.gmt
        let paidAt = schedule.occurrence(1, zone: from), shifted = schedule.occurrence(1, zone: to)
        #expect(shifted - paidAt == 3_600_000)
        h.zone.withLock { $0 = from }; h.clock.withLock { $0 = paidAt }
        try h.store.upsert(schedule); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        try h.store.upsert(schedule)
        h.zone.withLock { $0 = to }; h.clock.withLock { $0 = shifted }
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
    }
    @Test func consumedIndexSurvivesZoneRollbackAcrossPolicyRevisions() async throws {
        let h = try Harness()
        var revisionA = RecurringScheduleTests.schedule(start: 1_772_337_600_000)
        revisionA.destinationType = "ark"; revisionA.destination = "ark-test-destination"
        revisionA.nativeRevision = UUID()
        let newYork = try #require(TimeZone(identifier: "America/New_York"))
        h.zone.withLock { $0 = newYork }
        h.clock.withLock { $0 = 1_774_753_200_000 }
        try h.store.upsert(revisionA); await h.executor.execute()
        var revisionB = revisionA
        revisionB.startAt = h.clock.withLock { $0 } + 1; revisionB.nextRunAt = revisionB.startAt
        try await h.executor.save(revisionB, editing: true)
        h.clock.withLock { $0 += 1 }; await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 2)
        try h.store.upsert(revisionA)
        h.zone.withLock { $0 = .gmt }; h.clock.withLock { $0 = 1_775_016_000_000 }
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 2)
        #expect(try h.store.load().schedules[revisionA.id]?.status == "needs_attention")
    }

    @Test func explicitEditCreatesNewPolicyWithoutReusingConsumedOccurrence() async throws {
        let h = try Harness(); let original = try h.add()
        await h.executor.execute()
        var edit = original
        edit.startAt = h.now + 1; edit.nextRunAt = h.now + 1; edit.amountSat = 1234
        h.clock.withLock { $0 += 1 }
        try await h.executor.save(edit, editing: true)
        #expect(try h.store.load().schedules[original.id]?.nativeRevision != nil)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 2)
    }
    @Test func corruptJournalStopsAllExecutionWithoutChangingBytes() async throws {
        let h = try Harness(); _ = try h.add()
        try Data("broken journal".utf8).write(to: h.journal.file)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try String(contentsOf: h.journal.file, encoding: .utf8) == "broken journal")
        #expect(h.executor.error != nil)
    }
    @Test func cancelDoesNotUnlockUncertainSend() async throws {
        let h = try Harness(); let schedule = try h.add(); h.ambiguous = true
        await h.executor.execute()
        try await h.executor.cancel(schedule.id)
        #expect(h.controller.locked)
        #expect(try h.store.load().schedules[schedule.id] == nil)
        #expect(try h.journal.load()[schedule.id] != nil)
    }
    @Test func syncSerializesLatestListAndKeepsFailurePending() async throws {
        let h = try Harness(); var schedule = try h.add()
        h.syncHook = { _ in
            if h.sentEntries.count == 1 { schedule.status = "paused"; try h.store.upsert(schedule) }
        }
        await h.executor.syncServer()
        #expect(h.sentEntries.count == 2 && h.sentEntries.last?.isEmpty == true)
        #expect(try h.store.load().pending == false)
        h.syncHook = { _ in throw URLError(.notConnectedToInternet) }
        await h.executor.syncServer()
        #expect(try h.store.load().pending)
    }
    @Test func consumedWatermarkSurvivesLaterRevisionAndRollback() async throws {
        let h = try Harness(); let revisionA = try h.add()
        await h.executor.execute()
        var revisionB = revisionA
        revisionB.startAt = h.now + 1; revisionB.nextRunAt = h.now + 1
        try await h.executor.save(revisionB, editing: true)
        h.clock.withLock { $0 += 1 }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 2)
        try h.store.upsert(revisionA)
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 2)
        #expect(try h.store.load().schedules[revisionA.id]?.status == "needs_attention")
    }
    @Test func legacyJournalMigratesConsumptionBeforeAttemptReplacement() throws {
        let h = try Harness(); let schedule = try h.add()
        let owner = h.owner.withLock { $0 }
        var old = RecurringJournal.Entry(binding: owner, scheduleID: schedule.id, plan: schedule.plan(now: h.now), markedAt: h.now)
        old.resolved = true; old.consumed = true
        try JSONEncoder().encode([schedule.id: old]).write(to: h.journal.file)
        _ = try h.journal.load()
        var new = old; new.consumed = nil; new.policyRevision = UUID()
        try h.journal.save(new)
        let index = try #require(old.plan.occurrenceIndex)
        #expect(try h.journal.consumed(binding: owner, scheduleID: schedule.id, at: h.now, policyRevision: nil, occurrenceIndex: index))
        var otherWallet = owner; otherWallet.instance = UUID()
        #expect(try !h.journal.consumed(
            binding: otherWallet,
            scheduleID: schedule.id,
            at: h.now,
            policyRevision: nil,
            occurrenceIndex: index))
    }

    @Test(arguments: [false, true]) func oldJournalMigrationRetainsUnknownRevisionHistory(v1: Bool) async throws {
        let h = try Harness(); let schedule = try h.add()
        let owner = h.owner.withLock { $0 }
        try h.store.bind(owner)
        var old = RecurringJournal.Entry(binding: owner, scheduleID: schedule.id,
                                         plan: schedule.plan(now: h.now), markedAt: h.now, policyRevision: UUID())
        old.resolved = true; old.consumed = true
        let attempts = try JSONSerialization.jsonObject(with: JSONEncoder().encode([schedule.id: old]))
        let binding = try JSONSerialization.jsonObject(with: JSONEncoder().encode(owner))
        let bytes: Data
        if v1 {
            bytes = try JSONSerialization.data(withJSONObject: ["version": 1, "attempts": attempts,
                                                                "consumed": [["binding": binding, "scheduleID": schedule.id, "through": h.now]]])
        } else { bytes = try JSONEncoder().encode([schedule.id: old]) }
        try bytes.write(to: h.journal.file)
        _ = try h.journal.load()
        let migrated = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: h.journal.file)) as? [String: Any])
        #expect(migrated["version"] as? Int == 3)
        let indices = try #require(migrated["consumedIndices"] as? [[String: Any]])
        let index = try #require(old.plan.occurrenceIndex)
        #expect(indices.first?["indices"] as? [Int] == [index])
        #expect(indices.first?["policyRevision"] as? String == old.policyRevision?.uuidString)
        var replacement = old; replacement.consumed = nil; replacement.policyRevision = UUID()
        try h.journal.save(replacement)
        // No timestamp/index for this rolled-back revision survives in the old journal.
        h.clock.withLock { $0 += 10 * 86_400_000 }
        h.zone.withLock { $0 = .gmt }
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
        #expect(try h.journal.consumed(binding: owner, scheduleID: schedule.id, at: h.now + 1,
                                       policyRevision: old.policyRevision, occurrenceIndex: index))
        var other = owner; other.instance = UUID()
        #expect(try !h.journal.consumed(binding: other, scheduleID: schedule.id, at: h.now + 1,
                                        policyRevision: old.policyRevision, occurrenceIndex: index))
    }
    @Test func v2IndexScopeDoesNotBlockFreshRevisionOrWallet() throws {
        let h = try Harness(); let schedule = try h.add(); let owner = h.owner.withLock { $0 }
        var entry = RecurringJournal.Entry(binding: owner, scheduleID: schedule.id,
                                           plan: schedule.plan(now: h.now), markedAt: h.now, policyRevision: UUID())
        entry.resolved = true; entry.consumed = true
        try h.journal.save(entry)
        let index = try #require(entry.plan.occurrenceIndex)
        #expect(try h.journal.consumed(binding: owner, scheduleID: schedule.id, at: h.now + 1,
                                       policyRevision: entry.policyRevision, occurrenceIndex: index))
        #expect(try !h.journal.consumed(binding: owner, scheduleID: schedule.id, at: h.now + 1,
                                        policyRevision: UUID(), occurrenceIndex: index))
        #expect(try h.journal.consumed(binding: owner, scheduleID: schedule.id, at: h.now,
                                       policyRevision: UUID(), occurrenceIndex: 0))
        var other = owner; other.instance = UUID()
        #expect(try !h.journal.consumed(binding: other, scheduleID: schedule.id, at: h.now,
                                        policyRevision: entry.policyRevision, occurrenceIndex: index))
        entry.plan.occurrenceIndex = index + 1; entry.plan.scheduledFor = h.now + 1
        try h.journal.save(entry); try h.journal.save(entry)
        let json = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: h.journal.file)) as? [String: Any])
        let indices = try #require(json["consumedIndices"] as? [[String: Any]])
        #expect(indices.first?["indices"] as? [Int] == [index, index + 1])
    }

    @Test(arguments: ["ark", "lnurl", "offer", "lightning"]) func variableOrExcessiveFeesNeedApprovalAndNeverConsume(method: String) async throws {
        let h = try Harness(); let schedule = try h.add()
        h.method = method; h.fee = 501 // F = 250; cap = 500 for 50,000 sats.
        await h.executor.execute(); await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_approval")
        #expect(try h.journal.load().isEmpty)
        #expect(h.saved == nil)
        #expect(h.notified.count == 1)
        #expect(h.notified.first?.lastError?.contains(method == "ark" ? "0 sats" : "500 sats") == true)
    }
    @Test(arguments: ["lnurl", "offer", "lightning"]) func belowCapLightningPaysAutomaticallyExactlyOnce(method: String) async throws {
        let h = try Harness(); let schedule = try h.add(); h.method = method; h.fee = 250
        await h.executor.execute(); await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.status == "active")
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 1)
        #expect(!h.notified.contains { $0.status == "needs_approval" })
    }
    @Test func scheduleAboveCapWithLowQuoteNeedsApproval() async throws {
        let h = try Harness(); let schedule = try h.add(); h.method = "offer"; h.fee = 1
        h.fees = .init(minFeeSats: 0, baseFeeSats: 0, ppmExpiryTable: [.init(expiryBlocksThreshold: 0, ppm: 50_000)])
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.journal.load().isEmpty && h.saved == nil)
        #expect(h.notified.count == 1)
        #expect(h.notified.first?.status == "needs_approval")
        #expect(h.notified.first?
            .lastError ==
            "Ark's published Lightning fees for this amount can reach 2500 sats, above your automatic limit of 1000 sats. Review it to pay manually.")
        #expect(try h.store.load().schedules[schedule.id]?.lastError == h.notified.first?.lastError)
    }
    @Test func arkInfoFailureUsesFallbackCap() async throws {
        let h = try Harness(); _ = try h.add(); h.method = "offer"; h.fee = 501; h.feeFetchFails = true
        await h.executor.execute()
        #expect(h.feeFetches == 1 && h.calls.withLock { $0 } == 0)
        #expect(h.notified.first?.lastError == "The fee for this payment (501 sats) is above your automatic limit of 500 sats. Review it to pay manually.")
    }
    @Test func finalGuardUsesCapturedScheduleAndQuoteWithoutRefetching() async throws {
        let h = try Harness(); _ = try h.add(); h.method = "offer"; h.fee = 800
        h.fees = .init(minFeeSats: 0, baseFeeSats: 600, ppmExpiryTable: [])
        // Only the captured schedule/quote allows this fee; fallback or refetch blocks it.
        h.beforePay = { h.fees = .init(minFeeSats: 5000, baseFeeSats: 0, ppmExpiryTable: []); h.fee = 5000 }
        await h.executor.execute()
        #expect(h.feeFetches == 1 && h.calls.withLock { $0 } == 1)
        #expect(!h.notified.contains { $0.status == "needs_approval" })
    }
    @Test func manualApprovalUsesReviewedQuoteAndSettlesOccurrenceOnce() async throws {
        let h = try Harness(); let schedule = try h.add(); h.method = "lnurl"; h.fee = 501
        await h.executor.execute()
        let review = try await h.executor.review(schedule.id)
        #expect(review.quote.fee == 501)
        await h.executor.execute(manual: true, approval: review)
        // SendConfirmation selects its receipt view while locked, until Done acknowledges it.
        #expect(h.controller.locked)
        #expect(h.controller.attempt?.receipt != nil)
        switch h.controller.state {
        case .succeeded: break
        default: Issue.record("The attended review must keep its success state")
        }
        await h.executor.execute(manual: true, approval: review)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 1)
        #expect(try h.journal.load()[schedule.id]?.consumed == true)
        h.controller.acknowledge() // The normal Send receipt's Done action.
        #expect(!h.controller.locked && h.controller.attempt == nil)
        await h.executor.execute(manual: true, approval: review)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.runs.first?.status == "success")
    }
    @Test func manualReviewCannotOutlivePause() async throws {
        let h = try Harness(); let schedule = try h.add(); h.method = "offer"; h.fee = 501
        await h.executor.execute()
        let review = try await h.executor.review(schedule.id)
        try await h.executor.pause(schedule.id)
        await h.executor.execute(manual: true, approval: review)
        #expect(h.calls.withLock { $0 } == 0)
    }
    @Test(arguments: [-1.0, 86_400_000.0]) func finalGuardRejectsClockJumpDuringPreparation(delta: Double) async throws {
        let h = try Harness(); let schedule = try h.add()
        h.beforePay = { h.clock.withLock { $0 += delta } }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.journal.load()[schedule.id]?.resolved == true)
        #expect(try h.journal.load()[schedule.id]?.consumed != true)
        #expect(try h.store.load().schedules[schedule.id]?.inFlight == nil)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 0)
        #expect(!h.controller.locked)
    }

    struct ZoneChange: Decodable {
        var fromZone: String; var toZone: String; var startAt: Double
        var interval: RecurringInterval; var index: Int; var cached: Double
    }
    @Test(arguments: [false, true]) func idlePassAndTimeZoneEventRefreshDurableWakeAndReminders(event: Bool) async throws {
        for fixture in try Fixtures.load("recurring-zone-change", input: ZoneChange.self, expected: Double.self) {
            let h = try Harness()
            var schedule = RecurringScheduleTests.schedule(start: fixture.input.startAt)
            schedule.destinationType = "ark"; schedule.destination = "ark-test"
            schedule.interval = fixture.input.interval; schedule.nextOccurrenceIndex = fixture.input.index
            schedule.nextRunAt = fixture.input.cached
            try h.store.upsert(schedule)
            h.clock.withLock { $0 = fixture.input.startAt + 1000 }
            let zone = try #require(TimeZone(identifier: fixture.input.toZone))
            h.zone.withLock { $0 = zone }
            if event { await h.executor.timeChanged() } else { await h.executor.execute() }
            #expect(try h.store.load().schedules[schedule.id]?.nextRunAt == fixture.expected)
            #expect(h.sentEntries.last?.first?.nextRunAt == Int64(floor(fixture.expected / 1000)))
            #expect(h.reminders.first?.nextRunAt == fixture.expected)
            #expect(h.calls.withLock { $0 } == 0)
            #expect(try h.store.load().pending == false)
        }
    }

    @Test(arguments: [false, true]) func expiredPushBudgetNeverStartsSubmission(duringPreparation: Bool) async throws {
        let h = try Harness(); let schedule = try h.add()
        let gate = RecurringReservationTests.Gate()
        var held = false
        let hold: @MainActor @Sendable () async -> Void = {
            guard !held else { return }
            held = true
            await gate.wait()
        }
        if duringPreparation { h.beforePay = hold } else { h.preflight = hold }
        // The budget only has to outlast reaching the gate (about 0.1 s on CI); it then expires while held.
        let result = await AppDelegate.completeWithin(.seconds(1)) { await h.executor.execute() }
        #expect(result == .failed)
        #expect(h.calls.withLock { $0 } == 0)
        try #require(await gate.entered)
        await gate.release()
        await h.executor.execute() // Join settlement, without extending the expired worker's budget.
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[schedule.id]?.inFlight == nil)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 0)
        #expect(try h.store.load().schedules[schedule.id]?.status == "active")
        #expect(try h.journal.load()[schedule.id]?.consumed != true)
        #expect(!h.controller.locked)
    }
    @Test func paymentAlreadySubmittedSettlesAfterPushDeadline() async throws {
        let h = try Harness(); let schedule = try h.add()
        let gate = RecurringReservationTests.Gate()
        h.afterPay = { await gate.wait() }
        let result = await AppDelegate.completeWithin(.seconds(1)) { await h.executor.execute() }
        #expect(result == .failed)
        #expect(h.calls.withLock { $0 } == 1)
        try #require(await gate.entered)
        await gate.release()
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 1)
        #expect(try h.journal.load()[schedule.id]?.consumed == true)
    }

    @Test func clockJumpBehindBackoffStopsEvenWhenSameOccurrenceIsDue() async throws {
        let h = try Harness(); var schedule = try h.add()
        schedule.startAt -= 5000; schedule.retryNotBefore = h.now - 1000
        try h.store.upsert(schedule)
        h.beforePay = { h.clock.withLock { $0 -= 2000 } }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.journal.load()[schedule.id]?.consumed != true)
        #expect(!h.controller.locked)
    }

    @Test func oldSuccessfulReceiptCannotSettleAnEditedPolicyRevision() async throws {
        let h = try Harness()
        var original = try h.add()
        original.startAt = h.now; original.nextRunAt = h.now; original.amountSat = 1000
        try h.store.upsert(original)
        h.writeRemovalFails = true
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[original.id]?.occurrencesPaid == 1)
        #expect(h.controller.attempt?.receipt != nil)
        h.writeRemovalFails = false
        var input = original
        input.destination = "different-recipient"; input.amountSat = 2000; input.maxOccurrences = 1
        try await h.executor.save(input, editing: true)
        await h.executor.execute()
        let edited = try #require(h.store.load().schedules[original.id])
        #expect(edited.status == "needs_attention" && edited.occurrencesPaid == 0)
        #expect(try h.journal.load()[original.id]?.policyRevision != edited.nativeRevision)
        let notificationsBefore = h.notified
        await h.executor.checkStatus(original.id)
        #expect(try h.store.load().schedules[original.id] == edited,
                "An old receipt must not advance the edited recipient/amount")
        #expect(try h.journal.load()[original.id]?.resolved == true)
        #expect(try h.journal.load()[original.id]?.consumed == true)
        #expect(!h.controller.locked && h.controller.attempt == nil)
        #expect(h.notified == notificationsBefore, "Checking A must not announce the edited payment as sent")
        await h.executor.checkStatus(original.id)
        await h.executor.execute()
        #expect(try h.store.load().schedules[original.id] == edited)
        #expect(h.calls.withLock { $0 } == 1)
    }

    @Test(arguments: ["success", "alreadyPaid", "failed", "uncertain"])
    func statusCheckOutcomesCannotMutateAnotherPolicyRevision(outcome: String) async throws {
        let h = try Harness(); let original = try h.add(); h.ambiguous = true
        await h.executor.execute()
        var edited = original
        edited.destination = "different-recipient"; edited.amountSat = 2000
        edited.nativeRevision = UUID(); edited.status = "needs_attention"; edited.maxOccurrences = 1
        try h.store.upsert(edited)
        let notificationsBefore = h.notified
        switch outcome {
        case "success": h.resolution = .paid(.init(amount: original.amountSat, proof: .submitReturnedArkSuccess))
        case "alreadyPaid": h.resolution = .alreadyPaid(.init(amount: original.amountSat, proof: .submitReturnedArkSuccess))
        case "failed": h.resolution = .failed
        default: h.resolution = .uncertain
        }
        await h.executor.checkStatus(original.id)
        #expect(try h.store.load().schedules[original.id] == edited)
        #expect(h.notified == notificationsBefore)
        #expect(try h.journal.load()[original.id]?.resolved == (outcome != "uncertain"))
        #expect((try h.journal.load()[original.id]?.consumed == true) == ["success", "alreadyPaid"].contains(outcome))
        #expect(h.controller.locked == (outcome == "uncertain"))
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
    }

    @Test(arguments: ["edit", "replace", "restore", "rebind", "failureLatch", "suspend"])
    func changesAfterSubmissionKeepSettlementOwnership(action: String) async throws {
        let h = try Harness(); let original = try h.add()
        let owner = h.owner.withLock { $0 }
        var changed: RecurringPayment?
        h.afterPay = {
            var latest = try #require(h.store.load().schedules[original.id])
            switch action {
            case "edit": latest.destination = "other"; latest.amountSat = 2000; latest.nativeRevision = UUID(); try h.store.upsert(latest)
            case "replace": h.owner.withLock { $0.wallet.fingerprint = "other" }
            case "restore": h.owner.withLock { $0.instance = UUID() }
            case "rebind": try h.store.bind(.init(wallet: owner.wallet, instance: UUID()))
            case "failureLatch":
                latest.status = "needs_attention"; latest.lastError = "Safety storage failed"; latest.consecutiveFailures = 3
                try h.store.upsert(latest)
            default: h.stopped.withLock { $0 = true }
            }
            changed = try h.store.load().schedules[original.id]
        }
        await h.executor.execute()
        let saved = try #require(h.store.load().schedules[original.id])
        if ["edit", "replace", "restore", "rebind"].contains(action) { #expect(saved == changed) }
        else {
            #expect(saved.occurrencesPaid == 1)
            if action == "failureLatch" {
                #expect(saved.status == "needs_attention" && saved.consecutiveFailures == 3 && saved.lastError == "Safety storage failed")
            }
        }
        #expect((try h.journal.load()[original.id]?.consumed == true) == !["replace", "restore", "rebind"].contains(action))
        if action == "edit" {
            #expect(h.notified.last?.destination == original.destination && h.notified.last?.amountSat == original.amountSat)
        }
        h.owner.withLock { $0 = owner }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
    }

    @Test func recoveryNotificationSuspensionCannotRecreateCancelledPolicy() async throws {
        let h = try Harness(); let first = try h.add()
        var second = first; second.id = "second"
        try h.store.upsert(second)
        try h.marker(first, mmkv: true); try h.marker(second, mmkv: true)
        var cancelled: String?
        h.notificationHook = { result in
            guard cancelled == nil else { return }
            let id = result.id == first.id ? second.id : first.id
            cancelled = id
            await Task.yield()
            try await h.executor.cancel(id)
        }
        await h.executor.execute()
        let id = try #require(cancelled)
        #expect(try h.store.load().schedules[id] == nil)
        #expect(h.notified.count == 1)
        #expect(h.calls.withLock { $0 } == 0)
    }
}
