import Foundation
@testable import Noah
import Testing

struct RecurringScheduleTests {
    struct CalendarInput: Decodable { var zone: String; var startAt: Double; var interval: RecurringInterval; var index: Int }
    static func schedule(start: Double = 0) -> RecurringPayment {
        .init(
            id: "rent",
            label: "Rent",
            destination: "landlord@example.com",
            destinationType: "lnurl",
            amountSat: 50_000,
            comment: "",
            interval: .init(unit: "month", every: 1),
            startAt: start,
            nextRunAt: start,
            createdAt: 0,
            updatedAt: 0)
    }
    @Test func calendarMatchesRN() throws {
        for fixture in try Fixtures.load("recurring-schedule", input: CalendarInput.self, expected: Double.self) {
            var schedule = Self.schedule(start: fixture.input.startAt)
            schedule.interval = fixture.input.interval
            let zone = try #require(TimeZone(identifier: fixture.input.zone))
            #expect(schedule.occurrence(fixture.input.index, zone: zone) == fixture.expected, "\(fixture.name)")
        }
    }
    struct PolicyInput: Decodable { var zone: String; var schedule: RecurringPayment; var now: Double }
    @Test func planningMatchesRN() throws {
        for fixture in try Fixtures.load("recurring-policy", input: PolicyInput.self, expected: RecurringPayment.Plan.self) {
            let zone = try #require(TimeZone(identifier: fixture.input.zone))
            #expect(fixture.input.schedule.plan(now: fixture.input.now, zone: zone) == fixture.expected, "\(fixture.name)")
        }
    }
    @Test func missedOccurrencesAndResume() {
        var schedule = Self.schedule()
        schedule.interval = .init(unit: "day", every: 1)
        let now = 4.0 * 86_400_000
        let plan = schedule.plan(now: now, zone: .gmt)
        #expect(plan.occurrenceIndex == 4)
        schedule.success(plan, now: now, zone: .gmt)
        #expect(schedule.occurrencesPaid == 1)
        #expect(schedule.runs.map(\.status) == ["success", "skipped", "skipped", "skipped", "skipped"])
        #expect(schedule.plan(now: now, zone: .gmt).kind == "idle")
        schedule.resume(now: now + 86_400_000, zone: .gmt)
        #expect(schedule.nextOccurrenceIndex == 6)
    }
    @Test func limitsAndValidation() throws {
        var schedule = Self.schedule()
        schedule.maxOccurrences = 2
        #expect(schedule.next(2) == nil)
        schedule.endAt = schedule.occurrence(1)
        #expect(schedule.next(1) != nil)
        #expect(schedule.next(2) == nil)
        try schedule.validateInput(now: 0)
        schedule.destinationType = "onchain"
        #expect(throws: RecurringError.self) { try schedule.validateInput(now: 0) }
    }
}
