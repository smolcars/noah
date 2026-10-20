import Foundation

/// Register before entering Rust: endpoint paths can be opaque credentials.
enum EndpointLogPrivacy {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var unavailable = false
    static func failClosed() { lock.withLock { unavailable = true } }
    nonisolated(unsafe) private static var origins = Set<String>()
    nonisolated(unsafe) private static var paths = Set<String>()
    private static let urls = try! NSRegularExpression(pattern: #"(?i)\bhttps?://[^\s<>"']*"#)
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
    private static func origin(_ value: String) -> String? {
        guard var url = URLComponents(string: value), url.host != nil else { return nil }
        url.scheme = url.scheme?.lowercased(); url.host = url.host?.lowercased()
        if url.port == (url.scheme == "http" ? 80 : 443) { url.port = nil }
        url.path = ""; url.query = nil; url.fragment = nil; url.user = nil; url.password = nil
        return url.string
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
                guard let range = Range(match.range, in: clean), let origin = origin(String(clean[range])),
                      registered.contains(origin) else { continue }
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
