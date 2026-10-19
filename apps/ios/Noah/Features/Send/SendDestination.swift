import Foundation

struct SendDestination: Equatable, Sendable, Codable {
    var destinationType: String?
    var amount: Decimal?
    var isAmountEditable = true
    var error: String?
    var bip321: [String: String]?

    static func cleaned(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.lowercased().hasPrefix("lightning:") ? String(trimmed.dropFirst(10)) : trimmed
    }
    static func isLightningAddress(_ value: String) -> Bool {
        value.lowercased().range(of: "^[a-z0-9._-]+@[a-z0-9.-]+\\.[a-z]{2,4}$", options: .regularExpression) != nil
    }
    static func offer(_ value: String) -> Bool {
        let chunks = value.components(separatedBy: "+")
        let normalized = chunks.enumerated().map { $0.offset == 0 ? $0.element : String($0.element.drop(while: \.isWhitespace)) }.joined()
        guard chunks.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }),
              normalized == normalized.lowercased() || normalized == normalized.uppercased() else { return false }
        return normalized.range(of: "^lno1[02-9ac-hj-np-z]+$", options: .caseInsensitive.union(.regularExpression)) != nil
    }
    static func matches(_ actual: String, _ expected: String, type: String) -> Bool {
        actual == expected || (type == "ark" && actual == "testnet" && expected != "mainnet")
            || (type == "onchain" && actual == "testnet" && expected == "signet")
    }
    static func parse(_ value: String, network: String = AppVariant.current.rawValue) -> Self {
        if value.lowercased().hasPrefix("bitcoin:") { return uri(value, network: network) }
        let value = cleaned(value)
        if isLightningAddress(value) { return Self(destinationType: "lnurl") }
        if let invoice = PaymentEncoding.invoice(value) {
            guard matches(invoice.network, network, type: "lightning") else { return mismatch(invoice.network, network) }
            if let amount = invoice.sats, amount > 0, amount < 1 {
                return Self(destinationType: "lightning", error: "Invoice amount is less than 1 satoshi.")
            }
            return Self(destinationType: "lightning", amount: invoice.sats.flatMap { $0 >= 1 ? $0 : nil }, isAmountEditable: (invoice.sats ?? 0) < 1)
        }
        if offer(value) { return Self(destinationType: "offer") }
        if let actual = PaymentEncoding.bitcoinNetwork(value) {
            return matches(actual, network, type: "onchain") ? Self(destinationType: "onchain") : mismatch(actual, network)
        }
        if let decoded = PaymentEncoding.bech32(value), decoded.isM, ["ark", "tark"].contains(decoded.prefix) {
            let actual = decoded.prefix == "ark" ? "mainnet" : "testnet"
            return matches(actual, network, type: "ark") ? Self(destinationType: "ark") : mismatch(actual, network)
        }
        // Raw LNURL-pay links are supported by the FFI, in addition to RN's LUD-16 input.
        if let decoded = PaymentEncoding.bech32(value), decoded.prefix == "lnurl", !decoded.isM,
           let bytes = PaymentEncoding.bytes(decoded.words), let url = URL(string: String(decoding: bytes, as: UTF8.self)), url.scheme == "https", url.host != nil {
            return Self(destinationType: "lnurl")
        }
        return Self()
    }
    private static func mismatch(_ actual: String, _ expected: String) -> Self {
        Self(error: "Network mismatch: expected \(expected), got \(actual)")
    }
    private static func uri(_ value: String, network: String) -> Self {
        let parts = value.dropFirst(8).split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        var methods: [String: String] = [:], errors: [String] = [], seen: Set<String> = []
        var amount: Decimal?, invalid = false
        func add(_ raw: String, type: String) {
            let parsed = parse(raw, network: network)
            if parsed.destinationType == type {
                methods[["ark":"arkAddress", "lightning":"lightningInvoice", "offer":"offer", "onchain":"onchainAddress"][type]!] = raw
            } else if let error = parsed.error, error.hasPrefix("Network mismatch") {
                errors.append("Payment method " + error.prefix(1).lowercased() + error.dropFirst()); invalid = true
            } else if type == "offer" {
                errors.append("Invalid BOLT12 offer: Invalid checksum")
            }
        }
        if let address = parts.first, !address.isEmpty {
            if PaymentEncoding.bitcoinNetwork(String(address)) == nil { errors.append("Invalid bitcoin address"); invalid = true }
            else { add(String(address), type: "onchain") }
        }
        if parts.count > 1 {
            for pair in parts[1].split(separator: "&", omittingEmptySubsequences: false) {
                let pair = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                guard let key = pair.first.flatMap({ String($0).removingPercentEncoding }),
                      let decoded = (pair.count > 1 ? String(pair[1]) : "").removingPercentEncoding else { return Self(error: "Invalid BIP-321 URI") }
                let lower = key.lowercased()
                if ["amount", "label", "message"].contains(lower), !seen.insert(lower).inserted {
                    errors.append("Multiple \(lower) parameters not allowed"); invalid = true; continue
                }
                switch lower {
                case "amount":
                    if let btc = Decimal(string: decoded, locale: Locale(identifier: "en_US_POSIX")), btc >= 0, !decoded.contains(","), btc <= 21000000 {
                        var sats = btc * 100000000, rounded = Decimal(); NSDecimalRound(&rounded, &sats, 0, .plain); amount = rounded
                    } else { errors.append("Invalid amount format"); invalid = true }
                case "ark": add(decoded, type: "ark")
                case "lightning": add(decoded, type: "lightning")
                case "lno": add(decoded, type: "offer")
                case "bc", "tb", "tbs", "bcrt":
                    let expected = ["bc":"mainnet","tb":"testnet","tbs":"signet","bcrt":"regtest"][lower]!
                    if let actual = PaymentEncoding.bitcoinNetwork(decoded), matches(actual, expected, type: "onchain") { add(decoded, type: "onchain") }
                    else { errors.append("Address network mismatch for \(lower) parameter"); invalid = true }
                case "label", "message", "pop": break
                default: if lower.hasPrefix("req-") { errors.append("Unknown required parameter: \(key)"); invalid = true }
                }
            }
        }
        if invalid || methods.isEmpty { return Self(error: errors.isEmpty ? "No valid payment methods found" : errors.joined(separator: ", ")) }
        return Self(destinationType: "bip321", amount: amount.flatMap { $0 > 0 ? $0 : nil }, isAmountEditable: (amount ?? 0) <= 0, bip321: methods)
    }
}
