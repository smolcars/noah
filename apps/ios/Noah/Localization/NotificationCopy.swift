import Foundation

struct NotificationCopy {
    let title: String
    let body: String

    static func received(sats: Int64, usd: String?, unit: BitcoinAmountUnit, defaults: UserDefaults = .standard) -> Self {
        let locale = LegacyLocale.resolved(from: defaults)
        let bitcoin = BitcoinAmount.format(sats: sats, unit: unit, locale: Locale(identifier: locale))
        let amount = usd.map { "\(bitcoin) (≈ US$\($0))" } ?? bitcoin
        return .init(title: L10n.text("Lightning Payment Received! ⚡", locale: locale),
                     body: L10n.text("You received {amount}", amount, locale: locale))
    }

    static func reminder(amount: String, recipient: String, upcoming: Bool, defaults: UserDefaults = .standard) -> Self {
        let locale = LegacyLocale.resolved(from: defaults)
        return .init(title: L10n.text(upcoming ? "Upcoming recurring payment" : "Recurring payment waiting", locale: locale),
              body: upcoming ? L10n.text("{amount} to {recipient} will be sent tomorrow.", amount, recipient, locale: locale)
                : L10n.text("Open Noah to send {amount} to {recipient}.", amount, recipient, locale: locale))
    }
}
