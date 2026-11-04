import Foundation

/// Bitcoin amount display, ported from `client/src/lib/bitcoinAmount.ts`.
/// Amounts are always integer sats; the unit only changes presentation.
enum BitcoinAmountUnit: String, Codable, CaseIterable, Sendable {
    /// `₿ 1,234` (BIP-177). Default.
    case bip177
    /// `1,234 sats`.
    case sats
}

enum BitcoinAmount {
    static func format(sats: Int64, unit: BitcoinAmountUnit, locale: Locale = .current) -> String {
        let number = sats.formatted(.number.locale(locale))
        switch unit {
        case .bip177: return "₿\u{00A0}\(number)"
        case .sats: return "\(number) sats"
        }
    }

    /// "1,500 sats", translated and plural-aware, under a ₿ amount with no price to show beside it.
    static func satsLine(_ sats: Int64, locale: Locale) -> String {
        // The plural template prints the count ungrouped; show it grouped as everywhere else.
        L10n.plural("{n} sats", count: Int(clamping: sats), locale: locale.identifier)
            .replacingOccurrences(of: String(sats), with: sats.formatted(.number.locale(locale)))
    }
}
