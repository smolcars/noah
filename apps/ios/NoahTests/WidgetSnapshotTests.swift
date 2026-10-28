import BarkBoltFFI
import Foundation
@testable import Noah
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
        #expect(written.pending == 1_000)
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
        #expect(snapshot == WidgetSnapshot(writtenAt: Self.now, balance: 125_000, pending: 5_000, dailyBalances: nil, amountUnit: .bip177,
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
            #expect(shown.status == .arriving(unit == .sats ? "+•••• sats arriving" : "+₿\u{00A0}•••• arriving"))
            let readsDigits = shown.accessibilityLabel.contains(where: \.isNumber)
            #expect(!readsDigits)
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
        WidgetDataWriter.republish(variant: .regtest, settings: settings)
        #expect(defaults.data(forKey: WidgetSnapshot.key) == nil) // Nothing to republish before the first write.

        WidgetDataWriter.write(balance: Self.balance(ark: 1_000), facts: .init(changes: []), variant: .regtest, settings: settings)
        try settings.setPersistedState(PersistedPrivacyState(isHomeBalanceHidden: true), key: PersistedPrivacyState.key,
                                       version: PersistedPrivacyState.version)
        try settings.setPersistedState(PersistedProfileState(preferredCurrency: "JPY", bitcoinAmountUnit: "sats"),
                                       key: PersistedProfileState.key, version: PersistedProfileState.version)
        WidgetDataWriter.republish(variant: .regtest, settings: settings)
        var snapshot = try #require(WidgetSnapshot.read(from: defaults, variant: "regtest"))
        #expect(snapshot.hidden && snapshot.amountUnit == .sats && snapshot.balance == 1_000)
        WidgetDataWriter.publishQuote(currency: "JPY", btcPrice: 15_000_000, variant: .regtest, settings: settings)
        snapshot = try #require(WidgetSnapshot.read(from: defaults, variant: "regtest"))
        #expect(snapshot.fiat == .init(currency: "JPY", btcPrice: 15_000_000))

        WidgetDataWriter.clear(defaults)
        #expect(WidgetSnapshot.read(from: defaults, variant: "regtest") == nil)
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
            let changes = WidgetBalanceHistory.changes(movements: [movement], chain: [], tip: 0, now: Self.now)
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
            let changes = WidgetBalanceHistory.changes(movements: [], chain: [chain], tip: 48, now: Self.now)
            if let height = chain.confirmation?.height, chain.balanceChangeSats != 0 {
                let at = Self.now.addingTimeInterval(-Double(48 - height) * 600)
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
        #expect(WidgetBalanceHistory.changes(movements: [board("successful")], chain: [funding], tip: 9_000, now: Self.now)
            == [.init(at: created, delta: 50_000), .init(at: created, delta: -50_500)])
        #expect(WidgetBalanceHistory.changes(movements: [board("pending")], chain: [funding], tip: 9_000, now: Self.now).isEmpty)
        // Not yet in a block: not a change.
        var unconfirmed = funding
        unconfirmed.confirmation = nil
        #expect(WidgetBalanceHistory.changes(movements: [], chain: [unconfirmed], tip: 9_000, now: Self.now).isEmpty)
    }
}
