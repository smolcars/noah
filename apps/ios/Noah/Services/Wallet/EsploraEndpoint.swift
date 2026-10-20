import Darwin
import Foundation

/// RN's normalizeEsploraEndpoint and genesis validation, shared with Android via esplora.json.
enum EsploraEndpoint {
    struct Failure: LocalizedError, Equatable {
        let message: String
        var errorDescription: String? { message }
    }
    static func normalize(_ value: String) throws -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines).filter { $0 != "\t" && $0 != "\n" && $0 != "\r" }.replacingOccurrences(of: "\\", with: "/")
        guard !trimmed.isEmpty else { throw Failure(message: "Enter an Esplora API endpoint.") }
        let input = trimmed.range(of: "^https?://", options: [.regularExpression, .caseInsensitive]) == nil ? "https://" + trimmed : trimmed
        guard input.range(of: "^https?://[^/]", options: [.regularExpression, .caseInsensitive]) != nil,
              input.range(of: "%(?![0-9a-fA-F]{2})", options: .regularExpression) == nil else {
            throw Failure(message: "Enter a valid Esplora API URL.")
        }
        // Validate raw authority before Foundation can discard an overflowing port or IPv6 scope.
        let authority = input.components(separatedBy: "://")[1].prefix { !"/?#".contains($0) }
        let hostPort = authority.split(separator: "@", omittingEmptySubsequences: false).last!
        let portStart = hostPort.hasPrefix("[") ? hostPort.firstIndex(of: "]").map { hostPort.index(after: $0) } : hostPort.firstIndex(of: ":")
        guard !hostPort.contains("%") || !hostPort.hasPrefix("[") else { throw Failure(message: "Enter a valid Esplora API URL.") }
        if let start = portStart, start < hostPort.endIndex {
            let suffix = hostPort[start...]
            guard suffix.first == ":", !suffix.dropFirst().isEmpty,
                  suffix.dropFirst().allSatisfy({ $0.isASCII && $0.isNumber }),
                  let port = UInt16(suffix.dropFirst()), port > 0 else { throw Failure(message: "Enter a valid Esplora API URL.") }
        }
        guard var url = URLComponents(string: input.replacingOccurrences(of: "\\", with: "/")), let host = url.host, !host.isEmpty,
              !host.contains(where: { $0.isWhitespace }), url.url != nil else {
            throw Failure(message: "Enter a valid Esplora API URL.")
        }
        guard url.user == nil, url.password == nil else { throw Failure(message: "The Esplora endpoint cannot include credentials.") }
        guard (url.query ?? "").isEmpty, (url.fragment ?? "").isEmpty else { throw Failure(message: "The Esplora endpoint cannot include a query string or fragment.") }
        guard url.port.map({ (0...65535).contains($0) }) ?? true else { throw Failure(message: "Enter a valid Esplora API URL.") }
        url.scheme = url.scheme?.lowercased(); url.host = try canonicalHost(host.lowercased())
        if url.port == (url.scheme == "http" ? 80 : 443) { url.port = nil }
        // WHATWG special schemes treat backslashes as separators and recognize encoded dot segments.
        var segments: [String] = []
        for segment in url.percentEncodedPath.split(separator: "/", omittingEmptySubsequences: false).dropFirst() {
            switch segment.replacingOccurrences(of: "%2e", with: ".", options: .caseInsensitive) {
            case ".": continue
            case "..": if !segments.isEmpty { segments.removeLast() }
            default: segments.append(String(segment))
            }
        }
        var path = ("/" + segments.joined(separator: "/")).replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
        for suffix in ["/blocks/tip/height", "/blocks/tip/hash", "/block-height/0"] where path.hasSuffix(suffix) {
            path.removeLast(suffix.count); break
        }
        if path.isEmpty, url.query != nil || url.fragment != nil { path = "/" }
        url.percentEncodedPath = path
        guard let result = url.string else { throw Failure(message: "Enter a valid Esplora API URL.") }
        return result.replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
    }
    static func canonicalHost(_ host: String) throws -> String {
        guard host.hasPrefix("["), host.hasSuffix("]") else { return try ipv4Host(host) }
        var address = in6_addr()
        guard String(host.dropFirst().dropLast()).withCString({ inet_pton(AF_INET6, $0, &address) }) == 1 else {
            throw Failure(message: "Enter a valid Esplora API URL.")
        }
        var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        guard inet_ntop(AF_INET6, &address, &buffer, socklen_t(buffer.count)) != nil else {
            throw Failure(message: "Enter a valid Esplora API URL.")
        }
        return "[" + String(cString: buffer) + "]"
    }
    /// WHATWG's IPv4 numbers allow fewer than four components (127.1), hex and octal.
    private static func ipv4Host(_ host: String) throws -> String {
        var parts = host.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        if parts.last == "" { parts.removeLast() }
        func number(_ part: String) -> UInt64? {
            if part.lowercased().hasPrefix("0x") { return UInt64(part.dropFirst(2), radix: 16) ?? (part.count == 2 ? 0 : nil) }
            if part.count > 1, part.first == "0" { return UInt64(part.dropFirst(), radix: 8) }
            return UInt64(part)
        }
        guard let last = parts.last,
              number(last) != nil || last.range(of: "^[0-9]+$", options: .regularExpression) != nil else { return host }
        let numbers = parts.compactMap(number)
        guard numbers.count == parts.count, (1...4).contains(numbers.count),
              numbers.dropLast().allSatisfy({ $0 <= 255 }),
              numbers.last! < (UInt64(1) << (8 * (5 - numbers.count))) else {
            throw Failure(message: "Enter a valid Esplora API URL.")
        }
        var address = numbers.last!
        for (index, value) in numbers.dropLast().enumerated() { address += value << (8 * (3 - index)) }
        return [24, 16, 8, 0].map { String((address >> $0) & 255) }.joined(separator: ".")
    }
    static func validateGenesis(_ value: String, variant: AppVariant) throws {
        guard variant != .regtest else { throw Failure(message: "Regtest wallets use Bitcoin Core instead of Esplora.") }
        let hash = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard hash.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
            throw Failure(message: "The endpoint returned an invalid block hash.")
        }
        let expected = variant == .mainnet ? "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f" : "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"
        guard hash == expected else { throw Failure(message: "The Esplora endpoint is for a different Bitcoin network.") }
    }
    static func validateTip(_ value: String) throws {
        let height = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard height.range(of: "^[0-9]+$", options: .regularExpression) != nil, UInt32(height) != nil else {
            throw Failure(message: "The endpoint returned an invalid block height.")
        }
    }
    typealias Fetch = @Sendable (String) async throws -> String
    static func validate(_ value: String, variant: AppVariant, fetch: Fetch = fetch) async throws -> String {
        let endpoint = try normalize(value)
        guard variant != .regtest else { throw Failure(message: "Regtest wallets use Bitcoin Core instead of Esplora.") }
        try validateGenesis(try await fetch(endpoint + "/block-height/0"), variant: variant)
        try validateTip(try await fetch(endpoint + "/blocks/tip/height"))
        return endpoint
    }
    static func fetch(_ value: String) async throws -> String {
        try await fetch(value, timeout: 5)
    }
    static func fetch(_ value: String, timeout: TimeInterval) async throws -> String {
        guard let url = URL(string: value) else { throw Failure(message: "Enter a valid Esplora API URL.") }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout // Includes the whole body, even if bytes keep arriving.
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let request = URLRequest(url: url)
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            var data = Data()
            for try await byte in bytes {
                guard data.count < 1024 else { throw URLError(.dataLengthExceedsMaximum) }
                data.append(byte)
            }
            guard let text = String(data: data, encoding: .utf8) else { throw URLError(.badServerResponse) }
            return text
        } catch { throw Failure(message: "Unable to reach the Esplora endpoint. Check the URL and try again.") }
    }
}
