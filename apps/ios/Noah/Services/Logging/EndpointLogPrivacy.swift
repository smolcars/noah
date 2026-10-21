import Foundation

/// Register before entering Rust: endpoint paths can be opaque credentials.
enum EndpointLogPrivacy {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var unavailable = false
    static func failClosed() { lock.withLock { unavailable = true } }
    static var requiresSerializedGuard: Bool { lock.withLock { unavailable || !origins.isEmpty } }
    nonisolated(unsafe) private static var origins = Set<String>()
    nonisolated(unsafe) private static var tokens = Set<String>()
    nonisolated(unsafe) private static var matcher: SecretMatcher?
    nonisolated(unsafe) private static var hosts = Set<String>()
    private static let urls = try! NSRegularExpression(pattern: #"(?i)(?<![a-z0-9+.-])[0-9+.-]*+[a-z][a-z0-9+.-]*+://[^\s<>"']*"#)
    // Admit only the SDK error type/status, never provider-controlled response text.
    private static let responseStatus = try! NSRegularExpression(
        pattern: #"HttpResponse(?:\s*\{\s*status:\s*|\()([0-9]{3})\b"#)

    // Only shipped/build configuration enters this set; never use effective custom settings.
    private static let publicOrigins: Set<String> = {
        var endpoints = ["https://mempool.space", "https://exp.host", "https://api.btcmap.org",
                         "https://tiles.openfreemap.org", "https://btcmap.org", "https://maps.apple.com",
                         "https://www.google.com", "https://www.openstreetmap.org", "https://openmaptiles.org",
                         "https://www.geonames.org", "https://t.me", "https://github.com", "https://play.google.com"]
        for variant in AppVariant.allCases {
            let config = WalletConfiguration.base(for: variant)
            endpoints += [ServerEndpoint.baseURL(for: variant).absoluteString, config.arkServer]
            endpoints += [config.esplora, config.bitcoind].compactMap { $0 }
        }
        return Set(endpoints.compactMap(origin))
    }()

    static func register(_ endpoint: String?) {
        guard let endpoint else { return }
        guard !hasExcessiveCombiningRun(endpoint) else { failClosed(); return }
        guard var url = URLComponents(string: endpoint) else { failClosed(); return }
        let credentials = [url.percentEncodedUser, url.percentEncodedPassword].compactMap { $0 }
        let query = url.percentEncodedQuery
        // Selection validation rejects credentials/query; protect them in legacy registrations too.
        url.user = nil; url.password = nil; url.query = nil; url.fragment = nil
        guard let base = url.string, let origin = origin(base), let host = host(base) else { failClosed(); return }
        var sensitive = Set<String>()
        func add(_ value: String, opaque: Bool = false) {
            for view in LogFile.decodedViews(value, percentEncoded: true, compatibility: true) ?? [value] {
                guard let token = SecretMatcher.normalize(view) else { failClosed(); return }
                if !token.isEmpty && (!opaque || token.utf16.count >= 6 && !commonWords.contains(token.lowercased())) { sensitive.insert(view) }
            }
        }
        add(host)
        // Foundation exposes the Unicode IDNA spelling through URLComponents.host.
        let unicodeHost = URLComponents(string: "https://" + host)?.host ?? host
        add(unicodeHost)
        for name in [host, unicodeHost] where !name.contains(":") {
            for label in name.split(separator: ".").dropLast() where label.utf16.count >= 6 && !publicNames.contains(String(label)) {
                add(String(label))
            }
        }
        if let port = url.port { add(host + ":" + String(port)) }
        for value in credentials where value.utf16.count >= 6 { add(value) }
        if credentials.count == 2, credentials.joined(separator: ":").utf16.count >= 6 { add(credentials.joined(separator: ":")) }
        for view in LogFile.decodedViews(url.percentEncodedPath, percentEncoded: true, compatibility: true) ?? [url.percentEncodedPath] {
            for segment in view.split(separator: "/") { add(String(segment), opaque: true) }
        }
        for field in query?.split(separator: "&") ?? [] {
            if let equal = field.firstIndex(of: "=") { add(String(field[field.index(after: equal)...]), opaque: true) }
        }
        lock.withLock {
            if !sensitive.isSubset(of: tokens) { tokens.formUnion(sensitive); matcher = nil }
            origins.insert(origin); hosts.insert(host)
        }
    }

    private static let commonWords: Set<String> = ["api", "block", "blocks", "tip", "height", "hash", "tx", "txs", "transaction", "transactions", "address", "scripthash", "mempool", "v0", "v1", "v2", "testnet", "signet", "mainnet", "regtest", "esplora", "fee-estimates", "outspend", "outspends", "status", "hex", "raw", "merkle-proof", "merkleblock-proof", "utxo", "broadcast"]
    private static let publicNames: Set<String> = {
        var names: Set<String> = ["example", "localhost", "com", "org", "net", "edu", "gov", "co", "ac"]
        for origin in publicOrigins {
            if let host = authority(origin) { names.formUnion(host.split(separator: ".").map(String.init)) }
        }
        return names
    }()

    /// Aho–Corasick over boundary-preserving and normalized UTF-16 views; terminal failure links need only a hit bit.
    struct SecretMatcher: Sendable {
        private struct Node: Sendable {
            var edges: [UInt16: Int] = [:]
            var failure = 0
            var terminal = false
        }
        private var nodes = [Node()]
        init(_ tokens: Set<String>) {
            var views = Set<String>()
            for token in tokens {
                guard let normalized = Self.normalize(token) else { continue }
                views.formUnion([token, Self.fold(token), normalized, Self.withoutMarks(normalized)])
            }
            for token in views {
                var state = 0
                for unit in token.utf16 {
                    if let next = nodes[state].edges[unit] { state = next }
                    else {
                        let next = nodes.count
                        nodes.append(Node()); nodes[state].edges[unit] = next; state = next
                    }
                }
                if state != 0 { nodes[state].terminal = true }
            }
            var queue = Array(nodes[0].edges.values), head = 0
            while head < queue.count {
                let state = queue[head]; head += 1
                for (unit, next) in nodes[state].edges {
                    var fallback = nodes[state].failure
                    while fallback != 0 && nodes[fallback].edges[unit] == nil { fallback = nodes[fallback].failure }
                    nodes[next].failure = nodes[fallback].edges[unit] ?? 0
                    nodes[next].terminal = nodes[next].terminal || nodes[nodes[next].failure].terminal
                    queue.append(next)
                }
            }
        }
        static func normalize(_ text: String) -> String? {
            guard !EndpointLogPrivacy.hasExcessiveCombiningRun(text) else { return nil }
            let folded = fold(text.decomposedStringWithCompatibilityMapping)
            guard !EndpointLogPrivacy.hasExcessiveCombiningRun(folded) else { return nil }
            return folded.decomposedStringWithCompatibilityMapping
        }
        func contains(_ text: String) -> Bool {
            if EndpointLogPrivacy.hasExcessiveCombiningRun(text) { return true }
            guard nodes.count > 1 else { return false }
            if containsView(text) || containsView(Self.fold(text)) { return true }
            guard let normalized = Self.normalize(text) else { return true }
            // Reordering can insert a following mark inside an accented token's decomposition.
            return containsView(normalized) || containsView(Self.withoutMarks(normalized))
        }
        private static func withoutMarks(_ text: String) -> String {
            String(String.UnicodeScalarView(text.unicodeScalars.filter { !EndpointLogPrivacy.isMark($0) }))
        }
        private static func fold(_ text: String) -> String {
            text.lowercased(with: Locale(identifier: "en_US_POSIX")).uppercased(with: Locale(identifier: "en_US_POSIX"))
        }
        private func containsView(_ text: String) -> Bool {
            var state = 0
            for unit in text.utf16 {
                while state != 0 && nodes[state].edges[unit] == nil { state = nodes[state].failure }
                state = nodes[state].edges[unit] ?? 0
                if nodes[state].terminal { return true }
            }
            return false
        }
    }

    private static func isMark(_ scalar: UnicodeScalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .nonspacingMark, .spacingMark, .enclosingMark: true
        default: false
        }
    }

    // Per-scalar decomposition has bounded size; never reorder an unbounded whole-string run.
    static func hasExcessiveCombiningRun(_ text: String) -> Bool {
        var run = 0, decomposedRun = 0
        for scalar in text.unicodeScalars {
            if scalar.isASCII { run = 0; decomposedRun = 0; continue }
            run = isMark(scalar) ? run + 1 : 0
            if run > 32 { return true }
            for part in String(scalar).decomposedStringWithCompatibilityMapping.unicodeScalars {
                decomposedRun = isMark(part) ? decomposedRun + 1 : 0
                if decomposedRun > 32 { return true }
            }
        }
        return false
    }

    private static func authority(_ value: String) -> String? {
        guard let start = value.range(of: "://") else { return nil }
        return String(value[start.upperBound...].prefix { !"/\\?#".contains($0) })
    }
    private static func origin(_ value: String) -> String? {
        guard let authority = authority(value), let scheme = value.components(separatedBy: "://").first,
              let normalized = try? EsploraEndpoint.normalize(scheme + "://" + authority),
              let url = URLComponents(string: normalized), let host = host(normalized) else { return nil }
        return scheme.lowercased() + "://" + host + (url.port.map { ":" + String($0) } ?? "")
    }
    private static func host(_ value: String) -> String? {
        guard let normalized = try? EsploraEndpoint.normalize(value), let url = URLComponents(string: normalized),
              let parsed = url.host, let ascii = parsed.hasPrefix("[") ? parsed : url.url?.host else { return nil }
        return try? EsploraEndpoint.canonicalHost(ascii.lowercased().replacingOccurrences(of: #"\.+$"#, with: "", options: .regularExpression))
    }
    private static let plainAuthority = try! NSRegularExpression(pattern: #"^(?:\[[0-9a-fA-F:.]+\]|[a-zA-Z0-9.-]+)(?::[0-9]+)?$"#)
    private static func allowed(_ value: String, registered: Set<String>, hosts: Set<String>) -> Bool {
        let parts = value.components(separatedBy: "://")
        guard parts.count == 2, !value.contains("\\"), value.removingPercentEncoding != nil, let authority = authority(value),
              plainAuthority.firstMatch(in: authority, range: NSRange(authority.startIndex..., in: authority)) != nil,
              let origin = origin(value), !registered.contains(origin), publicOrigins.contains(origin) else { return false }
        // Only the canonical IP spelling may inherit an allowlisted origin.
        let rawHost = authority.hasPrefix("[") ? String(authority.prefix { $0 != "]" }) + "]" : String(authority.prefix { $0 != ":" })
        let tail = String(parts[1].dropFirst(authority.count))
        return rawHost.lowercased().replacingOccurrences(of: #"\.+$"#, with: "", options: .regularExpression) == host(value)
            && redactHosts(tail, registered: hosts) == tail
    }

    private static let hostTokens = try! NSRegularExpression(
        pattern: #"\[[0-9a-fA-F.]*:[0-9a-fA-F:.]*(?:%[^\[\]\s]+)?\](?::[^\s<>"'()/\\?#,\];{}!$&*=^`|]+)?|[^\s<>"'()/\\?#,\[\];{}!$&*=^`|]+"#)
    private static func redactHosts(_ text: String, registered: Set<String>, failClosed: Bool = true) -> String {
        let source = text as NSString
        var replacements: [NSRange] = []
        let urlRanges = urls.matches(in: text, range: NSRange(text.startIndex..., in: text)).map(\.range)
        var urlIndex = urlRanges.count - 1
        for match in hostTokens.matches(in: text, range: NSRange(text.startIndex..., in: text)).reversed() {
            while urlIndex >= 0 && urlRanges[urlIndex].location >= match.range.upperBound { urlIndex -= 1 }
            if urlIndex >= 0 && NSIntersectionRange(urlRanges[urlIndex], match.range).length > 0 { continue }
            let token = source.substring(with: match.range)
            guard token.contains(where: { $0.isLetter || $0.isNumber || !$0.isASCII }) else { continue }
            let address = token.prefix { $0 != "%" }
            if (address.contains("::") || address.filter({ $0 == ":" }).count == 7),
               address.allSatisfy({ "0123456789abcdefABCDEF:.".contains($0) }) {
                replacements.append(match.range); continue
            }
            let authority = String(token.split(separator: "@", omittingEmptySubsequences: false).last!)
            let rawHost = authority.hasPrefix("[") ? String(authority.prefix { $0 != "]" }) + "]" : String(authority.prefix { $0 != ":" })
            guard !rawHost.isEmpty else { continue }
            // Plain words need a registration; authority-shaped and encoded tokens require inspection.
            guard registered.contains(rawHost.lowercased()) || token.contains(".") || token.contains(":") || token.contains("%")
                || rawHost.allSatisfy({ $0.isASCII && $0.isNumber }) || rawHost.lowercased().hasPrefix("0x")
                || token.contains(where: { !$0.isASCII }) else { continue }
            let canonical = host("https://" + rawHost)
            if canonical.map(registered.contains) ?? failClosed { replacements.append(match.range) }
        }
        var parts: [String] = [], offset = 0
        for range in replacements.reversed() {
            parts.append(source.substring(with: NSRange(location: offset, length: range.location - offset)))
            parts.append("[custom-esplora]"); offset = range.upperBound
        }
        parts.append(source.substring(from: offset))
        return parts.joined()
    }
    static func redact(_ text: String) -> String { sanitize(text) ?? "[redacted]" }

    private static let recordHeader = try! NSRegularExpression(pattern: #"^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} [VDIWE] "#)
    private static let recordStart = try! NSRegularExpression(pattern: recordHeader.pattern + #"\["#)

    /// Keep continuation lines with their endpoint/error context, including historical files.
    static func redactLogLines(_ lines: [String]) -> [String] {
        redactLogLines(lines, sanitizeRecord: sanitizeLogRecord)
    }

    // Explicit snapshots isolate read-back regressions without resetting durable global history.
    static func redactLogLines(_ lines: [String], registered: Set<String>, matcher: SecretMatcher, hosts: Set<String>) -> [String] {
        redactLogLines(lines) { text in
            let hasHeader = recordHeader.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
            return sanitize(text, registered: registered, matcher: matcher, hosts: hosts, recordReadBack: hasHeader)
        }
    }

    private static func redactLogLines(_ lines: [String], sanitizeRecord: (String) -> String?) -> [String] {
        var result: [String] = [], record: [String] = []
        func flush() {
            guard !record.isEmpty else { return }
            result.append(contentsOf: (sanitizeRecord(record.joined(separator: "\n")) ?? "[redacted]").split(separator: "\n").map(String.init))
            record.removeAll(keepingCapacity: true)
        }
        for line in lines {
            if recordStart.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) != nil { flush() }
            record.append(line)
        }
        flush()
        return result
    }

    /// Only stored headers tolerate authority canonicalization failures (08/09 hours).
    private static func sanitizeLogRecord(_ text: String) -> String? {
        let (registered, matcher, hosts, unavailable) = snapshot()
        if unavailable { return "[custom-esplora]" }
        let hasHeader = recordHeader.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
        return sanitize(text, registered: registered, matcher: matcher, hosts: hosts, recordReadBack: hasHeader)
    }

    private static func snapshot() -> (Set<String>, SecretMatcher, Set<String>, Bool) {
        lock.withLock {
            if matcher == nil { matcher = SecretMatcher(tokens) }
            return (origins, matcher!, hosts, unavailable)
        }
    }

    /// Formatting can join individually safe fields into a registered secret.
    /// Only the token guard runs here: preserve timestamps and provider summaries.
    static func redactRecord(_ text: String) -> String {
        let (registered, matcher, _, unavailable) = snapshot()
        if unavailable { return "[custom-esplora]" }
        guard !registered.isEmpty else { return text }
        if hasExcessiveCombiningRun(text) { return "[custom-esplora]" }
        guard text.utf8.count <= 2 * LogFile.maxMessageBytes + 128,
              let views = LogFile.decodedViews(text, percentEncoded: true, compatibility: true) else { return "[redacted]" }
        return views.contains(where: matcher.contains) ? "[custom-esplora]" : text
    }

    static func sanitize(_ text: String) -> String? {
        let (registered, matcher, hosts, unavailable) = snapshot()
        if unavailable { return "[custom-esplora]" }
        return sanitize(text, registered: registered, matcher: matcher, hosts: hosts)
    }

    // Explicit snapshots also let tests cover a clean installation without resetting global history.
    static func sanitize(_ text: String, registered: Set<String>, matcher: SecretMatcher = SecretMatcher([]), hosts: Set<String> = []) -> String? {
        sanitize(text, registered: registered, matcher: matcher, hosts: hosts, recordReadBack: false)
    }

    private static func sanitize(_ text: String, registered: Set<String>, matcher: SecretMatcher, hosts: Set<String>, recordReadBack: Bool) -> String? {
        if !registered.isEmpty && hasExcessiveCombiningRun(text) { return "[custom-esplora]" }
        // Exported lines include a bounded tag and timestamp as well as the message.
        guard text.utf8.count <= 2 * LogFile.maxMessageBytes + 128 else { return "[oversized log message omitted]" }
        func summary(_ value: String) -> String { matcher.contains(value) ? "[custom-esplora]" : value }
        if let match = responseStatus.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) {
            return summary("HttpResponse { status: " + (text as NSString).substring(with: match.range(at: 1)) + " }")
        }
        guard let views = LogFile.decodedViews(text, percentEncoded: true, compatibility: !registered.isEmpty) else { return text.contains("HttpResponse") ? summary("HttpResponse") : nil }
        for view in views {
            if let match = responseStatus.firstMatch(in: view, range: NSRange(view.startIndex..., in: view)) {
                return summary("HttpResponse { status: " + (view as NSString).substring(with: match.range(at: 1)) + " }")
            }
        }
        if views.contains(where: { $0.contains("HttpResponse") }) { return summary("HttpResponse") }
        guard !registered.isEmpty else { return text }
        var result = text
        for (index, view) in views.enumerated() {
            let endpointOnly = redactEndpoints(view, registered: registered, matcher: matcher, hosts: hosts, recordReadBack: recordReadBack)
            if endpointOnly == "[custom-esplora]" || (index > 0 && endpointOnly != view) { return "[custom-esplora]" }
            // Host replacement must not hide credential labels or break mnemonic runs.
            let plain = LogFile.redactPlain(view)
            if index > 0 && plain != view { return "[redacted]" }
            if index == 0 {
                result = plain == view ? endpointOnly : redactEndpoints(plain, registered: registered, matcher: matcher, hosts: hosts, recordReadBack: recordReadBack)
            }
        }
        return result
    }

    private static func redactEndpoints(_ text: String, registered: Set<String>, matcher: SecretMatcher, hosts: Set<String>, recordReadBack: Bool) -> String {
        // Match complete raw/decoded views before authority replacement can hide a boundary token.
        if recordReadBack && matcher.contains(text) { return "[custom-esplora]" }
        let headerLength = recordReadBack ? (recordHeader.firstMatch(in: text, range: NSRange(text.startIndex..., in: text))?.range.length ?? 0) : 0
        let prefix = (text as NSString).substring(to: headerLength)
        let body = (text as NSString).substring(from: headerLength)
        let source = body as NSString
        var parts: [String] = [], offset = 0
        for match in urls.matches(in: body, range: NSRange(location: 0, length: source.length)) {
            parts.append(source.substring(with: NSRange(location: offset, length: match.range.location - offset)))
            let value = source.substring(with: match.range)
            parts.append(allowed(value, registered: registered, hosts: hosts) ? value : "[custom-esplora]")
            offset = match.range.upperBound
        }
        parts.append(source.substring(from: offset))
        var clean = parts.joined()
        clean = redactHosts(prefix, registered: hosts, failClosed: false) + redactHosts(clean, registered: hosts)
        return matcher.contains(clean) ? "[custom-esplora]" : clean
    }
}
