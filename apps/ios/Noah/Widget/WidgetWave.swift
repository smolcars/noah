import Foundation

/// The widgets' balance wave (docs/native-rewrite/design/widgets.md, "The wave"). Shared by the
/// app, which writes the daily balances, and the widget extension, which draws them.
enum WidgetWave {
    static let days = 30

    /// A settled change to the balance.
    struct Change: Equatable, Sendable {
        var at: Date
        var delta: Int64
    }

    /// 31 daily balances, oldest first. Today's is `current`; each earlier day takes away every change
    /// whose local date (in `calendar`'s time zone) is after it. A change dated in the future (clock skew)
    /// counts as today; changes older than the window cancel out.
    static func dailyBalances(current: Int64, changes: [Change], now: Date, calendar: Calendar) -> [Int64] {
        let today = calendar.startOfDay(for: now)
        var onDay = [Int64](repeating: 0, count: days + 1)
        for change in changes {
            let day = calendar.startOfDay(for: min(change.at, now))
            guard let ago = calendar.dateComponents([.day], from: day, to: today).day, ago < days else { continue }
            onDay[days - ago] += change.delta
        }
        var values = [Int64](repeating: current, count: days + 1)
        var later: Int64 = 0
        for index in stride(from: days, to: 0, by: -1) {
            later += onDay[index]
            values[index - 1] = current - later
        }
        return values
    }

    /// The change label's amount: exact, not smoothed.
    static func change(_ values: [Int64]) -> Int64 { (values.last ?? 0) - (values.first ?? 0) }

    /// Calm water: every day the same (a new wallet, or no activity for 30 days).
    static func isCalm(_ values: [Int64]) -> Bool { values.allSatisfy { $0 == values.first } }

    /// The balances as of `now` when they were written on an earlier day: the days since have no known
    /// change, so they keep the last value.
    static func shifted(_ values: [Int64], writtenAt: Date, now: Date, calendar: Calendar) -> [Int64] {
        let elapsed = calendar.dateComponents([.day], from: calendar.startOfDay(for: writtenAt),
                                              to: calendar.startOfDay(for: now)).day ?? 0
        guard elapsed > 0, let last = values.last else { return values }
        let kept = values.dropFirst(min(elapsed, values.count))
        return Array(kept) + Array(repeating: last, count: values.count - kept.count)
    }

    /// Two passes of `(prev + 2·v + next) / 4` with clamped ends, so daily steps read as swell.
    static func swell(_ series: [Double]) -> [Double] {
        var values = series
        for _ in 0..<2 {
            values = values.indices.map { index in
                (values[max(0, index - 1)] + 2 * values[index] + values[min(values.count - 1, index + 1)]) / 4
            }
        }
        return values
    }

    /// Fritsch–Carlson tangents for a monotone cubic through the points, so the curve never overshoots.
    static func tangents(xs: [Double], ys: [Double]) -> [Double] {
        let count = xs.count
        guard count > 1 else { return Array(repeating: 0, count: count) }
        let slopes = (0..<count - 1).map { (ys[$0 + 1] - ys[$0]) / (xs[$0 + 1] - xs[$0]) }
        var tangents = [Double](repeating: 0, count: count)
        tangents[0] = slopes[0]
        tangents[count - 1] = slopes[count - 2]
        for index in 1..<count - 1 {
            tangents[index] = slopes[index - 1] * slopes[index] <= 0 ? 0 : (slopes[index - 1] + slopes[index]) / 2
        }
        for index in 0..<count - 1 {
            guard slopes[index] != 0 else {
                tangents[index] = 0
                tangents[index + 1] = 0
                continue
            }
            let a = tangents[index] / slopes[index], b = tangents[index + 1] / slopes[index]
            let length = a * a + b * b
            if length > 9 {
                let scale = 3 / length.squareRoot()
                tangents[index] = scale * a * slopes[index]
                tangents[index + 1] = scale * b * slopes[index]
            }
        }
        return tangents
    }
}
