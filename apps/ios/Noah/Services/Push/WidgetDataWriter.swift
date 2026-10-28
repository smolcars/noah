import Foundation
import Synchronization
import WidgetKit

/// Writes what the widgets read (`WidgetSnapshot`). Each writer (foreground refresh, push jobs,
/// background refresh, recurring) writes the whole snapshot, and it's written again when a setting it
/// shows changes. App-group storage only; never logged.
enum WidgetDataWriter {
    /// Home's Renew rule, as a writer read it.
    enum Renewal: Equatable, Sendable {
        /// Not read: the widget keeps what it has.
        case unknown
        case notDue
        case blocks(UInt32)
    }

    /// What a writer read besides the balance (`WalletSession.widgetFacts`).
    struct Facts: Equatable, Sendable {
        /// Settled balance changes for the wave. Nil when not read: the earlier days stay as written and
        /// today takes the new balance.
        var changes: [WidgetWave.Change]? // swiftlint:disable:this discouraged_optional_collection - nil: not read, unlike no changes
        var renewal = Renewal.unknown
    }

    /// Home's persisted settings the widgets follow.
    struct Display: Equatable, Sendable {
        var hidden: Bool
        var unit: BitcoinAmountUnit
        var currency: String
    }

    /// Home's latest quote in this process; the snapshot carries it to the next one.
    private static let quote = Mutex<WidgetSnapshot.Fiat?>(nil)

    static func write(balance: WalletBalance, facts: Facts, variant: AppVariant, settings: SettingsStorage = .shared,
                      now: Date = Date()) {
        guard let defaults = UserDefaults(suiteName: variant.appGroup) else { return }
        save(snapshot(balance: balance, facts: facts, previous: stored(defaults), display: Display(settings: settings),
                      quote: quote.withLock { $0 }, variant: variant, now: now, calendar: .current), to: defaults)
        mirrorLanguage(defaults)
        WidgetCenter.shared.reloadAllTimelines()
    }

    // swiftlint:disable:next function_parameter_count - the pure step the tests drive: every input explicit
    static func snapshot(balance: WalletBalance, facts: Facts, previous: WidgetSnapshot?, display: Display,
                         quote: WidgetSnapshot.Fiat?, variant: AppVariant, now: Date, calendar: Calendar) -> WidgetSnapshot {
        let current = Int64(clamping: balance.totalSats)
        var daily: [Int64]? // swiftlint:disable:this discouraged_optional_collection - nil: unknown (calm water)
        if let changes = facts.changes {
            daily = WidgetWave.dailyBalances(current: current, changes: changes, now: now, calendar: calendar)
        } else if let previous, let written = previous.dailyBalances, written.count == WidgetWave.days + 1 {
            daily = Array(WidgetWave.shifted(written, writtenAt: previous.writtenAt, now: now, calendar: calendar).dropLast()) + [current]
        }
        let renewBy: Date? = switch facts.renewal {
        case .unknown: previous?.renewBy
        case .notDue: nil
        case .blocks(let blocks): now.addingTimeInterval(Double(blocks) * 600)
        }
        return WidgetSnapshot(writtenAt: now, balance: balance.totalSats, pending: balance.pendingSats, dailyBalances: daily,
                              amountUnit: display.unit, hidden: display.hidden,
                              fiat: fiat(quote, previous?.fiat, currency: display.currency), renewBy: renewBy,
                              variant: variant.rawValue)
    }

    /// The hide setting, the unit or the fiat currency changed: the same snapshot, shown the new way.
    static func republish(variant: AppVariant, settings: SettingsStorage = .shared) {
        guard let defaults = UserDefaults(suiteName: variant.appGroup), let previous = stored(defaults) else { return }
        let display = Display(settings: settings)
        var snapshot = previous
        snapshot.hidden = display.hidden
        snapshot.amountUnit = display.unit
        snapshot.fiat = fiat(quote.withLock { $0 }, previous.fiat, currency: display.currency)
        guard snapshot != previous else { return }
        save(snapshot, to: defaults)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Home has a new quote.
    static func publishQuote(currency: String, btcPrice: Double, variant: AppVariant, settings: SettingsStorage = .shared) {
        quote.withLock { $0 = .init(currency: currency, btcPrice: btcPrice) }
        republish(variant: variant, settings: settings)
    }

    /// Deleting the wallet: no snapshot, old or new, so the widgets show No wallet.
    static func clear(_ defaults: UserDefaults) {
        defaults.removeObject(forKey: WidgetSnapshot.key)
        defaults.removeObject(forKey: WidgetSnapshot.legacyKey)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Copies Settings' language choice where the widget can read it (it can't read the app's
    /// own defaults), redrawing the widget only when it changed.
    static func publishLanguage(variant: AppVariant) {
        guard let defaults = UserDefaults(suiteName: variant.appGroup), mirrorLanguage(defaults) else { return }
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// The newest quote in the user's currency.
    private static func fiat(_ candidates: WidgetSnapshot.Fiat?..., currency: String) -> WidgetSnapshot.Fiat? {
        candidates.compactMap(\.self).first { $0.currency == currency }
    }

    private static func stored(_ defaults: UserDefaults) -> WidgetSnapshot? {
        defaults.data(forKey: WidgetSnapshot.key).flatMap { try? JSONDecoder().decode(WidgetSnapshot.self, from: $0) }
    }

    private static func save(_ snapshot: WidgetSnapshot, to defaults: UserDefaults) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        defaults.set(data, forKey: WidgetSnapshot.key)
        defaults.removeObject(forKey: WidgetSnapshot.legacyKey)
    }

    @discardableResult private static func mirrorLanguage(_ defaults: UserDefaults) -> Bool {
        let language = LegacyLocale.read()
        guard defaults.string(forKey: LegacyLocale.key) != language else { return false }
        defaults.set(language, forKey: LegacyLocale.key)
        return true
    }
}

extension WidgetDataWriter.Display {
    init(settings: SettingsStorage) {
        let profile = settings.persistedState(PersistedProfileState.self, key: PersistedProfileState.key) ?? .init()
        self.init(hidden: settings.persistedState(PersistedPrivacyState.self, key: PersistedPrivacyState.key)?.isHomeBalanceHidden ?? false,
                  unit: profile.amountUnit, currency: profile.currency)
    }
}
