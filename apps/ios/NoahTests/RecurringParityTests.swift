import Foundation
@testable import Noah
import Testing

@MainActor struct RecurringParityTests {
    @Test(arguments: ["", "Rent for April", " ", "Thank you 🌱"])
    func lnurlNotesKeepLightningRoute(comment: String) async throws {
        let route = SendRecipientRoute(destination: "recipient@example.com", minMsat: 1_000,
                                       maxMsat: 100_000_000, commentAllowed: 140, arkAddress: "ark-recipient")
        let payment = try await RecurringRecipient.lnurlPayment(
            .init(destination: route.destination, method: "lnurl", amount: 50_000, comment: comment),
            route: route, arkAddress: Self.address)
        #expect(payment.method == (comment.isEmpty ? "ark" : "lnurl"))
        #expect(payment.destination == (comment.isEmpty ? "ark-recipient" : route.destination))
        #expect(payment.comment == comment && payment.amount == 50_000)
        var lightningOnly = route; lightningOnly.arkAddress = nil
        #expect(try await RecurringRecipient.lnurlPayment(payment, route: lightningOnly, arkAddress: Self.address).comment == comment)
    }

    /// A `bark` payment option (lnurl/luds#303) pays the address its callback returns, while the amount fits its range.
    @Test func paymentOptionPaysTheCallbacksAddress() async throws {
        var route = SendRecipientRoute(destination: "recipient@example.com", minMsat: 1_000,
                                       maxMsat: 100_000_000, commentAllowed: 140, arkAddress: "ark-recipient")
        route.arkOption = .init(id: "second", minMsat: 1_000, maxMsat: 60_000_000)
        let input = SendPayment(destination: route.destination, method: "lnurl", amount: 50_000, comment: "")
        let viaOption = try await RecurringRecipient.lnurlPayment(input, route: route, arkAddress: Self.address)
        #expect(viaOption.method == "ark" && viaOption.destination == "callback-second")
        // Above the option's range: discovery's address still pays it.
        var large = input; large.amount = 70_000
        #expect(try await RecurringRecipient.lnurlPayment(large, route: route, arkAddress: Self.address).destination == "ark-recipient")
    }

    private nonisolated static func address(_ rail: SendArkRail) -> String {
        switch rail {
        case .address(let address): address
        case .option(let id): "callback-\(id)"
        }
    }

    @Test(arguments: [UInt64(0), 101, UInt64.max])
    func lnurlAmountBoundsStillApply(amount: UInt64) async {
        let route = SendRecipientRoute(destination: "recipient@example.com", minMsat: 1_000,
                                       maxMsat: 100_000, commentAllowed: 140, arkAddress: "ark-recipient")
        await #expect(throws: (any Error).self) {
            try await RecurringRecipient.lnurlPayment(.init(destination: route.destination, method: "lnurl", amount: amount, comment: "Note"),
                                                      route: route, arkAddress: Self.address)
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

    @Test(arguments: ["recurring_payment_reminder", "recurring_payment_result", "recurring_payment_due"])
    func notificationTapRetainsRouteUntilWalletNavigationIsReady(type: String) throws {
        let route = SettingsRoute()
        let payload = ["notification_type": type]
        let text = try #require(String(bytes: JSONSerialization.data(withJSONObject: payload), encoding: .utf8))
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
