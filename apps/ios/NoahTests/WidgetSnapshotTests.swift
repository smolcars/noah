import BarkBoltFFI
import Foundation
@testable import Noah
import Synchronization
import Testing

/// The widgets' snapshot (docs/native-rewrite/design/widgets.md, "Data the app writes" and "Testing").
@Suite(.serialized)
struct WidgetSnapshotTests {
    private static let now = Date(timeIntervalSince1970: 1_793_113_200) // 2026-10-27T15:00:00Z
    private static var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        return calendar
    }

    private static func balance(ark: UInt64 = 0, claimable: UInt64 = 0, onchain: UInt64 = 0, unconfirmed: UInt64 = 0) -> WalletBalance {
        WalletBalance(ark: .init(spendable: ark, needsRefresh: 0, pendingLightningSend: 0, claimableLightningReceive: claimable,
                                 pendingInRound: 0, pendingExit: 0, pendingBoard: 0, pendingArkoorSend: 0, pendingOffboard: 0),
                      onchain: .init(confirmed: onchain, pending: unconfirmed, total: onchain + unconfirmed))
    }

    private static func snapshot(_ balance: WalletBalance, _ facts: WidgetDataWriter.Facts, previous: WidgetSnapshot? = nil,
                                 hidden: Bool = false, unit: BitcoinAmountUnit = .bip177, currency: String = "USD",
                                 quote: WidgetSnapshot.Fiat? = nil, now: Date = now) -> WidgetSnapshot {
        WidgetDataWriter.snapshot(balance: balance, facts: facts, previous: previous,
                                  display: .init(hidden: hidden, unit: unit, currency: currency), quote: quote,
                                  variant: .regtest, now: now, calendar: utc)
    }

    /// An isolated suite for each test, so no test touches the app group.
    private static func defaults() throws -> UserDefaults {
        let name = "widget-snapshot-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: name))
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test func writesAndReadsTheWholeSnapshot() throws {
        let day = 86_400.0
        let written = Self.snapshot(Self.balance(ark: 21_000, claimable: 1_000),
                                    .init(changes: [.init(at: Self.now.addingTimeInterval(-3 * day), delta: 20_000)], renewal: .blocks(108)),
                                    unit: .sats, quote: .init(currency: "USD", btcPrice: 100_000))
        #expect(written.balance == 22_000)
        #expect(written.arriving == 1_000)
        #expect(written.dailyBalances?.count == 31)
        #expect(written.dailyBalances?.suffix(5) == [2_000, 22_000, 22_000, 22_000, 22_000])
        #expect(written.fiat == .init(currency: "USD", btcPrice: 100_000))
        #expect(written.renewHours(now: Self.now) == 18)
        #expect(written.variant == "regtest")
        let defaults = try Self.defaults()
        defaults.set(try JSONEncoder().encode(written), forKey: WidgetSnapshot.key)
        #expect(WidgetSnapshot.read(from: defaults, variant: "regtest") == written)
    }

    @Test func oldKeysStillDrawTheNewDesign() throws {
        let defaults = try Self.defaults()
        #expect(WidgetSnapshot.read(from: defaults, variant: "regtest") == nil) // No wallet.
        // What React Native and earlier native builds wrote: doubles, plus keys the new design drops.
        defaults.set(["totalBalance": 125_000.0, "onchainBalance": 30_000.0, "offchainBalance": 95_000.0, "pendingBalance": 5_000.0,
                      "closestExpiryBlocks": 90.0, "expiryThreshold": 288.0], forKey: WidgetSnapshot.legacyKey)
        let snapshot = try #require(WidgetSnapshot.read(from: defaults, variant: "regtest", now: Self.now))
        // The old pending total also counted outgoing payments, so it never shows as arriving (as on Android).
        #expect(snapshot == WidgetSnapshot(writtenAt: Self.now, balance: 125_000, arriving: 0, dailyBalances: nil, amountUnit: .bip177,
                                           hidden: false, fiat: nil, renewBy: nil, variant: "regtest"))
        let shown = WidgetPresentation(snapshot: snapshot, now: Self.now, language: "en", calendar: Self.utc)
        #expect(shown.balance == "₿\u{00A0}125,000")
        #expect(shown.wave == nil && shown.change == nil && shown.fiat == nil)
        #expect(shown.status == nil)
        defaults.set(["totalBalance": -1.0, "pendingBalance": "5000"], forKey: WidgetSnapshot.legacyKey)
        #expect(WidgetSnapshot.read(from: defaults, variant: "regtest")?.balance == 0)
    }

    @Test func hiddenDrawsNoWaveAndDotsEverywhere() {
        let changes: [WidgetWave.Change] = [.init(at: Self.now.addingTimeInterval(-86_400 * 5), delta: 50_000)]
        let fiat = WidgetSnapshot.Fiat(currency: "USD", btcPrice: 100_000)
        for unit in BitcoinAmountUnit.allCases {
            let snapshot = Self.snapshot(Self.balance(ark: 50_000, claimable: 100_000), .init(changes: changes),
                                         hidden: true, unit: unit, quote: fiat)
            #expect(snapshot.dailyBalances != nil) // Written, but never drawn while hidden.
            let shown = WidgetPresentation(snapshot: snapshot, now: Self.now, language: "en", calendar: Self.utc)
            #expect(shown.wave == nil)
            #expect(shown.change == nil)
            #expect(shown.balance == (unit == .sats ? "•••••• sats" : "₿\u{00A0}••••••"))
            #expect(shown.fiat == "••••")
            #expect(shown.status == .arriving("•••• arriving")) // Home's hidden arriving line.
            let readsDigits = shown.accessibilityLabel.contains(where: \.isNumber)
            #expect(!readsDigits)
        }
    }

    /// The same balance and records give Home's arriving line: incoming only, capped at on-chain pending.
    @Test func arrivingIsHomesArrivingAmount() {
        func balance(claimable: UInt64, sending: UInt64 = 0, boarding: UInt64 = 0, onchainPending: UInt64 = 0) -> WalletBalance {
            WalletBalance(ark: .init(spendable: 10_000, needsRefresh: 0, pendingLightningSend: sending, claimableLightningReceive: claimable,
                                     pendingInRound: 0, pendingExit: 0, pendingBoard: boarding, pendingArkoorSend: 0, pendingOffboard: 0),
                          onchain: .init(confirmed: 5_000, pending: onchainPending, total: 5_000 + onchainPending))
        }
        struct Case { let balance: WalletBalance, incoming: UInt64, expected: UInt64 }
        let cases = [
            Case(balance: balance(claimable: 2_000), incoming: 0, expected: 2_000),
            Case(balance: balance(claimable: 0, sending: 7_000), incoming: 0, expected: 0), // A Lightning send in flight isn't arriving.
            Case(balance: balance(claimable: 0, boarding: 40_000, onchainPending: 1_200), incoming: 0, expected: 0), // A board's change isn't either.
            Case(balance: balance(claimable: 0, onchainPending: 30_000), incoming: 30_000, expected: 30_000), // An incoming on-chain payment is.
            Case(balance: balance(claimable: 500, onchainPending: 1_000), incoming: 30_000, expected: 1_500), // Capped at what the wallet counts pending.
        ]
        for testCase in cases {
            let (balance, incoming, expected) = (testCase.balance, testCase.incoming, testCase.expected)
            let snapshot = Self.snapshot(balance, .init(changes: [], incomingOnchain: incoming))
            #expect(snapshot.arriving == expected)
            #expect(snapshot.arriving == HomeStatusLine.arriving(balance, incomingOnchain: incoming))
            let home = HomeStatusLine.current(error: nil, balance: balance, incomingOnchain: incoming, activeRounds: 0)
            let shown = WidgetPresentation(snapshot: snapshot, now: Self.now, language: "en", calendar: Self.utc).status
            if case .arriving(let sats) = home {
                #expect(shown == .arriving("+" + BitcoinAmount.format(sats: Int64(sats), unit: .bip177, locale: Locale(identifier: "en")) + " arriving"))
            } else {
                #expect(shown == nil)
            }
        }
    }

    @Test func showsHomesAmountsInTheAppsLanguage() {
        let changes: [WidgetWave.Change] = [.init(at: Self.now.addingTimeInterval(-86_400 * 40), delta: 300_000),
                                            .init(at: Self.now.addingTimeInterval(-86_400 * 10), delta: -47_258)]
        let snapshot = Self.snapshot(Self.balance(ark: 252_742), .init(changes: changes), currency: "EUR",
                                     quote: .init(currency: "EUR", btcPrice: 100_000))
        let english = WidgetPresentation(snapshot: snapshot, now: Self.now, language: "en", calendar: Self.utc)
        let fiat = FiatCurrency.display(sats: 252_742, btcPrice: 100_000, currency: "EUR", locale: Locale(identifier: "en"))
        #expect(english.balance == "₿\u{00A0}252,742")
        #expect(english.fiat == fiat)
        #expect(english.change == .init(amount: "−₿\u{00A0}47,258", period: "30 days"))
        #expect(english.chip == "Regtest")
        #expect(english.accessibilityLabel == "₿\u{00A0}252,742, \(fiat), −₿\u{00A0}47,258 · 30 days")
        let swedish = WidgetPresentation(snapshot: snapshot, now: Self.now, language: "sv", calendar: Self.utc)
        #expect(swedish.balance == BitcoinAmount.format(sats: 252_742, unit: .bip177, locale: Locale(identifier: "sv")))
        #expect(swedish.change?.period == L10n.plural("{n} days", count: 30, locale: "sv"))
        // A calm month has no label; a new wallet is calm water at ₿ 0.
        let calm = Self.snapshot(Self.balance(ark: 30_000), .init(changes: [.init(at: Self.now.addingTimeInterval(-86_400 * 45), delta: 30_000)]))
        #expect(WidgetPresentation(snapshot: calm, now: Self.now, language: "en", calendar: Self.utc).change == nil)
        let fresh = WidgetPresentation(snapshot: Self.snapshot(Self.balance(), .init(changes: [])), now: Self.now, language: "en", calendar: Self.utc)
        #expect(fresh.balance == "₿\u{00A0}0" && fresh.wave == nil && fresh.change == nil && fresh.status == nil)
    }

    /// Same VTXOs, same answer as Home's nudge: present only when `HomeRenewal.select` finds some, with its hours.
    @Test func renewFollowsHomesRule() {
        let tip: UInt32 = 1_000
        let cases: [[HomeRenewal.Coin]] = [
            [], // Nothing expiring.
            [.init(id: "a", sats: 5_000, expiryHeight: 1_108, spendable: true)],
            [.init(id: "a", sats: 5_000, expiryHeight: 1_108, spendable: false)], // Locked.
            [.init(id: "a", sats: 5_000, expiryHeight: 990, spendable: true)], // Already expired.
            [.init(id: "a", sats: 5_000, expiryHeight: 1_005, spendable: true), .init(id: "b", sats: 1, expiryHeight: 1_130, spendable: true)],
        ]
        for coins in cases {
            let home = HomeRenewal.select(coins, tip: tip)
            let snapshot = Self.snapshot(Self.balance(ark: 5_000, claimable: 100),
                                         .init(changes: [], renewal: home.map { .blocks($0.blocksLeft) } ?? .notDue))
            #expect(snapshot.renewHours(now: Self.now) == home?.hoursLeft)
            let status = WidgetPresentation(snapshot: snapshot, now: Self.now, language: "en", calendar: Self.utc).status
            if let home {
                #expect(status == .renew(expiry: home.expiryText(locale: "en")), "Renew wins over arriving")
            } else {
                #expect(status == .arriving("+₿\u{00A0}100 arriving"))
            }
        }
        // After the due time the line goes, without the app.
        let due = Self.snapshot(Self.balance(ark: 5_000), .init(changes: [], renewal: .blocks(6)))
        #expect(due.renewHours(now: Self.now.addingTimeInterval(3_599)) == 0)
        #expect(due.renewHours(now: Self.now.addingTimeInterval(3_600)) == nil)
        let dates = due.timelineDates(now: Self.now, calendar: Self.utc)
        #expect(dates.first == Self.now)
        #expect(dates.contains(Self.now.addingTimeInterval(3_601)))
        #expect(dates.last == Self.utc.startOfDay(for: Self.now).addingTimeInterval(86_400))
    }

    @Test func writersWithoutRecordsKeepTheEarlierDaysAndRenew() {
        let first = Self.snapshot(Self.balance(ark: 30_000), .init(changes: [.init(at: Self.now.addingTimeInterval(-86_400 * 3), delta: 30_000)],
                                                                   renewal: .blocks(60)), quote: .init(currency: "USD", btcPrice: 90_000))
        // Recurring paid 5,000 the next day and read only the balance.
        let next = Self.snapshot(Self.balance(ark: 25_000), .init(), previous: first, now: Self.now.addingTimeInterval(86_400))
        #expect(next.dailyBalances?.suffix(6) == [0, 30_000, 30_000, 30_000, 30_000, 25_000])
        #expect(next.renewBy == first.renewBy)
        #expect(next.fiat == first.fiat)
        // A currency without a quote has no fiat line until Home has one.
        #expect(Self.snapshot(Self.balance(ark: 25_000), .init(), previous: first, currency: "EUR").fiat == nil)
        #expect(Self.snapshot(Self.balance(ark: 25_000), .init(), previous: first, currency: "EUR",
                              quote: .init(currency: "EUR", btcPrice: 80_000)).fiat?.btcPrice == 80_000)
        // Nothing earlier: calm water.
        #expect(Self.snapshot(Self.balance(ark: 25_000), .init()).dailyBalances == nil)
    }
}

/// In the serialized suite that already writes the app group's snapshot, so the two never race.
extension ForegroundWidgetRefreshTests {
    private static func balance(ark: UInt64) -> WalletBalance {
        WalletBalance(ark: .init(spendable: ark, needsRefresh: 0, pendingLightningSend: 0, claimableLightningReceive: 0,
                                 pendingInRound: 0, pendingExit: 0, pendingBoard: 0, pendingArkoorSend: 0, pendingOffboard: 0),
                      onchain: .init(confirmed: 0, pending: 0, total: 0))
    }

    @Test func settingsAndQuotesRepublishAndDeletionClears() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "widget-settings-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try SettingsStorage(rootPath: root.path)
        let defaults = try #require(UserDefaults(suiteName: AppVariant.regtest.appGroup))
        let previous = (defaults.object(forKey: WidgetSnapshot.key), defaults.object(forKey: WidgetSnapshot.legacyKey))
        defer { defaults.set(previous.0, forKey: WidgetSnapshot.key); defaults.set(previous.1, forKey: WidgetSnapshot.legacyKey) }
        defaults.removeObject(forKey: WidgetSnapshot.key)
        WidgetDataWriter.republish(variant: .regtest, display: { .init(settings: settings) })
        #expect(defaults.data(forKey: WidgetSnapshot.key) == nil) // Nothing to republish before the first write.

        WidgetDataWriter.write(balance: Self.balance(ark: 1_000), facts: .init(changes: []), variant: .regtest,
                               ticket: WidgetDataWriter.ticket(variant: .regtest), display: { .init(settings: settings) })
        try settings.setPersistedState(PersistedPrivacyState(isHomeBalanceHidden: true), key: PersistedPrivacyState.key,
                                       version: PersistedPrivacyState.version)
        try settings.setPersistedState(PersistedProfileState(preferredCurrency: "JPY", bitcoinAmountUnit: "sats"),
                                       key: PersistedProfileState.key, version: PersistedProfileState.version)
        WidgetDataWriter.republish(variant: .regtest, display: { .init(settings: settings) })
        var snapshot = try #require(WidgetSnapshot.read(from: defaults, variant: "regtest"))
        #expect(snapshot.hidden && snapshot.amountUnit == .sats && snapshot.balance == 1_000)
        WidgetDataWriter.publishQuote(currency: "JPY", btcPrice: 15_000_000, variant: .regtest, display: { .init(settings: settings) })
        snapshot = try #require(WidgetSnapshot.read(from: defaults, variant: "regtest"))
        #expect(snapshot.fiat == .init(currency: "JPY", btcPrice: 15_000_000))

        WidgetDataWriter.clear(appGroup: AppVariant.regtest.appGroup)
        #expect(WidgetSnapshot.read(from: defaults, variant: "regtest") == nil)
    }
}

/// A writer that read the settings before the user hid their balance can't save over the hidden snapshot.
extension ForegroundWidgetRefreshTests {
    @Test func aSlowWriterCannotUndoHidingTheBalance() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "widget-race-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try SettingsStorage(rootPath: root.path)
        let defaults = try #require(UserDefaults(suiteName: AppVariant.regtest.appGroup))
        let previous = (defaults.object(forKey: WidgetSnapshot.key), defaults.object(forKey: WidgetSnapshot.legacyKey))
        defer { defaults.set(previous.0, forKey: WidgetSnapshot.key); defaults.set(previous.1, forKey: WidgetSnapshot.legacyKey) }
        let balance = Self.balance(ark: 1_000)
        WidgetDataWriter.write(balance: balance, facts: .init(changes: []), variant: .regtest,
                               ticket: WidgetDataWriter.ticket(variant: .regtest), display: { .init(settings: settings) })
        let readSettings = Mutex(false)
        // A push job's writer reads the settings (balance shown), then takes a while to save.
        let ticket = WidgetDataWriter.ticket(variant: .regtest)
        let writer = Task.detached {
            WidgetDataWriter.write(balance: balance, facts: .init(changes: []), variant: .regtest, ticket: ticket, display: {
                let display = WidgetDataWriter.Display(settings: settings)
                readSettings.withLock { $0 = true }
                Thread.sleep(forTimeInterval: 0.3)
                return display
            })
        }
        try await SendPaymentTests.waitUntil { readSettings.withLock { $0 } }
        // Meanwhile the user hides their balance: Home saves it, then republishes.
        try settings.setPersistedState(PersistedPrivacyState(isHomeBalanceHidden: true), key: PersistedPrivacyState.key,
                                       version: PersistedPrivacyState.version)
        let republish = Task.detached { WidgetDataWriter.republish(variant: .regtest, display: { .init(settings: settings) }) }
        _ = await writer.value
        await republish.value
        #expect(WidgetSnapshot.read(from: defaults, variant: "regtest")?.hidden == true)
    }

    /// Switching currency drops the other currency's quote; switching back inside Home's cache window brings the
    /// cached quote back to the widget without a fetch.
    @Test func switchingBackToACachedCurrencyKeepsTheFiatLine() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "widget-currency-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try SettingsStorage(rootPath: root.path)
        let defaults = try #require(UserDefaults(suiteName: AppVariant.regtest.appGroup))
        let previous = (defaults.object(forKey: WidgetSnapshot.key), defaults.object(forKey: WidgetSnapshot.legacyKey))
        defer { defaults.set(previous.0, forKey: WidgetSnapshot.key); defaults.set(previous.1, forKey: WidgetSnapshot.legacyKey) }
        let display: () -> WidgetDataWriter.Display = { .init(settings: settings) }
        func choose(_ currency: String) throws {
            try settings.setPersistedState(PersistedProfileState(preferredCurrency: currency, bitcoinAmountUnit: "bip177"),
                                           key: PersistedProfileState.key, version: PersistedProfileState.version)
            WidgetDataWriter.republish(variant: .regtest, display: display) // What AppModel.updateProfile does.
        }
        func fiat() -> WidgetSnapshot.Fiat? { WidgetSnapshot.read(from: defaults, variant: "regtest")?.fiat }
        try choose("USD")
        WidgetDataWriter.write(balance: Self.balance(ark: 1_000), facts: .init(changes: []), variant: .regtest,
                               ticket: WidgetDataWriter.ticket(variant: .regtest), display: display)
        let now = Date(timeIntervalSince1970: 10_000)
        var fetches: [String] = []
        let home = HomeModel(dependencies: .init(
            price: { fetches.append($0); return $0 == "USD" ? 100_000 : 90_000 }, rounds: { [] }, backup: { PersistedBackupState() },
            retryBackup: {}, autoBoards: { [] }, backgroundSnapshot: { .init(isRunning: false, completionRevision: 0) },
            now: { now }, renewal: { nil },
            publishQuote: { WidgetDataWriter.publishQuote(currency: $0, btcPrice: $1, variant: .regtest, display: display) }))
        await home.loadPrice(currency: "USD")
        #expect(fiat() == .init(currency: "USD", btcPrice: 100_000))
        try choose("EUR")
        #expect(fiat() == nil)
        await home.loadPrice(currency: "EUR")
        #expect(fiat() == .init(currency: "EUR", btcPrice: 90_000))
        try choose("USD")
        await home.loadPrice(currency: "USD") // Cached: no fetch.
        #expect(fiat() == .init(currency: "USD", btcPrice: 100_000))
        #expect(fetches == ["USD", "EUR"])
    }

    /// A push reads balance A and stalls; the foreground reads and publishes newer balance B; the push's late write
    /// of A is dropped. A writer still reading a deleted wallet can't bring it back either.
    @Test func aStalledWriterCannotPublishAnOlderBalance() throws {
        let defaults = try #require(UserDefaults(suiteName: AppVariant.regtest.appGroup))
        let previous = (defaults.object(forKey: WidgetSnapshot.key), defaults.object(forKey: WidgetSnapshot.legacyKey))
        defer { defaults.set(previous.0, forKey: WidgetSnapshot.key); defaults.set(previous.1, forKey: WidgetSnapshot.legacyKey) }
        let display: () -> WidgetDataWriter.Display = { .init(hidden: false, unit: .bip177, currency: "USD") }
        let push = WidgetDataWriter.ticket(variant: .regtest) // Then it reads balance A and stalls.
        let foreground = WidgetDataWriter.ticket(variant: .regtest)
        #expect(WidgetDataWriter.write(balance: Self.balance(ark: 2_000), facts: .init(changes: []), variant: .regtest,
                                       ticket: foreground, display: display))
        let stale = Date().addingTimeInterval(60)
        #expect(!WidgetDataWriter.write(balance: Self.balance(ark: 1_000), facts: .init(changes: []), variant: .regtest,
                                        ticket: push, display: display, now: stale))
        let shown = try #require(WidgetSnapshot.read(from: defaults, variant: "regtest"))
        #expect(shown.balance == 2_000 && shown.writtenAt < stale)
        // The other way round both land, newest last; a ticket is used once.
        let first = WidgetDataWriter.ticket(variant: .regtest), second = WidgetDataWriter.ticket(variant: .regtest)
        #expect(WidgetDataWriter.write(balance: Self.balance(ark: 3_000), facts: .init(changes: []), variant: .regtest, ticket: first, display: display))
        #expect(WidgetDataWriter.write(balance: Self.balance(ark: 4_000), facts: .init(changes: []), variant: .regtest, ticket: second, display: display))
        #expect(!WidgetDataWriter.write(balance: Self.balance(ark: 4_000), facts: .init(changes: []), variant: .regtest, ticket: second, display: display))
        #expect(WidgetSnapshot.read(from: defaults, variant: "regtest")?.balance == 4_000)
        // Deleting the wallet while a push is still reading it.
        let reading = WidgetDataWriter.ticket(variant: .regtest)
        WidgetDataWriter.clear(appGroup: AppVariant.regtest.appGroup)
        #expect(!WidgetDataWriter.write(balance: Self.balance(ark: 5_000), facts: .init(changes: []), variant: .regtest, ticket: reading, display: display))
        #expect(WidgetSnapshot.read(from: defaults, variant: "regtest") == nil)
        #expect(WidgetDataWriter.write(balance: Self.balance(ark: 0), facts: .init(changes: []), variant: .regtest,
                                       ticket: WidgetDataWriter.ticket(variant: .regtest), display: display))
    }
}

/// Bark's records → the wave's balance changes, against the history fixtures Activity is held to.
struct WidgetBalanceHistoryTests {
    private static let now = Date(timeIntervalSince1970: 1_793_113_200)

    @Test func settledMovementsAreChangesAtActivitysDate() throws {
        let cases = try Fixtures.load("transaction-history", input: HistoryFixtureTests.Input.self, expected: HistoryFixtureTests.JSON.self)
            .filter { $0.input.operation == "movement" }
        #expect(cases.count == 51)
        var settled = 0
        for fixture in cases {
            let movement = try #require(fixture.input.movement).sdk
            let changes = WidgetBalanceHistory.changes(movements: [movement], chain: [], chainDates: [:], pending: .init(), now: Self.now)
            if movement.status == "successful" && movement.effectiveBalanceSats != 0 {
                settled += 1
                let row = HistoryMapping.transaction(movement, now: Self.now)
                #expect(changes == [.init(at: try #require(row.timestamp), delta: movement.effectiveBalanceSats)], "\(fixture.name)")
            } else {
                // Failed and canceled aren't changes, nor pending ones that still hold their sats (no fixture names an offboard's
                // transaction; receives and boards count by their own state, `aReceiveCountsByItsOwnState`).
                #expect(changes.isEmpty, "\(fixture.name)")
            }
        }
        #expect(settled > 0)
    }

    @Test func chainTransactionsAreChangesConfirmedOrNot() throws {
        let cases = try Fixtures.load("transaction-history", input: HistoryFixtureTests.Input.self, expected: HistoryFixtureTests.JSON.self)
            .filter { $0.input.operation == "onchain" }
        #expect(cases.count == 6)
        for fixture in cases {
            let chain = try #require(fixture.input.onchain).decode(HistoryFixtureTests.ChainInput.self).sdk
            let estimate = WidgetBalanceHistory.estimate(chain, tip: 48, now: Self.now)
            #expect(estimate == chain.confirmation.map { Self.now.addingTimeInterval(-Double(48 - $0.height) * 600) }, "\(fixture.name)")
            // Dated by the store, confirmed or not: the on-chain total counts both.
            let at = Self.now.addingTimeInterval(-86_400)
            let changes = WidgetBalanceHistory.changes(movements: [], chain: [chain], chainDates: [chain.txid: at], pending: .init(), now: Self.now)
            if chain.balanceChangeSats != 0 {
                #expect(changes == [.init(at: at, delta: chain.balanceChangeSats)], "\(fixture.name)")
            } else {
                #expect(changes.isEmpty, "\(fixture.name)")
            }
        }
    }

    /// A board's two halves count on its date: the funding transaction as the on-chain total counts it, the board once
    /// bark reports its VTXO in `pendingBoard` (`pendingBoardVtxos`).
    @Test func boardsCountBothHalvesOnTheirDate() {
        let created = Self.now.addingTimeInterval(-86_400)
        let board = { (status: String) in
            Self.movement("bark.board", "board", metadata: #"{"chain_anchor":"funding-txid:0"}"#, effective: 50_000, created: created,
                          status: status, outputs: ["board-vtxo"])
        }
        let funding = WalletTransaction(txid: "funding-txid", txHex: "", onchainFeeSats: 500, balanceChangeSats: -50_500,
                                        confirmation: .init(height: 10, hash: "block"), isCpfp: false)
        let elsewhere: [String: Date] = ["funding-txid": Self.now] // Never used for a transaction a movement names.
        func changes(_ status: String, _ transaction: WalletTransaction, boards: Set<String> = []) -> [WidgetWave.Change] {
            WidgetBalanceHistory.changes(movements: [board(status)], chain: [transaction], chainDates: elsewhere,
                                         pending: .init(boardVtxos: boards), now: Self.now)
        }
        #expect(changes("successful", funding) == [.init(at: created, delta: 50_000), .init(at: created, delta: -50_500)])
        var unconfirmed = funding
        unconfirmed.confirmation = nil
        for transaction in [funding, unconfirmed] {
            #expect(changes("pending", transaction) == [.init(at: created, delta: -50_500)]) // Its VTXO isn't counted yet.
            #expect(changes("pending", transaction, boards: ["board-vtxo"]) == [.init(at: created, delta: 50_000), .init(at: created, delta: -50_500)])
            #expect(changes("pending", transaction, boards: ["another-board"]) == [.init(at: created, delta: -50_500)])
        }
    }

    private static var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        return calendar
    }

    private static func movement(_ name: String, _ kind: String, metadata: String, effective: Int64, created: Date,
                                 status: String = "successful", id: UInt32 = 9, outputs: [String] = []) -> Movement {
        let at = HistoryRules.isoDate(created)
        return Movement(id: id, status: status, subsystemName: name, subsystemKind: kind, metadataJson: metadata,
                        intendedBalanceSats: effective, effectiveBalanceSats: effective, offchainFeeSats: 0, sentToAddresses: [],
                        receivedOnAddresses: [], inputVtxoIds: [], outputVtxoIds: outputs, exitedVtxoIds: [], createdAt: at, updatedAt: at,
                        completedAt: nil, paymentHash: nil, lightningInvoice: nil, lightningOffer: nil)
    }

    /// A round offboard names its on-chain credit by the round's funding transaction: both halves share its date,
    /// on either side of the window's edge, and a renewal in the same round doesn't take the credit.
    @Test func aRoundOffboardsTwoHalvesStayTogetherAcrossTheWindowEdge() {
        let credit = WalletTransaction(txid: "round-txid", txHex: "", onchainFeeSats: nil, balanceChangeSats: 50_000,
                                       confirmation: .init(height: 1_000, hash: "h"), isCpfp: false)
        let recently: [String: Date] = ["round-txid": Self.now] // What a height estimate would say.
        for days in [40.0, 30.5, 29.5, 5.0] {
            let created = Self.now.addingTimeInterval(-days * 86_400)
            let offboard = Self.movement("bark.round", "offboard", metadata: #"{"funding_txid":"round-txid"}"#, effective: -50_300,
                                         created: created)
            let renewal = Self.movement("bark.round", "refresh", metadata: #"{"funding_txid":"round-txid"}"#, effective: 0,
                                        created: created, status: "pending")
            for movements in [[offboard], [renewal, offboard], [offboard, renewal]] {
                let changes = WidgetBalanceHistory.changes(movements: movements, chain: [credit], chainDates: recently, pending: .init(), now: Self.now)
                #expect(changes == [.init(at: created, delta: -50_300), .init(at: created, delta: 50_000)], "\(days) days")
                let values = WidgetWave.dailyBalances(current: 100_000, changes: changes, now: Self.now, calendar: Self.utc)
                #expect(WidgetWave.change(values) == (days > 30 ? 0 : -300), "\(days) days")
            }
        }
    }

    /// The first estimate of an on-chain date is kept, so it never moves with the clock or the tip; the block's
    /// own time from History replaces it; entries go with their transactions and with the wallet.
    @Test func onChainDatesNeverDrift() throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "widget-dates-\(UUID())")
        defer { try? FileManager.default.removeItem(at: folder) }
        let dates = WidgetChainDates(url: folder.appending(path: "dates.json"))
        let receive = WalletTransaction(txid: "chain-txid", txHex: "", onchainFeeSats: nil, balanceChangeSats: 21_000,
                                        confirmation: .init(height: 1_000, hash: "h"), isCpfp: false)
        var seen: [Date] = []
        for (day, tip) in [(0.0, UInt32(1_006)), (1, 1_006), (2, 1_300), (31, 1_006)] {
            let now = Self.now.addingTimeInterval(day * 86_400)
            let estimate = try #require(WidgetBalanceHistory.estimate(receive, tip: tip, now: now))
            let confirmed = dates.dates([receive.txid: estimate], keeping: [receive.txid])
            let changes = WidgetBalanceHistory.changes(movements: [], chain: [receive], chainDates: confirmed, pending: .init(), now: now)
            seen += changes.map(\.at)
        }
        #expect(seen == Array(repeating: Self.now.addingTimeInterval(-3_600), count: 4))
        let block = Self.now.addingTimeInterval(-5_000)
        dates.record(txid: receive.txid, at: block)
        #expect(dates.dates([receive.txid: Self.now], keeping: [receive.txid]) == [receive.txid: block])
        // Another wallet's transaction goes; a wallet deletion clears the rest.
        #expect(dates.dates(["other": Self.now], keeping: ["other"]) == ["other": Self.now])
        #expect(dates.dates([receive.txid: Self.now], keeping: [receive.txid]) == [receive.txid: Self.now])
        dates.clear()
        #expect(!FileManager.default.fileExists(atPath: folder.appending(path: "dates.json").path))
    }

    private static func balance(spendable: UInt64 = 0, sending: UInt64 = 0, claimable: UInt64 = 0, boarding: UInt64 = 0,
                                offboarding: UInt64 = 0, confirmed: UInt64 = 0, unconfirmed: UInt64 = 0) -> WalletBalance {
        WalletBalance(ark: .init(spendable: spendable, needsRefresh: 0, pendingLightningSend: sending, claimableLightningReceive: claimable,
                                 pendingInRound: 0, pendingExit: 0, pendingBoard: boarding, pendingArkoorSend: 0, pendingOffboard: offboarding),
                      onchain: .init(confirmed: confirmed, pending: unconfirmed, total: confirmed + unconfirmed))
    }

    /// The writer's 31 days for `balance` and the records, as `widgetFacts` reads them; each case checks that today is the
    /// balance and so yesterday is the balance less today's changes.
    private static func days(_ balance: WalletBalance, _ movements: [Movement] = [], chain: [WalletTransaction] = [],
                             chainDates: [String: Date] = [:], pending: WidgetBalanceHistory.Pending = .init(),
                             now: Date = now) throws -> [Int64] {
        let facts = WidgetDataWriter.Facts(changes: WidgetBalanceHistory.changes(movements: movements, chain: chain, chainDates: chainDates,
                                                                                 pending: pending, now: now),
                                           offboards: WidgetBalanceHistory.offboards(movements: movements, now: now))
        let snapshot = WidgetDataWriter.snapshot(balance: balance, facts: facts, previous: nil,
                                                 display: .init(hidden: false, unit: .bip177, currency: "USD"), quote: nil,
                                                 variant: .regtest, now: now, calendar: utc)
        let days = try #require(snapshot.dailyBalances)
        #expect(days.last == Int64(balance.totalSats))
        return days
    }

    /// 100,000 sats that arrived ten days ago, settled.
    private static let earlier = movement("bark.arkoor", "receive", metadata: "{}", effective: 100_000, created: now.addingTimeInterval(-10 * 86_400))
    private static let before = Array(repeating: Int64(0), count: 20) + Array(repeating: 100_000, count: 10)

    /// An unconfirmed on-chain receive is in the balance, so it's today's change from when it's first seen, and confirming
    /// (even the next day, even with History's block time) never moves it.
    @Test func anUnconfirmedReceiveIsTodaysChangeAndStaysThere() throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "widget-dates-\(UUID())")
        defer { try? FileManager.default.removeItem(at: folder) }
        let dates = WidgetChainDates(url: folder.appending(path: "dates.json"))
        var receive = WalletTransaction(txid: "receive-txid", txHex: "", onchainFeeSats: nil, balanceChangeSats: 50_000,
                                        confirmation: nil, isCpfp: false)
        let seen = Self.now.addingTimeInterval(-3_600)
        let pending = try Self.days(Self.balance(spendable: 100_000, unconfirmed: 50_000), [Self.earlier], chain: [receive],
                                    chainDates: dates.dates([:], keeping: [receive.txid], unconfirmed: [receive.txid], now: seen))
        #expect(pending == Self.before + [150_000])

        receive.confirmation = .init(height: 1_000, hash: "h")
        let tomorrow = Self.now.addingTimeInterval(86_400)
        dates.record(txid: receive.txid, at: tomorrow.addingTimeInterval(-600)) // History's block time doesn't move it either.
        let estimate = try #require(WidgetBalanceHistory.estimate(receive, tip: 1_001, now: tomorrow))
        let confirmed = try Self.days(Self.balance(spendable: 100_000, confirmed: 50_000), [Self.earlier], chain: [receive],
                                      chainDates: dates.dates([receive.txid: estimate], keeping: [receive.txid], now: tomorrow), now: tomorrow)
        #expect(confirmed == Array(pending.dropFirst()) + [150_000])
    }

    /// A Lightning send in flight still holds its sats in the balance (`pendingLightningSend`), so it isn't a change until
    /// it settles.
    @Test func aPendingLightningSendIsNoChangeUntilItSettles() throws {
        let send = { (status: String) in
            Self.movement("bark.lightning_send", "send", metadata: "{}", effective: -20_010, created: Self.now.addingTimeInterval(-600), status: status)
        }
        #expect(try Self.days(Self.balance(spendable: 79_990, sending: 20_010), [Self.earlier, send("pending")]) == Self.before + [100_000])
        #expect(try Self.days(Self.balance(spendable: 79_990), [Self.earlier, send("successful")]) == Self.before + [79_990])
    }

    /// An offboard is a change only as far as `pendingOffboard` lets its sats go: not while its transaction is named but
    /// not out (a failed broadcast), nor while it holds change being split off; then once it's out, and nothing moves when
    /// it settles. Two at once share the bucket.
    @Test func anOffboardCountsOnceTheBalanceLetsItsSatsGo() throws {
        let offboard = { (status: String, created: Date) in
            Self.movement("bark.offboard", "send_onchain", metadata: #"{"offboard_txid":"out"}"#, effective: -30_300, created: created,
                          status: status)
        }
        let first = offboard("pending", Self.now.addingTimeInterval(-1_200))
        #expect(try Self.days(Self.balance(offboarding: 100_000), [Self.earlier, first]) == Self.before + [100_000]) // Splitting change.
        // Named, held, broadcast failing (`ReadyForBroadcast`), and again on the retry.
        for _ in 0..<2 {
            #expect(try Self.days(Self.balance(spendable: 69_700, offboarding: 30_300), [Self.earlier, first]) == Self.before + [100_000])
        }
        #expect(try Self.days(Self.balance(spendable: 69_700), [Self.earlier, first]) == Self.before + [69_700]) // Out.
        #expect(try Self.days(Self.balance(spendable: 69_700), [Self.earlier, offboard("successful", Self.now.addingTimeInterval(-1_200))])
            == Self.before + [69_700])

        var second = offboard("pending", Self.now.addingTimeInterval(-600))
        second.effectiveBalanceSats = -10_000
        #expect(try Self.days(Self.balance(spendable: 59_700, offboarding: 10_000), [Self.earlier, first, second]) == Self.before + [69_700])
        #expect(try Self.days(Self.balance(spendable: 59_700), [Self.earlier, first, second]) == Self.before + [59_700])
    }

    /// A board: nothing while its funding transaction isn't out; then the funding transaction and the board (once
    /// `pendingBoardVtxos` reports its VTXO) are today's change, net of the fee, and nothing moves when it confirms and
    /// completes.
    @Test func aPendingBoardCountsBothHalvesFromItsDate() throws {
        let deposit = WalletTransaction(txid: "deposit-txid", txHex: "", onchainFeeSats: nil, balanceChangeSats: 50_500,
                                        confirmation: .init(height: 900, hash: "d"), isCpfp: false)
        let dated: [String: Date] = [deposit.txid: Self.now.addingTimeInterval(-10 * 86_400)]
        let board = { (status: String) in
            Self.movement("bark.board", "board", metadata: #"{"chain_anchor":"funding-txid:0"}"#, effective: 50_000,
                          created: Self.now.addingTimeInterval(-600), status: status, outputs: ["board-vtxo"])
        }
        var funding = WalletTransaction(txid: "funding-txid", txHex: "", onchainFeeSats: 500, balanceChangeSats: -50_500,
                                        confirmation: nil, isCpfp: false)
        let earlier = Array(repeating: Int64(0), count: 20) + Array(repeating: 50_500, count: 10)
        let confirming = WidgetBalanceHistory.Pending(boardVtxos: ["board-vtxo"])
        #expect(try Self.days(Self.balance(confirmed: 50_500), [board("pending")], chain: [deposit], chainDates: dated) == earlier + [50_500])
        #expect(try Self.days(Self.balance(boarding: 50_000), [board("pending")], chain: [deposit, funding], chainDates: dated, pending: confirming)
            == earlier + [50_000])
        funding.confirmation = .init(height: 1_000, hash: "f")
        #expect(try Self.days(Self.balance(boarding: 50_000), [board("pending")], chain: [deposit, funding], chainDates: dated, pending: confirming)
            == earlier + [50_000])
        #expect(try Self.days(Self.balance(spendable: 50_000), [board("successful")], chain: [deposit, funding], chainDates: dated)
            == earlier + [50_000])
    }

    /// Lightning receives count by their own state (`pendingLightningReceives`: "preimage-revealed"), whatever order they
    /// progress in; one cancelled or failed is no change at all, and the earlier days never move.
    @Test func aReceiveCountsByItsOwnState() throws {
        let older = { (status: String, effective: Int64) in
            Self.movement("bark.lightning_receive", "receive", metadata: "{}", effective: effective, created: Self.now.addingTimeInterval(-1_200),
                          status: status, id: 1)
        }
        let newer = { (status: String, effective: Int64) in
            Self.movement("bark.lightning_receive", "receive", metadata: "{}", effective: effective, created: Self.now.addingTimeInterval(-600),
                          status: status, id: 2)
        }
        let both = [Self.earlier, older("pending", 10_000), newer("pending", 20_000)]
        #expect(try Self.days(Self.balance(spendable: 100_000), both) == Self.before + [100_000]) // Both HTLCs ready, none claimable.
        // The newer one's preimage is out first.
        #expect(try Self.days(Self.balance(spendable: 100_000, claimable: 20_000), both, pending: .init(claimableReceives: [2]))
            == Self.before + [120_000])
        #expect(try Self.days(Self.balance(spendable: 100_000, claimable: 30_000), both, pending: .init(claimableReceives: [1, 2]))
            == Self.before + [130_000])
        // The older one is cancelled instead.
        #expect(try Self.days(Self.balance(spendable: 100_000, claimable: 20_000), [Self.earlier, older("canceled", 0), newer("pending", 20_000)],
                              pending: .init(claimableReceives: [2])) == Self.before + [120_000])
        #expect(try Self.days(Self.balance(spendable: 120_000), [Self.earlier, older("canceled", 0), newer("successful", 20_000)])
            == Self.before + [120_000])
        // A claimable one that then fails.
        #expect(try Self.days(Self.balance(spendable: 100_000), [Self.earlier, older("canceled", 0), newer("failed", 0)]) == Self.before + [100_000])
    }

    private struct Records {
        var movements: [Movement]
        var chain: [WalletTransaction] = []
        var pending = WidgetBalanceHistory.Pending()
    }

    /// What the writer draws for `shown` (the summary's balance, read before the records) from `records` and the reading
    /// after them, as `widgetFacts` hands them on (none when it was late or failed), after a snapshot written earlier today.
    private static func written(_ shown: WalletBalance, _ records: Records, after: WalletBalance?, earlier: [Int64],
                                dated: [String: Date] = [:]) throws -> [Int64] {
        var facts = WidgetDataWriter.Facts()
        if let after {
            facts.changes = WidgetBalanceHistory.changes(movements: records.movements, chain: records.chain, chainDates: dated,
                                                         pending: records.pending, now: now)
            facts.balance = after
        }
        let previous = WidgetSnapshot(writtenAt: now.addingTimeInterval(-300), balance: shown.totalSats, arriving: 0, dailyBalances: earlier,
                                      amountUnit: .bip177, hidden: false, fiat: nil, renewBy: nil, variant: "regtest")
        let snapshot = WidgetDataWriter.snapshot(balance: shown, facts: facts, previous: previous,
                                                 display: .init(hidden: false, unit: .bip177, currency: "USD"), quote: nil,
                                                 variant: .regtest, now: now, calendar: utc)
        return try #require(snapshot.dailyBalances)
    }

    /// The reading after the records is capped (bark's balance waits on the chain tip): a slow one stops the wait at the
    /// limit and a failed one gives nothing, and either way the writer keeps the earlier days.
    @Test func aSlowOrFailingAfterReadFallsBackWithoutBlocking() async throws {
        let shown = Self.balance(spendable: 130_000)
        let clock = ContinuousClock(), start = clock.now
        let slow = await WidgetBalanceCheck().run(within: .milliseconds(100)) {
            try? await Task.sleep(for: .seconds(1))
            return shown
        }
        #expect(slow == nil)
        #expect(clock.now - start < .milliseconds(800))
        #expect(await WidgetBalanceCheck().run(within: .seconds(2)) { nil } == nil)
        #expect(await WidgetBalanceCheck().run(within: .seconds(2)) { shown } == shown)
        let earlier = Self.before + [130_000]
        #expect(try Self.written(shown, Records(movements: [Self.earlier]), after: nil, earlier: earlier) == earlier)
    }

    private actor Count {
        private(set) var value = 0
        func add() { value += 1 }
    }

    /// A read that outlives its wait blocks new ones until it returns: refreshes meanwhile start none (and keep the
    /// earlier days), so stalled reads never pile up as counted work; once it returns, the next refresh reads again.
    @Test func aStalledCheckLeavesOneReadUntilItReturns() async throws {
        let check = WidgetBalanceCheck(), starts = Count(), shown = Self.balance(spendable: 130_000)
        let (stall, release) = AsyncStream<Void>.makeStream()
        let stalled: @Sendable () async -> WalletBalance? = {
            await starts.add()
            for await _ in stall { break }
            return shown
        }
        #expect(await check.run(within: .milliseconds(50), stalled) == nil)
        let clock = ContinuousClock(), start = clock.now
        for _ in 0..<3 {
            #expect(await check.run(within: .seconds(2), stalled) == nil)
        }
        #expect(clock.now - start < .seconds(1)) // Turned away at once, not after waiting.
        #expect(await starts.value == 1)
        #expect(await check.isReading)

        release.yield()
        var tries = 0
        while await check.isReading, tries < 200 {
            tries += 1
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(!(await check.isReading))
        #expect(await check.run(within: .seconds(2)) {
            await starts.add()
            return shown
        } == shown)
        #expect(await starts.value == 2)
    }

    /// A claimable receive settles between the summary and the records, or within the records (its movement read pending,
    /// its pending entry gone): the reading after them doesn't agree with the summary's, so the earlier days stay; with a
    /// summary from after it settled, the records draw the same days.
    @Test func aReceiveSettlingBetweenTheReadsNeverMovesTheEarlierDays() throws {
        let receive = { (status: String) in
            Self.movement("bark.lightning_receive", "receive", metadata: "{}", effective: 30_000, created: Self.now.addingTimeInterval(-600),
                          status: status, id: 2)
        }
        let claimable = Self.balance(spendable: 100_000, claimable: 30_000), settled = Self.balance(spendable: 130_000)
        let omitted = Records(movements: [Self.earlier, receive("pending")]), done = Records(movements: [Self.earlier, receive("successful")])
        let earlier = Self.before + [130_000]
        #expect(try Self.written(claimable, omitted, after: settled, earlier: earlier) == earlier)
        #expect(try Self.written(claimable, done, after: settled, earlier: earlier) == earlier)
        #expect(try Self.written(settled, done, after: settled, earlier: earlier) == earlier)
        // Unchecked, the omitted read would have lifted every earlier day by 30,000.
        #expect(try Self.written(claimable, omitted, after: claimable, earlier: earlier) != earlier)
    }

    /// The same for a board whose VTXO is registered between the reads.
    @Test func aBoardSettlingBetweenTheReadsNeverMovesTheEarlierDays() throws {
        let deposit = WalletTransaction(txid: "deposit-txid", txHex: "", onchainFeeSats: nil, balanceChangeSats: 50_500,
                                        confirmation: .init(height: 900, hash: "d"), isCpfp: false)
        let funding = WalletTransaction(txid: "funding-txid", txHex: "", onchainFeeSats: 500, balanceChangeSats: -50_500,
                                        confirmation: .init(height: 1_000, hash: "f"), isCpfp: false)
        let dated: [String: Date] = [deposit.txid: Self.now.addingTimeInterval(-10 * 86_400)]
        let board = { (status: String) in
            Self.movement("bark.board", "board", metadata: #"{"chain_anchor":"funding-txid:0"}"#, effective: 50_000,
                          created: Self.now.addingTimeInterval(-600), status: status, outputs: ["board-vtxo"])
        }
        let confirming = Self.balance(boarding: 50_000), registered = Self.balance(spendable: 50_000)
        let omitted = Records(movements: [board("pending")], chain: [deposit, funding])
        let done = Records(movements: [board("successful")], chain: [deposit, funding])
        let earlier = Array(repeating: Int64(0), count: 20) + Array(repeating: 50_500, count: 10) + [50_000]
        #expect(try Self.written(confirming, omitted, after: registered, earlier: earlier, dated: dated) == earlier)
        #expect(try Self.written(registered, done, after: registered, earlier: earlier, dated: dated) == earlier)
        #expect(try Self.written(confirming, omitted, after: confirming, earlier: earlier, dated: dated) != earlier)
    }
}
