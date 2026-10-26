import Foundation
import OSLog

/// Tagged app logger, the native counterpart of `client/src/lib/log.ts`.
/// Writes to OSLog and to the rotating file the Logs screen exports.
/// Never pass secrets; `LogFile` also redacts secret-shaped text at the boundary.
struct AppLog: Sendable {
    enum Level: String, Sendable {
        case verbose, debug, info, warn, error

        var symbol: String {
            switch self {
            case .verbose: "V"
            case .debug: "D"
            case .info: "I"
            case .warn: "W"
            case .error: "E"
            }
        }
    }

    let tag: String
    private let file: LogFile

    init(_ tag: String, file: LogFile = .shared) {
        self.tag = tag
        self.file = file
    }

    func v(_ message: String) { log(.verbose, message) }
    func d(_ message: String) { log(.debug, message) }
    func i(_ message: String) { log(.info, message) }
    func w(_ message: String) { log(.warn, message) }
    func e(_ message: String) { log(.error, message) }

    func log(_ level: Level, _ message: String) {
        file.append(level: level, tag: tag, message: message, systemLog: true)
    }
}

/// Rotating log files in `Caches/noah_logs`, same layout and limits as the
/// React Native app (`noah.log`, `noah.log.1` … `.4`, 512 KB each).
final class LogFile: @unchecked Sendable {
    // All file access happens on `queue`.
    static let shared = LogFile(
        directory: URL.cachesDirectory.appending(path: "noah_logs", directoryHint: .isDirectory))

    static let fileName = "noah.log"
    static let maxFileSizeBytes = 512 * 1024
    static let maxFiles = 4
    static let maxLines = 4000
    static let maxMessageBytes = 16 * 1024

    private let directory: URL
    private let queue: DispatchQueue
    private let lock = NSLock()
    private let capacity: Int
    private let systemSink: @Sendable (AppLog.Level, String, String) -> Void
    private let redactor: @Sendable (String) -> String
    /// The longest a drain waits for the writer; also the lifecycle drain's budget.
    let drainLimit: TimeInterval
    private var pending = 0
    private var dropped = 0
    static let maxPendingRecords = 256
    private let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return formatter
    }()

    init(directory: URL, capacity: Int = LogFile.maxPendingRecords,
         queue: DispatchQueue = DispatchQueue(label: "com.noahwallet.log-file"),
         systemSink: @escaping @Sendable (AppLog.Level, String, String) -> Void = { LogFile.emitSystemLog($0, $1, $2) },
         redact: @escaping @Sendable (String) -> String = { LogFile.redact($0) }, drainLimit: TimeInterval = 0.2) {
        precondition(capacity > 0)
        self.directory = directory
        self.capacity = capacity
        self.queue = queue
        self.systemSink = systemSink
        self.redactor = redact
        self.drainLimit = drainLimit
    }

    func append(level: AppLog.Level, tag: String, message: String, date: Date = Date(),
                systemLog: Bool = false) {
        // Only admission holds the lock; raw text stays in the bounded in-memory queue.
        lock.withLock {
            guard pending < capacity else { dropped += 1; return }
            enqueueDroppedMarker()
            pending += 1
            queue.async { [self] in
                let cleaned = redactor(message)
                let safeTag = redactor(tag)
                if systemLog {
                    let formatted = "[\(safeTag)] \(cleaned)"
                    let record = EndpointLogPrivacy.redactRecord(formatted)
                    let systemMessage = record == formatted ? cleaned : record
                    let systemTag = record == formatted ? safeTag : ""
                    systemSink(level, systemTag, systemMessage)
                }
                persist(level: level, tag: safeTag, message: cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
                    .replacingOccurrences(of: "\n", with: "\n  "), date: date)
                lock.withLock {
                    pending -= 1
                    if pending == 0 { enqueueDroppedMarker() }
                }
            }
        }
    }

    static func emitSystemLog(_ level: AppLog.Level, _ tag: String, _ message: String) {
        let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "com.noahwallet", category: tag)
        switch level {
        case .verbose, .debug: logger.debug("\(message, privacy: .public)")
        case .info: logger.info("\(message, privacy: .public)")
        case .warn: logger.warning("\(message, privacy: .public)")
        case .error: logger.error("\(message, privacy: .public)")
        }
    }

    // Caller holds lock. A marker follows all records admitted before the overflow.
    private func enqueueDroppedMarker() {
        guard dropped > 0 else { return }
        let count = dropped
        dropped = 0
        queue.async { [self] in
            persist(level: .warn, tag: "logging", message: "[dropped \(count) log records]", date: Date())
        }
    }

    private func persist(level: AppLog.Level, tag: String, message: String, date: Date) {
        let formatted = "\(formatter.string(from: date)) \(level.symbol) [\(tag)] \(message)"
        let record = EndpointLogPrivacy.redactRecord(formatted + "\n")
        let line = record.hasSuffix("\n") ? record : record + "\n"
        try? write(Data(line.utf8))
    }

    /// The newest `maxLines` lines across all files, oldest first.
    func lines() -> [String] {
        flush()
        return queue.sync { (try? readLines()) ?? [] }
    }

    private func readLines() throws -> [String] {
        let files: [URL]
        do {
            files = try FileManager.default.contentsOfDirectory(
                at: directory, includingPropertiesForKeys: nil, options: .skipsHiddenFiles)
        } catch CocoaError.fileReadNoSuchFile { return [] }
        let ordered = files
            .filter { $0.lastPathComponent.hasPrefix(Self.fileName) }
            .sorted { Self.age(of: $0) > Self.age(of: $1) }
        var lines: [String] = []
        for url in ordered {
            let contents = try String(contentsOf: url, encoding: .utf8)
            lines.append(contentsOf: EndpointLogPrivacy.redactLogLines(contents.split(whereSeparator: \.isNewline).map(String.init)))
        }
        return Array(lines.suffix(Self.maxLines))
    }

    /// Snapshot/export barriers also emit any outstanding overflow count.
    func flush() {
        lock.withLock { enqueueDroppedMarker() }
        queue.sync {}
    }

    func flushAsync() async {
        await withCheckedContinuation { continuation in
            lock.withLock {
                enqueueDroppedMarker()
                queue.async { continuation.resume() }
            }
        }
    }

    private final class DrainCompletion: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<Void, Never>?

        init(_ continuation: CheckedContinuation<Void, Never>) { self.continuation = continuation }

        func finish() {
            let waiting = lock.withLock {
                let waiting = continuation
                continuation = nil
                return waiting
            }
            waiting?.resume()
        }
    }

    /// Neither the caller nor a worker thread waits for a stalled writer.
    func drainAsync(until deadline: ContinuousClock.Instant) async {
        let remaining = ContinuousClock.now.duration(to: deadline).components
        let seconds = min(drainLimit, Double(remaining.seconds) + Double(remaining.attoseconds) / 1e18)
        guard seconds > 0 else { return }
        await withCheckedContinuation { continuation in
            let completion = DrainCompletion(continuation)
            lock.withLock {
                enqueueDroppedMarker()
                queue.async { completion.finish() }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + seconds) { completion.finish() }
        }
    }

    func linesForExport() async -> [String] {
        await flushAsync()
        return await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: (try? self.readLines()) ?? []) }
        }
    }

    /// UI reads distinguish an empty rotation from a file that cannot be read.
    func checkedLinesForExport() async throws -> [String] {
        await flushAsync()
        return try await withCheckedThrowingContinuation { continuation in
            queue.async { continuation.resume(with: Result { try self.readLines() }) }
        }
    }

    private static func age(of url: URL) -> Int {
        Int(url.pathExtension) ?? 0
    }

    private func write(_ data: Data) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let active = directory.appending(path: Self.fileName)
        if let size = try? FileManager.default.attributesOfItem(atPath: active.path)[.size] as? Int,
            size + data.count > Self.maxFileSizeBytes
        {
            try rotate(active: active)
        }
        if !FileManager.default.fileExists(atPath: active.path) {
            FileManager.default.createFile(atPath: active.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: active)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: data)
    }

    private func rotate(active: URL) throws {
        let fileManager = FileManager.default
        for index in stride(from: Self.maxFiles, through: 1, by: -1) {
            let source = index == 1 ? active : directory.appending(path: "\(Self.fileName).\(index - 1)")
            guard fileManager.fileExists(atPath: source.path) else { continue }
            let destination = directory.appending(path: "\(Self.fileName).\(index)")
            try? fileManager.removeItem(at: destination)
            try fileManager.moveItem(at: source, to: destination)
        }
    }

    /// Shared by persistence and telemetry. Unlabelled hashes/txids remain useful diagnostics.
    static func redact(_ message: String) -> String {
        guard message.utf8.count <= maxMessageBytes else { return "[oversized log message omitted]" }
        return redactForTelemetry(message) ?? "[redacted]"
    }

    /// Nil means inspection was uncertain; telemetry must omit the message.
    static func redactForTelemetry(_ message: String) -> String? {
        redactForTelemetry(message, endpointPrivacy: EndpointLogPrivacy.sanitize)
    }

    static func redactForTelemetry(_ message: String, endpointPrivacy: (String) -> String?) -> String? {
        guard message.utf8.count <= maxMessageBytes else { return nil }
        guard let safe = endpointPrivacy(message), let views = decodedViews(safe) else { return nil }
        for view in views.dropFirst() where redactPlain(view) != view { return "[redacted]" }
        return redactPlain(safe)
    }

    // Decoding loses source offsets, so a hit in any decoded view omits the entire message.
    static func decodedViews(_ message: String, percentEncoded: Bool = false, compatibility: Bool = false) -> [String]? {
        let escapes = percentEncoded ? encodedEscapes : jsonEscapes
        var views = [message]
        for _ in 0..<4 {
            if compatibility && EndpointLogPrivacy.hasExcessiveCombiningRun(views.last!) { return nil }
            let source = compatibility ? views.last!.precomposedStringWithCompatibilityMapping : views.last!
            if compatibility && EndpointLogPrivacy.hasExcessiveCombiningRun(source) { return nil }
            if source != views.last! { views.append(source) }
            let matches = escapes.matches(in: source, range: NSRange(source.startIndex..., in: source))
            if matches.isEmpty { return views }
            let original = source as NSString
            var parts: [String] = []
            var offset = 0
            for match in matches {
                let token = original.substring(with: match.range)
                let decoded = token.hasPrefix("&") ? decodeEntity(token) : token.hasPrefix("%") ? token.removingPercentEncoding :
                    (try? JSONDecoder().decode(String.self, from: Data(("\"" + token + "\"").utf8)))
                guard let value = decoded else { return nil }
                parts.append(original.substring(with: NSRange(location: offset, length: match.range.location - offset)))
                parts.append(value)
                offset = match.range.upperBound
            }
            parts.append(original.substring(from: offset))
            views.append(parts.joined())
        }
        if compatibility && EndpointLogPrivacy.hasExcessiveCombiningRun(views.last!) { return nil }
        let last = compatibility ? views.last!.precomposedStringWithCompatibilityMapping : views.last!
        if compatibility && EndpointLogPrivacy.hasExcessiveCombiningRun(last) { return nil }
        if last != views.last! { views.append(last) }
        guard escapes.firstMatch(in: last, range: NSRange(last.startIndex..., in: last)) == nil else { return nil }
        return views
    }

    private static func decodeEntity(_ token: String) -> String? {
        let entity = String(token.dropFirst().dropLast(token.hasSuffix(";") ? 1 : 0))
        let code: UInt32?
        if entity.lowercased().hasPrefix("#x") { code = UInt32(entity.dropFirst(2), radix: 16) }
        else if entity.hasPrefix("#") { code = UInt32(entity.dropFirst()) }
        else { code = ["amp": 38, "quot": 34, "apos": 39, "lt": 60, "gt": 62, "colon": 58, "sol": 47][entity] }
        return code.flatMap(UnicodeScalar.init).map(String.init)
    }

    static func redactPlain(_ message: String) -> String {
        var result = replaceTokens(in: message, tokens: schemeTokens, pattern: urlUserinfo,
                                   start: schemeStart, template: "$1[redacted]@")
        result = redactLabels(result, addresses: true)
        result = replaceEmails(result)
        result = redactLabels(result, addresses: false)
        result = replaceURLs(result)
        for (index, pattern) in sensitivePatterns.enumerated() {
            result = pattern.stringByReplacingMatches(in: result,
                range: NSRange(result.startIndex..., in: result), withTemplate: "[redacted]")
            if index == 1 {
                result = replaceTokens(in: result, tokens: jwtTokens, pattern: jwt)
                result = replacePushTokens(result)
            }
        }
        let decomposed = result.decomposedStringWithCompatibilityMapping
        let normalized = normalizeMnemonic(result)
        let skeleton = detectionSkeleton(result)
        if skeleton != normalized, !mnemonicRanges(in: skeleton).isEmpty { return "[redacted]" }
        let runs = mnemonicRanges(in: normalized)
        // Compatibility expansion/case folding may invalidate offsets into the original.
        if !runs.isEmpty, decomposed != result || normalized.utf16.count != result.utf16.count {
            return "[redacted]"
        }
        return replacing(result, ranges: runs.map { ($0, "[redacted]") })
    }

    private static func normalizeMnemonic(_ text: String) -> String {
        text.decomposedStringWithCompatibilityMapping.folding(options: .caseInsensitive,
            locale: Locale(identifier: "en_US_POSIX"))
    }

    // For privacy detection only; wallet seed validation intentionally stays stricter.
    private static func detectionSkeleton(_ text: String) -> String {
        let scalars = normalizeMnemonic(text).unicodeScalars.compactMap { scalar -> UnicodeScalar? in
            if scalar.properties.isDefaultIgnorableCodePoint { return nil }
            switch scalar.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark: return nil
            default: return latinConfusables[scalar] ?? scalar
            }
        }
        return String(String.UnicodeScalarView(scalars))
    }

    private static let latinConfusables: [UnicodeScalar: UnicodeScalar] = {
        let groups = ["a": "аα", "b": "ьβ", "c": "сϲσ", "d": "ԁ", "e": "еε", "f": "ғ",
                      "g": "ɡ", "h": "һн", "i": "іι", "j": "ј", "k": "κк", "l": "ӏ",
                      "m": "мμ", "n": "ո", "o": "оο", "p": "рρ", "s": "ѕ", "t": "тτ",
                      "u": "υս", "v": "νѵ", "w": "ԝω", "x": "хχ", "y": "уγ", "z": "ζ"]
        var result: [UnicodeScalar: UnicodeScalar] = [:]
        for (latin, lookalikes) in groups {
            for scalar in lookalikes.unicodeScalars { result[scalar] = latin.unicodeScalars.first! }
        }
        return result
    }()

    private static func mnemonicRanges(in normalized: String) -> [NSRange] {
        let words = wordPattern.matches(in: normalized, range: NSRange(normalized.startIndex..., in: normalized))
        let source = normalized as NSString
        var runs: [NSRange] = []
        var start = 0, count = 0
        for (index, word) in words.enumerated() {
            let text = source.substring(with: word.range)
            if bip39.contains(text) {
                if count == 0 { start = index }
                count += 1
            } else {
                if count >= 12 { runs.append(NSRange(location: words[start].range.location,
                    length: words[index - 1].range.upperBound - words[start].range.location)) }
                count = 0
            }
        }
        if count >= 12, let last = words.last {
            runs.append(NSRange(location: words[start].range.location,
                                length: last.range.upperBound - words[start].range.location))
        }
        return runs
    }

    /// Dictionary order is not defined. Count fragments too, so interleaved metadata
    /// cannot turn a complete phrase into harmless-looking individual word fields.
    static func containsMnemonic(in leaves: [String]) -> Bool {
        let joined = leaves.joined(separator: " ")
        guard joined.utf8.count <= maxMessageBytes else { return true }
        guard let views = decodedViews(joined) else { return true }
        if views.contains(where: { !mnemonicRanges(in: detectionSkeleton($0)).isEmpty }) { return true }
        var count = 0
        for leaf in leaves {
            guard let decoded = decodedViews(leaf)?.last else { return true }
            let normalized = detectionSkeleton(decoded)
            let words = wordPattern.matches(in: normalized, range: NSRange(normalized.startIndex..., in: normalized))
            if !words.isEmpty, words.allSatisfy({ bip39.contains((normalized as NSString).substring(with: $0.range)) }) {
                count += words.count
                if count >= 12 { return true }
            }
        }
        return false
    }

    static func prepareRedaction() {
        _ = bip39
        _ = wordPattern
        _ = sensitivePatterns
    }

    private static let bip39: Set<String> = {
        guard let url = Bundle.main.url(forResource: "bip39-english", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            preconditionFailure(#"Missing bundled BIP39 word list"#)
        }
        return Set(text.split(whereSeparator: \.isWhitespace).map(String.init))
    }()
    private static let jsonEscapes = try! NSRegularExpression(pattern: #"(?:\\u[0-9a-fA-F]{4})++|\\["\\/bfnrt]"#)
    private static let encodedEscapes = try! NSRegularExpression(pattern: jsonEscapes.pattern + #"|(?:%[0-9a-fA-F]{2})+|&(?:#[xX][0-9a-fA-F]+;?|#[0-9]+;?|[A-Za-z][A-Za-z0-9]*;)"#)
    private static let wordPattern = try! NSRegularExpression(pattern: #"[\p{L}\p{M}]+"#)
    // Search once per maximal token, never again at every internal dot/hyphen boundary.
    private static func replaceTokens(in text: String, tokens: NSRegularExpression,
                                      pattern: NSRegularExpression, start: NSRegularExpression = boundary,
                                      template: String = "[redacted]") -> String {
        let full = NSRange(text.startIndex..., in: text)
        let source = text as NSString
        var consumed = 0
        var replacements: [(NSRange, String)] = []
        for token in tokens.matches(in: text, range: full) where token.range.location >= consumed {
            if tokens === schemeTokens {
                let end = token.range.upperBound
                guard end + 3 <= source.length, source.substring(with: NSRange(location: end, length: 3)) == "://" else { continue }
            }
            guard let beginning = start.firstMatch(in: text, options: .withTransparentBounds, range: token.range),
                  let match = pattern.firstMatch(in: text, options: [.anchored, .withTransparentBounds],
                    range: NSRange(location: beginning.range.location, length: full.length - beginning.range.location)) else { continue }
            replacements.append((match.range, pattern.replacementString(for: match, in: text, offset: 0, template: template)))
            consumed = match.range.upperBound
        }
        return replacing(text, ranges: replacements)
    }

    private static func replacing(_ text: String, ranges: [(NSRange, String)]) -> String {
        let source = text as NSString
        var parts: [String] = []
        var offset = 0
        for (range, value) in ranges {
            parts.append(source.substring(with: NSRange(location: offset, length: range.location - offset)))
            parts.append(value)
            offset = range.upperBound
        }
        parts.append(source.substring(from: offset))
        return parts.joined()
    }

    private static func replaceURLs(_ text: String) -> String {
        let source = text as NSString
        let matches = urlTokens.matches(in: text, range: NSRange(text.startIndex..., in: text))
        return replacing(text, ranges: matches.compactMap { match in
            source.substring(with: match.range).contains("?") ? (match.range, "[redacted]") : nil
        })
    }

    private static func replacePushTokens(_ text: String) -> String {
        let source = text as NSString
        let matches = pushTokens.matches(in: text, range: NSRange(text.startIndex..., in: text))
        return replacing(text, ranges: matches.compactMap { match in
            source.character(at: match.range.upperBound - 1) == 93 ? (match.range, "[redacted]") : nil
        })
    }

    private static func replaceEmails(_ text: String) -> String {
        let source = text as NSString
        let full = NSRange(location: 0, length: source.length)
        var replacements: [(NSRange, String)] = []
        var consumed = 0
        for token in emailTokens.matches(in: text, range: full) where token.range.location >= consumed {
            guard let beginning = boundary.firstMatch(in: text, options: .withTransparentBounds, range: token.range),
                  let local = emailLocal.firstMatch(in: text, options: [.anchored, .withTransparentBounds],
                    range: NSRange(location: beginning.range.location, length: full.length - beginning.range.location)) else { continue }
            var offset = local.range.upperBound
            var end: Int?
            var first = true
            while let part = domainPart.firstMatch(in: text, options: .anchored,
                range: NSRange(location: offset, length: full.length - offset)) {
                if !first, let tld = domainTLD.firstMatch(in: text, options: [.anchored, .withTransparentBounds],
                    range: NSRange(location: offset, length: full.length - offset)) { end = tld.range.upperBound }
                first = false
                offset = part.range.upperBound
                guard offset < full.length, source.character(at: offset) == 46 else { break }
                offset += 1
            }
            if let end {
                replacements.append((NSRange(location: beginning.range.location, length: end - beginning.range.location), "[redacted]"))
                consumed = end
            }
        }
        return replacing(text, ranges: replacements)
    }

    private static func redactLabels(_ text: String, addresses: Bool) -> String {
        let source = text as NSString
        let full = NSRange(location: 0, length: source.length)
        let keyword = addresses ? addressKeyword : credentialKeyword
        let tail = addresses ? addressTail : credentialTail
        for token in labelTokens.matches(in: text, range: full) {
            guard let beginning = boundary.firstMatch(in: text, options: .withTransparentBounds, range: token.range) else { continue }
            let label = source.substring(with: token.range).lowercased()
            var ends: [Int] = []
            // Only these labels can cross a literal space; all other separators are in the token.
            let pairs = addresses ? [("lightning", "address")] : [("seed", "phrase"), ("priv", "key"), ("private", "key"), ("secret", "key")]
            for (left, right) in pairs where label.hasSuffix(left) {
                let end = token.range.upperBound
                if end < source.length, source.character(at: end) == 32,
                   let next = labelTokens.firstMatch(in: text, options: .anchored,
                     range: NSRange(location: end + 1, length: source.length - end - 1)),
                   source.substring(with: next.range).lowercased().hasPrefix(right) {
                    ends.append(next.range.upperBound)
                }
            }
            if keyword.firstMatch(in: label, range: NSRange(label.startIndex..., in: label)) != nil {
                ends.append(token.range.upperBound)
            }
            for end in ends {
                if tail.firstMatch(in: text, options: [.anchored, .withTransparentBounds],
                    range: NSRange(location: end, length: source.length - end)) != nil {
                    return source.substring(to: beginning.range.location) + "[redacted]"
                }
            }
            if !addresses {
                // Exact key/x-amz alternatives can start after a dot or hyphen inside the token.
                let dot = source.range(of: ".", options: .backwards, range: token.range)
                let start = dot.location == NSNotFound ? token.range.location : dot.upperBound
                let range = NSRange(location: start, length: token.range.upperBound - start)
                if let special = specialLabel.firstMatch(in: text, options: .withTransparentBounds, range: range),
                   tail.firstMatch(in: text, options: [.anchored, .withTransparentBounds],
                    range: NSRange(location: token.range.upperBound, length: source.length - token.range.upperBound)) != nil {
                    return source.substring(to: special.range.location) + "[redacted]"
                }
            }
        }
        return text
    }

    private static let boundary = try! NSRegularExpression(pattern: #"\b"#)
    private static let labelTokens = try! NSRegularExpression(pattern: #"[\w.-]+"#)
    private static let specialLabel = try! NSRegularExpression(pattern: #"(?i)\b(?:key$|x-amz-[\w-]++)"#)
    private static let addressKeyword = try! NSRegularExpression(pattern: #"email|lightning[_-]?address"#)
    private static let credentialKeyword = try! NSRegularExpression(
        pattern: #"mnemonic|seed|priv(?:ate)?[_-]?key|secret(?:[_-]?key)?|preimage|sig(?:nature)?|token|authorization|password"#)
    private static let addressTail = try! NSRegularExpression(pattern: #"["']?\s*[:=]"#)
    private static let credentialTail = try! NSRegularExpression(
        pattern: #"["']?(?:\s*[:=({\[]|(?:\s+|["']\s*)["']?[A-Za-z0-9+/_=-]{16,})"#)
    private static let schemeTokens = try! NSRegularExpression(pattern: #"(?i)[a-z0-9+.-]+"#)
    private static let schemeStart = try! NSRegularExpression(pattern: #"(?i)\b[a-z]"#)
    private static let urlUserinfo = try! NSRegularExpression(
        pattern: #"(?i)(\b[a-z][a-z0-9+.-]*+://)[^\s/@<>"']++@"#)
    private static let emailTokens = try! NSRegularExpression(pattern: #"(?i)[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+"#)
    private static let emailLocal = try! NSRegularExpression(pattern: #"(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]++@"#)
    private static let domainPart = try! NSRegularExpression(pattern: #"(?i)[A-Z0-9-]+"#)
    private static let domainTLD = try! NSRegularExpression(pattern: #"(?i)[A-Z]{2,63}\b"#)
    private static let jwtTokens = try! NSRegularExpression(pattern: #"[A-Za-z0-9_-]+"#)
    private static let jwt = try! NSRegularExpression(pattern: #"\b[A-Za-z0-9_-]{8,}+\.[A-Za-z0-9_-]{8,}+\.[A-Za-z0-9_-]{8,}+"#)
    private static let urlTokens = try! NSRegularExpression(pattern: #"(?i)\bhttps?://[^\s<>"']*+"#)
    private static let pushTokens = try! NSRegularExpression(pattern: #"(?i)\bExpo(?:nent)?PushToken\[[^\]]*+(?:\]|$)"#)
    private static let sensitivePatterns = [
        #"(?i)\bhttps?%3a%2f%2f[^\s<>"']*"#,
        #"(?i)\bBearer\s+[A-Za-z0-9._~+/-]++=*"#,
        #"\b(?:[xt]prv[1-9A-HJ-NP-Za-km-z]{20,}+|[59][1-9A-HJ-NP-Za-km-z]{50}|[KLc][1-9A-HJ-NP-Za-km-z]{51})\b"#,
    ].map { try! NSRegularExpression(pattern: $0) }
}
