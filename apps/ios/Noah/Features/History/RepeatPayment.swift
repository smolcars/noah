import BarkBoltFFI
import Foundation

/// RN's `noah.repeat_payment` v1 envelope. Keep the entered fiat text and
/// currency: Send may restore fiat only when the preferred currency matches.
struct RepeatPayment: Codable, Equatable, Sendable {
    var destination: String
    var comment: String
    var amountMode: String
    var amountInput: String
    var amountSat: Int64?
    var fiatCurrency: String?

    static func normalizeLightningAddress(_ value: String) -> String? {
        var value = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if value.hasPrefix("lightning:") { value.removeFirst(10) }
        let parts = value.split(separator: "@", omittingEmptySubsequences: false)
        return parts.count == 2 && parts.allSatisfy({ !$0.isEmpty }) ? value : nil
    }

    static func parse(_ value: Any?) -> Self? {
        guard let object = value as? [String: Any],
              let version = object["version"] as? NSNumber,
              CFGetTypeID(version) != CFBooleanGetTypeID(), version == 1,
              let rawDestination = object["destination"] as? String,
              let destination = normalizeLightningAddress(rawDestination),
              let mode = object["amount_mode"] as? String, ["fiat", "sats"].contains(mode),
              let input = object["amount_value"] as? String,
              let amount = numericAmount(input), amount.isFinite, amount > 0 else { return nil }
        let currency = object["fiat_currency"] as? String
        if mode == "fiat", !FiatCurrency.supported.contains(currency ?? "") { return nil }
        return Self(destination: destination, comment: object["comment"] as? String ?? "",
                    amountMode: mode.uppercased(), amountInput: input,
                    fiatCurrency: mode == "fiat" ? currency : nil)
    }

    // JavaScript Number accepts radix-prefixed amounts as well as decimal/exponent text.
    private static func numericAmount(_ text: String) -> Double? {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        for (prefix, radix) in [("0x", 16), ("0b", 2), ("0o", 8)] where text.lowercased().hasPrefix(prefix) {
            return UInt64(text.dropFirst(2), radix: radix).map(Double.init)
        }
        return Double(text)
    }

    static func resolve(metadata: Self?, destination: String?, paymentMethod: String?, amountSat: Int64) -> Self? {
        guard amountSat > 0 else { return nil }
        if var metadata { metadata.amountSat = amountSat; return metadata }
        let destination = paymentMethod == "lightning-address"
            ? destination.flatMap(normalizeLightningAddress)
            : paymentMethod == "ark" ? destination?.trimmingCharacters(in: .whitespacesAndNewlines) : nil
        guard let destination, !destination.isEmpty else { return nil }
        return Self(destination: destination, comment: "", amountMode: "SATS",
                    amountInput: String(amountSat), amountSat: amountSat)
    }

    /// Preserves a comment by routing through LNURL instead of the direct Ark shortcut.
    static func usesDirectArkRoute(method: String, comment: String?) -> Bool {
        method == "ark" && (comment == nil || comment == "")
    }

    /// Associates RN repeat metadata with the newly committed Ark-routed send.
    static func newArkoorMovementID(existing: Set<UInt32>, movements: [Movement], destination: String, amountSat: Int64) -> UInt32? {
        movements.filter {
            !existing.contains($0.id) && $0.status == "successful"
                && $0.subsystemName.lowercased() == "bark.arkoor" && $0.subsystemKind.lowercased() == "send"
                && $0.sentToAddresses.contains { $0.paymentMethod.value == destination && Int64($0.amountSats) == amountSat }
        }.map(\.id).max()
    }

    struct Prefill: Codable, Equatable {
        let amountInput: String
        let amountMode: String
    }

    func prefill(preferredCurrency: String) -> Prefill {
        amountMode == "FIAT" && fiatCurrency == preferredCurrency
            ? Prefill(amountInput: amountInput, amountMode: "FIAT")
            : Prefill(amountInput: String(amountSat ?? 0), amountMode: "SATS")
    }
}
