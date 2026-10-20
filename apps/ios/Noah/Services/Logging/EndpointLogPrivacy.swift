import Foundation

/// Register before entering Rust: endpoint paths can be opaque credentials.
enum EndpointLogPrivacy {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var origins = Set<String>()
    private static let urls = try! NSRegularExpression(pattern: #"(?i)\bhttps?://[^\s<>\"']*"#)

    static func register(_ endpoint: String?) {
        guard let endpoint, let origin = origin(endpoint) else { return }
        _ = lock.withLock { origins.insert(origin) }
    }
    private static func origin(_ value: String) -> String? {
        guard var url = URLComponents(string: value), url.host != nil else { return nil }
        url.path = ""; url.query = nil; url.fragment = nil; url.user = nil; url.password = nil
        return url.string
    }
    static func redact(_ text: String) -> String {
        let registered = lock.withLock { origins }
        guard !registered.isEmpty else { return text }
        var result = text
        for match in urls.matches(in: text, range: NSRange(text.startIndex..., in: text)).reversed() {
            guard let range = Range(match.range, in: result), let origin = origin(String(result[range])),
                  registered.contains(origin) else { continue }
            result.replaceSubrange(range, with: origin)
        }
        return result
    }
}
