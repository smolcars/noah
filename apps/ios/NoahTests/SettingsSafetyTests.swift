import Foundation
import Testing
@testable import Noah

/// Settings' money and destructive paths: a delete stays locked until it ends; amounts are whole sats as typed.
@MainActor struct SettingsSafetyTests {
    @Test func aDeleteStaysLockedUntilItEndsAndRunsOnce() async {
        let deletion = WalletDeletion()
        var calls = 0
        #expect(!(await deletion.delete { calls += 1 }))
        #expect(calls == 0, "nothing runs before the word matches")
        deletion.confirmation = " Delete "
        let gate = AsyncStream<Void>.makeStream()
        let first = Task { await deletion.delete { calls += 1; for await _ in gate.stream { break } } }
        while calls == 0 { await Task.yield() }
        #expect(deletion.deleting)
        // A rebuilt sheet (rotation, a pose change) sees the same lock and can't start another.
        #expect(!(await deletion.delete { calls += 1 }))
        gate.continuation.yield()
        #expect(await first.value)
        #expect(!deletion.deleting && calls == 1 && deletion.failure == nil)
    }

    @Test func aFailedDeleteUnlocksWithTodaysMessage() async {
        let deletion = WalletDeletion()
        deletion.confirmation = "delete"
        struct Boom: Error {}
        #expect(!(await deletion.delete { throw Boom() }))
        #expect(!deletion.deleting)
        #expect(deletion.failure == "Couldn't delete wallet. Restart Noah and try again.")
    }

    @Test func moveToArkTakesWholePositiveSatsAsTyped() {
        #expect(OnchainAmountInput.sats("150000") == 150_000)
        #expect(OnchainAmountInput.sats("1.5") == nil)
        #expect(OnchainAmountInput.sats("-1000") == nil)
        #expect(OnchainAmountInput.sats("0") == nil)
        #expect(OnchainAmountInput.sats("1 000") == nil)
        #expect(OnchainAmountInput.sats("١٢٣") == nil)
        #expect(OnchainAmountInput.sats("") == nil)
        #expect(OnchainAmountInput.sats("99999999999999999999") == nil)
    }

    @Test func largeOccurrenceLimitsKeepTheirValue() {
        var schedule = RecurringScheduleTests.schedule(start: 1_000)
        schedule.maxOccurrences = 3_000_000_000
        #expect(schedule.maxOccurrences == 3_000_000_000)
        let text = L10n.plural("After {n} payments", count: schedule.maxOccurrences!, locale: "en")
        #expect(text.hasPrefix("After ") && text.filter(\.isNumber) == "3000000000")
    }
}
