import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringParityTests {
    @Test(arguments: ["", "Rent for April", " ", "Thank you 🌱"])
    func lnurlNotesKeepLightningRoute(comment: String) throws {
        let route = SendRecipientRoute(destination: "recipient@example.com", minMsat: 1_000,
            maxMsat: 100_000_000, commentAllowed: 140, arkAddress: "ark-recipient")
        let payment = try RecurringRecipient.lnurlPayment(.init(destination: route.destination, method: "lnurl", amount: 50_000, comment: comment), route: route)
        #expect(payment.method == (comment.isEmpty ? "ark" : "lnurl"))
        #expect(payment.destination == (comment.isEmpty ? "ark-recipient" : route.destination))
        #expect(payment.comment == comment && payment.amount == 50_000)
        var lightningOnly = route; lightningOnly.arkAddress = nil
        #expect(try RecurringRecipient.lnurlPayment(payment, route: lightningOnly).comment == comment)
    }

    @Test(arguments: [UInt64(0), 101, UInt64.max])
    func lnurlAmountBoundsStillApply(amount: UInt64) {
        let route = SendRecipientRoute(destination: "recipient@example.com", minMsat: 1_000,
            maxMsat: 100_000, commentAllowed: 140, arkAddress: "ark-recipient")
        #expect(throws: (any Error).self) {
            try RecurringRecipient.lnurlPayment(.init(destination: route.destination, method: "lnurl", amount: amount, comment: "Note"), route: route)
        }
    }

    @Test(arguments: ["America/New_York", "UTC"])
    func endDateUsesRNs235900(zoneID: String) throws {
        let zone = try #require(TimeZone(identifier: zoneID))
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        let date = try #require(calendar.date(from: DateComponents(year: 2026, month: 11, day: 1, hour: 12, minute: 30, second: 42, nanosecond: 123_000_000)))
        let end = RecurringPaymentEditorView.endDateTimestamp(date, zone: zone)
        let parts = calendar.dateComponents([.day, .hour, .minute, .second], from: Date(timeIntervalSince1970: end / 1000))
        #expect(parts.day == 1 && parts.hour == 23 && parts.minute == 59 && parts.second == 0)
        #expect(end.truncatingRemainder(dividingBy: 1000) == 0)
        var schedule = RecurringScheduleTests.schedule(start: end)
        schedule.endAt = end
        #expect(schedule.next(0, zone: zone) == end)
        schedule.startAt += 1
        #expect(schedule.next(0, zone: zone) == nil)
    }

    @Test(arguments: [UInt64(2_100_000_000_000_000), 2_100_000_000_000_001, 9_007_199_254_740_991, UInt64.max])
    func newAndEditedAmountsRequireBitcoinSupplyLimit(amount: UInt64) async throws {
        let h = try RecurringExecutorTests.Harness(); let original = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        var input = original; input.startAt = h.now; input.nextRunAt = h.now; input.amountSat = amount
        if amount == RecurringPayment.maximumAmount {
            try await h.executor.save(input, editing: true)
            input.id = UUID().uuidString
            try await h.executor.save(input, editing: false)
        } else {
            await #expect(throws: (any Error).self) { try await h.executor.save(input, editing: true) }
            input.id = UUID().uuidString
            await #expect(throws: (any Error).self) { try await h.executor.save(input, editing: false) }
            #expect(try h.store.load().schedules[original.id]?.amountSat == original.amountSat)
        }
        #expect(h.calls.withLock { $0 } == 0)
    }

    @Test(arguments: [UInt64(2_100_000_000_000_001), 9_007_199_254_740_991, UInt64.max])
    func rnOutOfRangePolicyRemainsVisibleAndCannotResume(amount: UInt64) async throws {
        let h = try RecurringExecutorTests.Harness(); var invalid = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        invalid.amountSat = amount
        try h.store.upsert(invalid) // RN-shaped persisted policy, not a new native approval.
        let originalBytes = h.settings.string(forKey: RecurringStore.key)
        let saved = try #require(h.store.load().schedules[invalid.id])
        #expect(saved.amountSat == amount && saved.status == "needs_attention")
        let unsupported = amount > 9_007_199_254_740_991
        #expect(saved.unsupportedStorage == unsupported)
        #expect(saved.lastError == (unsupported ? RecurringPayment.unsupportedMessage : RecurringPayment.amountLimitMessage))
        #expect(h.settings.string(forKey: RecurringStore.key) == originalBytes)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        await #expect(throws: (any Error).self) { try await h.executor.resume(invalid.id) }
        await #expect(throws: (any Error).self) { try await h.executor.overrideAndResume(invalid.id, confirmed: true) }
        var valid = invalid; valid.id = UUID().uuidString; valid.amountSat = 1234
        valid.startAt = h.now; valid.nextRunAt = h.now
        try await h.executor.save(valid, editing: false)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[invalid.id]?.status == "needs_attention")
        try await h.executor.cancel(invalid.id)
        #expect(try h.store.load().schedules[invalid.id] == nil)
    }

    @Test(arguments: [#""0""#, "false", "true", "null", "{}", "[]", "1", "0.5"])
    func malformedEnvelopeVersionPreservesBytes(version: String) throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let raw = "{\"version\":\(version),\"state\":{\"schedules\":{}}}"
        h.settings.set(raw, forKey: RecurringStore.key)
        #expect(throws: (any Error).self) { try h.store.load() }
        #expect(throws: (any Error).self) { try h.store.upsert(RecurringScheduleTests.schedule()) }
        #expect(h.settings.string(forKey: RecurringStore.key) == raw)
    }

    @Test(arguments: ["recurring_payment_reminder", "recurring_payment_result", "recurring_payment_due"])
    func notificationTapRetainsRouteUntilWalletNavigationIsReady(type: String) throws {
        let route = SettingsRoute()
        let payload = ["notification_type": type]
        let text = String(decoding: try JSONSerialization.data(withJSONObject: payload), as: UTF8.self)
        let variants: [[AnyHashable: Any]] = [payload, ["body": text], ["body": payload],
            ["dataString": text], ["data": payload], ["data": ["body": text]],
            ["UIApplicationLaunchOptionsRemoteNotificationKey": ["body": text]]]
        for variant in variants {
            route.page = nil
            route.receive(variant)
            #expect(route.page == .recurring)
            route.receive(["notification_type": "heartbeat"])
            #expect(route.page == .recurring) // An unrelated callback cannot discard a pending tap.
        }
        route.page = nil
        route.receive(["body": "malformed"])
        route.receive(["notification_type": "maintenance"])
        #expect(route.page == nil)
    }
}
