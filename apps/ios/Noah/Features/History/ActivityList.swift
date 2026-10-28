import Foundation

/// The Activity tab's list rules (docs/native-rewrite/design/activity.md, "List").
enum ActivityList {
    enum GroupTitle: Equatable { case pending, day(HomeActivityDay.Title) }
    struct Group: Identifiable {
        let id: String
        let title: GroupTitle
        var transactions: [HistoryTransaction]
    }

    /// "Pending" first, holding everything still moving (unconfirmed on-chain, waiting for a
    /// round, Lightning in flight, exits), then day groups in the calendar's time zone. The
    /// input keeps history's order, newest first.
    static func groups(_ transactions: [HistoryTransaction], now: Date, calendar: Calendar = .current) -> [Group] {
        let pending = transactions.filter(\.isPending)
        let days = HomeActivityDay.group(transactions.filter { !$0.isPending }, now: now, calendar: calendar)
            .map { Group(id: $0.id, title: .day($0.title), transactions: $0.transactions) }
        return (pending.isEmpty ? [] : [Group(id: "pending", title: .pending, transactions: pending)]) + days
    }

    /// Case-insensitive: names, notes, kinds, amounts (with or without separators), txids,
    /// payment hashes and payment IDs. `words` are the row's shown title and kind.
    static func matches(_ transaction: HistoryTransaction, query: String, words: [String]) -> Bool {
        var needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if needle.hasPrefix("₿") { needle = String(needle.dropFirst()).trimmingCharacters(in: .whitespaces) }
        guard !needle.isEmpty else { return true }
        // "2,100", "2 100" and "2100" all find a ₿2,100 payment.
        let digits = needle.filter { ![",", ".", "'", " ", "\u{00A0}", "\u{202F}", "_"].contains($0) }
        // The amount shown, so an amountless retry never matches its first attempt's number.
        if !digits.isEmpty, digits.allSatisfy(\.isASCIIDigit), let amount = transaction.shownAmount,
           String(amount).contains(digits) { return true }
        let fields: [String?] = [
            transaction.destination, transaction.repeatPayment?.destination, transaction.repeatPayment?.comment,
            transaction.paymentDescription, transaction.recurring?.name, transaction.txid, transaction.paymentHash,
            transaction.id, transaction.movementId.map(String.init),
        ]
        return (fields.compactMap(\.self) + words).contains { $0.lowercased().contains(needle) }
    }
}

private extension Character {
    var isASCIIDigit: Bool { isASCII && isNumber }
}

extension HistoryFilter {
    /// The menu's name for it (activity.md: All, Lightning, Ark, On-chain).
    var title: String {
        switch self {
        case .all: "All"
        case .lightning: "Lightning"
        case .ark: "Ark"
        case .onchain: "On-chain"
        }
    }

    /// The empty state while this filter hides every row.
    var emptyTitle: String {
        switch self {
        case .all: "No activity yet"
        case .lightning: "No Lightning payments"
        case .ark: "No Ark payments"
        case .onchain: "No on-chain payments"
        }
    }

    /// The export sheet's sentence, by how many rows the file holds.
    func exportSentence(count: Int, locale: String) -> String {
        switch self {
        case .all: L10n.plural("{n} payments as a CSV file.", count: count, locale: locale)
        case .lightning: L10n.plural("{n} Lightning payments as a CSV file.", count: count, locale: locale)
        case .ark: L10n.plural("{n} Ark payments as a CSV file.", count: count, locale: locale)
        case .onchain: L10n.plural("{n} on-chain payments as a CSV file.", count: count, locale: locale)
        }
    }
}
