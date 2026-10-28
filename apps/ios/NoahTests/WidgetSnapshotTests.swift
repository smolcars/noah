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
        #expect(snapshot == WidgetSnapshot(writtenAt: Self.now, balance: 125_000, arriving: 5_000, dailyBalances: nil, amountUnit: .bip177,
                                           hidden: false, fiat: nil, renewBy: nil, variant: "regtest"))
        let shown = WidgetPresentation(snapshot: snapshot, now: Self.now, language: "en", calendar: Self.utc)
        #expect(shown.balance == "₿\u{00A0}125,000")
        #expect(shown.wave == nil && shown.change == nil && shown.fiat == nil)
        #expect(shown.status == .arriving("+₿\u{00A0}5,000 arriving"))
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
        await writer.value
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

/// Bark's records → the wave's settled balance changes, against the history fixtures Activity is held to.
struct WidgetBalanceHistoryTests {
    private static let now = Date(timeIntervalSince1970: 1_793_113_200)

    @Test func settledMovementsAreChangesAtActivitysDate() throws {
        let cases = try Fixtures.load("transaction-history", input: HistoryFixtureTests.Input.self, expected: HistoryFixtureTests.JSON.self)
            .filter { $0.input.operation == "movement" }
        #expect(cases.count == 51)
        var settled = 0
        for fixture in cases {
            let movement = try #require(fixture.input.movement).sdk
            let changes = WidgetBalanceHistory.changes(movements: [movement], chain: [], confirmed: [:], now: Self.now)
            if movement.status == "successful" && movement.effectiveBalanceSats != 0 {
                settled += 1
                let row = HistoryMapping.transaction(movement, now: Self.now)
                #expect(changes == [.init(at: try #require(row.timestamp), delta: movement.effectiveBalanceSats)], "\(fixture.name)")
            } else {
                #expect(changes.isEmpty, "\(fixture.name)") // Pending, failed and canceled aren't changes.
            }
        }
        #expect(settled > 0)
    }

    @Test func confirmedChainTransactionsAreChanges() throws {
        let cases = try Fixtures.load("transaction-history", input: HistoryFixtureTests.Input.self, expected: HistoryFixtureTests.JSON.self)
            .filter { $0.input.operation == "onchain" }
        #expect(cases.count == 6)
        for fixture in cases {
            let chain = try #require(fixture.input.onchain).decode(HistoryFixtureTests.ChainInput.self).sdk
            let estimate = WidgetBalanceHistory.estimate(chain, tip: 48, now: Self.now)
            #expect(estimate == chain.confirmation.map { Self.now.addingTimeInterval(-Double(48 - $0.height) * 600) }, "\(fixture.name)")
            // Dated by the store; a date for an unconfirmed transaction still doesn't make it a change.
            let at = Self.now.addingTimeInterval(-86_400)
            let changes = WidgetBalanceHistory.changes(movements: [], chain: [chain], confirmed: [chain.txid: at], now: Self.now)
            if chain.confirmation != nil, chain.balanceChangeSats != 0 {
                #expect(changes == [.init(at: at, delta: chain.balanceChangeSats)], "\(fixture.name)")
            } else {
                #expect(changes.isEmpty, "\(fixture.name)")
            }
        }
    }

    /// A board's two halves count together on its date, and neither while it's pending.
    @Test func boardsCountBothHalvesTogether() throws {
        func board(_ status: String) -> Movement {
            Movement(id: 7, status: status, subsystemName: "bark.board", subsystemKind: "board",
                     metadataJson: #"{"chain_anchor":"funding-txid:0"}"#, intendedBalanceSats: 50_000, effectiveBalanceSats: 50_000,
                     offchainFeeSats: 0, sentToAddresses: [], receivedOnAddresses: [], inputVtxoIds: [], outputVtxoIds: [], exitedVtxoIds: [],
                     createdAt: "2026-10-01T12:00:00.000Z", updatedAt: "2026-10-01T12:00:00.000Z", completedAt: nil,
                     paymentHash: nil, lightningInvoice: nil, lightningOffer: nil)
        }
        let funding = WalletTransaction(txid: "funding-txid", txHex: "", onchainFeeSats: 500, balanceChangeSats: -50_500,
                                        confirmation: .init(height: 10, hash: "block"), isCpfp: false)
        let created = try #require(HistoryRules.parseDate("2026-10-01T12:00:00.000Z"))
        let elsewhere: [String: Date] = ["funding-txid": Self.now] // Never used for a transaction a movement names.
        #expect(WidgetBalanceHistory.changes(movements: [board("successful")], chain: [funding], confirmed: elsewhere, now: Self.now)
            == [.init(at: created, delta: 50_000), .init(at: created, delta: -50_500)])
        #expect(WidgetBalanceHistory.changes(movements: [board("pending")], chain: [funding], confirmed: elsewhere, now: Self.now).isEmpty)
        // Not yet in a block: not a change.
        var unconfirmed = funding
        unconfirmed.confirmation = nil
        #expect(WidgetBalanceHistory.changes(movements: [], chain: [unconfirmed], confirmed: elsewhere, now: Self.now).isEmpty)
    }

    private static var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        return calendar
    }

    private static func movement(_ name: String, _ kind: String, metadata: String, effective: Int64, created: Date,
                                 status: String = "successful") -> Movement {
        let at = HistoryRules.isoDate(created)
        return Movement(id: 9, status: status, subsystemName: name, subsystemKind: kind, metadataJson: metadata,
                        intendedBalanceSats: effective, effectiveBalanceSats: effective, offchainFeeSats: 0, sentToAddresses: [],
                        receivedOnAddresses: [], inputVtxoIds: [], outputVtxoIds: [], exitedVtxoIds: [], createdAt: at, updatedAt: at,
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
                let changes = WidgetBalanceHistory.changes(movements: movements, chain: [credit], confirmed: recently, now: Self.now)
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
            let changes = WidgetBalanceHistory.changes(movements: [], chain: [receive], confirmed: confirmed, now: now)
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
}
