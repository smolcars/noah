import Foundation
@testable import Noah
import Testing

/// `apps/fixtures/widgets.json`: the same daily balances, change, calm water and swell as Android.
struct WidgetWaveTests {
    struct Change: Decodable { var at: String; var delta: Int64 }

    struct Input: Decodable {
        var kind: String
        var now: String?
        var timeZone: String?
        var currentBalance: Int64?
        var changes: [Change]? // swiftlint:disable:this discouraged_optional_collection - fixture field only some cases carry
        var series: [Double]? // swiftlint:disable:this discouraged_optional_collection - fixture field only some cases carry
    }

    struct Expected: Decodable {
        var values: [Double]
        var change: Int64?
        var calm: Bool? // swiftlint:disable:this discouraged_optional_boolean - fixture field only some cases carry
    }

    private static func date(_ value: String) throws -> Date {
        try #require(ISO8601DateFormatter().date(from: value), "\(value)")
    }

    private static func calendar(_ identifier: String) throws -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: identifier))
        return calendar
    }

    @Test func matchesTheSharedFixture() throws {
        let cases = try Fixtures.load("widgets", input: Input.self, expected: Expected.self)
        #expect(cases.count == 13)
        for testCase in cases {
            let input = testCase.input
            switch input.kind {
            case "dailyBalances":
                let changes = try (input.changes ?? []).map { WidgetWave.Change(at: try Self.date($0.at), delta: $0.delta) }
                let values = WidgetWave.dailyBalances(
                    current: try #require(input.currentBalance), changes: changes,
                    now: try Self.date(try #require(input.now)), calendar: try Self.calendar(try #require(input.timeZone)))
                #expect(values.map(Double.init) == testCase.expected.values, "\(testCase.name)")
                #expect(WidgetWave.change(values) == testCase.expected.change, "\(testCase.name)")
                #expect(WidgetWave.isCalm(values) == testCase.expected.calm, "\(testCase.name)")
            case "swell":
                let values = WidgetWave.swell(try #require(input.series))
                #expect(values.count == testCase.expected.values.count, "\(testCase.name)")
                for (value, expected) in zip(values, testCase.expected.values) {
                    #expect(abs(value - expected) < 1e-6, "\(testCase.name)")
                }
            default:
                Issue.record("Unknown kind \(input.kind)")
            }
        }
    }

    @Test func monotoneTangentsNeverOvershoot() {
        let xs = (0..<31).map(Double.init)
        let ys = WidgetWave.swell([0, 0, 0, 10, 10, 10, 0, 0, 5, 5, 5, 5, 20, 0, 0, 0, 0, 3, 3, 3, 3, 3, 3, 3, 9, 9, 1, 1, 1, 7, 7])
        let tangents = WidgetWave.tangents(xs: xs, ys: ys)
        for index in 0..<xs.count - 1 {
            // Hermite segments stay inside their endpoints when both control points do.
            let h = (xs[index + 1] - xs[index]) / 3
            let low = min(ys[index], ys[index + 1]) - 1e-9, high = max(ys[index], ys[index + 1]) + 1e-9
            for control in [ys[index] + tangents[index] * h, ys[index + 1] - tangents[index + 1] * h] {
                #expect(control >= low && control <= high, "segment \(index)")
            }
        }
        #expect(WidgetWave.tangents(xs: [0, 1, 2], ys: [4, 4, 4]) == [0, 0, 0])
    }

    @Test func aSnapshotFromAnEarlierDayShiftsToToday() throws {
        let calendar = try Self.calendar("UTC")
        let values = (0...30).map(Int64.init)
        let written = try Self.date("2026-10-25T23:00:00Z")
        #expect(WidgetWave.shifted(values, writtenAt: written, now: try Self.date("2026-10-25T23:59:00Z"), calendar: calendar) == values)
        let later = WidgetWave.shifted(values, writtenAt: written, now: try Self.date("2026-10-27T08:00:00Z"), calendar: calendar)
        #expect(later == Array(2...30).map(Int64.init) + [30, 30])
        let gone = WidgetWave.shifted(values, writtenAt: written, now: try Self.date("2026-12-27T08:00:00Z"), calendar: calendar)
        #expect(gone == Array(repeating: 30, count: 31))
        #expect(WidgetWave.isCalm(gone))
    }
}
