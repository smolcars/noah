import Foundation

/// Register before entering Rust: endpoint paths can be opaque credentials.
enum EndpointLogPrivacy {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var unavailable = false
    static func failClosed() { lock.withLock { unavailable = true } }
    nonisolated(unsafe) private static var origins = Set<String>()
    nonisolated(unsafe) private static var paths = Set<String>()
    private static let urls = try! NSRegularExpression(pattern: #"(?i)https?://[^\s<>"']*"#)
    // Admit only the SDK error type/status, never provider-controlled response text.
    private static let responseStatus = try! NSRegularExpression(
        pattern: #"HttpResponse(?:\s*\{\s*status:\s*|\()([0-9]{3})\b"#)

    static func register(_ endpoint: String?) {
        guard let endpoint, let url = URLComponents(string: endpoint), let origin = origin(endpoint) else { return }
        var sensitive = Set<String>()
        for path in LogFile.decodedViews(url.percentEncodedPath, percentEncoded: true) ?? [url.percentEncodedPath] {
            let trimmed = path.replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
            if !trimmed.isEmpty { sensitive.insert(trimmed) }
        }
        lock.withLock { paths.formUnion(sensitive); origins.insert(origin) }
    }
    // Extract before parsing: log punctuation is not part of a URL authority.
    private static let authority = try! NSRegularExpression(
        pattern: #"(?i)^https?://(?:\[[^\]\s]+\](?::[^/\\?#\s<>"'()\[\],;{}!$&*+=^`|]*)?|[^/\\?#\s<>"'()\[\],;{}!$&*+=^`|]+)"#)
    private static func origin(_ value: String) -> String? {
        guard let match = authority.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)),
              let range = Range(match.range, in: value),
              let url = URLComponents(string: String(value[range]).trimmingCharacters(in: CharacterSet(charactersIn: "."))),
              let scheme = url.scheme?.lowercased(), let parsedHost = url.host,
              // URL.host supplies IDNA ASCII; URLComponents.host supplies bracketed IPv6.
              let ascii = parsedHost.hasPrefix("[") ? parsedHost : url.url?.host,
              let host = try? EsploraEndpoint.canonicalHost(ascii.lowercased().replacingOccurrences(of: #"\.+$"#, with: "", options: .regularExpression)) else { return nil }
        let port = url.port.flatMap { $0 == (scheme == "http" ? 80 : 443) ? nil : $0 }
        return scheme + "://" + host + (port.map { ":" + String($0) } ?? "")
    }
    private static func matches(_ value: String, registered: Set<String>) -> Bool {
        origin(value).map(registered.contains) ?? false
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
        let (registered, sensitive, unavailable) = lock.withLock { (origins, paths, unavailable) }
        if unavailable { return "[custom-esplora]" }
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
                guard let range = Range(match.range, in: clean), matches(String(clean[range]), registered: registered) else { continue }
                clean.replaceSubrange(range, with: "[custom-esplora]")
            }
            // Inspect relative references separately: another origin never inherits our paths.
            let relative = urls.stringByReplacingMatches(in: clean, range: NSRange(clean.startIndex..., in: clean), withTemplate: "")
            if sensitive.contains(where: { relative.contains($0) }) { return "[custom-esplora]" }
            if index > 0 && clean != view { return "[custom-esplora]" }
            if index == 0 { result = clean }
        }
        return result
    }
}
