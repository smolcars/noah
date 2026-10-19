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
}
