import Foundation

enum FiatCurrency {
    static let supported = ["USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY", "BRL", "KRW", "INR", "MXN", "SGD"]

    static func decimals(_ currency: String) -> Int { ["JPY", "KRW"].contains(currency) ? 0 : 2 }

    static func fiatToSats(_ amount: Double, btcPrice: Double) -> Int64 {
        guard amount.isFinite, btcPrice.isFinite, btcPrice > 0 else { return 0 }
        let sats = floor(amount / btcPrice * 100_000_000 + 0.5)
        guard sats.isFinite, abs(sats) <= 9_007_199_254_740_991 else { return 0 }
        return Int64(sats)
    }

    static func satsToFiat(_ sats: Int64, btcPrice: Double, currency: String) -> String {
        let digits = decimals(currency)
        var value = Double(sats) * btcPrice / 100_000_000
        // printf rounds exact ties to even; JS toFixed rounds them away from zero.
        // A decimal half-step is exactly binary-representable only after its 5s cancel.
        if (abs(value) * pow(2, Double(digits))).truncatingRemainder(dividingBy: 1) == 0.5 {
            let scale = pow(10, Double(digits))
            value = (value * scale).rounded(.toNearestOrAwayFromZero) / scale
        }
        return String(format: "%.*f", digits, value)
    }

    static func format(_ amount: Double, currency: String, locale: Locale = .current) -> String {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .currency
        formatter.currencyCode = currency
        formatter.minimumFractionDigits = decimals(currency)
        formatter.maximumFractionDigits = decimals(currency)
        formatter.roundingMode = .halfUp
        return formatter.string(for: amount) ?? ""
    }

    static func display(sats: Int64, btcPrice: Double, currency: String, locale: Locale = .current) -> String {
        format(Double(satsToFiat(sats, btcPrice: btcPrice, currency: currency)) ?? 0, currency: currency, locale: locale)
    }
}
