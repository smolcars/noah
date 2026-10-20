import Foundation

struct SendDestination: Equatable, Sendable {
    var destinationType: String?
    var amount: Decimal?
    var isAmountEditable = true
    var error: SendDestinationError?
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
                return Self(destinationType: "lightning", error: .subSatoshiInvoice)
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
        Self(error: .networkMismatch(expected: expected, actual: actual))
    }
    /// Match the bounded Android decimal domain before Foundation's permissive prefix parser.
    private static func uriAmount(_ value: String) -> Decimal? {
        let syntax = #"^[+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]{1,2})?$"#
        guard value.utf8.count <= 96,
              value.range(of: syntax, options: .regularExpression) == value.startIndex..<value.endIndex else { return nil }
        let parts = value.lowercased().split(separator: "e", omittingEmptySubsequences: false)
        let mantissa = parts[0].trimmingCharacters(in: CharacterSet(charactersIn: "+"))
            .split(separator: ".", omittingEmptySubsequences: false)
        let integer = String(mantissa[0].drop(while: { $0 == "0" }))
        let fraction = mantissa.dropFirst().first.map { String($0.reversed().drop(while: { $0 == "0" }).reversed()) } ?? ""
        guard let exponent = Int(parts.count == 2 ? String(parts[1]) : "0") else { return nil }
        let digits = (integer + fraction).drop(while: { $0 == "0" }).count
        let normalized = (integer.isEmpty ? "0" : integer) + (fraction.isEmpty ? "" : "." + fraction) + "e\(exponent)"
        guard digits <= 32, (-32...32).contains(exponent), (-32...32).contains(fraction.count - exponent),
              let btc = Decimal(string: normalized, locale: Locale(identifier: "en_US_POSIX")),
              !btc.isNaN, btc >= 0, btc <= 21000000 else { return nil }
        // RN parses a JS number, then uses Math.round(btc * 100_000_000).
        guard let number = Double(normalized) else { return nil }
        return Decimal(UInt64((number * 100000000).rounded(.toNearestOrAwayFromZero)))
    }
    private static func uri(_ value: String, network: String) -> Self {
        let parts = value.dropFirst(8).split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        var methods: [String: String] = [:], errors: [SendDestinationError] = [], seen: Set<String> = []
        var amount: Decimal?, invalid = false
        func add(_ raw: String, type: String) {
            let parsed = parse(raw, network: network)
            if parsed.destinationType == type {
                methods[["ark":"arkAddress", "lightning":"lightningInvoice", "offer":"offer", "onchain":"onchainAddress"][type]!] = raw
            } else if case .networkMismatch(let expected, let actual) = parsed.error {
                errors.append(.paymentMethodNetworkMismatch(expected: expected, actual: actual)); invalid = true
            } else if type == "offer" {
                errors.append(.invalidOffer)
            }
        }
        if let address = parts.first, !address.isEmpty {
            if PaymentEncoding.bitcoinNetwork(String(address)) == nil { errors.append(.invalidBitcoinAddress); invalid = true }
            else { add(String(address), type: "onchain") }
        }
        if parts.count > 1 {
            for pair in parts[1].split(separator: "&", omittingEmptySubsequences: false) {
                let pair = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                guard let key = pair.first.flatMap({ String($0).removingPercentEncoding }),
                      let decoded = (pair.count > 1 ? String(pair[1]) : "").removingPercentEncoding else { return Self(error: .invalidURI) }
                let lower = key.lowercased()
                if ["amount", "label", "message"].contains(lower), !seen.insert(lower).inserted {
                    errors.append(.duplicateParameter(lower)); invalid = true; continue
                }
                switch lower {
                case "amount":
                    if let sats = uriAmount(decoded) {
                        amount = sats
                    } else { errors.append(.invalidAmount); invalid = true }
                case "ark": add(decoded, type: "ark")
                case "lightning": add(decoded, type: "lightning")
                case "lno": add(decoded, type: "offer")
                case "bc", "tb", "tbs", "bcrt":
                    let expected = ["bc":"mainnet","tb":"testnet","tbs":"signet","bcrt":"regtest"][lower]!
                    if let actual = PaymentEncoding.bitcoinNetwork(decoded), matches(actual, expected, type: "onchain") { add(decoded, type: "onchain") }
                    else { errors.append(.addressNetworkMismatch(lower)); invalid = true }
                case "label", "message", "pop": break
                default: if lower.hasPrefix("req-") { errors.append(.unknownRequiredParameter(key)); invalid = true }
                }
            }
        }
        if invalid || methods.isEmpty { return Self(error: errors.isEmpty ? .noPaymentMethods : .invalidMethods(errors)) }
        return Self(destinationType: "bip321", amount: amount.flatMap { $0 > 0 ? $0 : nil }, isAmountEditable: (amount ?? 0) <= 0, bip321: methods)
    }
}

/// Parser failures stay language independent, including arguments and combined URI failures.
indirect enum SendDestinationError: LocalizedError, Equatable, Sendable {
    case networkMismatch(expected: String, actual: String)
    case paymentMethodNetworkMismatch(expected: String, actual: String)
    case subSatoshiInvoice, invalidOffer, invalidBitcoinAddress, invalidURI, invalidAmount, noPaymentMethods
    case duplicateParameter(String), addressNetworkMismatch(String), unknownRequiredParameter(String)
    case invalidMethods([SendDestinationError])

    var errorDescription: String? { message() }
    func message(locale: String = LegacyLocale.resolved()) -> String {
        switch self {
        case .networkMismatch(let expected, let actual):
            L10n.text("Network mismatch: expected {expected}, got {actual}", expected, actual, locale: locale)
        case .paymentMethodNetworkMismatch(let expected, let actual):
            L10n.text("Payment method network mismatch: expected {expected}, got {actual}", expected, actual, locale: locale)
        case .subSatoshiInvoice: L10n.text("Invoice amount is less than 1 satoshi.", locale: locale)
        case .invalidOffer: L10n.text("Invalid BOLT12 offer: Invalid checksum", locale: locale)
        case .invalidBitcoinAddress: L10n.text("Invalid bitcoin address", locale: locale)
        case .invalidURI: L10n.text("Invalid BIP-321 URI", locale: locale)
        case .invalidAmount: L10n.text("Invalid amount format", locale: locale)
        case .noPaymentMethods: L10n.text("No valid payment methods found", locale: locale)
        case .duplicateParameter(let key): L10n.text("Multiple {parameter} parameters not allowed", key, locale: locale)
        case .addressNetworkMismatch(let key): L10n.text("Address network mismatch for {parameter} parameter", key, locale: locale)
        case .unknownRequiredParameter(let key): L10n.text("Unknown required parameter: {key}", key, locale: locale)
        case .invalidMethods(let errors): errors.map { $0.message(locale: locale) }.joined(separator: ", ")
        }
    }
}
