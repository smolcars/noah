import Foundation

/// Register before entering Rust: endpoint paths can be opaque credentials.
enum EndpointLogPrivacy {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var origins = Set<String>()
    nonisolated(unsafe) private static var paths = Set<String>()
    private static let routes: Set<String> = ["api", "tx", "txs", "raw", "hex", "status", "outspend", "outspends", "address",
        "scripthash", "utxo", "chain", "mempool", "block", "blocks", "block-height", "tip", "height", "hash",
        "header", "txids", "txid", "merkle-proof", "merkleblock-proof", "fee-estimates", "recent"]
    private static let urls = try! NSRegularExpression(pattern: #"(?i)\bhttps?://[^\s<>"']*"#)
    // esplora-client 0.12.3 Display uses Rust Debug: retain the error variant and HTTP status.
    private static let responseBodies = try! NSRegularExpression(
        pattern: #"(HttpResponse \{ status: [0-9]+, message: )"(?:\\.|[^"\\]++)*+""#)

    static func register(_ endpoint: String?) {
        guard let endpoint, let url = URLComponents(string: endpoint), let origin = origin(endpoint) else { return }
        var sensitive = Set<String>()
        for path in LogFile.decodedViews(url.percentEncodedPath, percentEncoded: true) ?? [url.percentEncodedPath] {
            let trimmed = path.replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
            if !trimmed.isEmpty { sensitive.insert(trimmed) }
            sensitive.formUnion(path.split(separator: "/").map(String.init).filter { !routes.contains($0) })
        }
        lock.withLock { paths.formUnion(sensitive); origins.insert(origin) }
    }
    private static func origin(_ value: String) -> String? {
        guard var url = URLComponents(string: value), url.host != nil else { return nil }
        url.path = ""; url.query = nil; url.fragment = nil; url.user = nil; url.password = nil
        return url.string
    }
    static func sdkMessage(_ text: String) -> String {
        guard text.utf8.count <= LogFile.maxMessageBytes else { return "[oversized log message omitted]" }
        return redact(responseBodies.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text),
            withTemplate: "$1\"[provider response omitted]\""))
    }
    static func redact(_ text: String) -> String {
        let (registered, sensitive) = lock.withLock { (origins, paths) }
        guard !registered.isEmpty else { return text }
        // Exported lines include a bounded tag and timestamp as well as the message.
        guard text.utf8.count <= 2 * LogFile.maxMessageBytes + 128 else { return "[oversized log message omitted]" }
        guard let views = LogFile.decodedViews(text, percentEncoded: true) else { return "[redacted]" }
        var result = text
        for (index, view) in views.enumerated() {
            var clean = view
            for match in urls.matches(in: view, range: NSRange(view.startIndex..., in: view)).reversed() {
                guard let range = Range(match.range, in: clean), let origin = origin(String(clean[range])),
                      registered.contains(origin) else { continue }
                clean.replaceSubrange(range, with: "[custom-esplora]")
            }
            // Decoding loses offsets; omit the message when any relative path or segment matches.
            if sensitive.contains(where: { clean.contains($0) }) { return "[redacted]" }
            if index > 0 && clean != view { return "[custom-esplora]" }
            if index == 0 { result = clean }
        }
        return result
    }
}
