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

    private static let subsystem = Bundle.main.bundleIdentifier ?? "com.noahwallet"

    let tag: String
    private let logger: Logger
    private let file: LogFile

    init(_ tag: String, file: LogFile = .shared) {
        self.tag = LogFile.redact(tag)
        self.logger = Logger(subsystem: Self.subsystem, category: self.tag)
        self.file = file
    }

    func v(_ message: String) { log(.verbose, message) }
    func d(_ message: String) { log(.debug, message) }
    func i(_ message: String) { log(.info, message) }
    func w(_ message: String) { log(.warn, message) }
    func e(_ message: String) { log(.error, message) }

    func log(_ level: Level, _ message: String) {
        let message = LogFile.redact(message)
        switch level {
        case .verbose, .debug: logger.debug("\(message, privacy: .public)")
        case .info: logger.info("\(message, privacy: .public)")
        case .warn: logger.warning("\(message, privacy: .public)")
        case .error: logger.error("\(message, privacy: .public)")
        }
        file.append(level: level, tag: tag, message: message)
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
         queue: DispatchQueue = DispatchQueue(label: "com.noahwallet.log-file")) {
        precondition(capacity > 0)
        self.directory = directory
        self.capacity = capacity
        self.queue = queue
    }

    func append(level: AppLog.Level, tag: String, message: String, date: Date = Date()) {
        let cleaned = Self.redact(message).trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\n", with: "\n  ")
        let tag = Self.redact(tag)
        // Admission and queue ordering only. Rust callbacks never wait for filesystem work.
        lock.withLock {
            guard pending < capacity else { dropped += 1; return }
            enqueueDroppedMarker()
            pending += 1
            queue.async { [self] in
                persist(level: level, tag: tag, message: cleaned, date: date)
                lock.withLock {
                    pending -= 1
                    if pending == 0 { enqueueDroppedMarker() }
                }
            }
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
        let line = "\(formatter.string(from: date)) \(level.symbol) [\(tag)] \(message)\n"
        try? write(Data(line.utf8))
    }

    /// The newest `maxLines` lines across all files, oldest first.
    func lines() -> [String] {
        flush()
        return queue.sync { readLines() }
    }

    private func readLines() -> [String] {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil, options: .skipsHiddenFiles)) ?? []
        let ordered = files
            .filter { $0.lastPathComponent.hasPrefix(Self.fileName) }
            .sorted { Self.age(of: $0) > Self.age(of: $1) }
        var lines: [String] = []
        for url in ordered {
            guard let contents = try? String(contentsOf: url, encoding: .utf8) else { continue }
            lines.append(contentsOf: contents.split(whereSeparator: \.isNewline).map(String.init))
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

    /// Lifecycle callbacks alone may wait briefly; SDK/Rust admission never calls this.
    @discardableResult
    func drain(timeout: TimeInterval = 0.2) -> Bool {
        guard timeout > 0 else { return false }
        let done = DispatchSemaphore(value: 0)
        lock.withLock {
            enqueueDroppedMarker()
            queue.async { done.signal() }
        }
        return done.wait(timeout: .now() + min(timeout, 0.2)) == .success
    }

    func drainAsync(until deadline: ContinuousClock.Instant) async {
        _ = await Task.detached { [self] in
            let remaining = ContinuousClock.now.duration(to: deadline).components
            let seconds = Double(remaining.seconds) + Double(remaining.attoseconds) / 1e18
            drain(timeout: seconds)
        }.value
    }

    func linesForExport() async -> [String] {
        await flushAsync()
        return await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: self.readLines()) }
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
        guard message.utf8.count <= maxMessageBytes, let views = decodedViews(message) else { return nil }
        for view in views.dropFirst() where redactPlain(view) != view { return "[redacted]" }
        return redactPlain(message)
    }

    // Decoding loses source offsets, so a hit in any decoded view omits the entire message.
    private static func decodedViews(_ message: String) -> [String]? {
        var views = [message]
        for _ in 0..<4 {
            let source = views.last!
            let matches = jsonEscapes.matches(in: source, range: NSRange(source.startIndex..., in: source))
            if matches.isEmpty { return views }
            let decoded = NSMutableString(string: source)
            for match in matches.reversed() {
                let token = (source as NSString).substring(with: match.range)
                guard let value = try? JSONDecoder().decode(String.self, from: Data(("\"" + token + "\"").utf8)) else { return nil }
                decoded.replaceCharacters(in: match.range, with: value)
            }
            views.append(decoded as String)
        }
        let last = views.last!
        guard jsonEscapes.firstMatch(in: last, range: NSRange(last.startIndex..., in: last)) == nil else { return nil }
        return views
    }

    private static func redactPlain(_ message: String) -> String {
        var result = urlUserinfo.stringByReplacingMatches(in: message,
            range: NSRange(message.startIndex..., in: message), withTemplate: "$1[redacted]@")
        for pattern in sensitivePatterns {
            result = pattern.stringByReplacingMatches(in: result,
                range: NSRange(result.startIndex..., in: result), withTemplate: "[redacted]")
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
        let clean = NSMutableString(string: result)
        for range in runs.reversed() { clean.replaceCharacters(in: range, with: "[redacted]") }
        return clean as String
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
        var runs: [NSRange] = []
        var start = 0, count = 0
        for (index, word) in words.enumerated() {
            let text = (normalized as NSString).substring(with: word.range).lowercased()
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
            preconditionFailure("Missing bundled BIP39 word list")
        }
        return Set(text.split(whereSeparator: \.isWhitespace).map(String.init))
    }()
    private static let jsonEscapes = try! NSRegularExpression(pattern: #"(?:\\u[0-9a-fA-F]{4})+|\\["\\/bfnrt]"#)
    private static let wordPattern = try! NSRegularExpression(pattern: #"[\p{L}\p{M}]+"#)
    private static let urlUserinfo = try! NSRegularExpression(
        pattern: #"(?i)(\b[a-z][a-z0-9+.-]*://)[^\s/@<>"']+@"#)
    private static let sensitivePatterns = [
        #"(?is)\b[\w.-]*(?:email|lightning[_ -]?address)[\w.-]*["']?\s*[:=].*"#,
        #"(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9-]+(?:\.[A-Z0-9-]+)*\.[A-Z]{2,63}\b"#,
        // Consume structured/multiline values too; never attempt to parse a credential's end.
        #"(?is)\b(?:[\w.-]*(?:mnemonic|seed(?:[_ -]?phrase)?|priv(?:ate)?[_ -]?key|secret(?:[_ -]?key)?|preimage|sig(?:nature)?|token|authorization|password)[\w.-]*|key|x-amz-[\w-]+)["']?(?:\s*[:=({\[].*|(?:\s+|["']\s*)["']?[A-Za-z0-9+/_=-]{16,}.*)"#,
        #"(?i)\bhttps?://[^\s<>"']*\?[^\s<>"']*"#,
        #"(?i)\bhttps?%3a%2f%2f[^\s<>"']*"#,
        #"(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*|\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"#,
        #"(?i)\bExpo(?:nent)?PushToken\[[^\]]*\]"#,
        #"\b(?:[xt]prv[1-9A-HJ-NP-Za-km-z]{20,}|[59][1-9A-HJ-NP-Za-km-z]{50}|[KLc][1-9A-HJ-NP-Za-km-z]{51})\b"#,
    ].map { try! NSRegularExpression(pattern: $0) }
}
