import Foundation

/// Register before entering Rust: endpoint paths can be opaque credentials.
enum EndpointLogPrivacy {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var unavailable = false
    static func failClosed() { lock.withLock { unavailable = true } }
    nonisolated(unsafe) private static var origins = Set<String>()
    nonisolated(unsafe) private static var paths = Set<String>()
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
        guard let url = URLComponents(string: endpoint), let origin = origin(endpoint), let host = host(endpoint) else {
            failClosed(); return
        }
        var sensitive = Set<String>()
        for path in LogFile.decodedViews(url.percentEncodedPath, percentEncoded: true) ?? [url.percentEncodedPath] {
            let trimmed = path.replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
            if !trimmed.isEmpty { sensitive.insert(trimmed) }
        }
        lock.withLock { paths.formUnion(sensitive); origins.insert(origin); hosts.insert(host) }
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
    private static func allowed(_ value: String, registered: Set<String>) -> Bool {
        guard !value.contains("\\"), value.removingPercentEncoding != nil, let authority = authority(value),
              plainAuthority.firstMatch(in: authority, range: NSRange(authority.startIndex..., in: authority)) != nil,
              let origin = origin(value), !registered.contains(origin), publicOrigins.contains(origin) else { return false }
        // Only the canonical IP spelling may inherit an allowlisted origin.
        let rawHost = authority.hasPrefix("[") ? String(authority.prefix { $0 != "]" }) + "]" : String(authority.prefix { $0 != ":" })
        return rawHost.lowercased().replacingOccurrences(of: #"\.+$"#, with: "", options: .regularExpression) == host(value)
    }

    private static let hostTokens = try! NSRegularExpression(
        pattern: #"\[[^\[\]\s]+\](?::[^\s<>"'()/\\?#,\];{}!$&*=^`|]+)?|[^\s<>"'()/\\?#,\[\];{}!$&*=^`|]+"#)
    private static func redactHosts(_ text: String, registered: Set<String>) -> String {
        var result = text
        let urlRanges = urls.matches(in: text, range: NSRange(text.startIndex..., in: text)).map(\.range)
        var urlIndex = urlRanges.count - 1
        for match in hostTokens.matches(in: text, range: NSRange(text.startIndex..., in: text)).reversed() {
            while urlIndex >= 0 && urlRanges[urlIndex].location >= match.range.upperBound { urlIndex -= 1 }
            if urlIndex >= 0 && NSIntersectionRange(urlRanges[urlIndex], match.range).length > 0 { continue }
            guard let range = Range(match.range, in: result) else { continue }
            let token = String(result[range])
            if token.hasPrefix("["), !token.dropFirst().prefix(while: { $0 != "]" && $0 != "%" }).allSatisfy({ "0123456789abcdefABCDEF:.%".contains($0) }) { continue }
            guard token.contains(".") || (token.hasPrefix("[") && token.contains(":")) || token.allSatisfy({ $0.isASCII && $0.isNumber }) || token.lowercased().hasPrefix("0x") else { continue }
            let authority = String(token.split(separator: "@", omittingEmptySubsequences: false).last!)
            let rawHost = authority.hasPrefix("[") ? String(authority.prefix { $0 != "]" }) + "]" : String(authority.prefix { $0 != ":" })
            let canonical = host("https://" + rawHost)
            if canonical.map(registered.contains) ?? true { result.replaceSubrange(range, with: "[custom-esplora]") }
        }
        return result
    }
    static func redact(_ text: String) -> String { sanitize(text) ?? "[redacted]" }

    private static let recordStart = try! NSRegularExpression(pattern: #"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} [VDIWE] \["#)

    /// Keep continuation lines with their endpoint/error context, including historical files.
    static func redactLogLines(_ lines: [String]) -> [String] {
        var result: [String] = [], record: [String] = []
        func flush() {
            guard !record.isEmpty else { return }
            result.append(contentsOf: redact(record.joined(separator: "\n")).split(separator: "\n").map(String.init))
            record.removeAll(keepingCapacity: true)
        }
        for line in lines {
            if recordStart.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) != nil { flush() }
            record.append(line)
        }
        flush()
        return result
    }

    static func sanitize(_ text: String) -> String? {
        let (registered, sensitive, hosts, unavailable) = lock.withLock { (origins, paths, hosts, unavailable) }
        if unavailable { return "[custom-esplora]" }
        return sanitize(text, registered: registered, sensitive: sensitive, hosts: hosts)
    }

    // Explicit snapshots also let tests cover a clean installation without resetting global history.
    static func sanitize(_ text: String, registered: Set<String>, sensitive: Set<String> = [], hosts: Set<String> = []) -> String? {
        // Exported lines include a bounded tag and timestamp as well as the message.
        guard text.utf8.count <= 2 * LogFile.maxMessageBytes + 128 else { return "[oversized log message omitted]" }
        if let match = responseStatus.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) {
            return "HttpResponse { status: " + (text as NSString).substring(with: match.range(at: 1)) + " }"
        }
        guard let views = LogFile.decodedViews(text, percentEncoded: true) else { return text.contains("HttpResponse") ? "HttpResponse" : nil }
        for view in views {
            if let match = responseStatus.firstMatch(in: view, range: NSRange(view.startIndex..., in: view)) {
                return "HttpResponse { status: " + (view as NSString).substring(with: match.range(at: 1)) + " }"
            }
        }
        if views.contains(where: { $0.contains("HttpResponse") }) { return "HttpResponse" }
        guard !registered.isEmpty else { return text }
        var result = text
        for (index, view) in views.enumerated() {
            var clean = view
            for match in urls.matches(in: view, range: NSRange(view.startIndex..., in: view)).reversed() {
                guard let range = Range(match.range, in: clean), !allowed(String(clean[range]), registered: registered) else { continue }
                clean.replaceSubrange(range, with: "[custom-esplora]")
            }
            // Inspect relative references separately: another origin never inherits our paths.
            let relative = urls.stringByReplacingMatches(in: clean, range: NSRange(clean.startIndex..., in: clean), withTemplate: "")
            if sensitive.contains(where: { relative.contains($0) }) { return "[custom-esplora]" }
            clean = redactHosts(clean, registered: hosts)
            if index > 0 && clean != view { return "[custom-esplora]" }
            if index == 0 { result = clean }
        }
        return result
    }
}
