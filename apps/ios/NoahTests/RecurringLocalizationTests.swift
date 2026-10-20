import Foundation
import Testing
@testable import Noah

@Suite(.serialized) @MainActor struct RecurringLocalizationTests {
    nonisolated static let languages = ["en", "es", "ja", "ko", "sv"]
    private func localized<T>(_ language: String, _ body: () async throws -> T) async rethrows -> T {
        let defaults = UserDefaults.standard, previous = UserDefaults.standard.string(forKey: LegacyLocale.key)
        defaults.set(language, forKey: LegacyLocale.key)
        defer { defaults.set(previous, forKey: LegacyLocale.key) }
        return try await body()
    }
    private func display(_ language: String) -> String { L10n.text("Needs attention", locale: language) }

    // Covers both recovery loops and refreshDates: neither can overwrite a policy-evidence stop.
    @Test(arguments: languages, [false, true]) func policyStopSurvivesRecoveryAndClock(language: String, corrupt: Bool) async throws {
        try await localized(language) {
            let h = try RecurringExecutorTests.Harness(); var s = try h.add()
            defer { try? FileManager.default.removeItem(at: h.directory) }
            s.status = "needs_attention"; s.issue = .init(code: .policyEvidence); s.lastError = display(language)
            s.nextRunAt = h.now + 123
            try h.store.upsert(s)
            if corrupt { try Data("{broken".utf8).write(to: h.journal.file) }
            else { var planning = s; planning.status = "active"; try h.marker(planning, mmkv: false) }
            await h.executor.timeChanged(); await h.executor.execute(); await h.executor.execute(manual: true)
            let stopped = try #require(h.store.load().schedules[s.id])
            #expect(stopped.requiresPolicyApproval && stopped.status == "needs_attention")
            #expect(stopped.lastError == display(language) && stopped.nextRunAt == s.nextRunAt)
            #expect(h.calls.withLock { $0 } == 0)
        }
    }
    @Test(arguments: languages, [false, true]) func explicitReapprovalUsesIdentifier(language: String, edit: Bool) async throws {
        try await localized(language) {
            let h = try RecurringExecutorTests.Harness(); var s = try h.add()
            defer { try? FileManager.default.removeItem(at: h.directory) }
            s.status = "needs_attention"; s.issue = .init(code: .policyEvidence); s.lastError = display(language)
            try h.store.upsert(s)
            if edit {
                s.startAt = h.now; s.nextRunAt = h.now
                try await h.executor.save(s, editing: true)
            } else { try await h.executor.resume(s.id) }
            let approved = try #require(h.store.load().schedules[s.id])
            #expect(approved.status == "active" && !approved.requiresPolicyApproval && approved.issue == nil)
            #expect(approved.nativeRevision != s.nativeRevision)
            await h.executor.execute(); #expect(h.calls.withLock { $0 } == 0)
        }
    }
    @Test(arguments: languages) func legacyJournalReasonIsStableAndNotRenotified(language: String) async throws {
        try await localized(language) {
            let h = try RecurringExecutorTests.Harness(); let s = try h.add()
            defer { try? FileManager.default.removeItem(at: h.directory) }
            try h.marker(s, mmkv: false)
            let bytes = try Data(contentsOf: h.journal.file)
            var root = try #require(JSONSerialization.jsonObject(with: bytes) as? [String: Any]); root["version"] = 2
            var attempts = try #require(root["attempts"] as? [String: [String: Any]])
            attempts[s.id]?["resolved"] = true; root["attempts"] = attempts
            try JSONSerialization.data(withJSONObject: root).write(to: h.journal.file)
            await h.executor.execute()
            var stopped = try #require(h.store.load().schedules[s.id])
            #expect(stopped.issue == RecurringJournal.legacyReason)
            stopped.lastError = display(language); try h.store.upsert(stopped)
            let count = h.notified.count
            await h.executor.execute(); await h.executor.execute(manual: true)
            #expect(h.notified.count == count && h.calls.withLock { $0 } == 0)
            #expect(try h.store.load().schedules[s.id]?.lastError == display(language))
            await #expect(throws: (any Error).self) { try await h.executor.resume(s.id) }
        }
    }
    @Test(arguments: languages, ["ark", "quote", "schedule"]) func feeApprovalStoresArguments(language: String, kind: String) async throws {
        try await localized(language) {
            let h = try RecurringExecutorTests.Harness(); var input = try h.add(); input.amountSat = 1000; try h.store.upsert(input)
            defer { try? FileManager.default.removeItem(at: h.directory) }
            h.method = kind == "ark" ? "ark" : "lightning"; h.fee = kind == "schedule" ? 0 : 500
            if kind == "schedule" { h.fees = .init(minFeeSats: 500, baseFeeSats: 0, ppmExpiryTable: []) }
            await h.executor.execute(); await h.executor.execute(manual: true)
            let s = try #require(h.store.load().schedules.values.first)
            #expect(s.status == "needs_approval" && s.retryNotBefore == nil && h.calls.withLock { $0 } == 0)
            #expect(s.issue?.code == (kind == "ark" ? .arkFee : kind == "quote" ? .quoteExceedsCap : .scheduleExceedsCap))
            #expect(s.issue?.valid == true)
            #expect(try h.journal.load().isEmpty)
            if kind != "ark" { #expect(s.issue?.arguments == ["500", kind == "quote" ? "40" : "200"]) }
            let english = s.issue?.message(locale: "en")
            #expect(english == s.lastError)
        }
    }
    @Test(arguments: languages) func retryGateAndAmbiguityLatchIgnoreCopy(language: String) async throws {
        try await localized(language) {
            let h = try RecurringExecutorTests.Harness(); var s = try h.add()
            defer { try? FileManager.default.removeItem(at: h.directory) }
            let plan = s.plan(now: h.now)
            s.failure(plan, message: display(language), retryable: true, now: h.now); try h.store.upsert(s)
            await h.executor.execute(); #expect(h.calls.withLock { $0 } == 0)
            #expect(try h.store.load().schedules[s.id]?.issue?.code == .retryableFailure)
            h.clock.withLock { $0 = h.now + 900_000 }; h.ambiguous = true
            await h.executor.execute(); await h.executor.execute(manual: true)
            let stopped = try #require(h.store.load().schedules[s.id])
            #expect(h.calls.withLock { $0 } == 1 && stopped.status == "needs_attention")
            #expect(stopped.issue?.code == .uncertain && stopped.retryNotBefore == nil && h.controller.locked)
            await #expect(throws: (any Error).self) { try await h.executor.resume(s.id) }
        }
    }
    @Test(arguments: languages) func legacyDecodeAndUnknownTextFailClosed(language: String) async throws {
        try await localized(language) {
            let h = try RecurringExecutorTests.Harness(); let original = try h.add()
            defer { try? FileManager.default.removeItem(at: h.directory) }
            let raw = try #require(h.settings.string(forKey: RecurringStore.key))
            for (message, code) in [(RecurringPolicyLedger.message, RecurringIssue.Code.policyEvidence),
                (RecurringJournal.legacyMessage, .legacyHistory), (RecurringJournal.invalidMessage, .invalidJournal),
                (RecurringAutomationAuthority.message, .automationStopped), (RecurringPayment.unsupportedMessage, .unsupported),
                (RecurringPayment.amountLimitMessage, .amountLimit),
                ("Noah was interrupted while sending this payment. Check your transaction history before resuming.", .interrupted),
                ("This schedule has more than 5,000 missed occurrences. Cancel it and create a schedule with a future first payment.", .tooManyMissed),
                ("This payment may have been sent. Check its status before making another payment.", .uncertain),
                ("This invoice was already paid. This occurrence was not sent again. Check the recipient before resuming.", .alreadyPaid),
                ("The fee for this Ark payment must be 0 sats to pay automatically. Review it to pay manually.", .arkFee),
                ("The fee for this payment (41 sats) is above your automatic limit of 40 sats. Review it to pay manually.", .quoteExceedsCap),
                ("Ark's published Lightning fees for this amount can reach 500 sats, above your automatic limit of 200 sats. Review it to pay manually.", .scheduleExceedsCap),
                ("No payment was submitted.", .retryableFailure),
                ("This payment could not be prepared. No payment was submitted.", .retryableFailure),
                (display(language) + " unknown", .unknown)] {
                var root = try #require(JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
                var state = try #require(root["state"] as? [String: Any])
                var rows = try #require(state["schedules"] as? [String: [String: Any]])
                rows[original.id]?["issue"] = nil; rows[original.id]?["lastError"] = message
                rows[original.id]?["retryNotBefore"] = h.now + 123
                state["schedules"] = rows; root["state"] = state
                let legacy = String(decoding: try JSONSerialization.data(withJSONObject: root), as: UTF8.self)
                try h.settings.setDurably(legacy, forKey: RecurringStore.key)
                let row = try #require(RecurringStore(settings: h.settings).load().schedules[original.id])
                #expect(row.issue?.code == code && row.issue?.valid == true)
                if code == .retryableFailure { #expect(row.status == "active" && row.retryNotBefore != nil) }
                else { #expect(row.status != "active" && row.retryNotBefore == nil && row.plan(now: h.now).kind == "idle") }
                #expect(h.settings.string(forKey: RecurringStore.key) == legacy)
            }
        }
    }
    @Test(arguments: languages) func unknownCodesAndMalformedArgumentsStop(language: String) async throws {
        try await localized(language) {
            let h = try RecurringExecutorTests.Harness(); var s = try h.add()
            defer { try? FileManager.default.removeItem(at: h.directory) }
            s.issue = .init(code: .quoteExceedsCap, arguments: ["bogus"]); s.lastError = display(language)
            try h.store.upsert(s)
            let unknown = try #require(h.store.load().schedules[s.id])
            #expect(unknown.issue?.code == .unknown && unknown.status == "needs_attention")
            await h.executor.execute(); #expect(h.calls.withLock { $0 } == 0)
            let raw = try #require(h.settings.string(forKey: RecurringStore.key))
            try h.settings.setDurably(raw.replacingOccurrences(of: "quoteExceedsCap", with: "futureReason"), forKey: RecurringStore.key)
            #expect(try h.store.load().schedules[s.id]?.status == "needs_attention")
        }
    }
    @Test(arguments: languages) func unknownJournalOutcomeQuarantinesAndStorageFailureLatches(language: String) async throws {
        try await localized(language) {
            let h = try RecurringExecutorTests.Harness(); let s = try h.add()
            defer { try? FileManager.default.removeItem(at: h.directory) }
            await h.executor.execute(); #expect(h.calls.withLock { $0 } == 1)
            let bytes = try Data(contentsOf: h.journal.file)
            let raw = String(decoding: bytes, as: UTF8.self).replacingOccurrences(of: "succeeded", with: "futureOutcome")
            try Data(raw.utf8).write(to: h.journal.file)
            await h.executor.execute(); await h.executor.execute(manual: true)
            #expect(h.calls.withLock { $0 } == 1)
            #expect(try h.store.load().schedules[s.id]?.status == "needs_attention")
            #expect(FileManager.default.fileExists(atPath: h.journal.quarantineFile.path))
            await #expect(throws: (any Error).self) { try await h.executor.resume(s.id) }
            let failed = try RecurringExecutorTests.Harness(); _ = try failed.add()
            defer { try? FileManager.default.removeItem(at: failed.directory) }
            failed.storeWriteFails.withLock { $0 = true }; failed.fee = 1
            await failed.executor.execute()
            failed.storeWriteFails.withLock { $0 = false }; failed.fee = 0
            await failed.executor.execute(manual: true)
            #expect(failed.calls.withLock { $0 } == 0)
        }
    }

}
