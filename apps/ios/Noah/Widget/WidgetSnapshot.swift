import Foundation

/// What the app writes for its widgets and they only read (docs/native-rewrite/design/widgets.md,
/// "Data the app writes"): app-group `UserDefaults["widgetSnapshot"]`, JSON. Never logged.
struct WidgetSnapshot: Codable, Equatable, Sendable {
    static let key = "widgetSnapshot"
    /// The balance dictionary React Native and earlier native builds wrote. Read only as a fallback.
    static let legacyKey = "widgetBalance"
    static let currentVersion = 1

    struct Fiat: Codable, Equatable, Sendable {
        var currency: String
        /// Home's latest quote: one bitcoin in `currency`.
        var btcPrice: Double
    }

    var version = currentVersion
    /// The daily balances end on this local day.
    var writtenAt: Date
    var balance: UInt64
    /// Home's arriving amount: incoming money only (`HomeStatusLine.arriving`).
    var arriving: UInt64
    /// 31 balances in sats, oldest first (`WidgetWave.dailyBalances`); nil when unknown (calm water).
    var dailyBalances: [Int64]? // swiftlint:disable:this discouraged_optional_collection - persisted JSON; nil: unknown
    var amountUnit: BitcoinAmountUnit
    var hidden: Bool
    var fiat: Fiat?
    /// The soonest refreshable VTXO's expiry, present only while Home's Renew rule holds.
    var renewBy: Date?
    /// `AppVariant.rawValue`, for the network chip.
    var variant: String

    /// The new snapshot, else one built from the old keys (balance, ₿, no fiat, calm water, no status line: their
    /// pending total also counted outgoing payments, so it can't stand for arriving), else nil: no wallet.
    static func read(from defaults: UserDefaults, variant: String, now: Date = Date()) -> Self? {
        if let data = defaults.data(forKey: key), let snapshot = try? JSONDecoder().decode(Self.self, from: data) {
            return snapshot
        }
        guard let legacy = defaults.dictionary(forKey: legacyKey) else { return nil }
        func sats(_ key: String) -> UInt64 {
            // swiftlint:disable:next legacy_objc_type - the old dictionary's numbers come back boxed as NSNumber
            guard let value = (legacy[key] as? NSNumber)?.doubleValue, value.isFinite, value > 0 else { return 0 }
            return UInt64(min(value, Double(Int64.max)))
        }
        return Self(writtenAt: now, balance: sats("totalBalance"), arriving: 0, dailyBalances: nil,
                    amountUnit: .bip177, hidden: false, fiat: nil, renewBy: nil, variant: variant)
    }

    /// Whole hours until the Renew deadline, as Home counts them; nil once it has passed or when none is due.
    func renewHours(now: Date) -> Int? {
        guard let renewBy, renewBy > now else { return nil }
        return Int(renewBy.timeIntervalSince(now) / 3600)
    }

    /// When a widget's timeline redraws without the app: now; each time Renew's whole hours count down and when
    /// it's due (the line goes); and the next local midnight, when the wave moves on a day.
    func timelineDates(now: Date, calendar: Calendar) -> [Date] {
        var dates = [now]
        if let renewBy, let hours = renewHours(now: now) {
            dates += (0...min(hours, 48)).map { renewBy.addingTimeInterval(1 - Double($0) * 3600) }
        }
        if dailyBalances != nil, let midnight = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now)) {
            dates.append(midnight)
        }
        return dates.filter { $0 >= now }.sorted()
    }

    /// The daily balances as of `now`, or nil for calm water (unknown, hidden or every day the same).
    func wave(now: Date, calendar: Calendar) -> [Int64]? { // swiftlint:disable:this discouraged_optional_collection - nil: calm water
        guard !hidden, let dailyBalances, dailyBalances.count == WidgetWave.days + 1 else { return nil }
        let values = WidgetWave.shifted(dailyBalances, writtenAt: writtenAt, now: now, calendar: calendar)
        return WidgetWave.isCalm(values) ? nil : values
    }
}
