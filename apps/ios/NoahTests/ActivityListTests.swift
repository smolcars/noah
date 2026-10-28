import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

/// activity.md "List" and "Export": grouping, search, the filter, CSV rows, hidden amounts and
/// the "new" dot shared with Home.
@MainActor
struct ActivityListTests {
    static func row(_ id: String, _ date: String, amount: Int64 = 2_100, direction: String = "incoming",
                    status: String? = "successful", type: String = "Arkoor", kind: String? = nil) -> HistoryTransaction {
        HomeRulesTests.row(id, type: type, amount: amount, direction: direction, status: status, kind: kind, date: date)
    }

    static func calendar(_ zone: String) throws -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: zone))
        return calendar
    }

    @Test func pendingComesFirstThenDaysAcrossMidnightAndTimeZones() throws {
        var unconfirmed = Self.row("onchain-wallet-a", "1970-01-01T00:00:00.000Z", status: nil, type: "Onchain")
        unconfirmed.source = "onchain-wallet"
        unconfirmed.hasConfirmation = false
        unconfirmed.dateLabel = "Unconfirmed"
        let rows = [
            unconfirmed,
            Self.row("lightning", "2026-10-23T13:50:00Z", direction: "outgoing", status: "pending", type: "Bolt11"),
            Self.row("after-midnight", "2026-10-23T04:30:00Z"),
            Self.row("before-midnight", "2026-10-23T03:30:00Z"),
            Self.row("exit", "2026-10-22T20:00:00Z", direction: "outgoing", status: "pending", type: "Onchain", kind: "exit"),
            Self.row("older", "2026-10-20T12:00:00Z"),
        ]
        let now = try #require(HistoryRules.parseDate("2026-10-23T14:00:00Z"))

        // New York: 04:30Z is 00:30 today, 03:30Z is 23:30 yesterday.
        let york = ActivityList.groups(rows, now: now, calendar: try Self.calendar("America/New_York"))
        #expect(york.prefix(3).map(\.title) == [.pending, .day(.today), .day(.yesterday)])
        #expect(york.map { $0.transactions.map(\.id) } == [["onchain-wallet-a", "lightning", "exit"], ["after-midnight"],
                                                           ["before-midnight"], ["older"]])
        // Tokyo: both are the same afternoon, today.
        let tokyo = ActivityList.groups(rows, now: now, calendar: try Self.calendar("Asia/Tokyo"))
        #expect(tokyo.map { $0.transactions.map(\.id) } == [["onchain-wallet-a", "lightning", "exit"],
                                                            ["after-midnight", "before-midnight"], ["older"]])
        // Nothing pending: no Pending group.
        #expect(ActivityList.groups(Array(rows.dropFirst(2)).filter { !$0.isPending }, now: now).first?.title != .pending)
    }

    @Test func searchMatchesNamesNotesKindsAmountsAndIDs() {
        var row = Self.row("movement-7", "2026-10-22T12:00:00Z", amount: 2_100, direction: "outgoing", type: "Lnurl")
        row.movementId = 7
        row.destination = "Lucas@ln.tips"
        row.repeatPayment = RepeatPayment(destination: "lucas@ln.tips", comment: "Thanks for lunch", amountMode: "SATS", amountInput: "2100")
        row.txid = "ab12cd34ef"
        row.paymentHash = "9b7895829611f90c"
        row.paymentDescription = "Coffee Collective"
        let words = ["lucas@ln.tips", "Lightning"]
        for query in ["lucas", "LUCAS", "thanks for", "lightning", "2100", "2,100", "₿2,100", "2 100", "ab12cd", "9b7895",
                      "movement-7", "coffee"] {
            #expect(ActivityList.matches(row, query: query, words: words), "\(query)")
        }
        for query in ["maya", "3100", "zz"] { #expect(!ActivityList.matches(row, query: query, words: words), "\(query)") }
        #expect(ActivityList.matches(row, query: "  ", words: words))
        // The kind and status words a row shows are searchable, in English and the chosen language.
        let pending = HomeActivityRow(Self.row("p", "2026-10-22T12:00:00Z", direction: "outgoing", status: "pending", type: "Bolt11"),
                                      ownDomain: "noahwallet.io")
        #expect(ActivityList.kindWords(pending, locale: "en").contains("Sending"))
    }

    @Test func searchMatchesTheShownAmountOnly() {
        // An amountless retry: attempt 1's 1,000 is on record, the proven debit is 2,080.
        var paid = Self.row("movement-4", "2026-10-22T12:00:00Z", amount: 1_000, direction: "outgoing", type: "Bolt11")
        paid.subsystemName = "bark.lightning_send"
        paid.sentTo = [.init(destination: ActivityMappingTests.amountlessInvoice, amount_sat: 1_000, paymentMethod: "invoice")]
        paid.effectiveBalanceSat = -2_080
        #expect(ActivityList.matches(paid, query: "2,080", words: []))
        #expect(!ActivityList.matches(paid, query: "1000", words: []))
        // No proven amount: no amount matches at all.
        paid.effectiveBalanceSat = nil
        #expect(!ActivityList.matches(paid, query: "1000", words: []) && !ActivityList.matches(paid, query: "2080", words: []))
        #expect(ActivityList.matches(paid, query: "movement-4", words: []), "other fields still match")
    }

    @Test func filterNarrowsRows() {
        let ark = Self.row("ark", "2026-10-22T12:00:00Z")
        let lightning = Self.row("ln", "2026-10-22T12:00:00Z", type: "Bolt11")
        let chain = Self.row("chain", "2026-10-22T12:00:00Z", type: "Onchain", kind: "onboard")
        let rows = [ark, lightning, chain]
        #expect(rows.filter(HistoryFilter.lightning.includes).map(\.id) == ["ln"])
        #expect(rows.filter(HistoryFilter.ark.includes).map(\.id) == ["ark"])
        #expect(rows.filter(HistoryFilter.onchain.includes).map(\.id) == ["chain"])
        #expect(rows.filter(HistoryFilter.all.includes).count == 3)
        #expect(![lightning].contains(where: HistoryFilter.ark.includes))
    }

    @Test func csvKeepsTodaysColumnsNarrowedByTheFilterWithoutFailedRows() {
        let ark = Self.row("ark", "2026-10-22T12:00:00Z")
        let lightning = Self.row("ln", "2026-10-22T12:00:00Z", direction: "outgoing", type: "Bolt11")
        let failed = Self.row("failed", "2026-10-22T12:00:00Z", direction: "outgoing", status: "failed", type: "Bolt11")
        let canceled = Self.row("canceled", "2026-10-22T12:00:00Z", direction: "outgoing", status: "canceled", type: "Bolt11")
        let csv = HistoryCSV(transactions: [ark, lightning, failed, canceled].filter(HistoryFilter.lightning.includes), currency: "USD")
        let lines = csv.content.split(separator: "\n").map(String.init)
        #expect(lines.first == "Payment ID,Date,Type,Status,Direction,Amount (sats),BTC Price (USD),Transaction ID,Destination")
        // Canceled rows were exported before and still are; failed ones never were.
        #expect(lines.dropFirst().map { $0.split(separator: ",").first.map(String.init) } == ["ln", "canceled"])
        #expect(HistoryCSV.exports(failed) == false)
        #expect([ark, lightning, failed, canceled].filter(HistoryCSV.exports).count == 3)
    }

    @Test func csvExportsAnAmountlessRetrysProvenDebitOrABlankCell() {
        // Attempt 1 failed at 1,000; the retry paid 2,000 (+80). Bark kept attempt 1's destination amount.
        var paid = Self.row("movement-4", "2026-10-22T12:00:00Z", amount: 1_000, direction: "outgoing", type: "Bolt11")
        paid.subsystemName = "bark.lightning_send"
        paid.sentTo = [.init(destination: ActivityMappingTests.amountlessInvoice, amount_sat: 1_000, paymentMethod: "invoice")]
        paid.effectiveBalanceSat = -2_080
        var lines = HistoryCSV(transactions: [paid], currency: "USD").content.split(separator: "\n", omittingEmptySubsequences: false)
        #expect(lines[0] == "Payment ID,Date,Type,Status,Direction,Amount (sats),BTC Price (USD),Transaction ID,Destination")
        #expect(lines[1].split(separator: ",", omittingEmptySubsequences: false)[5] == "-2080")
        paid.effectiveBalanceSat = nil
        lines = HistoryCSV(transactions: [paid], currency: "USD").content.split(separator: "\n", omittingEmptySubsequences: false)
        let cells = lines[1].split(separator: ",", omittingEmptySubsequences: false)
        #expect(cells.count == 9 && cells[5].isEmpty, "a blank cell, never 1000")
        // A fixed-amount payment keeps today's cell.
        var fixed = paid
        fixed.sentTo = [.init(destination: ActivityMappingTests.coffeeInvoice, amount_sat: 1_000, paymentMethod: "invoice")]
        #expect(HistoryCSV.amount(fixed) == "-1000")
    }

    @Test func hiddenAmountsShowDots() {
        let row = HomeActivityRow(Self.row("ark", "2026-10-22T12:00:00Z", direction: "outgoing"), ownDomain: "noahwallet.io")
        #expect(row.amount(unit: .bip177, hidden: true, locale: Locale(identifier: "en")) == "••••")
        #expect(row.amount(unit: .bip177, hidden: false, locale: Locale(identifier: "en")).hasPrefix("−"))
    }

    @Test func newDotIsSharedWithHome() {
        let tracker = HomeActivityTracker()
        let old = [Self.row("old", "2026-10-22T12:00:00Z")]
        tracker.update(state: .everyday, transactions: old, balanceSats: 2_100, visible: true, walletKey: "w")
        tracker.update(state: .everyday, transactions: old, balanceSats: 2_100, visible: false, walletKey: "w")
        // Arrived while Activity was open: new there, and still new on Home until seen.
        let arrived = [Self.row("in", "2026-10-23T09:00:00Z"), Self.row("out", "2026-10-23T09:01:00Z", direction: "outgoing")] + old
        #expect(tracker.unseen(arrived) == ["in"])
        tracker.update(state: .everyday, transactions: arrived, balanceSats: 4_200, visible: false, walletKey: "w")
        #expect(tracker.unseen(arrived) == ["in"])
        // Once Activity has shown it for a moment it counts as seen on Home too.
        tracker.markSeen("in")
        #expect(tracker.unseen(arrived).isEmpty)
        tracker.update(state: .everyday, transactions: arrived, balanceSats: 4_200, visible: true, walletKey: "w")
        #expect(tracker.newIDs.isEmpty)
        // Unseen on Activity, it is still new when Home comes back.
        tracker.update(state: .everyday, transactions: arrived, balanceSats: 4_200, visible: false, walletKey: "w")
        let later = [Self.row("later", "2026-10-23T10:00:00Z")] + arrived
        tracker.update(state: .everyday, transactions: later, balanceSats: 6_300, visible: false, walletKey: "w")
        #expect(tracker.unseen(later) == ["later"])
        tracker.update(state: .everyday, transactions: later, balanceSats: 6_300, visible: true, walletKey: "w")
        #expect(tracker.newIDs == ["later"])
    }
}

/// The title row's reflow at large Dynamic Type (wide header on an iPhone in landscape, phone large title).
@MainActor
struct ActivityTitleRowTests {
    static func size(_ view: some View, _ type: DynamicTypeSize, width: CGFloat) -> CGSize {
        UIHostingController(rootView: view.environment(\.dynamicTypeSize, type))
            .sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
    }

    @Test func theChipMovesUnderTheTitleInsteadOfShrinking() {
        let row = ActivityTitleRow(style: ActivityText.wideTitle) { ActivityFilterChip(filter: .lightning, onClear: {}) }
        let title = Text("Activity").noahText(ActivityText.wideTitle)
        let chip = ActivityFilterChip(filter: .lightning, onClear: {})
        // Default text: side by side, one line tall.
        let regular = Self.size(row, .large, width: 340)
        #expect(regular.height < Self.size(title, .large, width: 340).height + 1)
        // The largest accessibility size in a 340-pt leading pane: the chip gets its own line, at full width.
        let largest = Self.size(row, .accessibility5, width: 340)
        let titleHeight = Self.size(title, .accessibility5, width: 340).height
        let chipSize = Self.size(chip, .accessibility5, width: 340)
        #expect(largest.height >= titleHeight + chipSize.height)
        #expect(chipSize.width > 60, "the chip keeps its name, not just ×")
    }
}
