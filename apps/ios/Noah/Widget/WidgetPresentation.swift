import Foundation

/// What a widget says (docs/native-rewrite/design/widgets.md, "What every widget shows"): Home's amounts,
/// unit, language, hide setting and Renew rule, from the snapshot alone.
struct WidgetPresentation: Equatable {
    enum Status: Equatable {
        /// Renew is due: "Expires in 18 hours", or "Expires in under an hour".
        case renew(expiry: String)
        /// "+₿ 100,000 arriving", or Home's hidden "•••• arriving".
        case arriving(String)
    }

    struct Change: Equatable {
        /// "+₿ 30,142".
        var amount: String
        /// "30 days".
        var period: String
    }

    /// The balance's digits ("252,742"), or dots while hidden; `unit` says how it's set.
    let number: String
    let unit: BitcoinAmountUnit
    let fiat: String?
    /// Nil while hidden or on calm water.
    let change: Change?
    let status: Status?
    /// Home's network chip ("Signet", "Regtest"); none on mainnet.
    let chip: String?
    /// Daily balances for the wave; nil draws calm water. Never present while hidden.
    let wave: [Int64]? // swiftlint:disable:this discouraged_optional_collection - nil: calm water
    let renewTitle: String

    init(snapshot: WidgetSnapshot, now: Date, language: String, calendar: Calendar = .current) {
        let locale = Locale(identifier: language)
        let hidden = snapshot.hidden, unit = snapshot.amountUnit
        self.unit = unit
        number = hidden ? "••••••" : Int64(clamping: snapshot.balance).formatted(.number.locale(locale))
        fiat = snapshot.fiat.map {
            hidden ? "••••" : FiatCurrency.display(sats: Int64(clamping: snapshot.balance), btcPrice: $0.btcPrice,
                                                   currency: $0.currency, locale: locale)
        }
        let wave = snapshot.wave(now: now, calendar: calendar)
        self.wave = wave
        change = wave.map { values in
            let change = WidgetWave.change(values)
            return Change(amount: (change < 0 ? "−" : "+") + BitcoinAmount.format(sats: Int64(clamping: change.magnitude), unit: unit, locale: locale),
                          period: L10n.plural("{n} days", count: WidgetWave.days, locale: language))
        }
        if let hours = snapshot.renewHours(now: now) {
            status = .renew(expiry: hours < 1 ? L10n.text("Expires in under an hour", locale: language)
                : L10n.plural("Expires in {n} hours", count: hours, locale: language))
        } else if snapshot.arriving > 0 {
            let amount = hidden ? "••••" : "+" + BitcoinAmount.format(sats: Int64(clamping: snapshot.arriving), unit: unit, locale: locale)
            status = .arriving(L10n.text("{amount} arriving", amount, locale: language))
        } else {
            status = nil
        }
        chip = switch snapshot.variant {
        case "signet": L10n.text("Signet", locale: language)
        case "regtest": L10n.text("Regtest", locale: language)
        default: nil
        }
        renewTitle = L10n.text("Renew", locale: language)
    }

    /// "₿ 252,742" or "252,742 sats", as `BitcoinAmount` sets it.
    var balance: String { unit == .sats ? "\(number) sats" : "₿\u{00A0}\(number)" }

    /// The one label VoiceOver reads: balance, fiat, the change label, then the status line.
    var accessibilityLabel: String {
        let status: String? = switch self.status {
        case .renew(let expiry): "\(renewTitle), \(expiry)"
        case .arriving(let text): text
        case nil: nil
        }
        return [balance, fiat, change.map { "\($0.amount) · \($0.period)" }, status].compactMap(\.self).joined(separator: ", ")
    }
}
