import Foundation

struct SendDestination: Equatable, Sendable {
    var destinationType: String?
    var amount: Decimal?
    var isAmountEditable = true
    var error: SendDestinationError?
    var bip321: [String: String]?

    static func cleaned(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if let lnurl = lud17(trimmed) { return lnurl }
        let payload = trimmed.lowercased().hasPrefix("lightning:") ? String(trimmed.dropFirst(10)) : trimmed
        let address = String(payload.unicodeScalars.drop(while: { $0 == "₿" }))
        return address != payload && isLightningAddress(address) ? address : payload
    }
    /// LUD-17 `lnurlp://` mapped to HTTPS as Bark maps it, then carried as the `lnurl1…` it stands for, so its URL keeps
    /// its case through Send's lowercased keys. A `.onion` host would need cleartext, so it stays unmapped (refused).
    private static func lud17(_ value: String) -> String? {
        guard value.lowercased().hasPrefix("lnurlp://") else { return nil }
        let rest = value.dropFirst(9)
        // Like Android's HttpUrl, extra slashes are refused.
        guard !rest.hasPrefix("/"), !rest.hasPrefix("\\"), let url = urlText("https://" + rest).flatMap(URLComponents.init(string:)),
              allowsLnurl(url), url.port.map({ (1...65535).contains($0) }) ?? true, let host = url.encodedHost.flatMap(Self.host)
        else { return nil }
        let port = url.port.map { $0 == 443 ? "" : ":\($0)" } ?? ""
        let path = removingDotSegments(url.percentEncodedPath)
        guard let canonical = URL(string: "https://\(host)\(port)\(path)" + (url.percentEncodedQuery.map { "?" + $0 } ?? "")
                                  + (url.percentEncodedFragment.map { "#" + $0 } ?? ""))?.absoluteString
        else { return nil }
        let lnurl = PaymentEncoding.encodeBech32("lnurl", Array(canonical.utf8))
        return lnurl.count <= 1023 ? lnurl : nil
    }
    /// What Noah's LNURL client may request: HTTPS, no userinfo, and a host Android's HttpUrl accepts that isn't `.onion`
    /// (that would need cleartext). Checked right before every request, and when parsing, so such a link is no destination.
    static func allowsLnurl(_ url: URLComponents) -> Bool {
        guard url.scheme == "https", (url.user ?? "").isEmpty, (url.password ?? "").isEmpty,
              let host = url.encodedHost.flatMap(Self.host) else { return false }
        return !isOnion(host)
    }
    /// A host normalised by `host(_:)` that ends in `.onion`, a trailing dot included.
    static func isOnion(_ host: String) -> Bool { host.range(of: #"\.onion\.*$"#, options: .regularExpression) != nil }
    /// A URL as Android's HttpUrl reads it, for Foundation to parse: a backslash before the query separates path segments,
    /// and what a URL can't hold (non-ASCII too) is escaped while existing `%XX` stay, which Foundation alone escapes again.
    static func urlText(_ text: String) -> String? {
        let query = text.firstIndex(where: { $0 == "?" || $0 == "#" }) ?? text.endIndex
        return (text[..<query].replacingOccurrences(of: "\\", with: "/") + text[query...])
            .addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "!"..."~").subtracting(CharacterSet(charactersIn: "\"<>\\^`{|}")))
    }
    /// The path Android's HttpUrl requests: `.` and `..` segments, also percent-encoded in either case (`%2e`, `.%2e`,
    /// `%2e.`, `%2e%2e`), are resolved, and an escaped slash (`%2f`) stays inside its segment. `path` is percent-encoded.
    static func removingDotSegments(_ path: String) -> String {
        var segments = [""]
        let parts = path.dropFirst().split(separator: "/", omittingEmptySubsequences: false)
        for (index, part) in parts.enumerated() {
            switch part.lowercased() {
            case ".", "%2e": continue
            case "..", "%2e.", ".%2e", "%2e%2e":
                // The path still ends in `/`: the emptied previous segment, or a new empty one.
                if segments.removeLast().isEmpty && !segments.isEmpty { segments[segments.count - 1] = "" } else { segments.append("") }
            default:
                if segments[segments.count - 1].isEmpty { segments[segments.count - 1] = String(part) } else { segments.append(String(part)) }
                if index < parts.count - 1 { segments.append("") }
            }
        }
        return "/" + segments.joined(separator: "/")
    }
    /// The host Android's HttpUrl requests: percent-decoded, IDNA and lower case, labels of 1–63 characters, and an IP
    /// literal only for IPv6 (no zone). Nil for anything else.
    static func host(_ value: String) -> String? {
        var components = URLComponents()
        components.host = value.removingPercentEncoding ?? value
        guard let host = components.encodedHost?.lowercased(), (1...253).contains(host.count) else { return nil }
        if host.hasPrefix("[") {
            var address = in6_addr()
            return host.hasSuffix("]") && !host.contains("%") && inet_pton(AF_INET6, String(host.dropFirst().dropLast()), &address) == 1
                ? host : nil
        }
        let labels = (host.hasSuffix(".") ? host.dropLast() : host[...]).split(separator: ".", omittingEmptySubsequences: false)
        guard labels.allSatisfy({ (1...63).contains($0.count) }),
              host.unicodeScalars.allSatisfy({ (0x21..<0x7f).contains($0.value) && !"#%/:?@[\\]".unicodeScalars.contains($0) })
        else { return nil }
        return host
    }
    /// Bark's rule: lnurl-rs 0.10's `LightningAddress` is email_address 0.2.5 with default options, so quoted local parts
    /// and domain literals count too. Lengths are UTF-8 bytes and characters are code points, as in Rust. Resolution stays
    /// HTTPS-only.
    static func isLightningAddress(_ value: String) -> Bool {
        let scalars = Array(value.unicodeScalars)
        guard let at = scalars.lastIndex(of: "@") else { return false }
        let local = scalars[..<at], domain = scalars[(at + 1)...]
        func size(_ text: ArraySlice<Unicode.Scalar>) -> Int { text.reduce(0) { $0 + UTF8.width($1) } }
        // Rust's char::is_alphanumeric: Alphabetic, or a number of any kind.
        func alphanumeric(_ c: Unicode.Scalar) -> Bool {
            c.properties.isAlphabetic || [.decimalNumber, .letterNumber, .otherNumber].contains(c.properties.generalCategory)
        }
        func atom(_ text: ArraySlice<Unicode.Scalar>) -> Bool {
            !text.isEmpty && text.allSatisfy { $0.value >= 0x80 || alphanumeric($0) || "!#$%&'*+-/=?^_`{|}~".unicodeScalars.contains($0) }
        }
        func quoted(_ text: ArraySlice<Unicode.Scalar>) -> Bool {
            var index = text.startIndex
            while index < text.endIndex {
                let c = text[index].value
                index += 1
                if c == 0x5c {
                    guard index < text.endIndex, (0x21...0x7e).contains(text[index].value) else { return false }
                    index += 1
                } else if !(c == 0x20 || c == 0x09 || c == 0x21 || (0x23...0x5b).contains(c) || (0x5d...0x7e).contains(c) || c >= 0x80) {
                    return false
                }
            }
            return true
        }
        let localValid = !local.isEmpty && size(local) <= 64 && (local.first == "\"" && local.last == "\""
            ? local.count > 2 && quoted(local.dropFirst().dropLast())
            : local.split(separator: ".", omittingEmptySubsequences: false).allSatisfy(atom))
        guard localValid, !domain.isEmpty, size(domain) <= 254 else { return false }
        if domain.first == "[" && domain.last == "]" {
            return domain.dropFirst().dropLast().allSatisfy { (0x21...0x5a).contains($0.value) || (0x5e...0x7e).contains($0.value) }
        }
        return domain.split(separator: ".", omittingEmptySubsequences: false).allSatisfy { label in
            guard let first = label.first, let last = label.last else { return false }
            return alphanumeric(first) && alphanumeric(last) && size(label) <= 63 && atom(label)
        }
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
        if isLightningAddress(value), let at = value.unicodeScalars.lastIndex(of: "@") {
            // Like an `lnurlp://` one, a `.onion` domain would need cleartext, so it is no destination.
            let domain = String(value.unicodeScalars[value.unicodeScalars.index(after: at)...])
            return host(domain).map(isOnion) == true ? Self() : Self(destinationType: "lnurl")
        }
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
           let bytes = PaymentEncoding.bytes(decoded.words),
           let url = urlText(String(decoding: bytes, as: UTF8.self)).flatMap(URLComponents.init(string:)), allowsLnurl(url) {
            // LNURL-auth logs in rather than pays: refuse it here instead of at the fetch. The query is read decoded, as the
            // LNURL client reads it, so encoding or case can't hide `tag=login`.
            let login = url.queryItems?.contains {
                $0.name.lowercased() == "tag" && $0.value?.lowercased() == "login"
            } == true
            return login ? Self(error: .lnurlAuth) : Self(destinationType: "lnurl")
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
                errors.append(.networkMismatch(expected: expected, actual: actual)); invalid = true
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
                // BIP-321 `req-` parameters must be understood or refused; Send understands these two.
                let lower = ["req-ark", "req-amount"].contains(key.lowercased()) ? String(key.lowercased().dropFirst(4)) : key.lowercased()
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
    case subSatoshiInvoice, invalidOffer, invalidBitcoinAddress, invalidURI, invalidAmount, noPaymentMethods
    case duplicateParameter(String), addressNetworkMismatch(String), unknownRequiredParameter(String)
    case invalidMethods([SendDestinationError])
    case lnurlAuth

    var errorDescription: String? { message() }
    func message(locale: String = LegacyLocale.resolved()) -> String {
        switch self {
        case .networkMismatch(let expected, let actual):
            L10n.text("Network mismatch: expected {expected}, got {actual}", expected, actual, locale: locale)
        case .subSatoshiInvoice: L10n.text("Invoice amount is less than 1 satoshi.", locale: locale)
        case .invalidOffer: L10n.text("Invalid BOLT12 offer", locale: locale)
        case .invalidBitcoinAddress: L10n.text("Invalid bitcoin address", locale: locale)
        case .invalidURI: L10n.text("Invalid BIP-321 URI", locale: locale)
        case .invalidAmount: L10n.text("Invalid amount format", locale: locale)
        case .noPaymentMethods: L10n.text("No valid payment methods found", locale: locale)
        case .duplicateParameter(let key): L10n.text("Multiple {parameter} parameters not allowed", key, locale: locale)
        case .addressNetworkMismatch(let key): L10n.text("Address network mismatch for {parameter} parameter", key, locale: locale)
        case .unknownRequiredParameter(let key): L10n.text("Unknown required parameter: {key}", key, locale: locale)
        case .invalidMethods(let errors): errors.map { $0.message(locale: locale) }.joined(separator: ", ")
        case .lnurlAuth: L10n.text("This is a login link, not a payment request", locale: locale)
        }
    }
}
