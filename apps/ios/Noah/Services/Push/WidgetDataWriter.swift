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
        /// The balance changes for the wave. Nil when not read: the earlier days stay as written and
        /// today takes the new balance.
        var changes: [WidgetWave.Change]? // swiftlint:disable:this discouraged_optional_collection - nil: not read, unlike no changes
        /// Pending offboards, a change as far as the balance written no longer holds them (`WidgetBalanceHistory.offboarded`).
        var offboards: [WidgetWave.Change] = []
        /// The balance read just after the records; the wave uses them only if the balance written, read before them, agrees
        /// (`WidgetBalanceHistory.agrees`), else it keeps the earlier days. Nil: not given, the caller vouches for them.
        var balance: WalletBalance?
        var renewal = Renewal.unknown
        /// Incoming unconfirmed on-chain (`HomeStatusLine.incomingOnchain`); nil when not read.
        var incomingOnchain: UInt64?
    }

    /// Home's persisted settings the widgets follow.
    struct Display: Equatable, Sendable {
        var hidden: Bool
        var unit: BitcoinAmountUnit
        var currency: String
    }

    /// Home's latest quote in this process; the snapshot carries it to the next one.
    private static let quote = Mutex<WidgetSnapshot.Fiat?>(nil)
    private static let threads = NSLock()

    /// A writer's place in line, taken before it reads the wallet: a write whose reads started before another
    /// writer's, or before the wallet was deleted, is dropped rather than saved over newer data.
    struct Ticket: Equatable, Sendable {
        fileprivate let value: Int // swiftlint:disable:this strict_fileprivate - WidgetDataWriter, which encloses it, issues and compares it
    }

    private static let issuedKey = "widgetSnapshotTickets", publishedKey = "widgetSnapshotPublished"

    static func ticket(variant: AppVariant) -> Ticket {
        var ticket = Ticket(value: 0)
        update(variant.appGroup) { defaults in
            ticket = Ticket(value: defaults.integer(forKey: issuedKey) + 1)
            defaults.set(ticket.value, forKey: issuedKey)
            return false
        }
        return ticket
    }

    /// Saves the whole snapshot unless a writer that started reading later, or a deletion, got there first.
    /// `display` reads Home's settings when the snapshot is saved, never before.
    @discardableResult
    static func write(balance: WalletBalance, facts: Facts, variant: AppVariant, ticket: Ticket,
                      display: () -> Display = { Display(settings: .shared) }, now: Date = Date()) -> Bool {
        let saved = update(variant.appGroup) { defaults in
            guard ticket.value > defaults.integer(forKey: publishedKey) else { return false }
            save(snapshot(balance: balance, facts: facts, previous: stored(defaults), display: display(),
                          quote: quote.withLock { $0 }, variant: variant, now: now, calendar: .current), to: defaults)
            defaults.set(ticket.value, forKey: publishedKey)
            mirrorLanguage(defaults)
            return true
        }
        if saved { WidgetCenter.shared.reloadAllTimelines() }
        return saved
    }

    /// One snapshot change at a time, across threads and processes: read, change and save under the app group's
    /// lock file. Each change reads the settings it shows inside, so a slower writer never saves over a newer setting.
    /// `change` says whether it saved.
    @discardableResult
    private static func update(_ appGroup: String, _ change: (UserDefaults) -> Bool) -> Bool {
        guard let defaults = UserDefaults(suiteName: appGroup) else { return false }
        threads.lock()
        defer { threads.unlock() }
        let lock = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
            .map { open($0.appending(path: "widget-snapshot.lock").path, O_CREAT | O_RDWR | O_CLOEXEC, 0o600) } ?? -1
        if lock >= 0 { flock(lock, LOCK_EX) }
        defer { if lock >= 0 { flock(lock, LOCK_UN); close(lock) } }
        return change(defaults)
    }

    // swiftlint:disable:next function_parameter_count - the pure step the tests drive: every input explicit
    static func snapshot(balance: WalletBalance, facts: Facts, previous: WidgetSnapshot?, display: Display,
                         quote: WidgetSnapshot.Fiat?, variant: AppVariant, now: Date, calendar: Calendar) -> WidgetSnapshot {
        let current = Int64(clamping: balance.totalSats)
        var daily: [Int64]? // swiftlint:disable:this discouraged_optional_collection - nil: unknown (calm water)
        if let changes = facts.changes, facts.balance.map({ WidgetBalanceHistory.agrees($0, balance) }) ?? true {
            let offboarded = WidgetBalanceHistory.offboarded(facts.offboards, held: balance.ark.pendingOffboard)
            daily = WidgetWave.dailyBalances(current: current, changes: changes + offboarded, now: now, calendar: calendar)
        } else if let previous, let written = previous.dailyBalances, written.count == WidgetWave.days + 1 {
            daily = Array(WidgetWave.shifted(written, writtenAt: previous.writtenAt, now: now, calendar: calendar).dropLast()) + [current]
        }
        let renewBy: Date? = switch facts.renewal {
        case .unknown: previous?.renewBy
        case .notDue: nil
        case .blocks(let blocks): now.addingTimeInterval(Double(blocks) * 600)
        }
        return WidgetSnapshot(writtenAt: now, balance: balance.totalSats,
                              arriving: HomeStatusLine.arriving(balance, incomingOnchain: facts.incomingOnchain ?? 0), dailyBalances: daily,
                              amountUnit: display.unit, hidden: display.hidden,
                              fiat: fiat(quote, previous?.fiat, currency: display.currency), renewBy: renewBy,
                              variant: variant.rawValue)
    }

    /// The hide setting, the unit or the fiat currency changed: the same snapshot, shown the new way.
    static func republish(variant: AppVariant, display: () -> Display = { Display(settings: .shared) }) {
        let changed = update(variant.appGroup) { defaults in
            guard let previous = stored(defaults) else { return false }
            let display = display()
            var snapshot = previous
            snapshot.hidden = display.hidden
            snapshot.amountUnit = display.unit
            snapshot.fiat = fiat(quote.withLock { $0 }, previous.fiat, currency: display.currency)
            guard snapshot != previous else { return false }
            save(snapshot, to: defaults)
            return true
        }
        if changed { WidgetCenter.shared.reloadAllTimelines() }
    }

    /// Home has a new quote.
    static func publishQuote(currency: String, btcPrice: Double, variant: AppVariant,
                             display: () -> Display = { Display(settings: .shared) }) {
        quote.withLock { $0 = .init(currency: currency, btcPrice: btcPrice) }
        republish(variant: variant, display: display)
    }

    /// Deleting the wallet: no snapshot, old or new, so the widgets show No wallet, and no dates kept for its
    /// transactions.
    static func clear(appGroup: String, chainDates: WidgetChainDates = .shared) {
        update(appGroup) { defaults in
            defaults.removeObject(forKey: WidgetSnapshot.key)
            defaults.removeObject(forKey: WidgetSnapshot.legacyKey)
            // Writers still reading the deleted wallet can't bring it back.
            defaults.set(defaults.integer(forKey: issuedKey), forKey: publishedKey)
            return true
        }
        chainDates.clear()
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
