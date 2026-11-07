import Foundation

/// What Siri says (docs/native-rewrite/design/voice-assistants.md, "Replies"). Amounts are whole sats whatever unit
/// the app shows; `apps/fixtures/assistant-replies.json` pins the English both apps build.
enum AssistantReply {
    /// The newest payment, as Siri tells it.
    struct Payment: Equatable, Sendable {
        var incoming: Bool
        var sats: Int64
        /// Who an outgoing payment went to: a Lightning address, else nil.
        var counterparty: String?
        /// The platform's relative date ("2 hours ago").
        var when: String
    }

    static func balance(sats: UInt64, fiat: String?, locale: String = LegacyLocale.resolved()) -> String {
        let amount = amount(Int64(clamping: sats), locale)
        guard let fiat else { return L10n.text("You have {amount}.", amount, locale: locale) }
        return L10n.text("You have {amount}, about {fiat}.", amount, fiat, locale: locale)
    }

    static func request(sats: Int64, locale: String = LegacyLocale.resolved()) -> String {
        L10n.text("Here's your Lightning invoice for {amount}.", amount(sats, locale), locale: locale)
    }

    static func arkAddress(locale: String = LegacyLocale.resolved()) -> String {
        L10n.text("Here's a new Ark address.", locale: locale)
    }

    static func lightningAddress(_ address: String?, locale: String = LegacyLocale.resolved()) -> String {
        guard let address else { return L10n.text("You don't have a Lightning address yet. Open Noah to pick one.", locale: locale) }
        return L10n.text("Your Lightning address is {address}.", address, locale: locale)
    }

    static func lastPayment(_ payment: Payment?, locale: String = LegacyLocale.resolved()) -> String {
        guard let payment else { return L10n.text("You don't have any payments yet.", locale: locale) }
        let amount = amount(payment.sats, locale)
        if payment.incoming { return L10n.text("You received {amount} {when}.", amount, payment.when, locale: locale) }
        guard let counterparty = payment.counterparty else { return L10n.text("You sent {amount} {when}.", amount, payment.when, locale: locale) }
        return L10n.text("You sent {amount} to {counterparty} {when}.", amount, counterparty, payment.when, locale: locale)
    }

    /// The newest settled payment in Activity's order. Renewals, moves between your own balances, exits, and pending,
    /// failed or canceled rows aren't payments.
    static func lastPayment(in history: [HistoryTransaction], now: Date = Date(), locale: String = LegacyLocale.resolved()) -> Payment? {
        let rows = HistoryRules.merge(history.filter { $0.source != "onchain-wallet" }, onchain: history.filter { $0.source == "onchain-wallet" })
            .sorted(by: HistoryRules.precedes)
        guard let row = rows.first(where: isPayment), let sats = row.shownAmount, let date = row.timestamp else { return nil }
        let incoming = row.direction == "incoming"
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: locale)
        formatter.dateTimeStyle = .named
        formatter.unitsStyle = .full
        return Payment(incoming: incoming, sats: sats,
                       counterparty: incoming ? nil : (row.repeatPayment?.destination ?? row.destination).flatMap(RepeatPayment.normalizeLightningAddress),
                       when: formatter.localizedString(for: min(date, now), relativeTo: now))
    }

    /// Confirmed on-chain rows carry no time of their own (bark-bolt-ffi gives a height). Date them as Activity and the
    /// widgets do: the date already known for the txid, else an estimate from the chain tip (tip − height × 10 min).
    static func datingChainRows(_ rows: [HistoryTransaction], known: [String: Date], tip: UInt32?, now: Date = Date()) -> [HistoryTransaction] {
        rows.map { row in
            guard row.source == "onchain-wallet", row.hasConfirmation == true, let txid = row.txid else { return row }
            let estimate = tip.flatMap { tip in row.confirmationHeight.map { now.addingTimeInterval(-Double(tip > $0 ? tip - $0 : 0) * 600) } }
            guard let date = known[txid] ?? estimate else { return row }
            var dated = row
            dated.date = HistoryRules.isoDate(date)
            dated.dateLabel = nil
            dated.sortTimestamp = date.timeIntervalSince1970 * 1000
            return dated
        }
    }

    static func isPayment(_ row: HistoryTransaction) -> Bool {
        ["incoming", "outgoing"].contains(row.direction) && !row.isRenewal && !row.isInternalTransfer && row.exitFeeFor == nil
            && !["offboard", "exit"].contains(row.movementKind) && !row.isPending && !row.isFailed && !row.isCanceled
            && row.shownAmount != nil && row.timestamp != nil
    }

    /// "5,000 sats", "1 sat": translated, plural-aware and grouped.
    private static func amount(_ sats: Int64, _ locale: String) -> String {
        BitcoinAmount.satsLine(sats, locale: Locale(identifier: locale))
    }
}

/// Why a wallet action failed, in words that say what to do next. Underlying errors can name destinations, so
/// they're never shown.
enum AssistantFailure: LocalizedError, Equatable {
    case unreachable, notReady, suspended, busy

    init(_ error: any Error) {
        switch error {
        case is AppServices.WalletSuspended: self = .suspended
        case is AppServices.SeedUnavailable, is AppServices.RestoreNotSettled, is StoredSeed.Failure,
             WalletSession.OpenError.walletDatabaseMissing: self = .notReady
        case WalletSession.OpenError.busy: self = .busy
        default: self = .unreachable
        }
    }

    var errorDescription: String? {
        switch self {
        case .unreachable: L10n.text("Noah couldn't reach the Ark server. Check your connection and try again.")
        case .notReady: L10n.text("Your wallet isn't ready. Open Noah, then try again.")
        case .suspended: L10n.text("Your wallet is suspended. Open Noah to resume it.")
        case .busy: L10n.text("Noah is busy. Try again in a moment.")
        }
    }
}
